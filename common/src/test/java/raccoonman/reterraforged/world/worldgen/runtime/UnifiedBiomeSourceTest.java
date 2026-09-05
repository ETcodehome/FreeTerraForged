package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

class UnifiedBiomeSourceTest {
	private static final Holder<Biome> BIOME = Holder.direct((Biome) null);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void generatorRetainsAcquisitionGraphBehindOwnedRuntimeSource() {
		BiomeSource acquisition = new StubBiomeSource(BIOME);
		TerraForgedChunkGenerator generator = new TerraForgedChunkGenerator(
			acquisition, Holder.direct((NoiseGeneratorSettings) null)
		);

		assertSame(acquisition, generator.acquisitionBiomeSource());
		assertSame(acquisition, MinecraftBiomeSourceGraphs.acquisitionSource(generator));
		assertNotSame(acquisition, generator.getBiomeSource());
		assertEquals(Set.of(BIOME), generator.getBiomeSource().possibleBiomes());
		assertSame(BIOME, generator.getBiomeSource().getNoiseBiome(0, 0, 0, null));
	}

	@Test
	void horizontalSearchRetainsVanillaResolutionBeforeRuntimeBinding() {
		AtomicInteger queries = new AtomicInteger();
		BiomeSource acquisition = new StubBiomeSource(BIOME) {
			@Override
			public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
				queries.incrementAndGet();
				return BIOME;
			}
		};
		UnifiedBiomeSource source = new UnifiedBiomeSource(acquisition, Optional.empty());

		Pair<BlockPos, Holder<Biome>> result = source.findBiomeHorizontal(
			0, 64, 0, 512, holder -> holder == BIOME, RandomSource.create(71L), null
		);

		assertNotNull(result);
		assertSame(BIOME, result.getSecond());
		assertEquals(66_049, queries.get());
	}

	@Test
	void horizontalSearchPreservesExactSmallQuery() {
		Holder<Biome> other = Holder.direct((Biome) null);
		BiomeSource acquisition = new StubBiomeSource(other) {
			@Override
			public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
				return quartX == 1 && quartZ == 1 ? BIOME : other;
			}
		};
		UnifiedBiomeSource source = new UnifiedBiomeSource(acquisition, Optional.empty());

		Pair<BlockPos, Holder<Biome>> result = source.findBiomeHorizontal(
			0, 64, 0, 16, holder -> holder == BIOME, RandomSource.create(29L), null
		);

		assertNotNull(result);
		assertEquals(new BlockPos(4, 64, 4), result.getFirst());
		assertSame(BIOME, result.getSecond());
	}

	@Test
	void ownerSerialSearchPinsOnePlanAcrossReplacement() throws ReflectiveOperationException {
		Holder<Biome> replacementBiome = Holder.direct((Biome) null);
		AtomicInteger acquisitionQueries = new AtomicInteger();
		AtomicInteger originalQueries = new AtomicInteger();
		AtomicInteger replacementQueries = new AtomicInteger();
		AtomicBoolean replaced = new AtomicBoolean();
		AtomicReference<WorldgenRuntimeBinding> bindingReference = new AtomicReference<>();
		BiomeSource acquisition = new StubBiomeSource(replacementBiome) {
			@Override
			public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
				acquisitionQueries.incrementAndGet();
				return replacementBiome;
			}
		};
		UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000009");
		WorldgenEpoch originalEpoch = epoch(owner, 0L);
		WorldgenEpoch replacementEpoch = epoch(owner, 1L);
		BiomeSourcePlanInput replacementInput = new BiomeSourcePlanInput(
			id("replacement"), Set.of(replacementBiome), WorldgenQueryMode.OWNER_SERIAL,
			(x, y, z, sampler) -> {
				replacementQueries.incrementAndGet();
				return replacementBiome;
			}
		);
		WorldgenPlan replacementPlan = directPlan(replacementEpoch, replacementInput);
		BiomeSourcePlanInput originalInput = new BiomeSourcePlanInput(
			id("original"), Set.of(BIOME), WorldgenQueryMode.OWNER_SERIAL,
			(x, y, z, sampler) -> {
				originalQueries.incrementAndGet();
				if (replaced.compareAndSet(false, true)) {
					WorldgenRuntimeBinding binding = bindingReference.get();
					binding.replace(
						binding.current(), replacementEpoch, replacementPlan, Map.of()
					);
				}
				return BIOME;
			}
		);
		WorldgenPlan originalPlan = directPlan(originalEpoch, originalInput);
		WorldgenRuntimeBinding binding = WorldgenRuntimeBinding.create(
			originalEpoch, originalPlan, Map.of()
		);
		bindingReference.set(binding);
		TerraForgedChunkGenerator generator = new TerraForgedChunkGenerator(
			acquisition, Holder.direct(NoiseGeneratorSettings.dummy()), Optional.of(originalInput)
		);
		var runtimeField = TerraForgedChunkGenerator.class.getDeclaredField("runtime");
		runtimeField.setAccessible(true);
		runtimeField.set(generator, binding);
		BiomeSource source = generator.getBiomeSource();

		Pair<BlockPos, Holder<Biome>> result = source.findBiomeHorizontal(
			0, 64, 0, 128, holder -> holder == BIOME, RandomSource.create(37L), null
		);

		assertNotNull(result);
		assertSame(BIOME, result.getSecond());
		assertEquals(4_225, originalQueries.get());
		assertEquals(0, replacementQueries.get());
		assertEquals(0, acquisitionQueries.get());
		assertSame(replacementBiome, source.getNoiseBiome(0, 0, 0, null));
		assertEquals(1, replacementQueries.get());
	}

	private static WorldgenPlan directPlan(
		WorldgenEpoch epoch,
		BiomeSourcePlanInput input
	) {
		WorldgenPlan base = WorldgenPlanCompilerTest.emptyPlan(epoch);
		WorldgenPlans.ProviderSelection direct = new WorldgenPlans.ProviderSelection(
			base.providerSelection().descriptor(), 0L, java.util.List.of(), Optional.empty(),
			Optional.empty(), Optional.empty(), Optional.of(input)
		);
		return new WorldgenPlan(
			epoch, base.biomeComposition(), direct, base.selectionDecoration(),
			base.spatialOwnership(), base.samplerDecoration(), base.densitySettings(),
			base.surface(), base.carvers(), base.placedFeatures(), base.structures(),
			base.execution(), base.report()
		);
	}

	private static WorldgenEpoch epoch(UUID owner, long revision) {
		return new WorldgenEpoch(
			owner, LevelStem.OVERWORLD, 1L, RegistryAccess.EMPTY, new LevelStem(null, null),
			"settings", revision, "resources-" + revision,
			new TagEpoch(revision, "tags-" + revision),
			WorldgenContributionRevision.Snapshot.empty(LevelStem.OVERWORLD.location())
		);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}

	private static class StubBiomeSource extends BiomeSource {
		private final Holder<Biome> biome;

		private StubBiomeSource(Holder<Biome> biome) {
			this.biome = biome;
		}

		@Override
		protected MapCodec<? extends BiomeSource> codec() {
			return MapCodec.unit(() -> this);
		}

		@Override
		protected Stream<Holder<Biome>> collectPossibleBiomes() {
			return Stream.of(this.biome);
		}

		@Override
		public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
			return this.biome;
		}
	}
}
