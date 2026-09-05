package raccoonman.reterraforged.world.worldgen.biolith;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.world.worldgen.runtime.CellIntervalSelector;

class BiolithCapabilityProviderTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void placementOrderUsesTheCompleteClimateTupleAndBiomeKey() {
		List<BiolithPlacementBridge.Placement> entries = new ArrayList<>(List.of(
			entry("zeta", point(1, 1, 1, 1, 1, 1, 1)),
			entry("alpha", point(1, 1, 1, 1, 1, 1, 1)),
			entry("offset", point(1, 1, 1, 1, 1, 1, 2)),
			entry("weirdness", point(1, 1, 1, 1, 1, 2, 0)),
			entry("temperature", point(2, 0, 0, 0, 0, 0, 0))
		));

		entries.sort(BiolithCapabilityProvider.placementOrder());

		assertEquals(
			List.of("alpha", "zeta", "offset", "weirdness", "temperature"),
			entries.stream().map(entry -> entry.biome().location().getPath()).toList()
		);
	}

	@Test
	void replacementChoicesPreserveAuthoredRangesAndVanillaResidual() {
		MappedRegistry<Biome> biomes = new MappedRegistry<>(
			Registries.BIOME, com.mojang.serialization.Lifecycle.stable()
		);
		ResourceKey<Biome> target = biomeKey("target");
		ResourceKey<Biome> output = biomeKey("output");
		Holder.Reference<Biome> targetHolder = biomes.register(
			target, biome(), RegistrationInfo.BUILT_IN
		);
		Holder.Reference<Biome> outputHolder = biomes.register(
			output, biome(), RegistrationInfo.BUILT_IN
		);
		biomes.freeze();

		List<CellIntervalSelector.Choice<Holder<Biome>>> choices =
			BiolithCapabilityProvider.replacementChoices(target, List.of(
				new BiolithPlacementBridge.Replacement(target, output, 0.2D, false),
				new BiolithPlacementBridge.Replacement(target, output, 0.4D, false)
			), biomes);

		assertEquals(3, choices.size());
		assertEquals(0.6D, choices.stream().filter(choice -> choice.value().equals(targetHolder))
			.mapToDouble(CellIntervalSelector.Choice::weight).sum(), 1.0E-12D);
		assertEquals(0.6D, choices.stream().filter(choice -> choice.value().equals(outputHolder))
			.mapToDouble(CellIntervalSelector.Choice::weight).sum(), 1.0E-12D);
		assertEquals(3, choices.stream().map(CellIntervalSelector.Choice::id).distinct().count());
	}

	private static BiolithPlacementBridge.Placement entry(String name, Climate.ParameterPoint point) {
		return new BiolithPlacementBridge.Placement(
			ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("test", name)),
			point,
			false
		);
	}

	private static ResourceKey<Biome> biomeKey(String path) {
		return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("test", path));
	}

	private static Biome biome() {
		return new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(0.8F)
			.downfall(0.4F)
			.specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder()
				.fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
			.mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
			.generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
			.build();
	}

	private static Climate.ParameterPoint point(
		long temperature,
		long humidity,
		long continentalness,
		long erosion,
		long depth,
		long weirdness,
		long offset
	) {
		return new Climate.ParameterPoint(
			parameter(temperature), parameter(humidity), parameter(continentalness), parameter(erosion),
			parameter(depth), parameter(weirdness), offset
		);
	}

	private static Climate.Parameter parameter(long value) {
		return new Climate.Parameter(value, value);
	}
}
