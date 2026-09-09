package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class BiomeCellCacheTest {
	@Test
	void collisionMissesInsteadOfReturningAnotherQuartColumn() {
		BiomeCellCache<Object> cache = new BiomeCellCache<>();
		Object owner = new Object();
		int firstX = 3;
		int firstZ = -9;
		int secondX = firstX;
		int secondZ = firstZ;
		while (secondX == firstX && secondZ == firstZ
			|| BiomeCellCache.slot(firstX, firstZ) != BiomeCellCache.slot(secondX, secondZ)) {
			secondX++;
		}
		WorldgenPlans.SpatialResult first = result("first", 1, 2);
		WorldgenPlans.SpatialResult second = result("second", 4, 5);

		cache.store(owner, firstX, firstZ, first);
		cache.store(owner, secondX, secondZ, second);

		assertNull(cache.find(owner, firstX, firstZ));
		assertEquals(second, cache.find(owner, secondX, secondZ));
	}

	@Test
	void clearRetiresValuesWhenTheSamplerChangesOwnerPlan() {
		BiomeCellCache<Object> cache = new BiomeCellCache<>();
		Object owner = new Object();
		cache.store(owner, 7, 11, result("domain", 2, 3));

		cache.clear();

		assertNull(cache.find(owner, 7, 11));
	}

	@Test
	void anOldPlanCannotRepopulateEntriesForItsReplacement() {
		BiomeCellCache<Object> cache = new BiomeCellCache<>();
		Object oldPlan = new Object();
		Object newPlan = new Object();
		cache.store(oldPlan, 7, 11, result("old", 2, 3));

		assertNull(cache.find(newPlan, 7, 11));
		cache.store(oldPlan, 7, 11, result("late-old", 4, 5));

		assertNull(cache.find(newPlan, 7, 11));
	}

	private static WorldgenPlans.SpatialResult result(String path, long cellX, long cellZ) {
		return new WorldgenPlans.SpatialResult(
			ResourceLocation.fromNamespaceAndPath("test", path), cellX, cellZ
		);
	}
}
