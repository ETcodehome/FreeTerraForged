package raccoonman.reterraforged.world.worldgen.biolith;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

class BiolithCapabilityProviderTest {
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

	private static BiolithPlacementBridge.Placement entry(String name, Climate.ParameterPoint point) {
		return new BiolithPlacementBridge.Placement(
			ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("test", name)),
			point,
			false
		);
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
