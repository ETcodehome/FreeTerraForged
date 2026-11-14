package etcodehome.freeterraforged.world.worldgen.biome;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.SpawnType;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeCellCache;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;

public interface FTFClimateSampler {
	void setSpawnSearch(SpawnSearch search);

	SpawnSearch getSpawnSearch();

	void setClimateQuerySemantics(
		ClimateQueryPolicy policy,
		@Nullable Preset preset,
		long seed,
		@Nullable GeneratorContext context
	);

	default void adoptClimateQuerySemantics(ClimateQuerySemantics semantics) {
		if (semantics != null) {
			setClimateQuerySemantics(
				semantics.policy(),
				semantics.preset(),
				semantics.seed(),
				semantics.surfaceContext()
			);
		}
	}

	ClimateQuerySemantics climateQuerySemantics();

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
