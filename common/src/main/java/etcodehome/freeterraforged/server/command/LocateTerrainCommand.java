package etcodehome.freeterraforged.server.command;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.search.LocateSearchSettings;
import etcodehome.freeterraforged.world.worldgen.search.TerrainLocateSearch;
import etcodehome.freeterraforged.world.worldgen.search.TerrainSearchTargets;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * {@code /ftf locate terrain <terrain>} finds a sampled point of an FTF terrain type.
 *
 * <p>The world's terrain is sampled on a preset-derived lattice by a bounded worker pool and the answer is reported
 * back on the server thread, so the server keeps ticking while the search runs. Targets whose identity can be changed
 * by terrain filters use the same authoritative filter pipeline as chunk generation; all other targets use the exact
 * raw-heightmap identity as a cheaper equivalent path.
 */
public final class LocateTerrainCommand {
	private record RunningSearch(CompletableFuture<TerrainLocateSearch.Result> future, String terrainName) {}
	private static final AtomicReference<RunningSearch> ACTIVE_SEARCH = new AtomicReference<>();

	public static final ResourceKey<Level> FTF_DIMENSION = ResourceKey.create(
		Registries.DIMENSION,
		FTFCommon.location("freeterraforged")
	);

	public record TargetLevel(ServerLevel level, GeneratorContext context) {}

	private static final String KEY = FTFCommon.MOD_ID + ".commands.locate.terrain.";
	private static final DynamicCommandExceptionType ERROR_UNKNOWN_TERRAIN = new DynamicCommandExceptionType(
		name -> Component.translatableWithFallback(KEY + "unknown", "Unknown terrain type: %s.", name)
	);
	private static final SimpleCommandExceptionType ERROR_NOT_FTF = new SimpleCommandExceptionType(
		Component.translatableWithFallback(KEY + "not_ftf", "No FreeTerraForged world is available.")
	);

	@Nullable
	public static GeneratorContext extractContext(@Nullable ServerLevel level) {
		if (level == null) {
			return null;
		}
		if ((Object) level.getChunkSource().randomState() instanceof FTFRandomState state) {
			GeneratorContext ctx = state.generatorContext();
			if (ctx != null) {
				return ctx;
			}
		}
		if (level.getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator generator) {
			return generator.generatorContext().orElse(null);
		}
		return null;
	}

	@Nullable
	public static TargetLevel findTargetLevel(CommandSourceStack source) {
		// 1. Try current level of command source
		ServerLevel currentLevel = source.getLevel();
		GeneratorContext context = extractContext(currentLevel);
		if (context != null) {
			return new TargetLevel(currentLevel, context);
		}

		MinecraftServer server = source.getServer();
		if (server == null) {
			return null;
		}

		// 2. Try the dedicated FTF dimension key (freeterraforged:freeterraforged)
		ServerLevel ftfLevel = server.getLevel(FTF_DIMENSION);
		if (ftfLevel != null) {
			context = extractContext(ftfLevel);
			if (context != null) {
				return new TargetLevel(ftfLevel, context);
			}
		}

		// 3. Try overworld
		ServerLevel overworld = server.overworld();
		if (overworld != null && overworld != currentLevel) {
			context = extractContext(overworld);
			if (context != null) {
				return new TargetLevel(overworld, context);
			}
		}

		// 4. Search all registered levels on the server (first matching FTF namespace)
		for (ServerLevel level : server.getAllLevels()) {
			if (level != currentLevel && level != ftfLevel && level != overworld) {
				if (level.dimension().location().getNamespace().equals(FTFCommon.MOD_ID)) {
					context = extractContext(level);
					if (context != null) {
						return new TargetLevel(level, context);
					}
				}
			}
		}
		for (ServerLevel level : server.getAllLevels()) {
			if (level != currentLevel && level != ftfLevel && level != overworld) {
				context = extractContext(level);
				if (context != null) {
					return new TargetLevel(level, context);
				}
			}
		}

		return null;
	}

	private LocateTerrainCommand() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("locate")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("cancel")
				.executes(context -> cancel(context.getSource()))
			)
			.then(Commands.literal("terrain")
				.then(Commands.argument("terrain", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(TerrainSearchTargets.names(), builder))
					.executes(context -> locate(context.getSource(), StringArgumentType.getString(context, "terrain")))
				)
			)
			.then(LocateBiomeCommand.build());
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("ftf")
			.requires(source -> source.hasPermission(2))
			.then(build())
		);
	}

	private static int cancel(CommandSourceStack source) {
		RunningSearch active = ACTIVE_SEARCH.getAndSet(null);
		String biome = LocateBiomeCommand.cancelActive();
		boolean terrainActive = active != null && !active.future.isDone();
		if (!terrainActive && biome == null) {
			source.sendFailure(Component.translatableWithFallback(
				KEY + "no_active_search",
				"No FreeTerraForged locate search is running."
			));
			return 0;
		}
		if (terrainActive) {
			active.future.cancel(true);
		}
		String targets = terrainActive && biome != null
			? active.terrainName + " and " + biome
			: terrainActive ? active.terrainName : biome;
		source.sendSuccess(() -> Component.translatableWithFallback(
			KEY + "cancelled",
			"Stopped the locate search for %s.",
			targets
		), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int locate(CommandSourceStack source, String name) throws CommandSyntaxException {
		Terrain target = TerrainSearchTargets.get(name);
		if (target == null) {
			throw ERROR_UNKNOWN_TERRAIN.create(name);
		}
		TargetLevel targetLevel = findTargetLevel(source);
		if (targetLevel == null) {
			throw ERROR_NOT_FTF.create();
		}
		ServerLevel level = targetLevel.level();
		GeneratorContext context = targetLevel.context();

		RunningSearch current = ACTIVE_SEARCH.get();
		if (current != null && !current.future.isDone()) {
			source.sendFailure(Component.translatableWithFallback(
				KEY + "already_running",
				"A terrain search for %s is already running. Use '/ftf locate cancel' to stop it first.",
				current.terrainName
			));
			return 0;
		}

		BlockPos origin = BlockPos.containing(source.getPosition());
		MinecraftServer server = source.getServer();
		long start = System.nanoTime();
		source.sendSuccess(() -> Component.translatableWithFallback(
			KEY + "searching",
			"Searching for terrain %s for up to %s seconds...",
			target.getName(),
			LocateSearchSettings.TIME_LIMIT_SECONDS
		), false);

		CompletableFuture<TerrainLocateSearch.Result> future = TerrainLocateSearch.findFirst(
			context, target, origin
		);

		RunningSearch search = new RunningSearch(future, target.getName());
		ACTIVE_SEARCH.set(search);

		future.whenComplete((result, error) -> {
			ACTIVE_SEARCH.compareAndSet(search, null);
			long elapsedNanos = System.nanoTime() - start;
			if (!server.isRunning()) {
				return;
			}
			try {
				server.execute(() -> {
					if (server.isRunning()) {
						report(source, level, target, result, error, elapsedNanos);
					}
				});
			} catch (RejectedExecutionException ignored) {
				// Server shut down before or during dispatch
			}
		});
		return Command.SINGLE_SUCCESS;
	}

	static CompletableFuture<LocatePreviewResult> findForPreview(
		GeneratorContext context,
		BlockPos origin,
		String name
	) {
		Terrain target = TerrainSearchTargets.get(name);
		if (target == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown terrain type: " + name));
		}
		CompletableFuture<TerrainLocateSearch.Result> search = TerrainLocateSearch.findFirst(
			context, target, origin
		);
		CompletableFuture<LocatePreviewResult> transformed = search.thenApply(found -> {
			TerrainLocateSearch.Match match = found.match();
			if (match != null) {
				return LocatePreviewResult.found(new BlockPos(match.x(), origin.getY(), match.z()));
			}
			return LocatePreviewResult.of(found.timedOut()
				? LocatePreviewResult.Status.TIMED_OUT
				: LocatePreviewResult.Status.NOT_FOUND);
		});
		CompletableFuture<LocatePreviewResult> result = new CompletableFuture<>() {
			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				search.cancel(mayInterruptIfRunning);
				transformed.cancel(mayInterruptIfRunning);
				return super.cancel(mayInterruptIfRunning);
			}
		};
		transformed.whenComplete((position, failure) -> {
			if (failure == null) {
				result.complete(position);
			} else {
				result.completeExceptionally(failure);
			}
		});
		return result;
	}

	private static void report(CommandSourceStack source, ServerLevel targetLevel, Terrain target, @Nullable TerrainLocateSearch.Result result, @Nullable Throwable error, long elapsedNanos) {
		String name = target.getName();
		if (isCancellation(error)) {
			return;
		}
		if (result == null) {
			if (error instanceof IllegalStateException ise && ise.getMessage() != null && ise.getMessage().contains("Cache is closed")) {
				FTFCommon.LOGGER.debug("Locating terrain {} terminated due to world unload: {}", name, ise.getMessage());
				return;
			}
			FTFCommon.LOGGER.error("Locating terrain {} failed", name, error);
			source.sendFailure(Component.translatableWithFallback(
				KEY + "failed",
				"Could not search for terrain %s because of an unexpected error. Check the server log for details.",
				name
			));
			return;
		}
		FTFCommon.LOGGER.info(
			"Locating terrain {} took {} ms ({} samples, step {})",
			name,
			TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
			result.samples(),
			result.resolution().horizontalStep()
		);

		TerrainLocateSearch.Match match = result.match();
		if (match == null) {
			source.sendFailure(result.timedOut()
				? Component.translatableWithFallback(
					KEY + "timed_out", "Could not find terrain %s within %s seconds.", name,
					LocateSearchSettings.TIME_LIMIT_SECONDS
				)
				: Component.translatableWithFallback(
					KEY + "not_found",
					"Could not find terrain %s before reaching Minecraft's world limits.",
					name
				));
			return;
		}
		int distance = (int) Math.sqrt((double) match.distanceSq());
		String tpCommand = targetLevel == source.getLevel()
			? "/tp @s " + match.x() + " ~ " + match.z()
			: "/execute in " + targetLevel.dimension().location() + " run tp @s " + match.x() + " ~ " + match.z();
		Component coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates", match.x(), "~", match.z()))
			.withStyle(style -> style
				.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tpCommand))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.coordinates.tooltip"))));
		source.sendSuccess(() -> Component.translatableWithFallback(
			KEY + "success", "Found %s terrain at %s (%s blocks away).", name, coordinates, distance
		), false);
	}

	private static boolean isCancellation(@Nullable Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof CancellationException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
