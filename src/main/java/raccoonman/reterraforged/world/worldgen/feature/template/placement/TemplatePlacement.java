package raccoonman.reterraforged.world.worldgen.feature.template.placement;

import java.util.function.Function;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import raccoonman.reterraforged.registries.RTFBuiltInRegistries;
import raccoonman.reterraforged.world.worldgen.feature.template.template.Dimensions;
import raccoonman.reterraforged.world.worldgen.feature.template.template.TemplateContext;

public interface TemplatePlacement<T extends TemplateContext> {
    // 1. Added "type" key
    // 2. Explicitly cast the codec to MapCodec to satisfy the dispatcher
    public static final Codec<TemplatePlacement<?>> CODEC = RTFBuiltInRegistries.TEMPLATE_PLACEMENT_TYPE.byNameCodec()
            .dispatch("type",
                    TemplatePlacement::codec,
                    (codec) -> (com.mojang.serialization.MapCodec<? extends TemplatePlacement<?>>) codec
            );

    boolean canPlaceAt(LevelAccessor world, BlockPos pos, Dimensions dimensions);

    boolean canReplaceAt(LevelAccessor world, BlockPos pos);

    T createContext();

    Codec<? extends TemplatePlacement<T>> codec();
}
