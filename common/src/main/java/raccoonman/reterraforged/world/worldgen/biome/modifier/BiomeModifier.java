package raccoonman.reterraforged.world.worldgen.biome.modifier;

import java.util.function.Function;
import java.util.List;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import raccoonman.reterraforged.registries.RTFBuiltInRegistries;

public interface BiomeModifier {
    public static final Codec<BiomeModifier> DIRECT_CODEC = RTFBuiltInRegistries.BIOME_MODIFIER_TYPE.byNameCodec().dispatch(BiomeModifier::codec, Function.identity());

	MapCodec<? extends BiomeModifier> codec();

	GenerationStep.Decoration step();

	/** Applies this declarative mutation to one immutable feature-step value. */
	List<Holder<PlacedFeature>> apply(
		Holder<Biome> biome,
		List<Holder<PlacedFeature>> features,
		HolderLookup.Provider lookups
	);
}
