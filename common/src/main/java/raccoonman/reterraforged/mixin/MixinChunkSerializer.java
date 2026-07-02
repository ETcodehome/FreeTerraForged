package raccoonman.reterraforged.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk; // Import this!
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

@Mixin(ChunkSerializer.class)
public class MixinChunkSerializer {

    @Inject(method = "write", at = @At("RETURN"))
    private static void injectSaveData(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag resultTag = cir.getReturnValue();
        if (resultTag != null && chunk instanceof IFlowFieldHolder holder) {
            holder.reterraforged$getFlowField().writeToNbt(resultTag);
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void injectLoadData(ServerLevel level, PoiManager poiManager, RegionStorageInfo regionStorageInfo, ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> cir) {
        ProtoChunk returnedChunk = cir.getReturnValue();
        if (returnedChunk != null) {

            // Fix: Strip off the wrapper if it's a fully generated chunk from disk!
            ChunkAccess targetChunk = returnedChunk;
            if (returnedChunk instanceof ImposterProtoChunk imposter) {
                targetChunk = imposter.getWrapped();
            }

            // Now apply the NBT data to the real underlying chunk
            if (targetChunk instanceof IFlowFieldHolder holder) {
                holder.reterraforged$getFlowField().readFromNbt(tag);
            }
        }
    }
}