package raccoonman.reterraforged.world.worldgen.terrablender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.resources.ResourceLocation;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenFacet;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenQueryMode;

class TerraBlenderCapabilityProviderTest {
	@Test
	void publicImmutableProviderSnapshotDeclaresIsolatedParallelReads() {
		TerraBlenderCapabilityProvider provider = new TerraBlenderCapabilityProvider();
		assertEquals(
			Set.of(WorldgenFacet.PROVIDER_SELECTION, WorldgenFacet.SPATIAL_OWNERSHIP),
			provider.facets()
		);
		assertEquals(
			WorldgenQueryMode.ISOLATED_PARALLEL_READ,
			provider.queryMode(WorldgenFacet.PROVIDER_SELECTION, null)
		);
	}

	@Test
	void removesOnlyExactDuplicatePairsAndPreservesRegistrationOrder() {
		Climate.ParameterPoint firstPoint = Climate.parameters(0, 0, 0, 0, 0, 0, 0);
		Climate.ParameterPoint secondPoint = Climate.parameters(1, 0, 0, 0, 0, 0, 0);
		Pair<Climate.ParameterPoint, String> first = Pair.of(firstPoint, "first");
		Pair<Climate.ParameterPoint, String> samePointOtherValue = Pair.of(firstPoint, "other");
		Pair<Climate.ParameterPoint, String> second = Pair.of(secondPoint, "second");

		assertEquals(
			List.of(first, samePointOtherValue, second),
			TerraBlenderCapabilityProvider.deduplicateEntries(
				List.of(first, first, samePointOtherValue, second, first)
			)
		);
	}

	@Test
	void defaultRegionAloneDoesNotClaimTheSelectedGraph() {
		assertFalse(TerraBlenderCapabilityProvider.isContributingRegionIds(List.of(
			ResourceLocation.withDefaultNamespace("overworld")
		)));
		assertTrue(TerraBlenderCapabilityProvider.isContributingRegionIds(List.of(
			ResourceLocation.withDefaultNamespace("overworld"),
			ResourceLocation.fromNamespaceAndPath("example", "overworld")
		)));
	}
}
