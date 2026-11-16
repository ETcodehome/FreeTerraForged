package etcodehome.freeterraforged.mixin;

import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.biome.BiomeColumnCache;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class MixinNoiseBasedChunkGenerator extends ChunkGenerator {
	public MixinNoiseBasedChunkGenerator(BiomeSource biomeSource, Function<Holder<Biome>, BiomeGenerationSettings> settingsGetter) {
		super(biomeSource, settingsGetter);
	}

	@WrapMethod(method = "doCreateBiomes")
	private void freeterraforged$wrapDoCreateBiomes(
		Blender blender,
		RandomState randomState,
		StructureManager structureManager,
		ChunkAccess chunk,
		Operation<Void> original
	) {
		boolean perfActive = WorldgenPerfTracker.isActive() && ((Object) this instanceof TerraForgedChunkGenerator);
		long t0 = perfActive ? System.nanoTime() : 0L;
		try {
			original.call(blender, randomState, structureManager, chunk);
		} finally {
			if (perfActive) {
				WorldgenPerfTracker.recordStage(
					chunk.getPos().x,
					chunk.getPos().z,
					WorldgenPerfTracker.Stage.BIOMES,
					System.nanoTime() - t0
				);
			}
		}
	}

	@WrapMethod(method = "doFill")
	private ChunkAccess freeterraforged$wrapDoFill(
		Blender blender,
		StructureManager structureManager,
		RandomState randomState,
		ChunkAccess chunk,
		int minCellY,
		int cellCountY,
		Operation<ChunkAccess> original
	) {
		boolean perfActive = WorldgenPerfTracker.isActive() && ((Object) this instanceof TerraForgedChunkGenerator);
		long t0 = perfActive ? System.nanoTime() : 0L;
		try {
			int effectiveCellCountY = cellCountY;
			if ((Object) randomState instanceof FTFRandomState ftfRandomState) {
				GeneratorContext genCtx = ftfRandomState.generatorContext();
				if (genCtx != null && chunk instanceof IChunkCellsHolder cellsHolder) {
					Cell[] cells = cellsHolder.freeterraforged$getChunkCells();
					if (cells != null && cells.length > 0) {
						float maxHeight = cells[0].height;
						for (int i = 1; i < cells.length; i++) {
							if (cells[i].height > maxHeight) {
								maxHeight = cells[i].height;
							}
						}
						int maxTerrainY = genCtx.levels.scale(maxHeight);
						int seaLevel = ((NoiseBasedChunkGenerator) (Object) this).getSeaLevel();
						int targetMaxY = Math.max(maxTerrainY, seaLevel) + 16;
						int cellHeight = ((NoiseBasedChunkGenerator) (Object) this).generatorSettings().value().noiseSettings().getCellHeight();
						int minY = minCellY * cellHeight;
						int neededCells = Math.floorDiv(targetMaxY - minY + cellHeight - 1, cellHeight);
						effectiveCellCountY = Math.min(cellCountY, Math.max(1, neededCells));
						if (perfActive) {
							WorldgenPerfTracker.ThreadStats ts = WorldgenPerfTracker.stats();
							ts.doFillCellsTotal += cellCountY;
							ts.doFillCellsSkipped += (cellCountY - effectiveCellCountY);
						}
					}
				}
			}
			return original.call(blender, structureManager, randomState, chunk, minCellY, effectiveCellCountY);
		} finally {
			if (perfActive) {
				WorldgenPerfTracker.recordStage(
					chunk.getPos().x,
					chunk.getPos().z,
					WorldgenPerfTracker.Stage.NOISE,
					System.nanoTime() - t0
				);
			}
		}
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
		if (!((Object) this instanceof TerraForgedChunkGenerator) || !((Object) randomState instanceof FTFRandomState rtfRandomState)) {
			return;
		}
		GeneratorContext generatorContext = rtfRandomState.generatorContext();
		if (generatorContext == null) {
			return;
		}
		this.freeterraforged$bakeFlowField(chunk, generatorContext);
	}

	@Inject(
		method = "createNoiseChunk",
		at = @At("RETURN")
	)
	private void freeterraforged$bindChunkCells(ChunkAccess chunk, StructureManager sm, Blender blender, RandomState rs, CallbackInfoReturnable<NoiseChunk> cir) {
		if ((Object) rs instanceof FTFRandomState rtfRs && rtfRs.generatorContext() != null) {
			if (cir.getReturnValue() instanceof IChunkCellsHolder noiseChunkHolder && chunk instanceof IChunkCellsHolder chunkHolder) {
				Cell[] cells = noiseChunkHolder.freeterraforged$getChunkCells();
				if (cells != null) {
					chunkHolder.freeterraforged$setChunkCells(cells);
				} else if (chunkHolder.freeterraforged$getChunkCells() != null) {
					noiseChunkHolder.freeterraforged$setChunkCells(chunkHolder.freeterraforged$getChunkCells());
				}
			}
		}
	}

	private void freeterraforged$bakeFlowField(ChunkAccess chunk, GeneratorContext generatorContext) {
		IFlowFieldHolder holder = (IFlowFieldHolder) chunk;
		ChunkFlowField flowField = holder.freeterraforged$getFlowField();
		IChunkCellsHolder cellsHolder = chunk instanceof IChunkCellsHolder ch ? ch : null;
		Cell[] cells = cellsHolder != null ? cellsHolder.freeterraforged$getOrCreateChunkCells(generatorContext) : null;
		for(int z = 0; z < 16; z++) {
			for(int x = 0; x < 16; x++) {
				Cell cell = cells != null
					? cells[z * 16 + x]
					: generatorContext.lookup.lookup(chunk.getPos().getBlockX(x), chunk.getPos().getBlockZ(z));
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
		if ((Object) this instanceof TerraForgedChunkGenerator generator
			&& (Object) randomState instanceof FTFRandomState rtfRandomState
			&& rtfRandomState.generatorContext() != null) {
			GeneratorContext generatorContext = rtfRandomState.generatorContext();
			final int chunkQuartX = chunk.getPos().x << 2;
			final int chunkQuartZ = chunk.getPos().z << 2;
			final long[] internalRegionX = new long[16];
			final long[] internalRegionZ = new long[16];
			final boolean hasInternalCells;
			if (chunk instanceof IChunkCellsHolder holder) {
				Cell[] chunkCells = holder.freeterraforged$getOrCreateChunkCells(generatorContext);
				if (chunkCells != null) {
					for (int qz = 0; qz < 4; qz++) {
						for (int qx = 0; qx < 4; qx++) {
							Cell cell = chunkCells[(qz * 4) * 16 + (qx * 4)];
							int idx = qz * 4 + qx;
							internalRegionX[idx] = cell.biomeRegionX;
							internalRegionZ[idx] = cell.biomeRegionZ;
						}
					}
					hasInternalCells = true;
				} else {
					hasInternalCells = false;
				}
			} else {
				hasInternalCells = false;
			}

			return blender.getBiomeResolver((quartX, quartY, quartZ, sampler) -> {
				int localQx = quartX - chunkQuartX;
				int localQz = quartZ - chunkQuartZ;
				long regionX;
				long regionZ;
				if (hasInternalCells && localQx >= 0 && localQx < 4 && localQz >= 0 && localQz < 4) {
					int idx = localQz * 4 + localQx;
					regionX = internalRegionX[idx];
					regionZ = internalRegionZ[idx];
				} else {
					BiomeColumnCache colCache = BiomeColumnCache.get();
					if (colCache.quartX == quartX && colCache.quartZ == quartZ) {
						regionX = colCache.regionX;
						regionZ = colCache.regionZ;
					} else {
						int blockX = QuartPos.toBlock(quartX);
						int blockZ = QuartPos.toBlock(quartZ);
						generatorContext.lookup.applyBiomeRegion(colCache.scratch.reset(), blockX, blockZ);
						colCache.quartX = quartX;
						colCache.quartZ = quartZ;
						colCache.regionX = colCache.scratch.biomeRegionX;
						colCache.regionZ = colCache.scratch.biomeRegionZ;
						regionX = colCache.regionX;
						regionZ = colCache.regionZ;
					}
				}
				if (!generator.isUnifiedBiomeSource(source)) {
					BiomeColumnCache colCache = BiomeColumnCache.get();
					colCache.quartX = quartX;
					colCache.quartZ = quartZ;
					colCache.regionX = regionX;
					colCache.regionZ = regionZ;
					return source.getNoiseBiome(quartX, quartY, quartZ, sampler);
				}
				return generator.resolveBiomeInCell(
					quartX, quartY, quartZ, sampler, regionX, regionZ
				);
			});
		}
		return blender.getBiomeResolver(source);
	}
}
