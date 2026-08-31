package raccoonman.reterraforged.world.worldgen.feature.placement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;

class SurfacePlacementClassifierTest {
	private static RegistryAccess registries;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@Test
	void recognizesThePublicCodecDefinedSurfacePipeline() {
		assertTrue(SurfacePlacementClassifier.classify(feature(
			RandomOffsetPlacement.vertical(ConstantInt.of(1))
		), registries).eligible());
	}

	@Test
	void rejectsAHorizontalOffsetWithoutGuessingItsIntent() {
		assertFalse(SurfacePlacementClassifier.classify(feature(
			RandomOffsetPlacement.of(ConstantInt.of(1), ConstantInt.of(1))
		), registries).eligible());
	}

	private static PlacedFeature feature(RandomOffsetPlacement offset) {
		List<PlacementModifier> placements = List.of(
			CountPlacement.of(3),
			InSquarePlacement.spread(),
			HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.absolute(256)),
			EnvironmentScanPlacement.scanningFor(
				Direction.DOWN,
				BlockPredicate.solid(),
				BlockPredicate.ONLY_IN_AIR_PREDICATE,
				32
			),
			offset,
			BiomeFilter.biome()
		);
		return new PlacedFeature(
			Holder.direct(new ConfiguredFeature<>(Feature.NO_OP, NoneFeatureConfiguration.INSTANCE)),
			placements
		);
	}
}
