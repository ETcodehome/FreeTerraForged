package etcodehome.freeterraforged.mixin;

import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
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
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class MixinNoiseBasedChunkGenerator extends ChunkGenerator {
	public MixinNoiseBasedChunkGenerator(BiomeSource biomeSource, Function<Holder<Biome>, BiomeGenerationSettings> settingsGetter) {
		super(biomeSource, settingsGetter);
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
				}
			}
		}
	}

	private void freeterraforged$bakeFlowField(ChunkAccess chunk, GeneratorContext generatorContext) {
		IFlowFieldHolder holder = (IFlowFieldHolder) chunk;
		ChunkFlowField flowField = holder.freeterraforged$getFlowField();
		IChunkCellsHolder cellsHolder = chunk instanceof IChunkCellsHolder ch ? ch : null;
		for(int x = 0; x < 16; x++) {
			for(int z = 0; z < 16; z++) {
				Cell cell = cellsHolder != null
					? cellsHolder.freeterraforged$getCell(x, z, generatorContext)
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
			ThreadLocal<Cell> scratch = ThreadLocal.withInitial(Cell::new);
			return blender.getBiomeResolver((quartX, quartY, quartZ, sampler) -> {
				int blockX = QuartPos.toBlock(quartX);
				int blockZ = QuartPos.toBlock(quartZ);
				Cell cell = scratch.get();
				generatorContext.lookup.applyBiomeRegion(cell.reset(), blockX, blockZ);
				return generator.resolveBiomeInCell(
					quartX, quartY, quartZ, sampler, cell.biomeRegionX, cell.biomeRegionZ
				);
			});
		}
		return blender.getBiomeResolver(source);
	}
}
