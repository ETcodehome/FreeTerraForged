package etcodehome.freeterraforged.mixin;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder;
import etcodehome.freeterraforged.world.worldgen.biome.FTFClimateSampler;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans;

@Mixin(NoiseChunk.class)
class MixinNoiseChunk implements IChunkCellsHolder {
	private RandomState randomState;
	private int chunkX, chunkZ;
	@Nullable
	private Aquifer.FluidPicker freeterraforged$fluidPicker;
	private CellSampler.Cache2d cache2d;
	@Nullable
	private Cell[] freeterraforged$chunkCells;

	@Inject(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;",
			shift = At.Shift.BEFORE
		)
	)
	private void freeterraforged$initializeNoiseChunkOwner(
		int cellCountXZ,
		RandomState randomState,
		int minBlockX,
		int minBlockZ,
		NoiseSettings noiseSettings,
		DensityFunctions.BeardifierOrMarker beardifierOrMarker,
		NoiseGeneratorSettings generatorSettings,
		Aquifer.FluidPicker fluidPicker,
		net.minecraft.world.level.levelgen.blending.Blender blender,
		CallbackInfo callback
	) {
		this.randomState = randomState;
		this.chunkX = SectionPos.blockToSectionCoord(minBlockX);
		this.chunkZ = SectionPos.blockToSectionCoord(minBlockZ);
		if ((Object) randomState instanceof FTFRandomState rtfRandomState) {
			Preset preset = rtfRandomState.preset();
			GeneratorContext generatorContext = rtfRandomState.generatorContext();
			if (preset != null && generatorContext != null) {
				this.freeterraforged$fluidPicker = this.freeterraforged$createFluidPicker(
					preset, generatorContext, generatorSettings
				);
				if (cellCountXZ > 1) {
					this.cache2d = new CellSampler.Cache2d();
					this.freeterraforged$chunkCells = ChunkFilterGrid.generateChunkCells(this.chunkX, this.chunkZ, generatorContext);
				}
			}
		}
	}

	@ModifyArg(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/Aquifer;createDisabled(Lnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)Lnet/minecraft/world/level/levelgen/Aquifer;"
		),
		index = 0
	)
	private Aquifer.FluidPicker freeterraforged$modifyDisabledFluidPicker(Aquifer.FluidPicker fluidPicker) {
		return this.freeterraforged$fluidPicker == null ? fluidPicker : this.freeterraforged$fluidPicker;
	}

	@ModifyArg(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/Aquifer;create(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/NoiseRouter;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;IILnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)Lnet/minecraft/world/level/levelgen/Aquifer;"
		),
		index = 6
	)
	private Aquifer.FluidPicker freeterraforged$modifyFluidPicker(Aquifer.FluidPicker fluidPicker) {
		return this.freeterraforged$fluidPicker == null ? fluidPicker : this.freeterraforged$fluidPicker;
	}

	@Unique
	private Aquifer.FluidPicker freeterraforged$createFluidPicker(
		Preset preset,
		GeneratorContext generatorContext,
		NoiseGeneratorSettings noiseGeneratorSettings
	) {
		int globalLavaLevel = preset.world().properties.lavaLevel;
		int seaLevel = noiseGeneratorSettings.seaLevel();
		int oceanDepth = preset.world().properties.oceanDepth;
		Aquifer.FluidStatus lava = new Aquifer.FluidStatus(globalLavaLevel, Blocks.LAVA.defaultBlockState());
		Aquifer.FluidStatus defaultFluid = new Aquifer.FluidStatus(seaLevel, noiseGeneratorSettings.defaultFluid());
		int oceanLavaLevel = seaLevel - oceanDepth - 5;
		if (globalLavaLevel <= oceanLavaLevel) {
			return (x, y, z) -> y < Math.min(globalLavaLevel, seaLevel) ? lava : defaultFluid;
		}

		WorldSettings.ControlPoints controlPoints = preset.world().controlPoints;
		float shallowOceanCP = controlPoints.shallowOcean;
		float coastCP = controlPoints.coast;
		float transitionRange = coastCP - shallowOceanCP;
		Aquifer.FluidStatus oceanLava = new Aquifer.FluidStatus(oceanLavaLevel, Blocks.LAVA.defaultBlockState());
		int[] columnCache = { Integer.MIN_VALUE, Integer.MIN_VALUE, globalLavaLevel };
		Aquifer.FluidStatus[] cachedLavaStatus = { lava };
		Cell scratchCell = new Cell();

		return (x, y, z) -> {
			int effectiveLava;
			Aquifer.FluidStatus effectiveLavaStatus;
			if (x == columnCache[0] && z == columnCache[1]) {
				effectiveLava = columnCache[2];
				effectiveLavaStatus = cachedLavaStatus[0];
			} else {
				effectiveLava = globalLavaLevel;
				effectiveLavaStatus = lava;
				generatorContext.lookup.applyCell(scratchCell.reset(), x, z, false, false);
				float continentEdge = scratchCell.continentEdge;

				if (continentEdge <= shallowOceanCP) {
					effectiveLava = oceanLavaLevel;
					effectiveLavaStatus = oceanLava;
				} else if (continentEdge < coastCP && transitionRange > 0) {
					float t = (continentEdge - shallowOceanCP) / transitionRange;
					effectiveLava = (int) Mth.lerp(t, oceanLavaLevel, globalLavaLevel);
					effectiveLavaStatus = new Aquifer.FluidStatus(effectiveLava, Blocks.LAVA.defaultBlockState());
				}
				columnCache[0] = x;
				columnCache[1] = z;
				columnCache[2] = effectiveLava;
				cachedLavaStatus[0] = effectiveLavaStatus;
			}

			return y < Math.min(effectiveLava, seaLevel) ? effectiveLavaStatus : defaultFluid;
		};
	}

	@Inject(
		at = @At("RETURN"),
		method = "cachedClimateSampler"
	)
	private void freeterraforged$bindWorldgenPlan(
		NoiseRouter noiseRouter,
		List<Climate.ParameterPoint> spawnTarget,
		CallbackInfoReturnable<Climate.Sampler> callback
	) {
		if ((Object) this.randomState instanceof FTFRandomState randomState
			&& randomState.preset() != null
			&& randomState.generatorContext() != null
			&& randomState.plan() != null
			&& randomState.binding() != null
			&& (Object) callback.getReturnValue() instanceof FTFClimateSampler sampler) {
			WorldgenPlan plan = randomState.plan();
			plan.samplerDecoration().initialize(
				plan,
				new WorldgenPlans.SamplerInputs(randomState.preset(), randomState.generatorContext()),
				callback.getReturnValue()
			);
			sampler.setWorldgenBinding(randomState.binding());
		}
	}

	@Inject(
		at = @At("HEAD"),
		method = "wrapNew",
		cancellable = true
	)
	private void wrapNew(DensityFunction function, CallbackInfoReturnable<DensityFunction> callback) {
		if((Object) this.randomState instanceof FTFRandomState randomState && function instanceof CellSampler mapped) {
			callback.setReturnValue(mapped.new CacheChunk(this.freeterraforged$chunkCells, this.chunkX << 4, this.chunkZ << 4, this.cache2d));
		}
	}

	@Override
	@Nullable
	public Cell[] freeterraforged$getChunkCells() {
		return this.freeterraforged$chunkCells;
	}

	@Override
	public void freeterraforged$setChunkCells(Cell[] cells) {
		this.freeterraforged$chunkCells = cells;
	}

	@Override
	public Cell[] freeterraforged$getOrCreateChunkCells(GeneratorContext context) {
		if (this.freeterraforged$chunkCells == null) {
			this.freeterraforged$chunkCells = ChunkFilterGrid.generateChunkCells(this.chunkX, this.chunkZ, context);
		}
		return this.freeterraforged$chunkCells;
	}

	@Override
	public Cell freeterraforged$getCell(int localX, int localZ, GeneratorContext context) {
		Cell[] cells = this.freeterraforged$getOrCreateChunkCells(context);
		return cells[(localZ & 15) * 16 + (localX & 15)];
	}
}
