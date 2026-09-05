package raccoonman.reterraforged.world.worldgen.lithostitched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.MapCodec;

import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;

class LithostitchedPreServerIsolationTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void isolationUsesAFreshGeneratorShellWithoutSerializingAnArbitrarySource() {
		OpaqueSource source = new OpaqueSource();
		NoiseBasedChunkGenerator original = generator(source);

		Registry<LevelStem> isolated = LithostitchedInjectionBridge.isolateDimensions(dimensions(original));
		NoiseBasedChunkGenerator shell = (NoiseBasedChunkGenerator) isolated
			.getOrThrow(LevelStem.OVERWORLD).generator();

		assertNotSame(original, shell);
		assertSame(source, original.getBiomeSource());
		assertSame(source, shell.getBiomeSource());
		assertEquals(0, source.codecCalls.get());
	}

	@Test
	void isolationUnwrapsAnExistingInjectorSoFinalizationCannotMutateTheOwnedSource() {
		OpaqueSource root = new OpaqueSource();
		InjectorBiomeSource realized = new InjectorBiomeSource(root);
		NoiseBasedChunkGenerator original = generator(realized);

		Registry<LevelStem> isolated = LithostitchedInjectionBridge.isolateDimensions(dimensions(original));
		NoiseBasedChunkGenerator shell = (NoiseBasedChunkGenerator) isolated
			.getOrThrow(LevelStem.OVERWORLD).generator();

		assertSame(realized, original.getBiomeSource());
		assertSame(root, shell.getBiomeSource());
		assertEquals(0, root.codecCalls.get());
	}

	private static NoiseBasedChunkGenerator generator(BiomeSource source) {
		return new NoiseBasedChunkGenerator(source, Holder.direct(NoiseGeneratorSettings.dummy()));
	}

	private static WorldDimensions dimensions(NoiseBasedChunkGenerator generator) {
		return new WorldDimensions(Map.of(
			LevelStem.OVERWORLD, new LevelStem(Holder.direct(null), generator)
		));
	}

	private static final class OpaqueSource extends BiomeSource {
		private final AtomicInteger codecCalls = new AtomicInteger();

		@Override
		protected MapCodec<? extends BiomeSource> codec() {
			this.codecCalls.incrementAndGet();
			throw new UnsupportedOperationException("A codec is not this source's cloning contract");
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
