package etcodehome.freeterraforged.neoforge.mixin.compat.nomansland;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import etcodehome.freeterraforged.neoforge.compat.nomansland.SnowDepth;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.feature.DecorateSnowFeature", remap = false)
public abstract class FtfDecorateSnowFeatureMixin {
    @WrapOperation(method = "place", slice = @Slice(from = @At(value = "FIELD",
            target = "Letcodehome/freeterraforged/world/worldgen/feature/DecorateSnowFeature$Config;smooth:Z")),
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z"))
    private boolean snowpatch$includeSnowloggedGrass(BlockState state, Block block, Operation<Boolean> original) {
        return original.call(state, block) || (block == Blocks.SNOW && SnowDepth.isSnowloggedGrass(state));
    }

    @Inject(method = "smoothSnow", at = @At("RETURN"))
    private static void snowpatch$initializeGrassDepth(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
            Cell cell, Levels levels, float threshold, CallbackInfo ci) {
        BlockState grass = chunk.getBlockState(pos);
        if (SnowDepth.isSnowloggedGrass(grass) && grass.getValue(SnowDepth.FTF_LAYERS) == 0) {
            // The original method can return without a write on zero/negative fractions.
            chunk.setBlockState(pos, SnowDepth.applyFtfSnow(grass, Blocks.SNOW.defaultBlockState()), false);
        }
    }

    @WrapOperation(method = "smoothSnow", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState snowpatch$preserveGrass(ChunkAccess chunk, BlockPos pos, BlockState snow,
            boolean moving, Operation<BlockState> original) {
        BlockState grass = chunk.getBlockState(pos);
        return original.call(chunk, pos, SnowDepth.isSnowloggedGrass(grass)
                ? SnowDepth.applyFtfSnow(grass, snow) : snow, moving);
    }
}
