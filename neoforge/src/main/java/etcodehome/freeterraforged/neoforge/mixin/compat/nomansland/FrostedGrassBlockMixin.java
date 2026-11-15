package etcodehome.freeterraforged.neoforge.mixin.compat.nomansland;

import com.farcr.nomansland.common.block.FrostedGrassBlock;
import etcodehome.freeterraforged.neoforge.compat.nomansland.SnowDepth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FrostedGrassBlock.class, remap = false)
public abstract class FrostedGrassBlockMixin {
    @Shadow @Final protected static VoxelShape SHAPE;

    @Inject(method = "createBlockStateDefinition", at = @At("TAIL"))
    private void snowpatch$addSnowLayers(StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(SnowDepth.FTF_LAYERS);
    }

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void snowpatch$adaptOutline(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (state.getValue(FrostedGrassBlock.SNOWLOGGED)) {
            Vec3 offset = state.getOffset(level, pos);
            cir.setReturnValue(Shapes.or(SHAPE.move(offset.x, offset.y, offset.z),
                    SnowDepth.snowState(state, level, pos).getShape(level, pos, context)));
        }
    }
}
