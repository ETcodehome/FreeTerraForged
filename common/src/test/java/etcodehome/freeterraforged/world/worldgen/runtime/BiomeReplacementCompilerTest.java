package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.dimension.LevelStem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeReplacementCompilerTest {

	private static RegistryAccess.Frozen registryAccess;
	private static Holder.Reference<Biome> plains;
	private static Holder.Reference<Biome> desert;
	private static Holder.Reference<Biome> forest;
	private static Holder.Reference<Biome> taiga;
	private static Holder.Reference<Biome> deepDark;
	private static Holder.Reference<Biome> deferredPlaceholder;

	@BeforeAll
	static void setUpRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());

		plains = registry.register(ResourceKey.create(Registries.BIOME, new ResourceLocation("minecraft:plains")), createBiome(), Lifecycle.stable());
		desert = registry.register(ResourceKey.create(Registries.BIOME, new ResourceLocation("minecraft:desert")), createBiome(), Lifecycle.stable());
		forest = registry.register(ResourceKey.create(Registries.BIOME, new ResourceLocation("minecraft:forest")), createBiome(), Lifecycle.stable());
		taiga = registry.register(ResourceKey.create(Registries.BIOME, new ResourceLocation("minecraft:taiga")), createBiome(), Lifecycle.stable());
		deepDark = registry.register(ResourceKey.create(Registries.BIOME, new ResourceLocation("minecraft:deep_dark")), createBiome(), Lifecycle.stable());
		deferredPlaceholder = registry.register(ResourceKey.create(Registries.BIOME, new ResourceLocation("terrablender:deferred_placeholder")), createBiome(), Lifecycle.stable());

		TagKey<Biome> forestTag = TagKey.create(Registries.BIOME, new ResourceLocation("minecraft:is_forest"));
		TagKey<Biome> emptyTag = TagKey.create(Registries.BIOME, new ResourceLocation("minecraft:is_empty_tag"));
		TagKey<Biome> mixedTag = TagKey.create(Registries.BIOME, new ResourceLocation("minecraft:mixed_with_marker"));
		TagKey<Biome> onlyMarkerTag = TagKey.create(Registries.BIOME, new ResourceLocation("minecraft:only_marker"));

		registry.bindTags(Map.of(
			forestTag, List.of(forest),
			emptyTag, List.of(),
			mixedTag, List.of(forest, deferredPlaceholder),
			onlyMarkerTag, List.of(deferredPlaceholder)
		));
		registry.freeze();
		registryAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(registry)).freeze();
	}

	private static Biome createBiome() {
		return new Biome.BiomeBuilder()
			.hasPrecipitation(false)
			.temperature(0.5f)
			.downfall(0.5f)
			.specialEffects(new BiomeSpecialEffects.Builder().waterColor(0).waterFogColor(0).fogColor(0).skyColor(0).build())
			.mobSpawnSettings(MobSpawnSettings.EMPTY)
			.generationSettings(BiomeGenerationSettings.EMPTY)
			.build();
	}

	private static WorldgenOwner createOwner(String configContent, ResourceKey<LevelStem> dimension) {
		BiomeReplacementConfig config = BiomeReplacementConfig.parse(configContent);
		BiomeReplacementConfigSnapshot snapshot = new BiomeReplacementConfigSnapshot(
			1L, "test_fingerprint", "freeterraforged/biome_replacement.properties",
			BiomeReplacementConfigSnapshot.Origin.NATIVE, configContent, config, false
		);

		return new WorldgenOwner() {
			@Override public UUID id() { return UUID.randomUUID(); }
			@Override public WorldgenOwnerType type() { return WorldgenOwnerType.PREVIEW_REQUEST; }
			@Override public long seed() { return 0L; }
			@Override public RegistryAccess.Frozen registries() { return registryAccess; }
			@Override public ResourceKey<LevelStem> dimension() { return dimension; }
			@Override public HolderLookup.Provider lookups() { return null; }
			@Override public LevelStem selectedStem() { return null; }
			@Override public String settingsIdentity() { return "test"; }
			@Override public long resourceRevision() { return 0L; }
			@Override public String resourceLayerFingerprint() { return "test"; }
			@Override public TagEpoch tagEpoch() { return null; }
			@Override public BiomeReplacementConfigSnapshot biomeReplacementConfig() { return snapshot; }
			@Override public WorldgenContributionRevision.Snapshot contributionRevision() { return null; }
		};
	}

	@Test
	void testDirectReplacement() {
		String content = "minecraft:plains > minecraft:desert";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertTrue(plan.active());
		assertFalse(plan.isConfigIgnored());
		assertEquals(1, plan.decisionCount());
		assertEquals(desert, plan.apply(plains, null));
	}

	@Test
	void testRemoval() {
		String content = "minecraft:deep_dark > null";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertTrue(plan.active());
		assertFalse(plan.isConfigIgnored());
		assertTrue(plan.isRemoved(deepDark));
	}

	@Test
	void testMissingBiomeGracefullySkipped() {
		String content = """
			modded:nonexistent > minecraft:desert
			minecraft:taiga > modded:nonexistent
			minecraft:plains > minecraft:desert
			""";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertFalse(plan.isConfigIgnored());
		assertTrue(plan.active());
		assertEquals(1, plan.decisionCount());
		assertEquals(desert, plan.apply(plains, null));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "missing_biome".equals(d.code()) && d.line() == 1));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "missing_biome".equals(d.code()) && d.line() == 2));
	}

	@Test
	void testInvalidIdentifierGracefullySkipped() {
		String content = """
			minecraft:Plains > minecraft:desert
			minecraft:plains > minecraft:Desert
			minecraft:forest > minecraft:desert
			""";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertFalse(plan.isConfigIgnored());
		assertTrue(plan.active());
		assertEquals(1, plan.decisionCount());
		assertEquals(desert, plan.apply(forest, null));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "invalid_resource_location".equals(d.code()) && d.line() == 1));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "invalid_resource_location".equals(d.code()) && d.line() == 2));
	}

	@Test
	void testInvalidDimensionGracefullySkipped() {
		String content = """
			[invalid dimension name]
			minecraft:plains > minecraft:desert
			[minecraft:overworld]
			minecraft:forest > minecraft:desert
			""";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertFalse(plan.isConfigIgnored());
		assertTrue(plan.active());
		assertEquals(1, plan.decisionCount());
		assertEquals(desert, plan.apply(forest, null));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "invalid_dimension".equals(d.code())));
	}

	@Test
	void testDirectIntegrationMarkerGracefullySkipped() {
		String content = """
			minecraft:plains > terrablender:deferred_placeholder
			minecraft:forest > minecraft:desert
			""";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertFalse(plan.isConfigIgnored());
		assertTrue(plan.active());
		assertEquals(1, plan.decisionCount());
		assertEquals(desert, plan.apply(forest, null));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "integration_marker".equals(d.code()) && d.line() == 1));
	}

	@Test
	void testTagWithMarkerOmittedGracefully() {
		String content = "#minecraft:mixed_with_marker > minecraft:plains";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertFalse(plan.isConfigIgnored());
		assertTrue(plan.active());
		assertEquals(1, plan.decisionCount());
		assertEquals(plains, plan.apply(forest, null));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "integration_marker_omitted".equals(d.code())));
	}

	@Test
	void testTagWithOnlyMarkersOmitted() {
		String content = "#minecraft:only_marker > minecraft:plains";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertFalse(plan.isConfigIgnored());
		assertFalse(plan.active());
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "integration_marker_omitted".equals(d.code())));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "empty_tag".equals(d.code())));
	}

	@Test
	void testEmptyTagEmitsWarningNotError() {
		String content = "#minecraft:is_empty_tag > minecraft:plains";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertFalse(plan.isConfigIgnored());
		assertFalse(plan.active());
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "empty_tag".equals(d.code())
			&& d.severity() == BiomeReplacementConfig.Severity.WARNING));
	}

	@Test
	void testMissingTagGracefullySkipped() {
		String content = """
			#modded:nonexistent_tag > minecraft:plains
			minecraft:plains > minecraft:desert
			""";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertFalse(plan.isConfigIgnored());
		assertTrue(plan.active());
		assertEquals(1, plan.decisionCount());
		assertEquals(desert, plan.apply(plains, null));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "missing_tag".equals(d.code())));
	}

	@Test
	void testReplacementTargetRemovedBailsOutEarly() {
		String content = """
			minecraft:plains > minecraft:desert
			minecraft:desert > null
			""";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertTrue(plan.isConfigIgnored());
		assertFalse(plan.active());
		assertEquals(0, plan.decisionCount());
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "replacement_target_removed".equals(d.code())));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "config_ignored".equals(d.code())));
		// Ensure no contradictory placement_preserved info diagnostic was emitted
		assertFalse(plan.diagnostics().stream().anyMatch(d -> "placement_preserved".equals(d.code())));
	}

	@Test
	void testFatalFormatVersionInvalidatesConfig() {
		String content = """
			format_version=99
			minecraft:plains > minecraft:desert
			""";
		WorldgenOwner owner = createOwner(content, LevelStem.OVERWORLD);
		BiomeReplacementPlan plan = BiomeReplacementCompiler.compile(owner, mod -> false);

		assertTrue(plan.isConfigIgnored());
		assertFalse(plan.active());
		assertEquals(0, plan.decisionCount());
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "unsupported_format_version".equals(d.code())));
		assertTrue(plan.diagnostics().stream().anyMatch(d -> "config_ignored".equals(d.code())));
	}
}
