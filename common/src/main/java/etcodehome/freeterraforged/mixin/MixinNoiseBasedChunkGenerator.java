package etcodehome.freeterraforged.mixin;

import java.util.function.Function;
import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.NoiseChunkHolder;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.NoiseChunkTileOwner;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class MixinNoiseBasedChunkGenerator extends ChunkGenerator {
	public MixinNoiseBasedChunkGenerator(BiomeSource biomeSource, Function<Holder<Biome>, BiomeGenerationSettings> settingsGetter) {
		super(biomeSource, settingsGetter);
	}

	@WrapMethod(method = "createBiomes")
	private CompletableFuture<ChunkAccess> freeterraforged$ownCreateBiomesTile(
		RandomState randomState,
		Blender blender,
		StructureManager structureManager,
		ChunkAccess chunk,
		Operation<CompletableFuture<ChunkAccess>> original
	) {
		if (!this.freeterraforged$ownsTileStages()) {
			return original.call(randomState, blender, structureManager, chunk);
		}
		beginTileStage(chunk);
		try {
			CompletableFuture<ChunkAccess> result = original.call(
				randomState, blender, structureManager, chunk
			);
			return result.whenComplete((ignored, failure) -> endTileStage(chunk));
		} catch (RuntimeException | Error failure) {
			endTileStageAfterFailure(chunk, failure);
			throw failure;
		}
	}

	@WrapMethod(method = "fillFromNoise")
	private CompletableFuture<ChunkAccess> freeterraforged$ownNoiseTile(
		Blender blender,
		RandomState randomState,
		StructureManager structureManager,
		ChunkAccess chunk,
		Operation<CompletableFuture<ChunkAccess>> original
	) {
		if (!this.freeterraforged$ownsTileStages()) {
			return original.call(blender, randomState, structureManager, chunk);
		}
		beginTileStage(chunk);
		try {
			CompletableFuture<ChunkAccess> result = original.call(
				blender, randomState, structureManager, chunk
			);
			return result.whenComplete((ignored, failure) -> endTileStage(chunk));
		} catch (RuntimeException | Error failure) {
			endTileStageAfterFailure(chunk, failure);
			throw failure;
		}
	}

	@WrapMethod(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V")
	private void freeterraforged$ownSurfaceTile(
		WorldGenRegion region,
		StructureManager structureManager,
		RandomState randomState,
		ChunkAccess chunk,
		Operation<Void> original
	) {
		if (!this.freeterraforged$ownsTileStages()) {
			original.call(region, structureManager, randomState, chunk);
			return;
		}
		beginTileStage(chunk);
		try {
			original.call(region, structureManager, randomState, chunk);
		} catch (RuntimeException | Error failure) {
			endTileStageAfterFailure(chunk, failure);
			throw failure;
		}
		endTileStage(chunk);
	}

	@WrapMethod(method = "applyCarvers")
	private void freeterraforged$ownCarverTile(
		WorldGenRegion region,
		long seed,
		RandomState randomState,
		BiomeManager biomeManager,
		StructureManager structureManager,
		ChunkAccess chunk,
		GenerationStep.Carving step,
		Operation<Void> original
	) {
		if (!this.freeterraforged$ownsTileStages()) {
			original.call(region, seed, randomState, biomeManager, structureManager, chunk, step);
			return;
		}
		beginTileStage(chunk);
		try {
			original.call(region, seed, randomState, biomeManager, structureManager, chunk, step);
		} catch (RuntimeException | Error failure) {
			endTileStageAfterFailure(chunk, failure);
			throw failure;
		}
		endTileStage(chunk);
	}

	private static void beginTileStage(ChunkAccess chunk) {
		((NoiseChunkHolder) chunk).freeterraforged$beginNoiseChunkTileStage();
	}

	private static void endTileStage(ChunkAccess chunk) {
		((NoiseChunkHolder) chunk).freeterraforged$endNoiseChunkTileStage();
	}

	private static void endTileStageAfterFailure(ChunkAccess chunk, Throwable failure) {
		try {
			endTileStage(chunk);
		} catch (RuntimeException | Error cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

	private boolean freeterraforged$ownsTileStages() {
		return (Object) this instanceof TerraForgedChunkGenerator;
	}

	@Inject(
			method = "doCreateBiomes",
			at = @At(
				value = "INVOKE",
				target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getOrCreateNoiseChunk(Ljava/util/function/Function;)Lnet/minecraft/world/level/levelgen/NoiseChunk;",
				shift = At.Shift.AFTER
			)
	)
	public void doCreateBiomes(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk, CallbackInfo callback) {
		if (!this.freeterraforged$ownsTileStages() || !((Object) randomState instanceof FTFRandomState rtfRandomState)) {
			return;
		}
		GeneratorContext generatorContext = rtfRandomState.generatorContext();

		if(generatorContext == null) {
			return;
		}

		Tile.Chunk tileChunk = currentTileChunk(chunk);
		if (tileChunk == null) {
			throw new IllegalStateException("FTF biome stage has no request-owned terrain tile");
		}
		this.freeterraforged$bakeFlowField(chunk, tileChunk);
	}

	private void freeterraforged$bakeFlowField(ChunkAccess chunk, Tile.Chunk tileChunk) {
		IFlowFieldHolder holder = (IFlowFieldHolder) chunk;
		ChunkFlowField flowField = holder.freeterraforged$getFlowField();
		for(int x = 0; x < 16; x++) {
			for(int z = 0; z < 16; z++) {
				Cell cell = tileChunk.getCell(x, z);
				if (cell.hasFlow) {
					if (flowField == null) {
						flowField = holder.freeterraforged$getOrCreateFlowField();
					}
					flowField.setFlow(x, z, cell.flowAngle);
				}
			}
		}
	}

	@Redirect(
		method = "doCreateBiomes",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/blending/Blender;getBiomeResolver(Lnet/minecraft/world/level/biome/BiomeResolver;)Lnet/minecraft/world/level/biome/BiomeResolver;"
		)
	)
	private BiomeResolver freeterraforged$useGeneratorRootBiomeResolver(
		Blender blender,
		BiomeResolver source,
		Blender methodBlender,
		RandomState randomState,
		StructureManager structureManager,
		ChunkAccess chunk
	) {
		if ((Object) this instanceof TerraForgedChunkGenerator generator) {
			Tile.Chunk tileChunk = currentTileChunk(chunk);
			if (tileChunk == null) {
				throw new IllegalStateException("FTF biome stage has no request-owned terrain tile");
			}
			return blender.getBiomeResolver((quartX, quartY, quartZ, sampler) -> {
				int blockX = QuartPos.toBlock(quartX);
				int blockZ = QuartPos.toBlock(quartZ);
				if (blockX >> 4 != tileChunk.getChunkX() || blockZ >> 4 != tileChunk.getChunkZ()) {
					return generator.resolveBiome(quartX, quartY, quartZ, sampler);
				}
				Cell cell = tileChunk.getCell(blockX, blockZ);
				return generator.resolveBiomeInCell(
					quartX, quartY, quartZ, sampler, cell.biomeRegionX, cell.biomeRegionZ
				);
			});
		}
		return blender.getBiomeResolver(source);
	}

	private static Tile.Chunk currentTileChunk(ChunkAccess chunk) {
		if (chunk instanceof NoiseChunkHolder holder
			&& holder.freeterraforged$getNoiseChunk() instanceof NoiseChunkTileOwner owner) {
			return owner.freeterraforged$currentTileChunk();
		}
		return null;
	}

}
