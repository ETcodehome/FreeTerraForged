package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;

class WorldgenContributionRevisionTest {
	private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
	private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");

	@Test
	void snapshotsAreCatalogScopedAndDimensionOwnedWithoutACentralLedger() {
		ResourceLocation first = id("first");
		ResourceLocation second = id("second");
		WorldgenProviderCatalog firstCatalog = WorldgenProviderCatalog.of(List.of(new RevisionProvider(first, 3L)));
		WorldgenProviderCatalog secondCatalog = WorldgenProviderCatalog.of(List.of(new RevisionProvider(second, 7L)));
		ResourceKey<LevelStem> overworldKey = ResourceKey.create(
			net.minecraft.core.registries.Registries.LEVEL_STEM, OVERWORLD
		);

		WorldgenContributionRevision.Snapshot firstSnapshot = WorldgenContributionRevision.snapshot(
			overworldKey, firstCatalog
		);
		WorldgenContributionRevision.Snapshot secondSnapshot = WorldgenContributionRevision.snapshot(
			overworldKey, secondCatalog
		);

		assertEquals(Map.of(new WorldgenContributionRevision.RevisionKey(first, OVERWORLD), 3L),
			firstSnapshot.revisions());
		assertEquals(Map.of(new WorldgenContributionRevision.RevisionKey(second, OVERWORLD), 7L),
			secondSnapshot.revisions());
		assertTrue(firstSnapshot.failures().isEmpty());
		assertTrue(secondSnapshot.failures().isEmpty());
	}

	@Test
	void monotonicComparisonRejectsAnyRegressingComponent() {
		ResourceLocation mechanism = id("comparison");
		var key = new WorldgenContributionRevision.RevisionKey(mechanism, OVERWORLD);
		var first = new WorldgenContributionRevision.Snapshot(OVERWORLD, Map.of(key, 1L));
		var advanced = new WorldgenContributionRevision.Snapshot(OVERWORLD, Map.of(key, 2L));
		var regressed = new WorldgenContributionRevision.Snapshot(OVERWORLD, Map.of());

		assertTrue(advanced.strictlyAdvances(first));
		assertFalse(first.strictlyAdvances(advanced));
		assertTrue(regressed.regressesFrom(first));
	}

	@Test
	void zeroRevisionProviderMembershipStillChangesTheOwnerVector() {
		var key = new WorldgenContributionRevision.RevisionKey(id("zero"), OVERWORLD);
		var absent = WorldgenContributionRevision.Snapshot.empty(OVERWORLD);
		var presentAtZero = new WorldgenContributionRevision.Snapshot(OVERWORLD, Map.of(key, 0L));

		assertTrue(presentAtZero.strictlyAdvances(absent));
		assertFalse(presentAtZero.regressesFrom(absent));
		assertFalse(absent.strictlyAdvances(presentAtZero));
		assertTrue(absent.regressesFrom(presentAtZero));
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}

	private record RevisionProvider(ResourceLocation id, long revision) implements WorldgenCapabilityProvider {
		@Override public int version() { return 1; }
		@Override public Set<WorldgenFacet> facets() { return Set.of(); }
		@Override public Set<WorldgenOwnerType> ownerTypes() { return Set.of(WorldgenOwnerType.WORLDGEN_EPOCH); }
		@Override public List<ProviderOrder> ordering() { return List.of(); }
		@Override public boolean providesContributionRevision() { return true; }
		@Override public OptionalLong contributionRevision(ResourceKey<LevelStem> dimension) {
			return OptionalLong.of(this.revision);
		}
		@Override public WorldgenApplicability applicability(WorldgenFacet facet, WorldgenCompilationContext context) {
			return WorldgenApplicability.NOT_APPLICABLE;
		}
		@Override public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
			return Optional.empty();
		}
		@Override public Optional<? extends WorldgenPlans.DomainPlan> compile(
			WorldgenFacet facet, WorldgenCompilationContext context
		) {
			return Optional.empty();
		}
		@Override public WorldgenQueryMode queryMode(WorldgenFacet facet, WorldgenCompilationContext context) {
			return WorldgenQueryMode.OWNER_SERIAL;
		}
	}
}
