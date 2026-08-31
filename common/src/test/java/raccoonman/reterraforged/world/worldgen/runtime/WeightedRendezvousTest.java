package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

class WeightedRendezvousTest {
	private static final Climate.ParameterList<Holder<Biome>> UNUSED_TABLE = new Climate.ParameterList<>(List.of(
		Pair.of(Climate.parameters(0, 0, 0, 0, 0, 0, 0), Holder.direct((Biome) null))
	));

	@Test
	void assignmentIsStableAndIndependentOfInputOrder() {
		List<WorldgenPlans.ProviderDomain> providers = List.of(domain("alpha", 1), domain("beta", 3));
		WorldgenPlans.ProviderDomain selected = WeightedRendezvous.select(17L, -92L, 41L, providers);
		assertEquals(selected, WeightedRendezvous.select(17L, -92L, 41L, providers.reversed()));
		assertEquals(selected, WeightedRendezvous.select(17L, -92L, 41L, providers));
	}

	@Test
	void observedShareTracksRegisteredWeight() {
		List<WorldgenPlans.ProviderDomain> providers = List.of(domain("alpha", 1), domain("beta", 3));
		Map<ResourceLocation, Integer> counts = new HashMap<>();
		for (int x = -100; x < 100; x++) {
			for (int z = -100; z < 100; z++) {
				counts.merge(WeightedRendezvous.select(991L, x, z, providers).id(), 1, Integer::sum);
			}
		}
		double betaShare = counts.get(ResourceLocation.fromNamespaceAndPath("test", "beta")) / 40000.0D;
		assertTrue(betaShare > 0.73D && betaShare < 0.77D, "observed beta share=" + betaShare);
	}

	@Test
	void addingProviderDoesNotRemapSurvivingWinners() {
		List<WorldgenPlans.ProviderDomain> original = List.of(domain("alpha", 1), domain("beta", 2));
		List<WorldgenPlans.ProviderDomain> expanded = List.of(domain("alpha", 1), domain("beta", 2), domain("gamma", 1));
		for (int x = -40; x <= 40; x++) {
			for (int z = -40; z <= 40; z++) {
				ResourceLocation before = WeightedRendezvous.select(9L, x, z, original).id();
				ResourceLocation after = WeightedRendezvous.select(9L, x, z, expanded).id();
				assertTrue(after.getPath().equals("gamma") || after.equals(before));
			}
		}
	}

	@Test
	void emptyProviderSetFailsClosed() {
		assertThrows(IllegalArgumentException.class, () -> WeightedRendezvous.select(0, 0, 0, List.of()));
	}

	private static WorldgenPlans.ProviderDomain domain(String path, double weight) {
		return new WorldgenPlans.ProviderDomain(
			ResourceLocation.fromNamespaceAndPath("test", path), weight, UNUSED_TABLE, 0
		);
	}
}
