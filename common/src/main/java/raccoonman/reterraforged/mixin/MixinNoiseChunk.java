package raccoonman.reterraforged.mixin;

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
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.NoiseChunkTileOwner;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlan;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlans;

@Mixin(NoiseChunk.class)
class MixinNoiseChunk implements NoiseChunkTileOwner {
	private RandomState randomState;
	private int chunkX, chunkZ;
	@Nullable
	private TileCache tileCache;
	@Nullable
	private Aquifer.FluidPicker reterraforged$fluidPicker;
	@Nullable
	private TileCache.Lease tileLease;
	@Nullable
	private volatile Tile tile;
	@Nullable
	private volatile Tile.Chunk chunk;
	private int tileStageDepth;
	private CellSampler.Cache2d cache2d;
	
	@Inject(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;",
			shift = At.Shift.BEFORE
		)
	)
	private void reterraforged$initializeNoiseChunkOwner(
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
		if ((Object) randomState instanceof RTFRandomState rtfRandomState) {
			Preset preset = rtfRandomState.preset();
			GeneratorContext generatorContext = rtfRandomState.generatorContext();
			if (preset != null && generatorContext != null) {
				this.reterraforged$fluidPicker = this.reterraforged$createFluidPicker(
					preset, generatorContext, generatorSettings
				);
				if (cellCountXZ > 1) {
					this.tileCache = generatorContext.cache;
					this.cache2d = new CellSampler.Cache2d();
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
	private Aquifer.FluidPicker reterraforged$modifyDisabledFluidPicker(Aquifer.FluidPicker fluidPicker) {
		return this.reterraforged$fluidPicker == null ? fluidPicker : this.reterraforged$fluidPicker;
	}

	@ModifyArg(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/Aquifer;create(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/NoiseRouter;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;IILnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)Lnet/minecraft/world/level/levelgen/Aquifer;"
		),
		index = 6
	)
	private Aquifer.FluidPicker reterraforged$modifyFluidPicker(Aquifer.FluidPicker fluidPicker) {
		return this.reterraforged$fluidPicker == null ? fluidPicker : this.reterraforged$fluidPicker;
	}

	@Unique
	private Aquifer.FluidPicker reterraforged$createFluidPicker(
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
				int cx = SectionPos.blockToSectionCoord(x);
				int cz = SectionPos.blockToSectionCoord(z);
				float continentEdge;
				Tile ownedTile = this.reterraforged$currentTile();
				if (ownedTile != null) {
					continentEdge = ownedTile.getChunkReader(cx, cz).getCell(x, z).continentEdge;
				} else {
					try (TileCache.Lease lease = cache.acquireAtChunk(cx, cz)) {
						continentEdge = lease.tile().getChunkReader(cx, cz).getCell(x, z).continentEdge;
					}
				}

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
	private void reterraforged$bindWorldgenPlan(
		NoiseRouter noiseRouter,
		List<Climate.ParameterPoint> spawnTarget,
		CallbackInfoReturnable<Climate.Sampler> callback
	) {
		if ((Object) this.randomState instanceof RTFRandomState randomState
			&& randomState.preset() != null
			&& randomState.generatorContext() != null
			&& randomState.plan() != null
			&& randomState.binding() != null
			&& (Object) callback.getReturnValue() instanceof RTFClimateSampler sampler) {
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
		if((Object) this.randomState instanceof RTFRandomState randomState && function instanceof CellSampler mapped) {
			callback.setReturnValue(mapped.new CacheChunk(this::reterraforged$currentTileChunk, this.cache2d, this.chunkX, this.chunkZ));
		}
	}

	@Override
	public synchronized void reterraforged$beginTileStage() {
		if (this.tileCache == null) {
			return;
		}
		if (this.tileLease != null) {
			this.tileStageDepth++;
			return;
		}
		TileCache.Lease acquired = this.tileCache.acquireAtChunk(this.chunkX, this.chunkZ);
		try {
			Tile ownedTile = acquired.tile();
			Tile.Chunk reader = ownedTile.getChunkReader(this.chunkX, this.chunkZ);
			this.tileLease = acquired;
			this.tile = ownedTile;
			this.chunk = reader;
			this.tileStageDepth = 1;
		} catch (RuntimeException | Error failure) {
			try {
				acquired.close();
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	@Override
	public synchronized void reterraforged$endTileStage() {
		if (this.tileStageDepth <= 0) {
			throw new IllegalStateException("NoiseChunk tile-stage ownership underflow");
		}
		if (--this.tileStageDepth > 0) {
			return;
		}
		TileCache.Lease releasing = this.tileLease;
		if (releasing != null) {
			this.chunk = null;
			this.tile = null;
			this.tileLease = null;
			releasing.close();
		}
	}

	@Override
	public Tile.Chunk reterraforged$currentTileChunk() {
		return this.chunk;
	}

	@Override
	public Tile reterraforged$currentTile() {
		return this.tile;
	}
}
