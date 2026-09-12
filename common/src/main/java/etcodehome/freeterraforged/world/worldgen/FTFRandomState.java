package etcodehome.freeterraforged.world.worldgen;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public interface FTFRandomState {
	void initialize(RegistryAccess registries);

	@Nullable
	Preset preset();

	@Nullable
	GeneratorContext generatorContext();

	long seed();
	
	DensityFunction wrap(DensityFunction function);

	Noise seed(Noise noise);
}
