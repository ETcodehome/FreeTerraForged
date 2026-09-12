package etcodehome.freeterraforged.world.worldgen.biome.modifier.neoforge;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

interface ForgeBiomeModifier extends BiomeModifier, net.neoforged.neoforge.common.world.BiomeModifier {
	void modify(Holder<Biome> biome, net.neoforged.neoforge.common.world.BiomeModifier.Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder);

	MapCodec<? extends ForgeBiomeModifier> codec();
}
