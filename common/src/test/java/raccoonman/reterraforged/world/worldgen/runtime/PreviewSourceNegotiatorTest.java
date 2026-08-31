package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

class PreviewSourceNegotiatorTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void resolvesOneFreshProviderSourceAndTransfersItsLifecycle() throws Exception {
		TestBiomeSource realized = new TestBiomeSource();
		TestBiomeSource fresh = new TestBiomeSource();
		AtomicInteger closes = new AtomicInteger();
		Provider provider = new Provider("source", context -> Optional.of(
			new RequestOwnedBiomeSource(fresh, closes::incrementAndGet)
		));

		PreviewSourceNegotiator.Result result = PreviewSourceNegotiator.resolve(
			context(realized), java.util.List.of(provider)
		);

		assertNotSame(realized, result.owned().source());
		assertEquals(provider.id(), result.provider().orElseThrow());
		assertEquals(0, closes.get());
		result.owned().close();
		assertEquals(1, closes.get());
	}

	@Test
	void providerSnapshotFactoryPrecedesGenericMultiNoiseCopy() throws Exception {
		BiomeSource realized = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.of(
			Pair.of(Climate.parameters(0, 0, 0, 0, 0, 0, 0), Holder.direct((Biome) null))
		)));
		TestBiomeSource fresh = new TestBiomeSource();
		AtomicInteger calls = new AtomicInteger();
		Provider provider = new Provider("snapshot", context -> {
			calls.incrementAndGet();
			return Optional.of(RequestOwnedBiomeSource.immutable(fresh));
		});

		PreviewSourceNegotiator.Result result = PreviewSourceNegotiator.resolve(
			context(realized), java.util.List.of(provider)
		);

		assertEquals(1, calls.get());
		assertEquals(provider.id(), result.provider().orElseThrow());
		assertEquals(fresh, result.owned().source());
		result.owned().close();
	}

	@Test
	void rejectsRealizedIdentityAndClosesTheRejectedLifecycle() {
		TestBiomeSource realized = new TestBiomeSource();
		AtomicInteger closes = new AtomicInteger();
		Provider provider = new Provider("same", context -> Optional.of(
			new RequestOwnedBiomeSource(realized, closes::incrementAndGet)
		));

		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> PreviewSourceNegotiator.resolve(context(realized), java.util.List.of(provider))
		);

		assertTrue(failure.getMessage().contains("realized source identity"));
		assertEquals(1, closes.get());
	}

	@Test
	void conflictingFactoriesCloseBothRequestOwnedResults() {
		TestBiomeSource realized = new TestBiomeSource();
		AtomicInteger closes = new AtomicInteger();
		Provider first = new Provider("first", context -> Optional.of(
			new RequestOwnedBiomeSource(new TestBiomeSource(), closes::incrementAndGet)
		));
		Provider second = new Provider("second", context -> Optional.of(
			new RequestOwnedBiomeSource(new TestBiomeSource(), closes::incrementAndGet)
		));

		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> PreviewSourceNegotiator.resolve(context(realized), java.util.List.of(second, first))
		);

		assertTrue(failure.getMessage().contains("test:first"));
		assertTrue(failure.getMessage().contains("test:second"));
		assertEquals(2, closes.get());
	}

	@Test
	void aLaterFactoryFailureCannotHideOrLeakAnEarlierResult() {
		TestBiomeSource realized = new TestBiomeSource();
		AtomicInteger closes = new AtomicInteger();
		Provider first = new Provider("first", context -> Optional.of(
			new RequestOwnedBiomeSource(new TestBiomeSource(), closes::incrementAndGet)
		));
		Provider second = new Provider("second", context -> {
			throw new IllegalStateException("snapshot failed");
		});

		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> PreviewSourceNegotiator.resolve(context(realized), java.util.List.of(second, first))
		);

		assertEquals("snapshot failed", failure.getCause().getMessage());
		assertEquals(1, closes.get());
	}

	private static PreviewSourceContext context(BiomeSource source) {
		return new PreviewSourceContext(
			1L,
			RegistryAccess.EMPTY,
			RegistryAccess.EMPTY,
			source,
			Holder.direct(NoiseGeneratorSettings.dummy()),
			"settings",
			"resources",
			new TagEpoch(0L, "tags")
		);
	}

	private static final class Provider implements WorldgenCapabilityProvider {
		private final ResourceLocation id;
		private final Factory factory;

		private Provider(String name, Factory factory) {
			this.id = ResourceLocation.fromNamespaceAndPath("test", name);
			this.factory = factory;
		}

		@Override
		public ResourceLocation id() {
			return this.id;
		}

		@Override
		public Set<WorldgenFacet> facets() {
			return Set.of();
		}

		@Override
		public Set<WorldgenOwnerType> ownerTypes() {
			return Set.of(WorldgenOwnerType.PREVIEW_REQUEST);
		}

		@Override
		public WorldgenApplicability applicability(
			WorldgenFacet facet,
			WorldgenCompilationContext context
		) {
			return WorldgenApplicability.APPLICABLE;
		}

		@Override
		public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) throws Exception {
			return this.factory.create(context);
		}

		@Override
		public int version() {
			return 1;
		}

		@Override
		public List<ProviderOrder> ordering() {
			return List.of();
		}

		@Override
		public WorldgenQueryMode queryMode(WorldgenFacet facet, WorldgenCompilationContext context) {
			return WorldgenQueryMode.OWNER_SERIAL;
		}

		@Override
		public Optional<? extends WorldgenPlans.DomainPlan> compile(
			WorldgenFacet facet,
			WorldgenCompilationContext context
		) {
			return Optional.empty();
		}
	}

	@FunctionalInterface
	private interface Factory {
		Optional<RequestOwnedBiomeSource> create(PreviewSourceContext context) throws Exception;
	}

	private static final class TestBiomeSource extends BiomeSource {
		private static final MapCodec<TestBiomeSource> CODEC = MapCodec.unit(TestBiomeSource::new);

		@Override
		protected MapCodec<? extends BiomeSource> codec() {
			return CODEC;
		}

		@Override
		protected Stream<Holder<Biome>> collectPossibleBiomes() {
			return Stream.empty();
		}

		@Override
		public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
			return null;
		}
	}
}
