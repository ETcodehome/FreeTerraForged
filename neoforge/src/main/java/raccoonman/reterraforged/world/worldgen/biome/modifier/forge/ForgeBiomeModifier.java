package raccoonman.reterraforged.world.worldgen.biome.modifier.forge;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifier;

interface ForgeBiomeModifier extends BiomeModifier, net.neoforged.common.world.BiomeModifier {
	MapCodec<? extends ForgeBiomeModifier> codec();
}
