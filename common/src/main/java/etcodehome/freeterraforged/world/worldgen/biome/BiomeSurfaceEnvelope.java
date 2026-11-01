package etcodehome.freeterraforged.world.worldgen.biome;

import net.minecraft.core.QuartPos;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;

public final class BiomeSurfaceEnvelope {
	static final int MIN_OFFSET = -3;
	static final int MAX_OFFSET = 6;
	static final int MAX_VERTICAL_OFFSET = 5;

	private static final ThreadLocal<Cell> SCRATCH_CELL = ThreadLocal.withInitial(Cell::new);

	public BiomeSurfaceEnvelope() {
	}

	/**
	 * Pre-populating is no longer needed since surface height is computed directly on demand
	 * with zero object allocations and zero cache retention.
	 */
	public void prepopulateFromRegionGrid(
		GeneratorContext context,
		int regionChunkX,
		int regionChunkZ,
		Cell[] grid,
		int totalSize,
		int border,
		int regionChunks
	) {
	}

	public void prepopulateFromChunkGrid(
		GeneratorContext context,
		int chunkX,
		int chunkZ,
		Cell[] grid,
		int totalSize,
		int border
	) {
	}

	public int minimumSurfaceY(GeneratorContext context, int quartX, int quartZ) {
		int blockX = QuartPos.toBlock(quartX);
		int blockZ = QuartPos.toBlock(quartZ);
		WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.ACTIVE ? WorldgenPerfTracker.stats() : null;
		WorldgenPerfTracker.ApplyCellCaller prev = null;
		if (ts != null) {
			prev = ts.currentCaller;
			ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.BIOME_ENVELOPE;
			ts.biomeEnvelopeCalls++;
		}
		try {
			Cell cell = SCRATCH_CELL.get();
			context.lookup.applyCell(cell.reset(), blockX, blockZ, false, false);
			int minY = context.levels.scale(cell.height);

			context.lookup.applyCell(cell.reset(), blockX + 3, blockZ, false, false);
			minY = Math.min(minY, context.levels.scale(cell.height));

			context.lookup.applyCell(cell.reset(), blockX, blockZ + 3, false, false);
			minY = Math.min(minY, context.levels.scale(cell.height));

			context.lookup.applyCell(cell.reset(), blockX + 3, blockZ + 3, false, false);
			minY = Math.min(minY, context.levels.scale(cell.height));

			return Math.min(context.levels.worldHeight - 1, minY);
		} finally {
			if (ts != null) {
				ts.currentCaller = prev;
			}
		}
	}

	public void clear() {
	}
}
