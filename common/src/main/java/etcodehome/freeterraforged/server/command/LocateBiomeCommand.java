package etcodehome.freeterraforged.server.command;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.ArgumentCommandNode;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;
import etcodehome.freeterraforged.world.worldgen.search.BiomeLocateSearch;
import etcodehome.freeterraforged.world.worldgen.search.BiomeSearchTarget;
import etcodehome.freeterraforged.world.worldgen.search.FTFBiomeLocateQuery;
import etcodehome.freeterraforged.world.worldgen.search.LocateSearchSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;

/** FTF-plan-aware, chunk-free biome locator. */
public final class LocateBiomeCommand {
    private static final String KEY = FTFCommon.MOD_ID + ".commands.locate.biome.";
    private static final AtomicReference<RunningSearch> ACTIVE_SEARCH = new AtomicReference<>();
    private static final DynamicCommandExceptionType ERROR_INVALID_TARGET = new DynamicCommandExceptionType(
        value -> Component.translatableWithFallback(KEY + "invalid", "Invalid biome ID or tag: %s.", value)
    );
    private static final SimpleCommandExceptionType ERROR_NOT_FTF = new SimpleCommandExceptionType(
        Component.translatableWithFallback(KEY + "not_ftf", "No FreeTerraForged world is available.")
    );

    private LocateBiomeCommand() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("biome")
            .then(Commands.argument("biome", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.BIOME))
                .suggests((context, builder) -> {
                    var biomes = context.getSource().registryAccess().lookupOrThrow(Registries.BIOME);
                    return SharedSuggestionProvider.suggest(Stream.concat(
                        biomes.listElementIds().map(key -> key.location().toString()),
                        biomes.listTagIds().map(tag -> "#" + tag.location())
                    ), builder);
                })
				.executes(context -> locate(
					context.getSource(), biomeTarget(context), LocateCommandOptions.defaults()
				))
				.then(Commands.argument(LocateCommandOptions.ARGUMENT, StringArgumentType.greedyString())
					.suggests(LocateCommandOptions::suggest)
					.executes(context -> locate(
						context.getSource(),
						biomeTarget(context),
						LocateCommandOptions.parse(StringArgumentType.getString(
							context, LocateCommandOptions.ARGUMENT
						))
					))
				)
            );
    }

    private static ResourceOrTagKeyArgument.Result<Biome> biomeTarget(
        com.mojang.brigadier.context.CommandContext<CommandSourceStack> context
    ) throws CommandSyntaxException {
		String rawTarget = rawArgument(context, "biome");
		try {
			BiomeSearchTarget.parse(rawTarget);
		} catch (IllegalArgumentException invalid) {
			throw ERROR_INVALID_TARGET.create(rawTarget);
		}
        return ResourceOrTagKeyArgument.getResourceOrTagKey(
            context, "biome", Registries.BIOME, ERROR_INVALID_TARGET
        );
    }

	private static String rawArgument(
		com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
		String name
	) {
		for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
			if (node.getNode().getName().equals(name) && node.getNode() instanceof ArgumentCommandNode<?, ?>) {
				return context.getInput().substring(node.getRange().getStart(), node.getRange().getEnd());
			}
		}
		return "";
	}

    private static int locate(
        CommandSourceStack source,
		ResourceOrTagKeyArgument.Result<Biome> targetBiome,
		LocateCommandOptions options
    ) throws CommandSyntaxException {
		LocateSearchSettings settings = options.searchSettings();
        String value = targetBiome.asPrintable();
        LocateTerrainCommand.TargetLevel target = LocateTerrainCommand.findTargetLevel(source);
        if (target == null) {
            throw ERROR_NOT_FTF.create();
        }
        BiomeLocateSearch.Query query = locateQuery(target.level());
        if (query == null) {
            throw ERROR_NOT_FTF.create();
        }

        RunningSearch current = ACTIVE_SEARCH.get();
        if (current != null && !current.future().isDone()) {
            source.sendFailure(Component.translatableWithFallback(
                KEY + "already_running",
                "A biome search for %s is already running. Use '/ftf locate cancel' to stop it first.",
                current.target()
            ));
            return 0;
        }

        ServerLevel level = target.level();
        BlockPos origin = level == source.getLevel()
            ? BlockPos.containing(source.getPosition())
            : level.getSharedSpawnPos();
        long start = System.nanoTime();
        CompletableFuture<BiomeLocateSearch.Result> future = BiomeLocateSearch.findFirst(
			query, targetBiome, origin, settings
        );
        RunningSearch search = new RunningSearch(future, value);
        ACTIVE_SEARCH.set(search);
        source.sendSuccess(() -> Component.translatableWithFallback(
            KEY + "searching", "Searching for biome %s for up to %s seconds...",
			value, settings.timeoutSeconds()
        ), false);

        MinecraftServer server = source.getServer();
		LocateCommandExecution.dispatch(
			future,
			options.pauseMainThread(),
			server,
			() -> ACTIVE_SEARCH.compareAndSet(search, null),
			(result, failure) -> report(
					source, level, value, result, failure,
					System.nanoTime() - start, settings.timeoutSeconds()
			)
		);
        return Command.SINGLE_SUCCESS;
    }

    static CompletableFuture<LocatePreviewResult> findForPreview(
        ServerLevel level,
        BlockPos origin,
        String value
    ) {
        BiomeSearchTarget target = BiomeSearchTarget.parse(value);
        BiomeLocateSearch.Query query = locateQuery(level);
        if (query == null) {
            return CompletableFuture.completedFuture(LocatePreviewResult.of(LocatePreviewResult.Status.UNAVAILABLE));
        }
        CompletableFuture<BiomeLocateSearch.Result> search = BiomeLocateSearch.findFirst(query, target, origin);
		CompletableFuture<LocatePreviewResult> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                search.cancel(mayInterruptIfRunning);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        search.whenComplete((found, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
            } else if (!found.targetPossible()) {
				result.complete(LocatePreviewResult.of(LocatePreviewResult.Status.IMPOSSIBLE));
            } else {
				BiomeLocateSearch.Match match = found.match();
				if (match != null) {
					result.complete(LocatePreviewResult.found(match.position()));
				} else {
					result.complete(LocatePreviewResult.of(found.timedOut()
						? LocatePreviewResult.Status.TIMED_OUT
						: LocatePreviewResult.Status.NOT_FOUND));
				}
            }
        });
        return result;
    }

    @Nullable
    static String cancelActive() {
        RunningSearch active = ACTIVE_SEARCH.getAndSet(null);
        if (active == null || active.future().isDone()) {
            return null;
        }
        active.future().cancel(true);
        return active.target();
    }

    @Nullable
    private static BiomeLocateSearch.Query locateQuery(ServerLevel level) {
        RandomState randomState = level.getChunkSource().randomState();
        if (!((Object) randomState instanceof FTFRandomState ftfState)) {
            return null;
        }
        WorldgenRuntimeBinding binding = ftfState.binding();
        if (binding == null || ftfState.generatorContext() == null) {
            return null;
        }
		WorldgenRuntimeBinding.BiomeRuntime runtime = binding.biomeRuntime();
		WorldgenPlan expected = runtime.plan();
        return new FTFBiomeLocateQuery(
			runtime.selection(),
            ftfState.generatorContext(),
            randomState.sampler(),
			runtime.placement(),
            () -> isCurrent(binding, expected)
        );
    }

    private static boolean isCurrent(WorldgenRuntimeBinding binding, WorldgenPlan expected) {
        try {
            return binding.plan() == expected;
        } catch (IllegalStateException closed) {
            return false;
        }
    }

    private static void report(
        CommandSourceStack source,
        ServerLevel level,
        String value,
        @Nullable BiomeLocateSearch.Result result,
        @Nullable Throwable failure,
		long elapsedNanos,
		long timeoutSeconds
    ) {
		if (failure != null || result == null) {
            if (isCancellation(failure)) {
                return;
            }
            FTFCommon.LOGGER.error("FTF biome locate failed", failure);
            source.sendFailure(Component.translatableWithFallback(
				KEY + "failed",
				"Could not search for biome %s because of an unexpected error. Check the server log for details.",
				value
            ));
            return;
        }
        if (!result.targetPossible()) {
            source.sendFailure(Component.translatableWithFallback(
				KEY + "impossible", "This FreeTerraForged world does not generate %s.", value
            ));
            return;
        }

        BiomeLocateSearch.Match match = result.match();
        if (match == null) {
            source.sendFailure(result.timedOut()
                ? Component.translatableWithFallback(
					KEY + "timed_out", "Could not find biome %s within %s seconds.",
					value, timeoutSeconds
                )
                : Component.translatableWithFallback(
					KEY + "not_found",
					"Could not find biome %s before reaching Minecraft's world limits.",
					value
                ));
            return;
        }

        BlockPos position = match.position();
        ResourceLocation biomeId = match.biome().unwrapKey()
            .map(ResourceKey::location)
            .orElse(ResourceLocation.withDefaultNamespace("unknown"));
        String teleport = level == source.getLevel()
            ? "/tp @s " + position.getX() + " " + position.getY() + " " + position.getZ()
            : "/execute in " + level.dimension().location() + " run tp @s "
                + position.getX() + " " + position.getY() + " " + position.getZ();
        Component coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable(
            "chat.coordinates", position.getX(), position.getY(), position.getZ()
        )).withStyle(style -> style
            .withColor(ChatFormatting.GREEN)
            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, teleport))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.coordinates.tooltip"))));
		int distance = (int) Math.sqrt((double) match.distanceSq());
		source.sendSuccess(() -> successMessage(biomeId, coordinates, distance), false);
        FTFCommon.LOGGER.info(
            "Located FTF biome {} in {} ms ({} columns, horizontal step {}, vertical step {}, roles {})",
            biomeId,
            TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
            result.columns(),
            result.plan().resolution().horizontalStep(),
            result.plan().resolution().verticalStep(),
            result.plan().roles().roles()
        );
    }

	private static Component successMessage(ResourceLocation biomeId, Component coordinates, int distance) {
		return Component.translatableWithFallback(
			KEY + "success",
			"Found biome %s at %s (%s blocks away).",
			Component.literal(biomeId.toString()),
			coordinates,
			distance
		);
	}

    private static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record RunningSearch(
        CompletableFuture<BiomeLocateSearch.Result> future,
        String target
    ) {
    }
}
