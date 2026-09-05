package raccoonman.reterraforged.world.worldgen.feature.placement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;

class DynamicHeightRangePlacementTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void recognizesOnlyTheCodecDefinedCanonicalRange() {
		assertTrue(DynamicHeightRangePlacement.isCanonicalRange(
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(256))
		));
		assertFalse(DynamicHeightRangePlacement.isCanonicalRange(
			HeightRangePlacement.uniform(VerticalAnchor.aboveBottom(1), VerticalAnchor.absolute(256))
		));
		assertFalse(DynamicHeightRangePlacement.isCanonicalRange(
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(255))
		));
		assertFalse(DynamicHeightRangePlacement.isCanonicalRange(
			HeightRangePlacement.triangle(VerticalAnchor.bottom(), VerticalAnchor.absolute(256))
		));
	}
}
