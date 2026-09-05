package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

final class UnifiedBiomeSource extends BiomeSource {
	private static final int PARALLEL_QUERY_THRESHOLD = 1024;
	private final BiomeSource acquisitionSource;
	private final Optional<BiomeSourcePlanInput> acquisitionPlanInput;
	private volatile TerraForgedChunkGenerator owner;

	UnifiedBiomeSource(
		BiomeSource acquisitionSource,
		Optional<BiomeSourcePlanInput> acquisitionPlanInput
	) {
		this.acquisitionSource = acquisitionSource;
		this.acquisitionPlanInput = acquisitionPlanInput;
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
		return this.initialPossibleBiomes().stream();
	}

	@Override
	public Set<Holder<Biome>> possibleBiomes() {
		TerraForgedChunkGenerator current = this.owner;
		return current == null
			? this.initialPossibleBiomes()
			: current.possibleRuntimeBiomes().orElseGet(this::initialPossibleBiomes);
	}

	private Set<Holder<Biome>> initialPossibleBiomes() {
		return this.acquisitionPlanInput
			.map(BiomeSourcePlanInput::possibleOutputs)
			.orElseGet(this.acquisitionSource::possibleBiomes);
	}

	@Override
	public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
		TerraForgedChunkGenerator current = this.owner;
		return current == null || current.plan().isEmpty()
			? this.acquisitionSource.getNoiseBiome(quartX, quartY, quartZ, sampler)
			: current.resolveBiome(quartX, quartY, quartZ, sampler);
	}

	@Override
	public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(
		int x,
		int y,
		int z,
		int radius,
		Predicate<Holder<Biome>> predicate,
		RandomSource random,
		Climate.Sampler sampler
	) {
		WorldgenBiomeSelection.Executable selection = this.currentSelection();
		int quartRadius = QuartPos.fromBlock(radius);
		int width = quartRadius * 2 + 1;
		long sampleCount = (long) width * width;
		if (selection == null || width <= 0 || sampleCount > Integer.MAX_VALUE) {
			return super.findBiomeHorizontal(x, y, z, radius, predicate, random, sampler);
		}
		int minQuartX = QuartPos.fromBlock(x) - quartRadius;
		int minQuartZ = QuartPos.fromBlock(z) - quartRadius;
		int quartY = QuartPos.fromBlock(y);
		Holder<Biome>[] biomes = selection.supportsIsolatedParallelRead()
			&& sampleCount >= PARALLEL_QUERY_THRESHOLD
			? resolveHorizontal(minQuartX, quartY, minQuartZ, width, sampler, selection)
			: null;
		Pair<BlockPos, Holder<Biome>> result = null;
		int matches = 0;
		for (int index = 0; index < sampleCount; index++) {
			Holder<Biome> biome = biomes == null
				? selection.resolve(
					minQuartX + index % width, quartY, minQuartZ + index / width, sampler
				)
				: biomes[index];
			if (!predicate.test(biome)) {
				continue;
			}
			if (result == null || random.nextInt(matches + 1) == 0) {
				result = Pair.of(
					new BlockPos(
						QuartPos.toBlock(minQuartX + index % width), y,
						QuartPos.toBlock(minQuartZ + index / width)
					),
					biome
				);
			}
			matches++;
		}
		return result;
	}

	@Override
	public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(
		BlockPos origin,
		int radius,
		int horizontalStep,
		int verticalStep,
		Predicate<Holder<Biome>> predicate,
		Climate.Sampler sampler,
		LevelReader level
	) {
		WorldgenBiomeSelection.Executable selection = this.currentSelection();
		if (selection == null) {
			return super.findClosestBiome3d(
				origin, radius, horizontalStep, verticalStep, predicate, sampler, level
			);
		}
		Set<Holder<Biome>> matches = selection.possibleBiomes().stream()
			.filter(predicate)
			.collect(Collectors.toUnmodifiableSet());
		if (matches.isEmpty()) {
			return null;
		}
		int[] heights = Mth.outFromOrigin(
			origin.getY(), level.getMinBuildHeight() + 1, level.getMaxBuildHeight(), verticalStep
		).toArray();
		int horizontalRadius = Math.floorDiv(radius, horizontalStep);
		List<BlockPos> ring = new ArrayList<>();
		int currentRing = -1;
		for (BlockPos offset : BlockPos.spiralAround(
			BlockPos.ZERO, horizontalRadius, Direction.EAST, Direction.SOUTH
		)) {
			int ringIndex = Math.max(Math.abs(offset.getX()), Math.abs(offset.getZ()));
			if (ringIndex != currentRing && !ring.isEmpty()) {
				Pair<BlockPos, Holder<Biome>> found = this.resolveRing(
					origin, horizontalStep, ring, heights, matches, sampler, selection
				);
				if (found != null) {
					return found;
				}
				ring.clear();
			}
			currentRing = ringIndex;
			ring.add(offset.immutable());
		}
		return this.resolveRing(
			origin, horizontalStep, ring, heights, matches, sampler, selection
		);
	}

	@SuppressWarnings("unchecked")
	private Pair<BlockPos, Holder<Biome>> resolveRing(
		BlockPos origin,
		int horizontalStep,
		List<BlockPos> ring,
		int[] heights,
		Set<Holder<Biome>> matches,
		Climate.Sampler sampler,
		WorldgenBiomeSelection.Executable selection
	) {
		long sampleCount = (long) ring.size() * heights.length;
		if (!selection.supportsIsolatedParallelRead()
			|| sampleCount < PARALLEL_QUERY_THRESHOLD
			|| sampleCount > Integer.MAX_VALUE) {
			for (BlockPos offset : ring) {
				for (int height : heights) {
					Holder<Biome> biome = selection.resolve(
						QuartPos.fromBlock(origin.getX() + offset.getX() * horizontalStep),
						QuartPos.fromBlock(height),
						QuartPos.fromBlock(origin.getZ() + offset.getZ() * horizontalStep),
						sampler
					);
					if (matches.contains(biome)) {
						return Pair.of(new BlockPos(
							origin.getX() + offset.getX() * horizontalStep,
							height,
							origin.getZ() + offset.getZ() * horizontalStep
						), biome);
					}
				}
			}
			return null;
		}
		Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[(int) sampleCount];
		IntStream indexes = IntStream.range(0, biomes.length);
		indexes.parallel().forEach(index -> {
			BlockPos offset = ring.get(index / heights.length);
			biomes[index] = selection.resolve(
				QuartPos.fromBlock(origin.getX() + offset.getX() * horizontalStep),
				QuartPos.fromBlock(heights[index % heights.length]),
				QuartPos.fromBlock(origin.getZ() + offset.getZ() * horizontalStep),
				sampler
			);
		});
		for (int index = 0; index < biomes.length; index++) {
			Holder<Biome> biome = biomes[index];
			if (matches.contains(biome)) {
				BlockPos offset = ring.get(index / heights.length);
				return Pair.of(new BlockPos(
					origin.getX() + offset.getX() * horizontalStep,
					heights[index % heights.length],
					origin.getZ() + offset.getZ() * horizontalStep
				), biome);
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Holder<Biome>[] resolveHorizontal(
		int minQuartX,
		int quartY,
		int minQuartZ,
		int width,
		Climate.Sampler sampler,
		WorldgenBiomeSelection.Executable selection
	) {
		Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[width * width];
		IntStream indexes = IntStream.range(0, biomes.length);
		if (biomes.length >= PARALLEL_QUERY_THRESHOLD) {
			indexes = indexes.parallel();
		}
		indexes.forEach(index -> biomes[index] = selection.resolve(
			minQuartX + index % width, quartY, minQuartZ + index / width, sampler
		));
		return biomes;
	}

	private WorldgenBiomeSelection.Executable currentSelection() {
		TerraForgedChunkGenerator current = this.owner;
		return current == null ? null : current.currentBiomeSelection();
	}
}
