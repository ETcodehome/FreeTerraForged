package etcodehome.freeterraforged.test;

import java.util.concurrent.TimeUnit;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.search.TerrainLocateSearch;
import etcodehome.freeterraforged.world.worldgen.search.TerrainSearchTargets;
import net.minecraft.core.BlockPos;

/** Live-plan regressions for both production terrain-locate evaluation paths. */
public final class TerrainLocateValidator {
	private static final int CAPTURE_EXTENT = 4_096;
	private static final int CAPTURE_STEP = 64;

	private TerrainLocateValidator() {
	}

	public static Result validate(GeneratorContext context, BlockPos origin) throws Exception {
		Captured rawCapture = captureRawTarget(context, origin);
		TimedResult raw = locate(context, rawCapture.terrain(), rawCapture.position());
		assertMatch(context, rawCapture.terrain(), raw.result(), "raw-identity");
		if (context.filters.affectsTerrainIdentity(rawCapture.terrain())) {
			throw new AssertionError("Raw terrain regression selected a filter-sensitive target");
		}

		TimedResult filtered = locate(context, TerrainType.BEACH, origin);
		assertMatch(context, TerrainType.BEACH, filtered.result(), "filtered");
		if (!context.filters.affectsTerrainIdentity(TerrainType.BEACH)) {
			throw new AssertionError("Beach terrain regression no longer exercises the filtered path");
		}

		return new Result(
			rawCapture.terrain().getName(),
			rawCapture.position(),
			position(raw.result().match()),
			TimeUnit.NANOSECONDS.toMillis(raw.nanos()),
			raw.result().samples(),
			raw.result().resolution().horizontalStep(),
			position(filtered.result().match()),
			TimeUnit.NANOSECONDS.toMillis(filtered.nanos()),
			filtered.result().samples(),
			filtered.result().resolution().horizontalStep()
		);
	}

	private static Captured captureRawTarget(GeneratorContext context, BlockPos origin) {
		Cell cell = new Cell();
		for (int ring = 0; ring <= CAPTURE_EXTENT; ring += CAPTURE_STEP) {
			int min = -ring;
			int max = ring;
			for (int z = min; z <= max; z += CAPTURE_STEP) {
				for (int x = min; x <= max; x += CAPTURE_STEP) {
					if (ring != 0 && Math.abs(x) != ring && Math.abs(z) != ring) {
						continue;
					}
					int blockX = origin.getX() + x;
					int blockZ = origin.getZ() + z;
					context.heightmap.apply(cell.reset(), blockX, blockZ, true);
					Terrain terrain = cell.terrain;
					if (TerrainSearchTargets.contains(terrain)
						&& !context.filters.affectsTerrainIdentity(terrain)) {
						return new Captured(new BlockPos(blockX, origin.getY(), blockZ), terrain);
					}
				}
			}
		}
		throw new AssertionError("Could not capture a supported filter-stable terrain near " + origin);
	}

	private static TimedResult locate(
		GeneratorContext context,
		Terrain target,
		BlockPos origin
	) throws Exception {
		long start = System.nanoTime();
		TerrainLocateSearch.Result result = TerrainLocateSearch.findFirst(context, target, origin)
			.get(65L, TimeUnit.SECONDS);
		return new TimedResult(result, System.nanoTime() - start);
	}

	private static void assertMatch(
		GeneratorContext context,
		Terrain expected,
		TerrainLocateSearch.Result result,
		String path
	) {
		if (result.timedOut() || result.match() == null) {
			throw new AssertionError(path + " terrain locate did not find " + expected.getName() + ": " + result);
		}
		TerrainLocateSearch.Match match = result.match();
		Cell[] chunk = context.filteredChunkCache.getFilteredChunk(match.x() >> 4, match.z() >> 4);
		Cell generated = ChunkFilterGrid.getPaddedCell(chunk, match.x() & 15, match.z() & 15);
		if (generated.terrain != expected) {
			throw new AssertionError(
				path + " terrain locate predicted " + expected.getName() + " at " + position(match)
					+ " but authoritative filtered terrain is " + generated.terrain.getName()
			);
		}
	}

	private static BlockPos position(TerrainLocateSearch.Match match) {
		return new BlockPos(match.x(), 0, match.z());
	}

	private record Captured(BlockPos position, Terrain terrain) {
	}

	private record TimedResult(TerrainLocateSearch.Result result, long nanos) {
	}

	public record Result(
		String rawTerrain,
		BlockPos rawCapture,
		BlockPos rawMatch,
		long rawMillis,
		long rawSamples,
		int rawStep,
		BlockPos beachMatch,
		long beachMillis,
		long beachSamples,
		int beachStep
	) {
	}
}
