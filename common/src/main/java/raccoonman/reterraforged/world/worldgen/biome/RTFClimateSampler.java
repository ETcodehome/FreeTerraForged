package raccoonman.reterraforged.world.worldgen.biome;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public interface RTFClimateSampler {
	void setSpawnSearchCenter(BlockPos center);
	
	BlockPos getSpawnSearchCenter();

	void setUndergroundBiomeBandingPreset(@Nullable Preset preset, long seed);

	@Nullable
	Preset getUndergroundBiomeBandingPreset();

	long getUndergroundBiomeBandingSeed();
}
