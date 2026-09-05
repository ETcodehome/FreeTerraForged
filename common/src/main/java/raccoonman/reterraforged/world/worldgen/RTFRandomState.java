package raccoonman.reterraforged.world.worldgen;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.levelgen.DensityFunction;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenEpoch;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlan;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;

public interface RTFRandomState extends AutoCloseable {
	void initialize(WorldgenEpoch epoch);

	void bindPlan(WorldgenRuntimeBinding binding);

	void preparePlanRebind(WorldgenEpoch epoch, WorldgenPlan plan);

	@Nullable
	WorldgenEpoch epoch();

	@Nullable
	WorldgenPlan plan();

	@Nullable
	WorldgenRuntimeBinding binding();

	boolean isTerraForged();

	@Nullable
	Preset preset();

	@Nullable
	GeneratorContext generatorContext();

	long seed();
	
	DensityFunction wrap(DensityFunction function);

	Noise seed(Noise noise);

	@Override
	void close();
}
