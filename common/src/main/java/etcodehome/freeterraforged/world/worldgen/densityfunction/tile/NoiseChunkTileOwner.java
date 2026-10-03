package etcodehome.freeterraforged.world.worldgen.densityfunction.tile;

import org.jetbrains.annotations.Nullable;

public interface NoiseChunkTileOwner {
	void freeterraforged$beginTileStage();

	void freeterraforged$endTileStage();

	@Nullable
	Tile.Chunk freeterraforged$currentTileChunk();

	@Nullable
	Tile freeterraforged$currentTile();
}
