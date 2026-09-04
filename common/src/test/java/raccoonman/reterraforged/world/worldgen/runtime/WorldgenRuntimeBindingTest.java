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
		assertEquals(first.possibleBiomes(), first.biomeDecorationPlan().possibleBiomes());
		assertEquals(first.possibleBiomes(), first.biomeSelection().possibleBiomes());
		binding.reject(secondEpoch, new IllegalStateException("synthetic rejection"));
		assertSame(first, binding.current());
		assertEquals("plan_replacement_rejected", binding.rejection().orElseThrow().failure().code());
		WorldgenContributionRevision.Snapshot failedCapture = new WorldgenContributionRevision.Snapshot(
			LevelStem.OVERWORLD.location(), Map.of(), Map.of(
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "provider"),
				CapabilityFailure.unavailable("revision_failed", "synthetic")
			)
		);
		binding.reject(
			id, 1L, "attempted-resources", firstEpoch.tagEpoch(), failedCapture,
			new IllegalStateException("capture failed")
		);
		assertSame(first, binding.current());
		assertSame(failedCapture, binding.rejection().orElseThrow().contributions());
		assertEquals(1L, binding.rejection().orElseThrow().resourceRevision());
		assertEquals("attempted-resources", binding.rejection().orElseThrow().resourceLayerFingerprint());

		WorldgenRuntimeBinding.State replaced = binding.replace(
			first, secondEpoch, secondPlan, Map.of()
		);

		assertSame(first, replaced);
		assertSame(secondEpoch, binding.current().epoch());
		assertSame(secondPlan, binding.current().plan());
		assertEquals(java.util.Optional.empty(), binding.rejection());
		assertEquals(1L, binding.current().epoch().contributionRevision().revisions().values().iterator().next());
		assertThrows(
			IllegalStateException.class,
			() -> binding.replace(first, firstEpoch, firstPlan, Map.of())
		);
		binding.close();
	}

	@Test
	void resourceOnlyReloadAdvancesTheSameOwnerAtomically() {
		UUID id = UUID.fromString("00000000-0000-0000-0000-000000000003");
		WorldgenEpoch current = epoch(id, 0L);
		WorldgenEpoch replacement = current.withInputs(
			1L, "changed-resources", current.tagEpoch(), current.contributionRevision()
		);

		assertEquals(id, replacement.id());
		assertEquals(1L, replacement.resourceRevision());
		assertEquals("changed-resources", replacement.resourceLayerFingerprint());
		assertSame(current.tagEpoch(), replacement.tagEpoch());
		assertSame(current.contributionRevision(), replacement.contributionRevision());
		assertEquals(true, replacement.inputRevisionStrictlyAdvances(current));
		assertEquals(false, replacement.inputRevisionRegressesFrom(current));
		assertThrows(IllegalArgumentException.class, () -> current.withInputs(
			current.resourceRevision(), "different-but-not-newer",
			current.tagEpoch(), current.contributionRevision()
		));
		assertEquals(true, current.inputRevisionRegressesFrom(replacement));
	}

	private static WorldgenEpoch epoch(UUID id, long contributionSequence) {
		net.minecraft.resources.ResourceLocation mechanism =
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "mechanism");
		return new WorldgenEpoch(
			id,
			LevelStem.OVERWORLD,
			1L,
			RegistryAccess.EMPTY,
			new LevelStem(null, null),
			"settings",
			0L,
			"resources",
			new TagEpoch(0L, "tags"),
			new WorldgenContributionRevision.Snapshot(
				LevelStem.OVERWORLD.location(),
				contributionSequence == 0L ? Map.of() : Map.of(
					new WorldgenContributionRevision.RevisionKey(
						mechanism, LevelStem.OVERWORLD.location()
					),
					contributionSequence
				)
			)
		);
	}
}
