package raccoonman.reterraforged.world.worldgen.feature.chance;

import java.util.function.Function;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import raccoonman.reterraforged.registries.RTFBuiltInRegistries;

public interface ChanceModifier {
	public static final Codec<ChanceModifier> CODEC = RTFBuiltInRegistries.CHANCE_MODIFIER_TYPE.byNameCodec().dispatch(ChanceModifier::codec, Function.identity());
	
	float getChance(ChanceContext chanceCtx, FeaturePlaceContext<?> placeCtx);
	
	MapCodec<? extends ChanceModifier> codec();
}
