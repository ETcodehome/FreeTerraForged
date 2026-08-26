package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;

class UndergroundBiomeSurfaceQueryTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void usesOnlyTheMatchingSampleContext() {
		Climate.Sampler sampler = Climate.empty();
		Climate.TargetPoint target = Climate.target(
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			NoiseRouterData.GLOBAL_OFFSET + 0.5F,
			0.0F
		);

		UndergroundBiomeSurfaceQuery.record(sampler, target, 3, 4, 5);

		assertEquals(0.0F, UndergroundBiomeSurfaceQuery.coverageFactor(target, 3, 4, 5));
		assertEquals(1.0F, UndergroundBiomeSurfaceQuery.coverageFactor(target, 4, 4, 5));
		assertEquals(1.0F, UndergroundBiomeSurfaceQuery.coverageFactor(
			Climate.target(0.0F, 0.0F, 0.0F, 0.0F, NoiseRouterData.GLOBAL_OFFSET + 0.5F, 0.0F),
			3,
			4,
			5
		));
	}
}
