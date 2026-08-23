package raccoonman.reterraforged.mixin;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import raccoonman.reterraforged.world.worldgen.util.WorldGenTracker;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseFill {

    private static final ThreadLocal<Long> rtf$fillStart = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> rtf$surfaceStart = ThreadLocal.withInitial(() -> 0L);

    @Inject(method = "doFill", at = @At("HEAD"))
    private void rtf$fillStart(final Blender blender, final StructureManager structureManager,
                               final RandomState random, final ChunkAccess chunk,
                               final int minCellY, final int cellCountY,
                               final CallbackInfoReturnable<ChunkAccess> cir) {
        long now = System.nanoTime();
        rtf$fillStart.set(now);
        WorldGenTracker.FIRST_START_NANOS.compareAndSet(0, now);
        int active = WorldGenTracker.ACTIVE_THREADS.incrementAndGet();
        WorldGenTracker.PEAK_CONCURRENCY.updateAndGet(peak -> Math.max(peak, active));
    }

    @Inject(method = "doFill", at = @At("TAIL"))
    private void rtf$fillEnd(final Blender blender, final StructureManager structureManager,
                             final RandomState random, final ChunkAccess chunk,
                             final int minCellY, final int cellCountY,
                             final CallbackInfoReturnable<ChunkAccess> cir) {
        final long start = rtf$fillStart.get();
        if (start != 0) {
            WorldGenTracker.TOTAL_NANOS.add(System.nanoTime() - start);
            WorldGenTracker.TOTAL_CHUNKS.increment(); // Register one completed chunk tally
        }
        WorldGenTracker.ACTIVE_THREADS.decrementAndGet();
    }

    @Inject(
            method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("HEAD"))
    private void rtf$surfaceStart(final WorldGenRegion level, final StructureManager structureManager,
                                  final RandomState random, final ChunkAccess chunk, final CallbackInfo ci) {
        rtf$surfaceStart.set(System.nanoTime());
        int active = WorldGenTracker.ACTIVE_THREADS.incrementAndGet();
        WorldGenTracker.PEAK_CONCURRENCY.updateAndGet(peak -> Math.max(peak, active));
    }

    @Inject(
            method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("RETURN"))
    private void rtf$surfaceEnd(final WorldGenRegion level, final StructureManager structureManager,
                                final RandomState random, final ChunkAccess chunk, final CallbackInfo ci) {
        final long start = rtf$surfaceStart.get();
        if (start != 0) {
            WorldGenTracker.TOTAL_NANOS.add(System.nanoTime() - start);
        }
        WorldGenTracker.ACTIVE_THREADS.decrementAndGet();
    }
}