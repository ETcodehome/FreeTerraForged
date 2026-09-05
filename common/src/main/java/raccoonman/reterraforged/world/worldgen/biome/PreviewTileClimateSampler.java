package raccoonman.reterraforged.world.worldgen.biome;

import net.minecraft.world.level.levelgen.DensityFunction;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.MarkerFunction;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

final class PreviewTileClimateSampler implements MarkerFunction.Mapped {
	private final TileLookup tileLookup;
	private final Heightmap heightmap;
	private final CellSampler.Field field;

	PreviewTileClimateSampler(
		Tile tile,
		Heightmap heightmap,
		float originX,
		float originZ,
		int zoom,
		CellSampler.Field field
	) {
		this(new TileLookup(tile, originX, originZ, zoom), heightmap, field);
	}

	PreviewTileClimateSampler(TileLookup tileLookup, Heightmap heightmap, CellSampler.Field field) {
		this.tileLookup = tileLookup;
		this.heightmap = heightmap;
		this.field = field;
	}

	@Override
	public double compute(FunctionContext context) {
		return this.field.read(this.tileLookup.lookup(context), this.heightmap);
	}

	@Override
	public double minValue() {
		return 0.0D;
	}

	@Override
	public double maxValue() {
		return 1.0D;
	}

	static final class TileLookup {
		private final Tile tile;
		private final float translateX;
		private final float translateZ;
		private final float zoom;
		private int lastX = Integer.MIN_VALUE;
		private int lastZ = Integer.MIN_VALUE;
		private Cell lastCell;

		TileLookup(Tile tile, float originX, float originZ, int zoom) {
			if (zoom <= 0) {
				throw new IllegalArgumentException("Preview zoom must be positive");
			}
			this.tile = tile;
			this.translateX = originX;
			this.translateZ = originZ;
			this.zoom = zoom;
		}

		Cell lookup(FunctionContext context) {
			return this.lookupBlock(context.blockX(), context.blockZ());
		}

		Cell lookupBlock(int blockX, int blockZ) {
			int size = this.tile.getBlockSize().size();
			int x = clamp(Math.round((blockX - this.translateX) / this.zoom), 0, size - 1);
			int z = clamp(Math.round((blockZ - this.translateZ) / this.zoom), 0, size - 1);
			if (this.lastCell == null || x != this.lastX || z != this.lastZ) {
				this.lastCell = this.tile.lookup(x, z);
				this.lastX = x;
				this.lastZ = z;
			}
			return this.lastCell;
		}

		private static int clamp(int value, int min, int max) {
			return Math.max(min, Math.min(max, value));
		}
	}
}
