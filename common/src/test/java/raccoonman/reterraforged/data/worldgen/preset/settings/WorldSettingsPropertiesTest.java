package raccoonman.reterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

class WorldSettingsPropertiesTest {
	@Test
	void copiedPresetOwnsItsSpawnConfiguration() {
		Preset first = Presets.makeRTFDefault();
		Preset second = first.copy();
		WorldSettings.Properties firstProperties = first.world().properties;
		WorldSettings.Properties secondProperties = second.world().properties;

		assertNotSame(firstProperties, secondProperties);
		secondProperties.spawnType = SpawnType.USER_SELECTED;
		secondProperties.spawnX = 1234;
		secondProperties.spawnZ = -5678;

		assertEquals(SpawnType.CONTINENT_CENTER, firstProperties.spawnType);
		assertEquals(0, firstProperties.spawnX);
		assertEquals(0, firstProperties.spawnZ);
		assertEquals(SpawnType.USER_SELECTED, secondProperties.spawnType);
		assertEquals(1234, secondProperties.spawnX);
		assertEquals(-5678, secondProperties.spawnZ);
	}
}
