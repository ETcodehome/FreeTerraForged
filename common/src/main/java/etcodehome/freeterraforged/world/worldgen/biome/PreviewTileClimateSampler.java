package etcodehome.freeterraforged.world.worldgen.biome;

import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.densityfunction.MarkerFunction;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;

final class PreviewTileClimateSampler implements MarkerFunction.Mapped {
	private final TileLookup tileLookup;
	private final Heightmap heightmap;
	private final CellSampler.Field field;

	PreviewTileClimateSampler(TileLookup tileLookup, Heightmap heightmap, CellSampler.Field field) {
		this.tileLookup = tileLookup;
		this.heightmap = heightmap;
		this.field = field;
	}

	@Override
	public double compute(FunctionContext context) {
		return this.field.readFinite(this.tileLookup.lookup(context), this.heightmap);
	}

	@Override
	public double minValue() {
		return -Float.MAX_VALUE;
	}

	@Override
	public double maxValue() {
		return Float.MAX_VALUE;
	}

	static final class TileLookup {
		private final Tile tile;
		private final WorldLookup worldLookup;
		private final Cell sampledCell = new Cell();
		private final float translateX;
		private final float translateZ;
		private final float zoom;
		private int lastX = Integer.MIN_VALUE;
		private int lastZ = Integer.MIN_VALUE;
		private Cell lastCell;

		TileLookup(Tile tile, WorldLookup worldLookup, float originX, float originZ, int zoom) {
			if (zoom <= 0) {
				throw new IllegalArgumentException("Preview zoom must be positive");
			}
			this.tile = tile;
			this.worldLookup = worldLookup;
			this.translateX = originX;
			this.translateZ = originZ;
			this.zoom = zoom;
		}

		Cell lookup(FunctionContext context) {
			return this.lookupBlock(context.blockX(), context.blockZ());
		}

		Cell lookupBlock(int blockX, int blockZ) {
			if (this.lastCell == null || blockX != this.lastX || blockZ != this.lastZ) {
				this.lastCell = null;
				int size = this.tile.getBlockSize().size();
				int x = (int) Math.round((blockX - (double) this.translateX) / this.zoom);
				int z = (int) Math.round((blockZ - (double) this.translateZ) / this.zoom);
				// A raster pixel is reusable only at its exact world position. Rounding a
				// quart query to a nearby pixel changes climate and provider-region identity.
				if (x >= 0 && x < size && z >= 0 && z < size
					&& this.translateX + x * (double) this.zoom == blockX
					&& this.translateZ + z * (double) this.zoom == blockZ) {
					this.lastCell = this.tile.lookup(x, z);
				} else {
					this.worldLookup.applyCell(this.sampledCell.reset(), blockX, blockZ, false, true);
					this.lastCell = this.sampledCell;
				}
				this.lastX = blockX;
				this.lastZ = blockZ;
			}
			return this.lastCell;
		}
	}
}
