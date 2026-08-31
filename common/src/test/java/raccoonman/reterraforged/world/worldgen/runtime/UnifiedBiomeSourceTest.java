package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
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

	private static final class StubBiomeSource extends BiomeSource {
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
