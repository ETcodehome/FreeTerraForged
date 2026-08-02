package raccoonman.reterraforged.world.worldgen.terrablender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class TerraBlenderSurfaceRulesTest {
	@Test
	void minecraftOnlyIsRedundant() {
		assertTrue(TerraBlenderSurfaceRules.hasOnlyMinecraftNamespace(Set.of("minecraft")));
	}

	@Test
	void additionalNamespaceRequiresDispatch() {
		assertFalse(TerraBlenderSurfaceRules.hasOnlyMinecraftNamespace(Set.of("minecraft", "biomesoplenty")));
	}

	@Test
	void emptyOrNonMinecraftNamespacesRequireDispatch() {
		assertFalse(TerraBlenderSurfaceRules.hasOnlyMinecraftNamespace(Set.of()));
		assertFalse(TerraBlenderSurfaceRules.hasOnlyMinecraftNamespace(Set.of("biomesoplenty")));
	}

	@Test
	void equalResultsSkipTheBiomeLookup() {
		AtomicBoolean biomeLookedUp = new AtomicBoolean();

		assertEquals("stone", TerraBlenderSurfaceRules.select("stone", "stone", () -> {
			biomeLookedUp.set(true);
			return true;
		}));
		assertFalse(biomeLookedUp.get());
	}

	@Test
	void differentResultsPreserveNamespaceDispatch() {
		assertEquals("grass", TerraBlenderSurfaceRules.select("grass", "stone", () -> true));
		assertEquals("stone", TerraBlenderSurfaceRules.select("grass", "stone", () -> false));
		assertEquals("stone", TerraBlenderSurfaceRules.select(null, "stone", () -> true));
	}
}
