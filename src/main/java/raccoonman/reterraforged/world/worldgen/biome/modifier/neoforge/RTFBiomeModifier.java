package raccoonman.reterraforged.world.worldgen.biome.modifier.neoforge; // Recommended to rename package to neoforge

import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.common.world.BiomeModifier;

/**
 * Interface for NeoForge-specific Biome Modifiers.
 * In 1.21.1, we extend net.neoforged.neoforge.common.world.BiomeModifier.
 */
public interface RTFBiomeModifier extends BiomeModifier {

	// 1.21.1 NeoForge requires a MapCodec for biome modifiers
	@Override
	MapCodec<? extends BiomeModifier> codec();
}