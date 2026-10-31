package etcodehome.freeterraforged.client.network;

import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import etcodehome.freeterraforged.network.ActivePresetSnapshotPayload;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetViewScreen;
import etcodehome.freeterraforged.network.PresetSnapshotPurpose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import etcodehome.freeterraforged.network.FlowFieldSyncPayload;
import etcodehome.freeterraforged.network.FlowSettingsSyncPayload;
import etcodehome.freeterraforged.world.worldgen.IFlowSettingsHolder;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;

public class FTFClientPayloadHandler {
	static {
		ActivePresetSnapshotHandlers.register(PresetSnapshotPurpose.COPY, snapshot ->
			Minecraft.getInstance().keyboardHandler.setClipboard(snapshot.presetJson())
		);
		ActivePresetSnapshotHandlers.register(PresetSnapshotPurpose.VIEW, snapshot ->
			Minecraft.getInstance().setScreen(new PresetViewScreen(snapshot.presetJson(), snapshot.dimension()))
		);
	}

	public static void handleActivePresetSnapshot(ActivePresetSnapshotPayload payload) {
		ActivePresetSnapshotHandlers.handle(payload);
	}

    public static void handleFlowFieldSync(FlowFieldSyncPayload payload, Player player) {
        if (player != null && player.level() instanceof ClientLevel clientLevel) {
            ChunkAccess chunk = clientLevel.getChunk(payload.pos().x, payload.pos().z, ChunkStatus.FULL, false);
            if (chunk instanceof IFlowFieldHolder holder) {
                holder.freeterraforged$getOrCreateFlowField().loadRawGrid(payload.rawGrid());
			}
		}
	}

	public static void handleFlowSettingsSync(FlowSettingsSyncPayload payload, Player player) {
		if (player != null && player.level() instanceof ClientLevel clientLevel) {
			((IFlowSettingsHolder) clientLevel).freeterraforged$setFlowSettings(payload.settings());
		}
	}

}
