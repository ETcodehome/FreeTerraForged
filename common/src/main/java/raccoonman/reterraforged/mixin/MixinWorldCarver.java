package raccoonman.reterraforged.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldCarver.class)
public class MixinWorldCarver {
    @Inject(method = "canReplaceBlock(Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;Lnet/minecraft/world/level/block/state/BlockState;)Z", at = @At("HEAD"), cancellable = true)
    private void preventWaterCarving(CarverConfiguration config, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        // Safer fluid check that prevents premature block loading
        if (state.getFluidState().isSourceOfType(Fluids.WATER) || state.getFluidState().isSourceOfType(Fluids.FLOWING_WATER)) {
            cir.setReturnValue(false);
        }
    }
}