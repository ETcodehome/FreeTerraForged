package etcodehome.freeterraforged.world.worldgen;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.levelgen.NoiseChunk;

public interface NoiseChunkHolder {
	@Nullable
	NoiseChunk freeterraforged$getNoiseChunk();

	void freeterraforged$beginNoiseChunkTileStage();

	void freeterraforged$endNoiseChunkTileStage();
}
