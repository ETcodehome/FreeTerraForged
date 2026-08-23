package raccoonman.reterraforged.world.worldgen.biome;

import net.minecraft.world.level.levelgen.DensityFunction;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.MarkerFunction;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

final class PreviewTileClimateSampler implements MarkerFunction.Mapped {
	private final Tile tile;
	private final Heightmap heightmap;
	private final float translateX;
	private final float translateZ;
	private final float zoom;
	private final CellSampler.Field field;

	PreviewTileClimateSampler(
		Tile tile,
		Heightmap heightmap,
		float originX,
		float originZ,
		int zoom,
		CellSampler.Field field
	) {
		this.tile = tile;
		this.heightmap = heightmap;
		this.translateX = originX;
		this.translateZ = originZ;
		this.zoom = zoom;
		this.field = field;
	}

	@Override
	public double compute(FunctionContext context) {
		int x = clamp(Math.round((context.blockX() - this.translateX) / this.zoom), 0, this.tile.getBlockSize().size() - 1);
		int z = clamp(Math.round((context.blockZ() - this.translateZ) / this.zoom), 0, this.tile.getBlockSize().size() - 1);
		Cell cell = this.tile.lookup(x, z);
		return this.field.read(cell, this.heightmap);
	}

	@Override
	public double minValue() {
		return 0.0D;
	}

	@Override
	public double maxValue() {
		return 1.0D;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
