package etcodehome.freeterraforged.neoforge.mixin.compat.nomansland;

import com.farcr.nomansland.common.registry.blocks.NMLBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(SimpleBlockFeature.class)
public abstract class SimpleBlockFeatureMixin {
    @Inject(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;canSurvive(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"),
            cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
    private void snowpatch$skipSnowyPebbles(FeaturePlaceContext<SimpleBlockConfiguration> context,
            CallbackInfoReturnable<Boolean> cir, SimpleBlockConfiguration config,
            WorldGenLevel level, BlockPos pos, BlockState state) {
        if (!state.is(NMLBlocks.PEBBLES.block())) {
            return;
        }
        // Worldgen pebbles precede the final snow pass, so existing snow alone
        // cannot identify snowy terrain. The biome check includes altitude.
        if (level.getBiome(pos).value().coldEnoughToSnow(pos)
                || level.getBlockState(pos).is(BlockTags.SNOW)
                || level.getBlockState(pos.below()).is(BlockTags.SNOW)) {
            cir.setReturnValue(false);
        }
    }
}
