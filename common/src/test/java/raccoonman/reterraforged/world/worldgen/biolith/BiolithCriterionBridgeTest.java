package raccoonman.reterraforged.world.worldgen.biolith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.terraformersmc.biolith.api.biome.BiolithFittestNodes;
import com.terraformersmc.biolith.api.biome.sub.Criterion;
import com.terraformersmc.biolith.api.biome.sub.BiomeParameterTargets;
import com.terraformersmc.biolith.api.biome.sub.CriterionBuilder;
import com.terraformersmc.biolith.api.biome.sub.CriterionType;
import com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlans;

class BiolithCriterionBridgeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void capturesEveryBuiltInShapeUsedByNoMansLandIntoOwnedNodes() {
		ResourceKey<Biome> alternate = biomeKey("alternate");
		BiolithCriterionBridge.Snapshot snapshot = BiolithCriterionBridge.capture(
			CriterionBuilder.allOf(
				CriterionBuilder.neighbor(BiomeTags.IS_OCEAN),
				CriterionBuilder.not(CriterionBuilder.NEAR_INTERIOR),
				CriterionBuilder.anyOf(
					CriterionBuilder.value(BiomeParameterTargets.TEMPERATURE, -0.5F, 0.5F),
					CriterionBuilder.deviationMin(BiomeParameterTargets.HUMIDITY, 0.05F)
				),
				CriterionBuilder.alternate(alternate, biomeKey("as_if"))
			)
		);

		assertTrue(snapshot.failure().isEmpty());
		BiolithCriterionBridge.AllOf root = assertInstanceOf(
			BiolithCriterionBridge.AllOf.class, snapshot.node().orElseThrow()
		);
		assertEquals(4, root.criteria().size());
		BiolithCriterionBridge.Neighbor neighbor = assertInstanceOf(
			BiolithCriterionBridge.Neighbor.class, root.criteria().getFirst()
		);
		assertEquals(BiomeTags.IS_OCEAN, neighbor.target().tag().orElseThrow());
		assertInstanceOf(BiolithCriterionBridge.Not.class, root.criteria().get(1));
		assertInstanceOf(BiolithCriterionBridge.AnyOf.class, root.criteria().get(2));
		assertInstanceOf(BiolithCriterionBridge.Alternate.class, root.criteria().get(3));
	}

	@Test
	void evaluatesValueDeviationCenterAndEdgeFromOwnedCandidateMetadata() {
		Holder<Biome> base = biome("base");
		Holder<Biome> neighbor = biome("neighbor");
		Climate.ParameterPoint point = new Climate.ParameterPoint(
			Climate.Parameter.span(-0.1F, 0.1F),
			Climate.Parameter.span(0.2F, 0.4F),
			Climate.Parameter.point(0),
			Climate.Parameter.point(0),
			Climate.Parameter.point(0),
			Climate.Parameter.point(0),
			0L
		);
		Climate.TargetPoint target = Climate.target(0, 0.5F, 0, 0, 0, 0);
		WorldgenPlans.ProviderResult selection = new WorldgenPlans.ProviderResult(
			id("domain"), base, base, false,
			new Climate.ParameterList<>(List.of(Pair.of(point, base))), target
		);
		WorldgenPlans.CandidateFit candidates = new WorldgenPlans.CandidateFit(
			new WorldgenPlans.CandidateMatch(point, base, 1L),
			Optional.of(new WorldgenPlans.CandidateMatch(
				Climate.parameters(1, 1, 1, 1, 1, 1, 0), neighbor, 4L
			))
		);
		BiolithCriterionBridge.Evaluation evaluation = new BiolithCriterionBridge.Evaluation(
			candidates, target,
			0, -64, 320, 63,
			ignored -> base,
			null
		);

		assertTrue(BiolithCriterionBridge.matches(
			new BiolithCriterionBridge.Numeric(
				BiolithCriterionBridge.NumericKind.VALUE,
				BiolithCriterionBridge.ParameterTarget.TEMPERATURE,
				-0.01F, 0.01F
			),
			evaluation
		));
		assertTrue(BiolithCriterionBridge.matches(
			new BiolithCriterionBridge.Numeric(
				BiolithCriterionBridge.NumericKind.DEVIATION,
				BiolithCriterionBridge.ParameterTarget.HUMIDITY,
				0.19F, 0.21F
			),
			evaluation
		));
		assertTrue(BiolithCriterionBridge.matches(
			new BiolithCriterionBridge.Ratio(
				BiolithCriterionBridge.RatioTarget.EDGE, 0.74F, 0.76F
			),
			evaluation
		));
		assertFalse(BiolithCriterionBridge.matches(
			new BiolithCriterionBridge.Ratio(
				BiolithCriterionBridge.RatioTarget.CENTER, 0.0F, 0.1F
			),
			evaluation
		));
	}

	@Test
	void depthOceanUsesOwnerBoundsAndSeaLevelWithoutAWorldReference() {
		Holder<Biome> base = biome("base");
		WorldgenPlans.ProviderResult selection = new WorldgenPlans.ProviderResult(
			id("domain"), base, false
		);
		BiolithCriterionBridge.Evaluation evaluation = new BiolithCriterionBridge.Evaluation(
			selection.candidateFit(),
			Climate.target(0, 0, 0, 0, 0, 0),
			-16, -64, 320, 63,
			ignored -> base,
			null
		);
		assertTrue(BiolithCriterionBridge.matches(
			new BiolithCriterionBridge.Numeric(
				BiolithCriterionBridge.NumericKind.VALUE,
				BiolithCriterionBridge.ParameterTarget.DEPTH_OCEAN,
				0.9F, Float.POSITIVE_INFINITY
			),
			evaluation
		));
	}

	@Test
	void replacementRangeParticipatesInCenterAndEdgeRatios() {
		Holder<Biome> base = biome("base");
		Climate.ParameterPoint point = Climate.parameters(0, 0, 0, 0, 0, 0, 0);
		WorldgenPlans.CandidateFit candidates = new WorldgenPlans.CandidateFit(
			new WorldgenPlans.CandidateMatch(point, base, 0L),
			Optional.empty()
		);
		BiolithCriterionBridge.Evaluation evaluation = new BiolithCriterionBridge.Evaluation(
			candidates,
			Climate.target(0, 0, 0, 0, 0, 0),
			0, -64, 320, 63,
			ignored -> base,
			new BiolithCriterionBridge.ReplacementContext(0.2F, 0.6F, 0.21F)
		);

		assertTrue(BiolithCriterionBridge.matches(
			new BiolithCriterionBridge.Ratio(
				BiolithCriterionBridge.RatioTarget.CENTER, 0.19F, 0.21F
			),
			evaluation
		));
		assertTrue(BiolithCriterionBridge.matches(
			new BiolithCriterionBridge.Ratio(
				BiolithCriterionBridge.RatioTarget.EDGE, 0.0F, 0.02F
			),
			evaluation
		));
	}

	@Test
	void customCriterionFailsClosedAtAcquisition() {
		BiolithCriterionBridge.Snapshot snapshot = BiolithCriterionBridge.capture(new CustomCriterion());

		assertTrue(snapshot.node().isEmpty());
		assertTrue(snapshot.failure().orElseThrow().contains("no immutable FTF evaluation contract"));
	}

	@Test
	void tagTargetsAreFrozenIntoOwnerScopedBiomeKeys() {
		MappedRegistry<Biome> biomes = new MappedRegistry<>(
			Registries.BIOME, com.mojang.serialization.Lifecycle.stable()
		);
		ResourceKey<Biome> key = biomeKey("tag_member");
		Holder.Reference<Biome> holder = biomes.register(
			key,
			new Biome.BiomeBuilder()
				.hasPrecipitation(true)
				.temperature(0.8F)
				.downfall(0.4F)
				.specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder()
					.fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
				.mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
				.generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
				.build(),
			RegistrationInfo.BUILT_IN
		);
		biomes.freeze();
		TagKey<Biome> tag = TagKey.create(Registries.BIOME, id("owner_tag"));
		biomes.bindTags(Map.of(tag, List.of(holder)));
		BiolithCriterionBridge.BiomeTarget unresolved = new BiolithCriterionBridge.BiomeTarget(
			Optional.empty(), Optional.of(tag)
		);

		BiolithCriterionBridge.BiomeTarget bound = unresolved.bind(biomes);
		biomes.bindTags(Map.of());

		assertThrows(IllegalStateException.class, () -> unresolved.matches(holder));
		assertTrue(bound.matches(holder));
		assertEquals(Set.of(key), bound.resolvedTag().orElseThrow());
	}

	private static ResourceKey<Biome> biomeKey(String path) {
		return ResourceKey.create(Registries.BIOME, id(path));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Holder<Biome> biome(String value) {
		return (Holder) Holder.direct(value);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}

	private static final class CustomCriterion implements Criterion {
		private final CriterionType<CustomCriterion> type = CriterionType.createType(
			MapCodec.unit(this), id("custom")
		);

		@Override
		public CriterionType<CustomCriterion> getType() {
			return this.type;
		}

		@Override
		public MapCodec<CustomCriterion> getCodec() {
			return this.type.getCodec();
		}

		@Override
		public boolean matches(
			BiolithFittestNodes<Holder<Biome>> fittestNodes,
			DimensionBiomePlacement biomePlacement,
			Climate.TargetPoint noisePoint,
			InclusiveRange<Float> replacementRange,
			float replacementNoise
		) {
			return true;
		}
	}
}
