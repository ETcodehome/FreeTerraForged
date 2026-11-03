package etcodehome.freeterraforged.client.network;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ActivePresetSnapshot;
import etcodehome.freeterraforged.network.ActivePresetSnapshotPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-side purpose router for the shared active-preset snapshot transport. */
public final class ActivePresetSnapshotHandlers {
	private static final Map<ResourceLocation, Consumer<ActivePresetSnapshot>> HANDLERS = new ConcurrentHashMap<>();

	private ActivePresetSnapshotHandlers() {
	}

	public static void register(ResourceLocation purpose, Consumer<ActivePresetSnapshot> handler) {
		Objects.requireNonNull(purpose, "purpose");
		Objects.requireNonNull(handler, "handler");
		if (HANDLERS.putIfAbsent(purpose, handler) != null) {
			throw new IllegalStateException("Active preset snapshot handler already registered for " + purpose);
		}
	}

	public static void handle(ActivePresetSnapshotPayload payload) {
		Consumer<ActivePresetSnapshot> handler = HANDLERS.get(payload.purpose());
		if (handler == null) {
			FTFCommon.LOGGER.warn("Ignoring active preset snapshot with unknown purpose {}", payload.purpose());
			return;
		}
		handler.accept(payload.snapshot());
	}
}
