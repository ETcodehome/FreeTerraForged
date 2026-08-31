package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Set;
import java.util.stream.Stream;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

final class UnifiedBiomeSource extends BiomeSource {
	private final BiomeSource acquisitionSource;
	private volatile TerraForgedChunkGenerator owner;

	UnifiedBiomeSource(BiomeSource acquisitionSource) {
		this.acquisitionSource = acquisitionSource;
	}

	void bind(TerraForgedChunkGenerator owner) {
		if (this.owner != null) {
			throw new IllegalStateException("Unified biome source is already bound");
		}
		this.owner = owner;
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		throw new UnsupportedOperationException("FTF runtime biome sources are serialized through their acquisition graph");
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return this.acquisitionSource.possibleBiomes().stream();
	}

	@Override
	public Set<Holder<Biome>> possibleBiomes() {
		TerraForgedChunkGenerator current = this.owner;
		return current == null
			? this.acquisitionSource.possibleBiomes()
			: current.possibleRuntimeBiomes().orElseGet(this.acquisitionSource::possibleBiomes);
	}

	@Override
	public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
		TerraForgedChunkGenerator current = this.owner;
		return current == null || current.plan().isEmpty()
			? this.acquisitionSource.getNoiseBiome(quartX, quartY, quartZ, sampler)
			: current.resolveBiome(quartX, quartY, quartZ, sampler);
	}
}
