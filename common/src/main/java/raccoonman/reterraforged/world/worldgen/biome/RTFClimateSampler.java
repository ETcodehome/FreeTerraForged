package raccoonman.reterraforged.world.worldgen.biome;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlan;
import raccoonman.reterraforged.world.worldgen.runtime.BiomeCellCache;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;

public interface RTFClimateSampler {
	void setSpawnSearchCenter(BlockPos center);
	
	BlockPos getSpawnSearchCenter();

	void setUndergroundBiomeBandingPreset(@Nullable Preset preset, long seed);

	@Nullable
	Preset getUndergroundBiomeBandingPreset();

	long getUndergroundBiomeBandingSeed();

	void setUndergroundBiomeSurfaceContext(@Nullable GeneratorContext context);

	@Nullable
	GeneratorContext getUndergroundBiomeSurfaceContext();

	float minimumSurfaceY(GeneratorContext context, int quartX, int quartZ);

	void setWorldgenPlan(@Nullable WorldgenPlan plan);

	void setWorldgenBinding(@Nullable WorldgenRuntimeBinding binding);

	@Nullable
	WorldgenPlan getWorldgenPlan();

	BiomeCellCache<WorldgenPlan> getBiomeCellCache();
}
