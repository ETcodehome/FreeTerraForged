package etcodehome.freeterraforged.world.worldgen.terrablender;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import terrablender.DimensionTypeTags;
import terrablender.api.SurfaceRuleManager;
import terrablender.worldgen.IExtendedNoiseGeneratorSettings;
import terrablender.worldgen.surface.NamespacedSurfaceRuleSource;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.runtime.UnifiedBiomeSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraBlenderSurfaceCompatibilityTest {

	@BeforeAll
	static void setUp() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static NoiseGeneratorSettings createDummySettings(SurfaceRules.RuleSource surfaceRule) {
		NoiseRouter dummyRouter = new NoiseRouter(
			DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
			DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
			DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
			DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero()
		);
		return new NoiseGeneratorSettings(
			NoiseSettings.create(0, 256, 1, 2),
			Blocks.STONE.defaultBlockState(),
			Blocks.WATER.defaultBlockState(),
			dummyRouter,
			surfaceRule,
			List.of(),
			63,
			false,
			false,
			false,
			false
		);
	}

	private static Biome createDummyBiome() {
		return new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(0.5F)
			.downfall(0.5F)
			.specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder()
				.fogColor(0)
				.waterColor(0)
				.waterFogColor(0)
				.skyColor(0)
				.build())
			.mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
			.generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
			.build();
	}

	@Test
	@DisplayName("UnifiedBiomeSource exposes underlying acquisition source for compatibility runtime")
	void testUnifiedBiomeSourceExposesAcquisitionSource() {
		FixedBiomeSource fixed = new FixedBiomeSource(Holder.direct(createDummyBiome()));
		UnifiedBiomeSource unified = new UnifiedBiomeSource(fixed, Optional.empty());

		assertSame(fixed, unified.acquisitionSource(), "UnifiedBiomeSource must expose its underlying acquisition source");
	}

	@Test
	@DisplayName("TerraForgedChunkGenerator exposes acquisitionBiomeSource matching initial input")
	void testTerraForgedChunkGeneratorExposesAcquisitionSource() {
		FixedBiomeSource fixed = new FixedBiomeSource(Holder.direct(createDummyBiome()));
		Holder<NoiseGeneratorSettings> settingsHolder = Holder.direct(createDummySettings(SurfaceRules.state(Blocks.STONE.defaultBlockState())));
		TerraForgedChunkGenerator generator = new TerraForgedChunkGenerator(fixed, settingsHolder);

		assertSame(fixed, generator.acquisitionBiomeSource(), "TerraForgedChunkGenerator must expose acquisitionBiomeSource");
	}

	@Test
	@DisplayName("TerraBlenderSurfaceBridge safely checks interface and SurfaceRuleManager produces NamespacedSurfaceRuleSource")
	void testTerraBlenderSurfaceBridgeSetsOverworldCategory() {
		SurfaceRules.RuleSource baseRule = SurfaceRules.state(Blocks.STONE.defaultBlockState());
		NoiseGeneratorSettings settings = createDummySettings(baseRule);

		// 1. Verify bridge handles un-mixin'd test instance safely without ClassCastException
		Holder<DimensionType> dummyHolder = Holder.Reference.createStandAlone(
			null, ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.withDefaultNamespace("overworld"))
		);
		TerraBlenderSurfaceBridge.bindSurfaceCategory(dummyHolder, settings);

		// 2. Register custom mod rule in TerraBlender
		SurfaceRules.RuleSource customModRule = SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(Blocks.RED_SAND.defaultBlockState()));
		SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.OVERWORLD, "testmod", customModRule);

		// 3. Verify TerraBlender's NamespacedSurfaceRuleSource aggregates base and modded rules
		SurfaceRules.RuleSource resolved = SurfaceRuleManager.getNamespacedRules(SurfaceRuleManager.RuleCategory.OVERWORLD, baseRule);
		assertNotNull(resolved, "resolved surface rule must not be null");
		assertTrue(resolved instanceof NamespacedSurfaceRuleSource, "SurfaceRuleManager must return NamespacedSurfaceRuleSource");

		NamespacedSurfaceRuleSource namespaced = (NamespacedSurfaceRuleSource) resolved;
		assertSame(baseRule, namespaced.base(), "base rule must be preserved as fallback");
		assertTrue(namespaced.sources().containsKey("minecraft"), "Namespaced rules must contain default 'minecraft' rules");
		assertTrue(namespaced.sources().containsKey("testmod"), "Namespaced rules must contain registered 'testmod' rules");
	}
}
