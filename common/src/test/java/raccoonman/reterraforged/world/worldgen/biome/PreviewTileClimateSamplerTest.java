package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import raccoonman.reterraforged.concurrent.SimpleResource;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.MutableFunctionContext;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

class PreviewTileClimateSamplerTest {
	@Test
	void mapsQuartClimateQueriesToTheGeneratedTileCoordinates() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		Size blocks = Size.make(4, 0);
		Size chunks = Size.make(1, 0);
		Cell[] cells = new Cell[blocks.arraySize()];
		for (int i = 0; i < cells.length; i++) {
			cells[i] = new Cell();
			cells[i].height = i;
		}
		Tile tile = new Tile(
			0,
			0,
			0,
			0,
			blocks,
			chunks,
			new SimpleResource<>(cells, ignored -> { }),
			new SimpleResource<>(new Tile.Chunk[1], ignored -> { })
		);
		try {
			PreviewTileClimateSampler sampler = new PreviewTileClimateSampler(
				tile,
				(Heightmap) null,
				-2.0F,
				-2.0F,
				1,
				CellSampler.Field.HEIGHT
			);
			MutableFunctionContext context = new MutableFunctionContext();
			assertEquals(0.0D, sampler.compute(context.at(-2, 0, -2)));
			assertEquals(15.0D, sampler.compute(context.at(1, 0, 1)));
		} finally {
			tile.close();
		}
	}
}
