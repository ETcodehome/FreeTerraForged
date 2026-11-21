package etcodehome.freeterraforged.server.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ActivePresetSnapshot;
import etcodehome.freeterraforged.network.ActivePresetSnapshotPayload;
import etcodehome.freeterraforged.network.PresetSnapshotPurpose;
import etcodehome.freeterraforged.server.preset.ActivePresetSnapshotService;
import etcodehome.freeterraforged.server.preset.ActivePresetSnapshotService.ActivePreset;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

/** Commands for inspecting the preset that actually owns a live FTF worldgen instance. */
public final class PresetCommand {
	private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String KEY = FTFCommon.MOD_ID + ".commands.preset.";
	private static final SimpleCommandExceptionType ERROR_NOT_FTF = new SimpleCommandExceptionType(
		Component.translatableWithFallback(KEY + "not_ftf", "No loaded FreeTerraForged dimension has an active preset")
	);
	private static final SimpleCommandExceptionType ERROR_PLAYER_ONLY = new SimpleCommandExceptionType(
		Component.translatableWithFallback(KEY + "player_only", "This command can only be used by a player")
	);
	private static final DynamicCommandExceptionType ERROR_UNKNOWN_SETTING = new DynamicCommandExceptionType(
		path -> Component.translatableWithFallback(KEY + "unknown_setting", "Unknown preset setting: %s", path)
	);
	private static final DynamicCommandExceptionType ERROR_AMBIGUOUS_SETTING = new DynamicCommandExceptionType(
		path -> Component.translatableWithFallback(
			KEY + "ambiguous_setting", "Preset setting '%s' is ambiguous; use its complete JSON path", path
		)
	);

	private PresetCommand() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("preset")
			.executes(context -> show(context.getSource()))
			.then(Commands.literal("setting")
				.then(Commands.argument("path", StringArgumentType.string())
					.suggests(PresetCommand::suggestSettings)
					.executes(context -> showSetting(
						context.getSource(), StringArgumentType.getString(context, "path")
					))
				)
			)
			.then(Commands.literal("view")
				.executes(context -> open(context.getSource()))
			);
	}

	private static int show(CommandSourceStack source) throws CommandSyntaxException {
		ActivePreset active = requireActive(source);
		String json = ActivePresetSnapshotService.encode(active.preset());
		sendToClient(source, active, PresetSnapshotPurpose.COPY, json);

		String name = active.preset().presentation().presetName;
		if (name == null || name.isBlank()) {
			name = Component.translatableWithFallback(KEY + "unnamed", "Unnamed preset").getString();
		}
		MutableComponent clickableName = copyComponent(name, json);
		String dimension = active.level().dimension().location().toString();
		source.sendSuccess(() -> Component.translatableWithFallback(
			KEY + "active", "Active preset: %s (%s)", clickableName, dimension
		), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int showSetting(CommandSourceStack source, String requestedPath) throws CommandSyntaxException {
		ActivePreset active = requireActive(source);
		JsonElement root = JsonParser.parseString(ActivePresetSnapshotService.encode(active.preset()));
		Map<String, JsonElement> paths = collectPaths(root);
		String path = resolvePath(paths, requestedPath);
		JsonElement value = paths.get(path);
		String valueJson = PRETTY_JSON.toJson(value);
		JsonObject pair = new JsonObject();
		pair.add(path.substring(path.lastIndexOf('.') + 1), value.deepCopy());
		String copied = PRETTY_JSON.toJson(pair);
		sendToClient(source, active, PresetSnapshotPurpose.COPY, copied);
		MutableComponent output = copyComponent(path + " = " + valueJson, copied);
		source.sendSuccess(() -> output, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int open(CommandSourceStack source) throws CommandSyntaxException {
		if (source.getPlayer() == null) {
			throw ERROR_PLAYER_ONLY.create();
		}
		ActivePreset active = requireActive(source);
		sendToClient(
			source, active, PresetSnapshotPurpose.VIEW, ActivePresetSnapshotService.encode(active.preset())
		);
		return Command.SINGLE_SUCCESS;
	}

	private static CompletableFuture<Suggestions> suggestSettings(
		CommandContext<CommandSourceStack> context,
		SuggestionsBuilder builder
	) {
		ActivePreset active = ActivePresetSnapshotService.find(context.getSource());
		if (active == null) {
			return builder.buildFuture();
		}
		try {
			Map<String, JsonElement> paths = collectPaths(JsonParser.parseString(
				ActivePresetSnapshotService.encode(active.preset())
			));
			return SharedSuggestionProvider.suggest(paths.keySet(), builder);
		} catch (RuntimeException ignored) {
			return builder.buildFuture();
		}
	}

	private static String resolvePath(Map<String, JsonElement> paths, String requested) throws CommandSyntaxException {
		String normalized = requested.trim().replace('/', '.');
		while (normalized.startsWith(".")) {
			normalized = normalized.substring(1);
		}
		String resolvedInput = normalized;
		if (paths.containsKey(normalized)) {
			return normalized;
		}
		String lower = normalized.toLowerCase(Locale.ROOT);
		List<String> matches = paths.keySet().stream()
			.filter(path -> path.equalsIgnoreCase(resolvedInput)
				|| path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).equals(lower))
			.toList();
		if (matches.isEmpty()) {
			throw ERROR_UNKNOWN_SETTING.create(requested);
		}
		if (matches.size() > 1) {
			throw ERROR_AMBIGUOUS_SETTING.create(requested);
		}
		return matches.getFirst();
	}

	private static Map<String, JsonElement> collectPaths(JsonElement root) {
		Map<String, JsonElement> paths = new LinkedHashMap<>();
		if (root.isJsonObject()) {
			root.getAsJsonObject().entrySet().forEach(entry -> collectPaths(entry.getValue(), entry.getKey(), paths));
		}
		return paths;
	}

	private static void collectPaths(JsonElement value, String path, Map<String, JsonElement> paths) {
		paths.put(path, value);
		if (value.isJsonObject()) {
			value.getAsJsonObject().entrySet().forEach(entry ->
				collectPaths(entry.getValue(), path + "." + entry.getKey(), paths)
			);
		}
	}

	private static ActivePreset requireActive(CommandSourceStack source) throws CommandSyntaxException {
		ActivePreset active = ActivePresetSnapshotService.find(source);
		if (active == null) {
			throw ERROR_NOT_FTF.create();
		}
		return active;
	}

	private static MutableComponent copyComponent(String display, String copied) {
		return Component.literal(display).withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copied))
			.withHoverEvent(new HoverEvent(
				HoverEvent.Action.SHOW_TEXT,
				Component.translatableWithFallback(KEY + "copy", "Click to copy")
			))
			.withInsertion(copied));
	}

	private static void sendToClient(
		CommandSourceStack source,
		ActivePreset active,
		ResourceLocation purpose,
		String content
	) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return;
		}
		ActivePresetSnapshot snapshot = ActivePresetSnapshotService.snapshot(source, active);
		if (!content.equals(snapshot.presetJson())) {
			snapshot = new ActivePresetSnapshot(
				content,
				snapshot.dimension(),
				snapshot.seed(),
				snapshot.playerX(),
				snapshot.playerZ(),
				snapshot.spawnX(),
				snapshot.spawnZ(),
				snapshot.biomeReplacements()
			);
		}
		player.connection.send(new ClientboundCustomPayloadPacket(
			new ActivePresetSnapshotPayload(purpose, snapshot)
		));
	}
}
