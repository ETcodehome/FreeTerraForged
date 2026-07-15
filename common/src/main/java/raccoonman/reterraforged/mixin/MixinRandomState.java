package raccoonman.reterraforged.mixin;

import net.minecraft.world.level.levelgen.*;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.google.common.base.Suppliers;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup.RegistryLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.config.PerformanceConfig;
import raccoonman.reterraforged.data.worldgen.compat.terrablender.TBNoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.tags.RTFDensityFunctionTags;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.RTFWorldGenContext;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.MarkerFunction;
import raccoonman.reterraforged.world.worldgen.densityfunction.NoiseFunction;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.terrablender.TBClimateSampler;
import raccoonman.reterraforged.world.worldgen.terrablender.TBCompat;

@Mixin(RandomState.class)
@Implements(@Interface(iface = RTFRandomState.class, prefix = "reterraforged$RTFRandomState$"))
class MixinRandomState {
	private DensityFunction.Visitor densityFunctionWrapper;
	@Shadow
	@Final
	private Climate.Sampler sampler;
	@Shadow
	@Final
	private SurfaceSystem surfaceSystem;

	private boolean hasContext;
	@Unique private boolean reterraforged$isRTFDimension = false; // Tracks if the BASE router belongs to RTF

	@Nullable
	private GeneratorContext generatorContext;
	@Nullable
	private Preset preset;

	private long seed;

	@Redirect(
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"
			),
			method = "<init>",
			require = 1
	)
	private NoiseRouter RandomState(NoiseRouter router, DensityFunction.Visitor visitor, NoiseGeneratorSettings noiseGeneratorSettings, HolderGetter<NormalNoise.NoiseParameters> params, final long seed) {
		this.seed = seed;

		final boolean isVanillaOverworld = RTFWorldGenContext.IS_VANILLA_OVERWORLD.get();
		this.densityFunctionWrapper = new DensityFunction.Visitor() {

			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof NoiseFunction.Marker marker) {
					return new NoiseFunction(marker.noise(), (int) seed);
				}
				if(function instanceof CellSampler.Marker marker) {

					if (!isVanillaOverworld) {
						return DensityFunctions.zero();
					}

					MixinRandomState.this.hasContext = true;
					return new CellSampler(Suppliers.memoize(() -> MixinRandomState.this.generatorContext.lookup), marker.field());
				}

				if (function instanceof MarkerFunction && !isVanillaOverworld) {
					return DensityFunctions.zero();
				}

				return visitor.apply(function);
			}

			@Override
			public NoiseHolder visitNoise(NoiseHolder noiseHolder) {
				return visitor.visitNoise(noiseHolder);
			}
		};

		// Map the base router first. If the current dimension naturally utilizes RTF, hasContext flips to true here.
		NoiseRouter mappedRouter = router.mapAll(this.densityFunctionWrapper);
		if (this.hasContext && isVanillaOverworld) {
			this.reterraforged$isRTFDimension = true;
		}
		return mappedRouter;
	}

	public void reterraforged$RTFRandomState$initialize(RegistryAccess registries) {
		RegistryLookup<Preset> presets = registries.lookupOrThrow(RTFRegistries.PRESET);

		// Always assign the global preset. UI previews and legacy features in other dimensions
		// rely on this being non-null to read settings without throwing an NPE.
		presets.get(Preset.KEY).ifPresent((presetHolder) -> {
			this.preset = presetHolder.value();
		});

		// Only compile global density tags and build a heavy Overworld GeneratorContext
		// if the base router mapping verified that this instance is actually an RTF worldgen dimension.
		if (this.reterraforged$isRTFDimension) {
			RegistryLookup<Noise> noises = registries.lookupOrThrow(RTFRegistries.NOISE);
			RegistryLookup<DensityFunction> functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);

			functions.get(RTFDensityFunctionTags.ADDITIONAL_NOISE_ROUTER_FUNCTIONS).ifPresent((set) -> {
				set.forEach((function) -> function.value().mapAll(this.densityFunctionWrapper));
			});

			if((Object) this.sampler instanceof TBClimateSampler tbClimateSampler && TBCompat.isEnabled()) {
				functions.get(TBNoiseRouterData.UNIQUENESS).ifPresent((uniqueness) -> {
					tbClimateSampler.setUniqueness(uniqueness.value().mapAll(this.densityFunctionWrapper));
				});
			}

			if (this.preset != null) {
				PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
						.resultOrPartial(RTFCommon.LOGGER::error)
						.orElseGet(PerformanceConfig::makeDefault);
				this.generatorContext = GeneratorContext.makeCached(this.preset, noises, (int) this.seed, config.tileSize(), config.batchCount(), ThreadPools.availableProcessors() > 4);
			}
		}
	}

	@Nullable
	public Preset reterraforged$RTFRandomState$preset() {
		return this.preset;
	}

	@Nullable
	public GeneratorContext reterraforged$RTFRandomState$generatorContext() {
		return this.generatorContext;
	}

	@Nullable
	public DensityFunction reterraforged$RTFRandomState$wrap(DensityFunction function) {
		return this.densityFunctionWrapper != null ? function.mapAll(this.densityFunctionWrapper) : function;
	}

	public Noise reterraforged$RTFRandomState$seed(Noise noise) {
		return Noises.shiftSeed(noise, (int) this.seed);
	}
}