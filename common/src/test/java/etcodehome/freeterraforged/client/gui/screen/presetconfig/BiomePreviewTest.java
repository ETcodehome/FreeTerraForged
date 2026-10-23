package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementPlan;

class BiomePreviewTest {
	@Test
	void hatchPatternRemainsVisibleAtEverySupportedZoom() {
		for (int zoom = 1; zoom <= 100; zoom++) {
			for (int center : List.of(-97, 0, 113)) {
				int phase = BiomePreview.hatchPhase(center, center, zoom);
				for (int z = 0; z < 4; z++) {
					for (int x = 0; x < 4; x++) {
						assertEquals(
							Math.floorMod(phase + x + z, 4),
							BiomePreview.hatchPhase(
								center + x * zoom,
								center + z * zoom,
								zoom
							)
						);
					}
				}
			}
		}
	}

	@Test
	void hatchPatternRejectsInvalidZoom() {
		assertThrows(IllegalArgumentException.class, () -> BiomePreview.hatchPhase(0, 0, 0));
	}

	@Test
	void overrideTooltipUsesOnePlainEnglishLine() {
		BiomePreview.OverrideInfo override = new BiomePreview.OverrideInfo(
			BiomeReplacementPlan.OverrideKind.REPLACED,
			"minecraft:dark_forest",
			"minecraft:cherry_grove",
			0,
			14,
			"minecraft:dark_forest"
		);

		assertEquals(
			List.of("Biome Replacement: minecraft:dark_forest -> minecraft:cherry_grove"),
			override.details()
		);
	}
}
