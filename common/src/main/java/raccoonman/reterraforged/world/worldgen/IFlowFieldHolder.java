package raccoonman.reterraforged.world.worldgen;

import org.jetbrains.annotations.Nullable;

public interface IFlowFieldHolder {
    @Nullable
    ChunkFlowField reterraforged$getFlowField();

    ChunkFlowField reterraforged$getOrCreateFlowField();
}
