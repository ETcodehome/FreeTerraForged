package raccoonman.reterraforged.world.worldgen.biome;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.SpawnType;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlan;
import raccoonman.reterraforged.world.worldgen.runtime.BiomeCellCache;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;

public interface RTFClimateSampler {
	void setSpawnSearch(SpawnSearch search);

	SpawnSearch getSpawnSearch();

	void setClimateQuerySemantics(
		ClimateQueryPolicy policy,
		@Nullable Preset preset,
		long seed,
		@Nullable GeneratorContext context
	);

	ClimateQuerySemantics climateQuerySemantics();

	float minimumSurfaceY(GeneratorContext context, int quartX, int quartZ);

	void setWorldgenPlan(@Nullable WorldgenPlan plan);

	void setWorldgenBinding(@Nullable WorldgenRuntimeBinding binding);

	@Nullable
	WorldgenPlan getWorldgenPlan();

	BiomeCellCache<WorldgenPlan> getBiomeCellCache();

	record SpawnSearch(SpawnType type, BlockPos center) {
		public SpawnSearch {
			java.util.Objects.requireNonNull(type, "type");
			java.util.Objects.requireNonNull(center, "center");
		}
	}
}
