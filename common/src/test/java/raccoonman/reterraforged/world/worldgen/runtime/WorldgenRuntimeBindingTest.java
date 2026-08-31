package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.dimension.LevelStem;

class WorldgenRuntimeBindingTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void publishesEpochPlanAndResourcesAsOneState() {
		UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");
		WorldgenEpoch firstEpoch = epoch(id, 0L);
		WorldgenEpoch secondEpoch = epoch(id, 1L);
		WorldgenPlan firstPlan = WorldgenPlanCompilerTest.emptyPlan(firstEpoch);
		WorldgenPlan secondPlan = WorldgenPlanCompilerTest.emptyPlan(secondEpoch);
		WorldgenRuntimeBinding binding = WorldgenRuntimeBinding.create(
			firstEpoch, firstPlan, Map.of()
		);
		WorldgenRuntimeBinding.State first = binding.current();

		WorldgenRuntimeBinding.State replaced = binding.replace(
			first, secondEpoch, secondPlan, Map.of()
		);

		assertSame(first, replaced);
		assertSame(secondEpoch, binding.current().epoch());
		assertSame(secondPlan, binding.current().plan());
		assertEquals(1L, binding.current().epoch().contributionSequence());
		assertThrows(
			IllegalStateException.class,
			() -> binding.replace(first, firstEpoch, firstPlan, Map.of())
		);
		binding.close();
	}

	private static WorldgenEpoch epoch(UUID id, long contributionSequence) {
		return new WorldgenEpoch(
			id,
			LevelStem.OVERWORLD,
			1L,
			RegistryAccess.EMPTY,
			new LevelStem(null, null),
			"settings",
			"resources",
			new TagEpoch(0L, "tags"),
			contributionSequence
		);
	}
}
