package raccoonman.reterraforged.world.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.data.worldgen.preset.settings.FlowSettings;

class FlowSettingsSnapshotTest {
	@Test
	void preservesEverySettingsCombinationAcrossTheNetworkByte() {
		for (int value = 0; value < 8; value++) {
			FlowSettingsSnapshot decoded = FlowSettingsSnapshot.decode((byte) value);
			assertEquals((byte) value, decoded.encode());
		}
	}

	@Test
	void copiesMutablePresetSettingsIntoAnImmutableValue() {
		FlowSettings source = new FlowSettings(false, true, false);
		FlowSettingsSnapshot snapshot = FlowSettingsSnapshot.from(source);

		source.boatFlowDynamics = false;

		assertEquals(new FlowSettingsSnapshot(false, true, false), snapshot);
	}

	@Test
	void chunkPersistenceContainsOnlyCalculatedFlowData() {
		ChunkFlowField field = new ChunkFlowField();
		field.setFlow(0, 0, (byte) 1);
		CompoundTag tag = new CompoundTag();

		field.writeToNbt(tag);

		assertFalse(tag.contains("RTFFlowSettings"));
		assertEquals(256, tag.getByteArray("RTFFlowField").length);
	}

	@Test
	void legacyChunkSettingsAreIgnoredAndNotWrittenAgain() {
		CompoundTag legacy = new CompoundTag();
		byte[] grid = new byte[256];
		grid[17] = 42;
		legacy.putByteArray("RTFFlowField", grid);
		legacy.putByte("RTFFlowSettings", (byte) 0);
		ChunkFlowField field = new ChunkFlowField();

		field.readFromNbt(legacy);
		CompoundTag rewritten = new CompoundTag();
		field.writeToNbt(rewritten);

		assertEquals(42, rewritten.getByteArray("RTFFlowField")[17]);
		assertFalse(rewritten.contains("RTFFlowSettings"));
	}
}
