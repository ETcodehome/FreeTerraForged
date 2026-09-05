package raccoonman.reterraforged.world.worldgen;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.levelgen.NoiseChunk;

public interface NoiseChunkHolder {
	@Nullable
	NoiseChunk reterraforged$getNoiseChunk();

	void reterraforged$beginNoiseChunkTileStage();

	void reterraforged$endNoiseChunkTileStage();
}
