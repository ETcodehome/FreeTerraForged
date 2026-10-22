package etcodehome.freeterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.Lifecycle;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.dimension.LevelStem;

class BiomeReplacementCompilerTest {
	static {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final ResourceKey<LevelStem> OVERWORLD = LevelStem.OVERWORLD;
	private static final ResourceKey<LevelStem> NETHER = LevelStem.NETHER;

	@Test
	void bindsTagsThenDirectRulesAndAppliesSelectedDimensionOverlay() {
		Fixture fixture = fixture();
		BiomeReplacementConfigSnapshot config = snapshot("""
			format_version=1
			validation=strict
			placement=preserve
			#test:forests > test:desert
			test:forest > test:plains
			[minecraft:the_nether]
			test:forest > test:nether_wastes
			""");

		BiomeReplacementPlan overworld = BiomeReplacementCompiler.compile(
			owner(fixture.registries, OVERWORLD, config), ignored -> false
		);
		assertSame(fixture.plains, overworld.apply(fixture.forest, null));
		assertSame(fixture.desert, overworld.apply(fixture.birchForest, null));
		assertEquals(2, overworld.decisionCount());

		BiomeReplacementPlan nether = BiomeReplacementCompiler.compile(
			owner(fixture.registries, NETHER, config), ignored -> false
		);
		assertSame(fixture.netherWastes, nether.apply(fixture.forest, null));
		assertSame(fixture.desert, nether.apply(fixture.birchForest, null));
	}

	@Test
	void selfReplacementIsDiscardedButStillMasksEarlierAndGlobalRules() {
		Fixture fixture = fixture();
		BiomeReplacementConfigSnapshot config = snapshot("""
			format_version=1
			validation=strict
			placement=preserve
			#test:forests > test:desert
			test:forest > test:forest
			[minecraft:the_nether]
			test:birch_forest > test:birch_forest
			""");

		BiomeReplacementPlan overworld = BiomeReplacementCompiler.compile(
			owner(fixture.registries, OVERWORLD, config), ignored -> false
		);
		assertSame(fixture.forest, overworld.apply(fixture.forest, null));
		assertSame(fixture.desert, overworld.apply(fixture.birchForest, null));
		assertEquals(1, overworld.decisionCount());

		BiomeReplacementPlan nether = BiomeReplacementCompiler.compile(
			owner(fixture.registries, NETHER, config), ignored -> false
		);
		assertSame(fixture.forest, nether.apply(fixture.forest, null));
		assertSame(fixture.birchForest, nether.apply(fixture.birchForest, null));
		assertEquals(0, nether.decisionCount());
		assertTrue(nether.diagnostics().stream().anyMatch(diagnostic ->
			diagnostic.code().equals("self_replacement")
		));
	}

	@Test
	void compatibilityModeSkipsMissingBiomesButRetainsValidRules() {
		Fixture fixture = fixture();
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(
			owner(fixture.registries, OVERWORLD, snapshot("""
				format_version=1
				validation=compatibility
				placement=preserve
				test:missing > test:desert
				test:forest > test:plains
				""")),
			ignored -> false
		);

		assertSame(fixture.plains, plan.apply(fixture.forest, null));
		assertTrue(plan.diagnostics().stream().anyMatch(diagnostic ->
			diagnostic.code().equals("missing_biome")
				&& diagnostic.severity() == BiomeReplacementConfig.Severity.WARNING
		));
	}

	@Test
	void markerIncludedByTagIsAlwaysRejected() {
		Fixture fixture = fixture(true);
		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> BiomeReplacementCompiler.compile(
				owner(fixture.registries, OVERWORLD, snapshot("""
					format_version=1
					validation=compatibility
					placement=preserve
					#test:forests > test:desert
					""")),
				ignored -> false
			)
		);
		assertTrue(failure.getMessage().contains("integration_marker"));
	}

	private static Fixture fixture() {
		return fixture(false);
	}

	private static Fixture fixture(boolean includeMarkerInTag) {
		MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
		Holder.Reference<Biome> forest = register(biomes, "test:forest");
		Holder.Reference<Biome> birchForest = register(biomes, "test:birch_forest");
		Holder.Reference<Biome> desert = register(biomes, "test:desert");
		Holder.Reference<Biome> plains = register(biomes, "test:plains");
		Holder.Reference<Biome> netherWastes = register(biomes, "test:nether_wastes");
		Holder.Reference<Biome> marker = register(biomes, "terrablender:deferred_placeholder");
		List<Holder<Biome>> tagMembers = includeMarkerInTag
			? List.of(forest, birchForest, marker)
			: List.of(forest, birchForest);
		biomes.bindTags(Map.of(
			TagKey.create(Registries.BIOME, ResourceLocation.parse("test:forests")),
			tagMembers
		));
		RegistryAccess.Frozen registries = new RegistryAccess.ImmutableRegistryAccess(
			List.of(biomes.freeze())
		).freeze();
		return new Fixture(registries, forest, birchForest, desert, plains, netherWastes);
	}

	private static Holder.Reference<Biome> register(MappedRegistry<Biome> registry, String id) {
		return registry.register(
			ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id)),
			new Biome.BiomeBuilder()
				.hasPrecipitation(true)
				.temperature(0.5F)
				.downfall(0.5F)
				.specialEffects(new BiomeSpecialEffects.Builder()
					.fogColor(0)
					.waterColor(0)
					.waterFogColor(0)
					.skyColor(0)
					.build())
				.mobSpawnSettings(MobSpawnSettings.EMPTY)
				.generationSettings(BiomeGenerationSettings.EMPTY)
				.build(),
			RegistrationInfo.BUILT_IN
		);
	}

	private static BiomeReplacementConfigSnapshot snapshot(String content) {
		return new BiomeReplacementConfigSnapshot(
			1L, "test-fingerprint", "test.properties",
			BiomeReplacementConfigSnapshot.Origin.NATIVE,
			content, BiomeReplacementConfig.parse(content), false
		);
	}

	private static WorldgenOwner owner(
		RegistryAccess.Frozen registries,
		ResourceKey<LevelStem> dimension,
		BiomeReplacementConfigSnapshot config
	) {
		return new WorldgenOwner() {
			@Override public UUID id() { return new UUID(0L, 1L); }
			@Override public WorldgenOwnerType type() { return WorldgenOwnerType.WORLDGEN_EPOCH; }
			@Override public long seed() { return 1L; }
			@Override public RegistryAccess.Frozen registries() { return registries; }
			@Override public ResourceKey<LevelStem> dimension() { return dimension; }
			@Override public HolderLookup.Provider lookups() { return registries; }
			@Override public LevelStem selectedStem() { throw new UnsupportedOperationException(); }
			@Override public String settingsIdentity() { return "test"; }
			@Override public long resourceRevision() { return 0L; }
			@Override public String resourceLayerFingerprint() { return "test"; }
			@Override public TagEpoch tagEpoch() { return new TagEpoch(0L, "test"); }
			@Override public BiomeReplacementConfigSnapshot biomeReplacementConfig() { return config; }
			@Override public WorldgenContributionRevision.Snapshot contributionRevision() {
				return WorldgenContributionRevision.Snapshot.empty(dimension.location());
			}
		};
	}

	private record Fixture(
		RegistryAccess.Frozen registries,
		Holder<Biome> forest,
		Holder<Biome> birchForest,
		Holder<Biome> desert,
		Holder<Biome> plains,
		Holder<Biome> netherWastes
	) {
	}
}
