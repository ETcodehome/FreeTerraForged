package raccoonman.reterraforged.network;

import java.util.Objects;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import raccoonman.reterraforged.world.worldgen.FlowSettingsSnapshot;

/** Per-connection state that suppresses redundant flow-settings synchronization. */
public final class FlowSettingsSyncState {
	private ResourceKey<Level> dimension;
	private byte settings = -1;

	public boolean update(ResourceKey<Level> dimension, FlowSettingsSnapshot settings) {
		Objects.requireNonNull(dimension, "dimension");
		byte encoded = Objects.requireNonNull(settings, "settings").encode();
		if (dimension.equals(this.dimension) && encoded == this.settings) {
			return false;
		}
		byte previous = this.settings;
		this.dimension = dimension;
		this.settings = encoded;
		return encoded != 0 || previous > 0;
	}
}
