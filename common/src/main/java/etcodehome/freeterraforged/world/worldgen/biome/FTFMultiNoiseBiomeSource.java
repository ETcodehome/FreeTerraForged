package etcodehome.freeterraforged.world.worldgen.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

public interface FTFMultiNoiseBiomeSource {
    Climate.ParameterList<Holder<Biome>> reterraforged$getParameters();
}
