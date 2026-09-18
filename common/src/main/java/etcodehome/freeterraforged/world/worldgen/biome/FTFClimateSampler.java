package etcodehome.freeterraforged.world.worldgen.biome;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;

public interface FTFClimateSampler {
	void setSpawnSearchCenter(BlockPos center);
	
	BlockPos getSpawnSearchCenter();

	void setUndergroundBiomeBandingPreset(@Nullable Preset preset, long seed);

	@Nullable
	Preset getUndergroundBiomeBandingPreset();

	long getUndergroundBiomeBandingSeed();

	void setUndergroundBiomeSurfaceContext(@Nullable GeneratorContext context);

	@Nullable
	GeneratorContext getUndergroundBiomeSurfaceContext();
}
