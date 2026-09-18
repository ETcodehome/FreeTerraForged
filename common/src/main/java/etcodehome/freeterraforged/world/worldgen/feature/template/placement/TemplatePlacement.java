package etcodehome.freeterraforged.world.worldgen.feature.template.placement;

import java.util.function.Function;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.registries.FTFBuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.Dimensions;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.TemplateContext;

public interface TemplatePlacement<T extends TemplateContext> {
    public static final Codec<TemplatePlacement<?>> CODEC = FTFBuiltInRegistries.TEMPLATE_PLACEMENT_TYPE.byNameCodec().dispatch(TemplatePlacement::codec, Function.identity());
    
    boolean canPlaceAt(LevelAccessor world, BlockPos pos, Dimensions dimensions);

    boolean canReplaceAt(LevelAccessor world, BlockPos pos);
    
    T createContext();
    
    MapCodec<? extends TemplatePlacement<T>> codec();
}
