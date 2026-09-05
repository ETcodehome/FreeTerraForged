package raccoonman.reterraforged.world.worldgen.feature.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;

class ChunkLocalPlacementClassifierTest {
	private static final ResourceLocation FEATURE_ID = ResourceLocation.fromNamespaceAndPath(
		"test", "chunk_local"
	);
	private static RegistryAccess registries;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@Test
	void compilesExactNestedOffsetForInSquareRoot() {
		RandomOffsetPlacement offset = RandomOffsetPlacement.of(
			UniformInt.of(-2, 2),
			UniformInt.of(-8, 0)
		);
		var classification = ChunkLocalPlacementClassifier.classify(
			root(List.of(CountPlacement.of(256), InSquarePlacement.spread(), BiomeFilter.biome()), offset),
			Optional.of(FEATURE_ID),
			registries
		);

		assertTrue(classification.eligible());
		assertEquals(FEATURE_ID, classification.confinement().featureId());
		assertEquals(1, classification.confinement().offsets().size());
		assertSame(offset, classification.confinement().offsets().iterator().next());
	}

	@Test
	void compilesExactNestedOffsetForWholeChunkRandomOffsetRoot() {
		RandomOffsetPlacement nested = RandomOffsetPlacement.horizontal(UniformInt.of(-2, 2));
		var classification = ChunkLocalPlacementClassifier.classify(
			root(List.of(
				CountPlacement.of(256),
				RandomOffsetPlacement.of(UniformInt.of(0, 15), ConstantInt.of(0)),
				BiomeFilter.biome()
			), nested),
			Optional.of(FEATURE_ID),
			registries
		);

		assertTrue(classification.eligible());
		assertTrue(classification.confinement().contains(nested));
	}

	@Test
	void rejectsMissingRootIdentity() {
		PlacedFeature feature = root(
			List.of(CountPlacement.of(256), InSquarePlacement.spread()),
			RandomOffsetPlacement.horizontal(UniformInt.of(-2, 2))
		);

		assertFalse(ChunkLocalPlacementClassifier.classify(
			feature, Optional.empty(), registries
		).eligible());
	}

	@Test
	void rejectsRootWithoutChunkLocalScatter() {
		PlacedFeature feature = root(
			List.of(CountPlacement.of(256)),
			RandomOffsetPlacement.horizontal(UniformInt.of(-2, 2))
		);

		assertFalse(ChunkLocalPlacementClassifier.classify(
			feature, Optional.of(FEATURE_ID), registries
		).eligible());
	}

	@Test
	void rejectsNestedPipelineWithAnotherHorizontalTransform() {
		RandomOffsetPlacement offset = RandomOffsetPlacement.horizontal(UniformInt.of(-2, 2));
		PlacedFeature nested = new PlacedFeature(
			Holder.direct(new ConfiguredFeature<>(Feature.NO_OP, NoneFeatureConfiguration.INSTANCE)),
			List.of(InSquarePlacement.spread(), offset)
		);

		assertFalse(ChunkLocalPlacementClassifier.classify(
			root(
				List.of(CountPlacement.of(256), InSquarePlacement.spread()),
				Holder.direct(nested)
			),
			Optional.of(FEATURE_ID),
			registries
		).eligible());
	}

	private static PlacedFeature root(
		List<PlacementModifier> rootPlacements,
		RandomOffsetPlacement nestedOffset
	) {
		PlacedFeature nested = new PlacedFeature(
			Holder.direct(new ConfiguredFeature<>(Feature.NO_OP, NoneFeatureConfiguration.INSTANCE)),
			List.of(CountPlacement.of(256), nestedOffset)
		);
		return root(rootPlacements, Holder.direct(nested));
	}

	private static PlacedFeature root(
		List<PlacementModifier> rootPlacements,
		Holder<PlacedFeature> nested
	) {
		RandomFeatureConfiguration random = new RandomFeatureConfiguration(List.of(), nested);
		return new PlacedFeature(
			Holder.direct(new ConfiguredFeature<>(Feature.RANDOM_SELECTOR, random)),
			rootPlacements
		);
	}
}
