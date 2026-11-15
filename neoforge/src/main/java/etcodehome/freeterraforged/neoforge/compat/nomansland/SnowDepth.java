package etcodehome.freeterraforged.neoforge.compat.nomansland;

import com.farcr.nomansland.common.block.FrostedGrassBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

public final class SnowDepth {
    // Zero keeps old chunks and non-FTF worlds on the neighboring-snow fallback.
    public static final IntegerProperty FTF_LAYERS = IntegerProperty.create("snowpatch_layers", 0, 7);

    private SnowDepth() {
    }

    public static BlockState snowState(BlockGetter level, BlockPos pos) {
        return snowState(level.getBlockState(pos), level, pos);
    }

    public static BlockState snowState(BlockState grass, BlockGetter level, BlockPos pos) {
        if (grass.hasProperty(FTF_LAYERS) && grass.getValue(FTF_LAYERS) > 0) {
            return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, grass.getValue(FTF_LAYERS));
        }
        int sum = 0;
        int count = 0;
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        // Only real snow is sampled: sampling other frosted grass would propagate
        // the one-layer reduction through a patch and make it order-dependent.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            neighbor.set(pos).move(direction);
            BlockState state = level.getBlockState(neighbor);
            if (state.is(Blocks.SNOW)) {
                sum += state.getValue(SnowLayerBlock.LAYERS);
                count++;
            } else if (state.is(Blocks.SNOW_BLOCK)) {
                sum += 8;
                count++;
            }
        }
        int layers = count == 0 ? 1 : Math.clamp(Math.round((float) sum / count) - 1, 1, 7);
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
    }

    public static boolean isSnowloggedGrass(BlockState state) {
        return state.getBlock() instanceof FrostedGrassBlock && state.getValue(FrostedGrassBlock.SNOWLOGGED);
    }

    public static BlockState applyFtfSnow(BlockState grass, BlockState snow) {
        if (!isSnowloggedGrass(grass) || !grass.hasProperty(FTF_LAYERS)) {
            return grass;
        }
        int layers;
        if (snow.is(Blocks.SNOW)) {
            layers = snow.getValue(SnowLayerBlock.LAYERS);
        } else if (snow.is(Blocks.SNOW_BLOCK)) {
            layers = 8;
        } else {
            return grass;
        }
        return grass.setValue(FrostedGrassBlock.SNOWLOGGED, true)
                .setValue(FTF_LAYERS, Math.clamp(layers - 1, 1, 7));
    }
}
