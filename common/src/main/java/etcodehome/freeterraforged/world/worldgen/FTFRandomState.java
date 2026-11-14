package etcodehome.freeterraforged.world.worldgen;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.levelgen.DensityFunction;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenEpoch;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;

public interface FTFRandomState extends AutoCloseable {
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
