package raccoonman.reterraforged.world.worldgen;

import java.util.Objects;

import raccoonman.reterraforged.data.worldgen.preset.settings.FlowSettings;

public record FlowSettingsSnapshot(
	boolean flowParticles,
	boolean boatFlowDynamics,
	boolean navigableWaterfalls
) {
	public static final FlowSettingsSnapshot DISABLED = new FlowSettingsSnapshot(false, false, false);

	public static FlowSettingsSnapshot from(FlowSettings settings) {
		Objects.requireNonNull(settings, "settings");
		return new FlowSettingsSnapshot(
			settings.enableFlowParticles(),
			settings.enableBoatFlowDynamics(),
			settings.enableNavigableWaterfalls()
		);
	}

	public static FlowSettingsSnapshot decode(byte value) {
		return new FlowSettingsSnapshot(
			(value & 1) != 0,
			(value & 2) != 0,
			(value & 4) != 0
		);
	}

	public byte encode() {
		int value = 0;
		if (this.flowParticles) {
			value |= 1;
		}
		if (this.boatFlowDynamics) {
			value |= 2;
		}
		if (this.navigableWaterfalls) {
			value |= 4;
		}
		return (byte) value;
	}
}
