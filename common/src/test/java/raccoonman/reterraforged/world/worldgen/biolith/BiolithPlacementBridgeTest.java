package raccoonman.reterraforged.world.worldgen.biolith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

class BiolithPlacementBridgeTest {
	@Test
	void dataReloadPreservesEquivalentCodeReplacementAndDropsEmptyTargets() {
		BiolithPlacementBridge.Collector collector = new BiolithPlacementBridge.Collector();
		ResourceKey<Biome> sharedTarget = biome("shared_target");
		ResourceKey<Biome> dataOnlyTarget = biome("data_only_target");
		ResourceKey<Biome> output = biome("output");

		assertTrue(collector.addReplacement(new BiolithPlacementBridge.Replacement(
			sharedTarget, output, 0.3D, true
		)));
		assertTrue(collector.addReplacement(new BiolithPlacementBridge.Replacement(
			sharedTarget, output, 0.3D, false
		)));
		assertTrue(collector.addReplacement(new BiolithPlacementBridge.Replacement(
			dataOnlyTarget, output, 0.5D, true
		)));
		assertFalse(collector.addReplacement(new BiolithPlacementBridge.Replacement(
			sharedTarget, output, 0.3D, false
		)));

		assertTrue(collector.clearFromData());
		BiolithPlacementBridge.Snapshot snapshot = collector.snapshot(
			BiolithPlacementBridge.Dimension.OVERWORLD, BiolithPlacementBridge.SUPPORTED_VERSION
		);

		assertEquals(1, snapshot.replacements().size());
		assertEquals(
			new BiolithPlacementBridge.Replacement(sharedTarget, output, 0.3D, false),
			snapshot.replacements().get(sharedTarget).getFirst()
		);
	}

	private static ResourceKey<Biome> biome(String path) {
		return ResourceKey.create(
			Registries.BIOME,
			ResourceLocation.fromNamespaceAndPath("test", path)
		);
	}
}
