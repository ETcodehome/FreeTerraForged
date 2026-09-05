package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import org.jetbrains.annotations.Nullable;

public interface NoiseChunkTileOwner {
	void reterraforged$beginTileStage();

	void reterraforged$endTileStage();

	@Nullable
	Tile.Chunk reterraforged$currentTileChunk();

	@Nullable
	Tile reterraforged$currentTile();
}
