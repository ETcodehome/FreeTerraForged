package etcodehome.freeterraforged.world.worldgen.biome.modifier.forge;

import com.mojang.serialization.Codec;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.world.ModifiableBiomeInfo;

public interface ForgeBiomeModifier extends BiomeModifier, net.minecraftforge.common.world.BiomeModifier {
	@Override
	Codec<? extends ForgeBiomeModifier> codec();
}
