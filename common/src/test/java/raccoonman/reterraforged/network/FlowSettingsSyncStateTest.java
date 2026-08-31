package raccoonman.reterraforged.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.world.worldgen.FlowSettingsSnapshot;

class FlowSettingsSyncStateTest {
	private static final ResourceKey<Level> ALTERNATE = ResourceKey.create(
		Registries.DIMENSION,
		ResourceLocation.fromNamespaceAndPath("reterraforged", "alternate")
	);

	@Test
	void synchronizesFirstUseChangesAndDimensionTransitionsOnly() {
		FlowSettingsSyncState state = new FlowSettingsSyncState();
		FlowSettingsSnapshot enabled = new FlowSettingsSnapshot(true, true, true);
		FlowSettingsSnapshot changed = new FlowSettingsSnapshot(false, true, true);

		assertTrue(state.update(Level.OVERWORLD, enabled));
		assertFalse(state.update(Level.OVERWORLD, enabled));
		assertTrue(state.update(Level.OVERWORLD, changed));
		assertFalse(state.update(Level.OVERWORLD, changed));
		assertTrue(state.update(ALTERNATE, changed));
		assertFalse(state.update(ALTERNATE, changed));
		assertTrue(state.update(Level.OVERWORLD, changed));
	}
}
