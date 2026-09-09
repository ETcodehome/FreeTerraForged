package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;

class WorldgenPreServerFinalizerTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void failuresAreOwnedByTheGeneratorAndReplacedAsOneImmutablePublication() throws Exception {
		TerraForgedChunkGenerator generator = generator();
		ResourceLocation firstProvider = ResourceLocation.fromNamespaceAndPath("test", "first");
		ResourceLocation secondProvider = ResourceLocation.fromNamespaceAndPath("test", "second");
		CapabilityFailure firstFailure = CapabilityFailure.unavailable("first_failure", "first");
		CapabilityFailure secondFailure = CapabilityFailure.unavailable("second_failure", "second");

		generator.publishPreServerFailures(Map.of(firstProvider, firstFailure));
		assertEquals(firstFailure, WorldgenPreServerFinalizer.failure(generator, firstProvider).orElseThrow());

		generator.publishPreServerFailures(Map.of(secondProvider, secondFailure));
		assertTrue(WorldgenPreServerFinalizer.failure(generator, firstProvider).isEmpty());
		assertEquals(secondFailure, WorldgenPreServerFinalizer.failure(generator, secondProvider).orElseThrow());

		generator.close();
		assertTrue(WorldgenPreServerFinalizer.failure(generator, secondProvider).isEmpty());
	}

	@Test
	void preServerProvidersAreNotLoadedForGraphsWithoutAnFtfRoot() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		PreServerProvider provider = new PreServerProvider(calls);
		ChunkGenerator vanilla = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
			new FixedBiomeSource(Holder.direct(null)), Holder.direct(NoiseGeneratorSettings.dummy())
		);

		WorldgenPreServerFinalizer.Report report = WorldgenPreServerFinalizer.finalize(
			context(vanilla), WorldgenProviderCatalog.of(List.of(provider))
		);

		assertTrue(report.diagnostics().isEmpty());
		assertEquals(0, calls.get());
	}

	@Test
	void selectedFtfRootRunsThePreServerProviderExactlyOnce() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		PreServerProvider provider = new PreServerProvider(calls);
		TerraForgedChunkGenerator generator = generator();
		WorldgenProviderCatalog catalog = WorldgenProviderCatalog.of(List.of(provider));
		try {
			WorldgenPreServerFinalizer.finalize(
				context(generator), catalog
			);
			assertEquals(1, calls.get());
			assertSame(catalog, generator.acquireProviderCatalog());
		} finally {
			generator.close();
		}
	}

	private static PreServerWorldgenContext context(ChunkGenerator generator) {
		return new PreServerWorldgenContext(
			net.minecraft.core.RegistryAccess.EMPTY,
			new WorldDimensions(Map.of(
				LevelStem.OVERWORLD, new LevelStem(Holder.direct(null), generator)
			)),
			1L
		);
	}

	private static TerraForgedChunkGenerator generator() {
		return new TerraForgedChunkGenerator(
			new FixedBiomeSource(Holder.direct(null)),
			Holder.direct(NoiseGeneratorSettings.dummy())
		);
	}

	private static final class PreServerProvider implements WorldgenCapabilityProvider {
		private final AtomicInteger calls;

		private PreServerProvider(AtomicInteger calls) {
			this.calls = calls;
		}

		@Override public ResourceLocation id() {
			return ResourceLocation.fromNamespaceAndPath("test", "pre_server");
		}
		@Override public int version() { return 1; }
		@Override public Set<WorldgenFacet> facets() { return Set.of(); }
		@Override public Set<WorldgenOwnerType> ownerTypes() {
			return Set.of(WorldgenOwnerType.PREVIEW_REQUEST);
		}
		@Override public List<ProviderOrder> ordering() { return List.of(); }
		@Override public boolean requiresPreServerFinalization() { return true; }
		@Override public void finalizePreServer(PreServerWorldgenContext context) {
			this.calls.incrementAndGet();
		}
		@Override public WorldgenApplicability applicability(
			WorldgenFacet facet, WorldgenCompilationContext context
		) { return WorldgenApplicability.NOT_APPLICABLE; }
		@Override public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
			return Optional.empty();
		}
		@Override public Optional<? extends WorldgenPlans.DomainPlan> compile(
			WorldgenFacet facet, WorldgenCompilationContext context
		) { return Optional.empty(); }
		@Override public WorldgenQueryMode queryMode(
			WorldgenFacet facet, WorldgenCompilationContext context
		) { return WorldgenQueryMode.OWNER_SERIAL; }
	}
}
