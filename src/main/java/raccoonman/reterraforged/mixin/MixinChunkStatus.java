package raccoonman.reterraforged.mixin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.datafixers.util.Either;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.WorldGenFlags;

@Mixin(ChunkStatus.class)
public class MixinChunkStatus {

	/**
	 * Target: Structure Starts generation task.
	 * In 1.21.1 Mojang mappings, this corresponds to the generation task for 'STRUCTURE_STARTS'.
	 * Note: 'remap = true' is preferred now that you are on NeoForge/Mojang mappings.
	 */
	@Inject(
			at = @At("HEAD"),
			method = "generateStructureStarts", // Updated to Mojang name
			remap = true
	)
	private static void generateStructureStarts$HEAD(ChunkStatus status, Executor executor, ServerLevel level, ChunkGenerator generator, StructureTemplateManager templateManager, ThreadedLevelLightEngine lightEngine, Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkResult.Fail>>> chunkLookup, List<ChunkAccess> regionChunks, ChunkAccess centerChunk, CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> callback) {
		RandomState randomState = level.getChunkSource().randomState();
		if((Object) randomState instanceof RTFRandomState rtfRandomState) {
			ChunkPos chunkPos = centerChunk.getPos();
			@Nullable
			GeneratorContext context = rtfRandomState.generatorContext();

			if(context != null) {
				context.cache.queueAtChunk(chunkPos.x, chunkPos.z);
				WorldGenFlags.setFastCellLookups(false);
			}
		}
	}

	@Inject(
			at = @At("TAIL"),
			method = "generateStructureStarts", // Updated to Mojang name
			remap = true
	)
	private static void generateStructureStarts$TAIL(ChunkStatus status, Executor executor, ServerLevel level, ChunkGenerator generator, StructureTemplateManager templateManager, ThreadedLevelLightEngine lightEngine, Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkResult.Fail>>> chunkLookup, List<ChunkAccess> regionChunks, ChunkAccess centerChunk, CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> callback) {
		RandomState randomState = level.getChunkSource().randomState();
		if((Object) randomState instanceof RTFRandomState rtfRandomState) {
			@Nullable
			GeneratorContext context = rtfRandomState.generatorContext();
			if(context != null) {
				WorldGenFlags.setFastCellLookups(true);
			}
		}
	}

	/**
	 * Target: Feature generation task.
	 * Previously method_51375.
	 */
	@Inject(
			at = @At("TAIL"),
			method = "applyBiomeDecoration", // Updated to Mojang name
			remap = true
	)
	private static void applyBiomeDecoration(ChunkStatus status, ServerLevel level, ChunkGenerator generator, List<ChunkAccess> chunks, ChunkAccess centerChunk, CallbackInfo callback) {
		RandomState randomState = level.getChunkSource().randomState();
		if((Object) randomState instanceof RTFRandomState rtfRandomState) {
			ChunkPos chunkPos = centerChunk.getPos();
			@Nullable
			GeneratorContext context = rtfRandomState.generatorContext();

			if(context != null) {
				context.cache.dropAtChunk(chunkPos.x, chunkPos.z);
			}
		}
	}
}