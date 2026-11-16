package etcodehome.freeterraforged.world.worldgen.search;

import java.util.HashSet;
import java.util.Set;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid;
import etcodehome.freeterraforged.world.worldgen.cell.filter.RegionFilterGrid;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenBiomeSelection;
import net.minecraft.world.level.biome.Climate;

/** Maintained regression checks for locate topology and authoritative terrain sampling. */
public final class LocateSearchValidator {
	private static final int[] SAMPLE_OFFSETS = {
		-129, -65, -64, -63, -17, -16, -15, -1, 0, 1, 15, 16, 17, 63, 64, 65, 129
	};

	private LocateSearchValidator() {
	}

	public static String validateTopology() {
		Set<Long> visited = new HashSet<>();
		ParallelFirstSearch.Result<String> search = ParallelFirstSearch.findFirst(
			0,
			0,
			() -> (tile, control) -> {
				long key = ((long) tile.regionChunkX() << 32) ^ (tile.regionChunkZ() & 0xFFFFFFFFL);
				if (!visited.add(key)) {
					throw new AssertionError("Locate search visited tile twice: " + tile);
				}
				String match = tile.regionChunkX() == 8 && tile.regionChunkZ() == 0 ? "match" : null;
				return new ParallelFirstSearch.TileResult<>(match, 0L);
			},
			Runnable::run,
			1
		).join();
		if (!"match".equals(search.match()) || search.timedOut()) {
			throw new AssertionError("Outward first-match ring traversal returned " + search);
		}
		if (visited.size() != 1 + 8 + 7) {
			throw new AssertionError("Unexpected outward tile coverage before the ring-two match: " + visited.size());
		}

		ParallelFirstSearch.Tile negativeTile = new ParallelFirstSearch.Tile(-4, -4);
		int originX = 3;
		int originZ = -7;
		int step = 20;
		long expected = bruteSampleCount(negativeTile, originX, originZ, step);
		long declared = negativeTile.sampleCount(originX, originZ, step);
		ParallelFirstSearch.TileResult<Void> sampled = negativeTile.findFirst(
			originX, originZ, step, () -> false, (x, z) -> null
		);
		if (declared != expected || sampled.samples() != expected) {
			throw new AssertionError(
				"Origin-anchored lattice disagrees at negative coordinates: expected " + expected
					+ ", declared " + declared + ", visited " + sampled.samples()
			);
		}
		return "outward first-match rings and origin-anchored lattice coverage";
	}

	public static String validateAuthoritativeSampling(
		GeneratorContext context,
		int originX,
		int originZ
	) {
		int checked = 0;
		for (int index = 0; index < SAMPLE_OFFSETS.length; index++) {
			int blockX = originX + SAMPLE_OFFSETS[index];
			int blockZ = originZ + SAMPLE_OFFSETS[(index * 7 + 3) % SAMPLE_OFFSETS.length];
			Cell[] chunk = context.filteredChunkCache.getFilteredChunk(blockX >> 4, blockZ >> 4);
			Cell generated = ChunkFilterGrid.getPaddedCell(chunk, blockX & 15, blockZ & 15);
			Cell sampled = PointFilterGrid.generate(blockX, blockZ, context, true).center();
			assertLocateEquivalent(context, generated, sampled, blockX, blockZ);
			checked++;
		}

		// Exercise every quart-aligned position in one fixed filter region. This covers the complete range of
		// offsets from the region boundary while comparing the sparse path to the actual generated cache.
		int regionX = Math.floorDiv(originX, RegionFilterGrid.REGION_BLOCKS)
			* RegionFilterGrid.REGION_BLOCKS;
		int regionZ = Math.floorDiv(originZ, RegionFilterGrid.REGION_BLOCKS)
			* RegionFilterGrid.REGION_BLOCKS;
		for (int blockZ = regionZ; blockZ < regionZ + RegionFilterGrid.REGION_BLOCKS; blockZ += 4) {
			for (int blockX = regionX; blockX < regionX + RegionFilterGrid.REGION_BLOCKS; blockX += 4) {
				Cell[] chunk = context.filteredChunkCache.getFilteredChunk(blockX >> 4, blockZ >> 4);
				Cell generated = ChunkFilterGrid.getPaddedCell(chunk, blockX & 15, blockZ & 15);
				Cell sampled = PointFilterGrid.generate(blockX, blockZ, context, true).center();
				assertLocateEquivalent(context, generated, sampled, blockX, blockZ);
				checked++;
			}
		}
		return checked + " sparse samples matched generated filtered cells";
	}

	public static String validateRawBiomeOptimization(
		GeneratorContext context,
		WorldgenBiomeSelection.Executable selection,
		Climate.Sampler sampler,
		int originX,
		int originZ
	) {
		BiomeLocateCellOptimization optimization = BiomeLocateCellOptimization.compile(
			selection, context, sampler
		);
		if (!optimization.enabled()) {
			throw new AssertionError("The built-in FTF plan did not enable its raw-equivalence locate path");
		}
		int checked = 0;
		Cell raw = new Cell();
		for (int ring = 0; ring <= 4_096 && checked < 16; ring += 128) {
			for (int z = -ring; z <= ring && checked < 16; z += 128) {
				for (int x = -ring; x <= ring && checked < 16; x += 128) {
					if (ring != 0 && Math.abs(x) != ring && Math.abs(z) != ring) {
						continue;
					}
					int blockX = originX + x;
					int blockZ = originZ + z;
					context.heightmap.apply(raw.reset(), blockX, blockZ, true);
					if (!optimization.canUseRaw(raw)) {
						continue;
					}
					Cell[] chunk = context.filteredChunkCache.getFilteredChunk(blockX >> 4, blockZ >> 4);
					Cell filtered = ChunkFilterGrid.getPaddedCell(chunk, blockX & 15, blockZ & 15);
					optimization.assertEquivalent(raw, filtered, blockX, blockZ);
					checked++;
				}
			}
		}
		if (checked == 0) {
			throw new AssertionError("Could not find a raw-equivalent biome sample in the validation capture");
		}
		return checked + " raw-equivalent samples matched generated filtered biome inputs";
	}

	private static long bruteSampleCount(
		ParallelFirstSearch.Tile tile,
		int originX,
		int originZ,
		int step
	) {
		long count = 0L;
		for (int z = tile.minBlockZ(); z < tile.minBlockZ() + ParallelFirstSearch.TILE_BLOCKS; z++) {
			if (Math.floorMod(z - originZ, step) != 0) {
				continue;
			}
			for (int x = tile.minBlockX(); x < tile.minBlockX() + ParallelFirstSearch.TILE_BLOCKS; x++) {
				if (Math.floorMod(x - originX, step) == 0) {
					count++;
				}
			}
		}
		return count;
	}

	private static void assertLocateEquivalent(
		GeneratorContext context,
		Cell generated,
		Cell sampled,
		int blockX,
		int blockZ
	) {
		if (generated.terrain != sampled.terrain
			|| generated.biomeRegionX != sampled.biomeRegionX
			|| generated.biomeRegionZ != sampled.biomeRegionZ) {
			throw mismatch(blockX, blockZ, "terrain/spatial ownership", generated, sampled);
		}
		for (var field : etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler.Field.values()) {
			float expected = field.readFinite(generated, context.heightmap);
			float actual = field.readFinite(sampled, context.heightmap);
			if (Float.floatToIntBits(expected) != Float.floatToIntBits(actual)) {
				throw mismatch(blockX, blockZ, field.getSerializedName(), expected, actual);
			}
		}
	}

	private static AssertionError mismatch(
		int blockX,
		int blockZ,
		String field,
		Object expected,
		Object actual
	) {
		return new AssertionError(
			"Sparse authoritative sampling disagrees with generated filtered terrain at "
				+ blockX + "," + blockZ + " for " + field + ": expected " + expected + ", got " + actual
		);
	}
}
