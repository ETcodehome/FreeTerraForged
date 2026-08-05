package raccoonman.reterraforged.mixin;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
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
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.ActiveChunk;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.MaxHeightUtil;
import raccoonman.reterraforged.world.worldgen.RTFChunk;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache;

@Mixin(NoiseChunk.class)
class MixinNoiseChunk {
	private RandomState randomState;
	private int chunkX, chunkZ;
	@Nullable
	private Tile.Chunk chunk;
	private CellSampler.Cache2d cache2d;
	
	@Shadow
	@Mutable
	private int cellCountY;
	@Shadow
    @Final
    private int cellHeight;
	@Shadow
    @Final
	int firstNoiseX;
	@Shadow
    @Final
    int firstNoiseZ;
	@Shadow
    @Final
	private int cellCountXZ;
	
	@Redirect(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"
		)
	)
	private NoiseRouter init(NoiseRouter noiseRouter, DensityFunction.Visitor visitor, int cellCountXZ, RandomState randomState, int minBlockX, int minBlockZ, NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifierOrMarker, NoiseGeneratorSettings generatorSettings) {
		this.randomState = randomState;
		this.chunkX = SectionPos.blockToSectionCoord(minBlockX);
		this.chunkZ = SectionPos.blockToSectionCoord(minBlockZ);
		GeneratorContext generatorContext;
		if((Object) randomState instanceof RTFRandomState rtfRandomState && cellCountXZ > 1 && (generatorContext = rtfRandomState.generatorContext()) != null) {
			this.chunk = generatorContext.cache.provideAtChunk(this.chunkX, this.chunkZ).getChunkReader(this.chunkX, this.chunkZ);

			RTFChunk rtfChunk = (RTFChunk) ActiveChunk.get();
			int maxHeight = Math.min(noiseSettings.height(), MaxHeightUtil.getMaxHeight(this.chunkX, this.chunkZ, rtfChunk.getMaxHeight().orElseGet(noiseSettings::height), generatorSettings, noiseSettings, beardifierOrMarker));
			this.cellCountY = Math.min(this.cellCountY, maxHeight / this.cellHeight);
		}
		this.cache2d = new CellSampler.Cache2d();
		return randomState.router();
	}

	@ModifyVariable(
		method = "<init>",
		at = @At("HEAD"),
		name = "fluidPicker",
		index = 7,
		ordinal = 0,
		argsOnly = true
	)
	private static Aquifer.FluidPicker modifyFluidPicker(Aquifer.FluidPicker fluidPicker, int i, RandomState randomState, int j, int k, NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifierOrMarker, NoiseGeneratorSettings noiseGeneratorSettings) {
		if((Object) randomState instanceof RTFRandomState rtfRandomState) {
			@Nullable
			Preset preset = rtfRandomState.preset();
			GeneratorContext generatorContext;
			if(preset != null && (generatorContext = rtfRandomState.generatorContext()) != null) {
				int globalLavaLevel = preset.world().properties.lavaLevel;
				int seaLevel = noiseGeneratorSettings.seaLevel();
				int oceanDepth = preset.world().properties.oceanDepth;
				Aquifer.FluidStatus lava = new Aquifer.FluidStatus(globalLavaLevel, Blocks.LAVA.defaultBlockState());
				Aquifer.FluidStatus defaultFluid = new Aquifer.FluidStatus(seaLevel, noiseGeneratorSettings.defaultFluid());

				int oceanLavaLevel = Math.min(globalLavaLevel, seaLevel - oceanDepth - 5);

				if (oceanLavaLevel == globalLavaLevel || globalLavaLevel >= seaLevel) {
					return (x, y, z) -> {
						if (y < Math.min(globalLavaLevel, seaLevel)) {
							return lava;
						}
						return defaultFluid;
					};
				}

				WorldSettings.ControlPoints controlPoints = preset.world().controlPoints;
				float shallowOceanCP = controlPoints.shallowOcean;
				float coastCP = controlPoints.coast;
				float transitionRange = coastCP - shallowOceanCP;
				TileCache cache = generatorContext.cache;
				Aquifer.FluidStatus oceanLava = new Aquifer.FluidStatus(oceanLavaLevel, Blocks.LAVA.defaultBlockState());
				int[] columnCache = { Integer.MIN_VALUE, Integer.MIN_VALUE, globalLavaLevel };
				Aquifer.FluidStatus[] cachedLavaStatus = { lava };

				return (x, y, z) -> {
					int effectiveLava;
					Aquifer.FluidStatus effectiveLavaStatus;
					if (x == columnCache[0] && z == columnCache[1]) {
						effectiveLava = columnCache[2];
						effectiveLavaStatus = cachedLavaStatus[0];
					} else {
						effectiveLava = globalLavaLevel;
						effectiveLavaStatus = lava;
						if (cache != null) {
							try {
								int cx = SectionPos.blockToSectionCoord(x);
								int cz = SectionPos.blockToSectionCoord(z);
								Tile tile = cache.provideAtChunk(cx, cz);
								Tile.Chunk tileChunk = tile.getChunkReader(cx, cz);
								Cell cell = tileChunk.getCell(x, z);
								float continentEdge = cell.continentEdge;

								if (continentEdge <= shallowOceanCP) {
									effectiveLava = oceanLavaLevel;
									effectiveLavaStatus = oceanLava;
								} else if (continentEdge < coastCP && transitionRange > 0) {
									float t = (continentEdge - shallowOceanCP) / transitionRange;
									effectiveLava = (int) Mth.lerp(t, oceanLavaLevel, globalLavaLevel);
									effectiveLavaStatus = new Aquifer.FluidStatus(effectiveLava, Blocks.LAVA.defaultBlockState());
								}
							} catch (Exception e) {
								// Preserve the global lava level when terrain lookup fails.
							}
						}
						columnCache[0] = x;
						columnCache[1] = z;
						columnCache[2] = effectiveLava;
						cachedLavaStatus[0] = effectiveLavaStatus;
					}

					if (y < Math.min(effectiveLava, seaLevel)) {
						return effectiveLavaStatus;
					}
					return defaultFluid;
				};
			}
		}
		return fluidPicker;
	}

	@Inject(
		at = @At("RETURN"),
		method = "cachedClimateSampler"
	)
	private void reterraforged$configureUndergroundBiomeBanding(
		NoiseRouter noiseRouter,
		List<Climate.ParameterPoint> spawnTarget,
		CallbackInfoReturnable<Climate.Sampler> callback
	) {
		if ((Object) this.randomState instanceof RTFRandomState randomState
			&& randomState.preset() != null
			&& (Object) callback.getReturnValue() instanceof RTFClimateSampler sampler) {
			sampler.setUndergroundBiomeBandingPreset(randomState.preset());
		}
	}

	@Inject(
		at = @At("HEAD"),
		method = "wrapNew",
		cancellable = true
	)
	private void wrapNew(DensityFunction function, CallbackInfoReturnable<DensityFunction> callback) {
		if((Object) this.randomState instanceof RTFRandomState randomState && function instanceof CellSampler mapped) {
			callback.setReturnValue(mapped.new CacheChunk(this.chunk, this.cache2d, this.chunkX, this.chunkZ));
		}
	}
}
