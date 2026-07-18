package raccoonman.reterraforged.mixin;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Provides a 1-slot chunk memoization cache for WorldGenRegion.getChunk.
 * Bypasses expensive coordinate-bound chunk map lookups during feature placement
 * when the same chunk is queried successively.
 */
@Mixin(WorldGenRegion.class)
public abstract class MixinWorldGenRegionCache {

    @Unique private ChunkAccess rtf$lastChunk;
    @Unique private int rtf$lastX = Integer.MIN_VALUE;
    @Unique private int rtf$lastZ = Integer.MIN_VALUE;
    @Unique private ChunkStatus rtf$lastStatus;

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true)
    private void rtf$memoHit(final int x, final int z, final ChunkStatus chunkStatus, final boolean requireChunk,
                             final CallbackInfoReturnable<ChunkAccess> cir) {
        if (this.rtf$lastChunk != null
                && x == this.rtf$lastX
                && z == this.rtf$lastZ
                && chunkStatus == this.rtf$lastStatus) {

            cir.setReturnValue(this.rtf$lastChunk);
        }
    }

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("RETURN"))
    private void rtf$memoStore(final int x, final int z, final ChunkStatus chunkStatus, final boolean requireChunk,
                               final CallbackInfoReturnable<ChunkAccess> cir) {
        final ChunkAccess result = cir.getReturnValue();
        if (result != null) {
            this.rtf$lastChunk = result;
            this.rtf$lastX = x;
            this.rtf$lastZ = z;
            this.rtf$lastStatus = chunkStatus;
        }
    }
}