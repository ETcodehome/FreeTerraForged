package etcodehome.freeterraforged.world.worldgen.cell.heightmap;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.CellLookup;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;

public class WorldLookup implements CellLookup {
	private static final ThreadLocal<Cell> SCRATCH_NEIGHBOR = ThreadLocal.withInitial(Cell::new);

	private final GeneratorContext context;
	private final float waterLevel;
	private final float beachLevel;
	private final Heightmap heightmap;
	private final Levels levels;
	
	public WorldLookup(GeneratorContext context) {
		this.context = context;
		this.heightmap = context.heightmap;
		this.waterLevel = context.levels.water;
		this.beachLevel = context.levels.water(5);
		this.levels = context.levels;
	}

	public GeneratorContext getContext() {
		return this.context;
	}
	
	public Heightmap getHeightmap() {
		return this.heightmap;
	}

	@Override
	public Cell lookup(int blockX, int blockZ) {
		Cell cell = new Cell();
		this.applyCell(cell, blockX, blockZ, false, false);
		this.computeSteepness(cell, blockX, blockZ);
		return cell;
	}

	public boolean applyCell(Cell cell, int x, int z, boolean applyClimate) {
		return this.applyCell(cell, x, z, false, applyClimate);
	}

	public boolean applyCell(Cell cell, int x, int z, boolean load, boolean applyClimate) {
		if (!WorldgenPerfTracker.ACTIVE) {
			boolean res = this.compute(cell, x, z, applyClimate);
			if (load) {
				this.computeSteepness(cell, x, z);
			}
			return res;
		}
		WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
		long t0 = System.nanoTime();
		boolean res = this.compute(cell, x, z, applyClimate);
		if (load) {
			this.computeSteepness(cell, x, z);
		}
		long elapsed = System.nanoTime() - t0;
		if (!ts.insideGridInit) {
			ts.standaloneApplyCellCount++;
			ts.standaloneApplyCellNanos += elapsed;
			int callerIdx = ts.currentCaller.ordinal();
			ts.callerCounts[callerIdx]++;
			ts.callerNanos[callerIdx] += elapsed;
		}
		return res;
	}

	public void applyBiomeRegion(Cell cell, int x, int z) {
		if (!WorldgenPerfTracker.ACTIVE) {
			this.heightmap.applyBiomeRegion(cell, x, z);
			return;
		}
		WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
		long t0 = System.nanoTime();
		this.heightmap.applyBiomeRegion(cell, x, z);
		ts.applyBiomeRegionCount++;
		ts.applyBiomeRegionNanos += (System.nanoTime() - t0);
	}

	public void computeSteepness(Cell cell, int cx, int cz) {
		float totalHeightDif = 0.0F;
		Cell neighbour = SCRATCH_NEIGHBOR.get();
		for (int dz = -1; dz <= 1; ++dz) {
			for (int dx = -1; dx <= 1; ++dx) {
				if (dx != 0 || dz != 0) {
					int x = cx + dx;
					int z = cz + dz;
					this.compute(neighbour.reset(), x, z, false);
					float height = Math.max(neighbour.height, this.waterLevel);
					totalHeightDif += Math.abs(cell.height - height);
				}
			}
		}
		cell.gradient = totalHeightDif * (10.0F * 15.0F / 8.0F);
	}

	private boolean compute(Cell cell, int x, int z, boolean applyClimate) {
		this.heightmap.apply(cell, x, z, applyClimate);
		if (cell.terrain == TerrainType.COAST && cell.height > this.waterLevel && cell.height <= this.beachLevel) {
			cell.terrain = TerrainType.BEACH;
		}
		return false;
	}
}
