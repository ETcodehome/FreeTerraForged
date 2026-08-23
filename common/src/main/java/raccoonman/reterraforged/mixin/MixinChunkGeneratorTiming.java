package raccoonman.reterraforged.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.world.worldgen.util.WorldGenTracker;

@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGeneratorTiming {

    private static final ThreadLocal<Long> rtf$structureStart = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> rtf$featureStart = ThreadLocal.withInitial(() -> 0L);

    // 1. Track Structure Logic Generation
    @Inject(
            method = "createStructures(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;)V",
            at = @At("HEAD")
    )
    private void rtf$structureStart(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                    StructureManager structureManager, ChunkAccess chunk,
                                    StructureTemplateManager templateManager, CallbackInfo ci) {
        rtf$structureStart.set(System.nanoTime());
        int active = WorldGenTracker.ACTIVE_THREADS.incrementAndGet();
        WorldGenTracker.PEAK_CONCURRENCY.updateAndGet(peak -> Math.max(peak, active));
    }

    @Inject(
            method = "createStructures(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;)V",
            at = @At("RETURN")
    )
    private void rtf$structureEnd(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                  StructureManager structureManager, ChunkAccess chunk,
                                  StructureTemplateManager templateManager, CallbackInfo ci) {
        long start = rtf$structureStart.get();
        if (start != 0) {
            WorldGenTracker.TOTAL_NANOS.add(System.nanoTime() - start);
        }
        WorldGenTracker.ACTIVE_THREADS.decrementAndGet();
    }

    // 2. Track Feature Placement (Trees, Ores, Plants, Biome Decorators)
    @Inject(
            method = "applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;)V",
            at = @At("HEAD")
    )
    private void rtf$featureStart(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager, CallbackInfo ci) {
        rtf$featureStart.set(System.nanoTime());
        int active = WorldGenTracker.ACTIVE_THREADS.incrementAndGet();
        WorldGenTracker.PEAK_CONCURRENCY.updateAndGet(peak -> Math.max(peak, active));
    }

    @Inject(
            method = "applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;)V",
            at = @At("RETURN")
    )
    private void rtf$featureEnd(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager, CallbackInfo ci) {
        long start = rtf$featureStart.get();
        long now = System.nanoTime();
        if (start != 0) {
            WorldGenTracker.TOTAL_NANOS.add(now - start);
        }
        WorldGenTracker.LAST_END_NANOS.set(now);
        WorldGenTracker.ACTIVE_THREADS.decrementAndGet();
    }
}