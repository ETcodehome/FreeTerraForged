package etcodehome.freeterraforged.server.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.network.ActivePresetSnapshotPayload;
import etcodehome.freeterraforged.network.PreviewSnapshotPurpose;
import etcodehome.freeterraforged.server.preset.ActivePresetSnapshotService;
import etcodehome.freeterraforged.server.preset.ActivePresetSnapshotService.ActivePreset;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;

/** Opens an in-game preview of the preset that owns the active FTF worldgen runtime. */
public final class PreviewCommand {
	private static final String KEY = FTFCommon.MOD_ID + ".commands.preview.";
	private static final SimpleCommandExceptionType ERROR_NOT_FTF = new SimpleCommandExceptionType(
		Component.translatableWithFallback(KEY + "not_ftf", "No loaded FreeTerraForged dimension has an active preset")
	);
	private static final SimpleCommandExceptionType ERROR_PLAYER_ONLY = new SimpleCommandExceptionType(
		Component.translatableWithFallback(KEY + "player_only", "This command can only be used by a player")
	);

	private PreviewCommand() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("preview").executes(context -> open(context.getSource()));
	}

	private static int open(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			throw ERROR_PLAYER_ONLY.create();
		}
		ActivePreset active = ActivePresetSnapshotService.find(source);
		if (active == null) {
			throw ERROR_NOT_FTF.create();
		}
		player.connection.send(new ClientboundCustomPayloadPacket(new ActivePresetSnapshotPayload(
			PreviewSnapshotPurpose.OPEN,
			ActivePresetSnapshotService.snapshot(source, active)
		)));
		return Command.SINGLE_SUCCESS;
	}
}
