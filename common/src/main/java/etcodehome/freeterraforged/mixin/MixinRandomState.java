package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.concurrent.ThreadPools;
import etcodehome.freeterraforged.config.PerformanceConfig;
import etcodehome.freeterraforged.data.worldgen.preset.settings.FlowSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.tags.FTFDensityFunctionTags;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.FTFWorldGenContext;
import etcodehome.freeterraforged.world.worldgen.biome.FTFClimateSampler;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.densityfunction.MarkerFunction;
import etcodehome.freeterraforged.world.worldgen.densityfunction.NoiseFunction;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
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
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

@Mixin(RandomState.class)
@Implements(@Interface(iface = FTFRandomState.class, prefix = "reterraforged$RTFRandomState$"))
class MixinRandomState {

	private DensityFunction.Visitor densityFunctionWrapper;
	private long seed;
	private boolean hasContext;
	@Shadow	@Final private Climate.Sampler sampler;
	@Unique private boolean reterraforged$isRTFDimension = false; // Tracks if the BASE router belongs to RTF
	@Nullable private GeneratorContext generatorContext;
	@Nullable private Preset preset;

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

		final boolean isVanillaOverworld = FTFWorldGenContext.IS_VANILLA_OVERWORLD.get();
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
		RegistryLookup<Preset> presets = registries.lookupOrThrow(FTFRegistries.PRESET);

		// Always assign the global preset. UI previews and legacy features in other dimensions
		// rely on this being non-null to read settings without throwing an NPE.
		presets.get(Preset.KEY).ifPresent((presetHolder) -> {
			this.preset = presetHolder.value();
		});

		// Only compile global density tags and build a heavy Overworld GeneratorContext
		// if the base router mapping verified that this instance is actually an RTF worldgen dimension.
		if (this.reterraforged$isRTFDimension) {
			if (this.preset != null && (Object) this.sampler instanceof FTFClimateSampler rtfClimateSampler) {
				rtfClimateSampler.setUndergroundBiomeBandingPreset(this.preset, this.seed);
			}

			RegistryLookup<Noise> noises = registries.lookupOrThrow(FTFRegistries.NOISE);
			RegistryLookup<DensityFunction> functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);

			functions.get(FTFDensityFunctionTags.ADDITIONAL_NOISE_ROUTER_FUNCTIONS).ifPresent((set) -> {
				set.forEach((function) -> function.value().mapAll(this.densityFunctionWrapper));
			});

			if (this.preset != null) {
				PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
						.resultOrPartial(FTFCommon.LOGGER::error)
						.orElseGet(PerformanceConfig::makeDefault);
				this.generatorContext = GeneratorContext.makeCached(this.preset, noises, (int) this.seed, config.tileSize(), config.batchCount(), ThreadPools.availableProcessors() > 4);
				if ((Object) this.sampler instanceof FTFClimateSampler rtfClimateSampler) {
					rtfClimateSampler.setUndergroundBiomeSurfaceContext(this.generatorContext);
				}
			}

			// populate static fields needed for mixins
			FlowSettings.CurrentPresetState.set(preset.flow());
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

	public Noise reterraforged$RTFRandomState$seed(Noise noise) {
		return Noises.shiftSeed(noise, (int) this.seed);
	}

	public long reterraforged$RTFRandomState$seed() { return this.seed;	}

	@Nullable
	public DensityFunction reterraforged$RTFRandomState$wrap(DensityFunction function) {
		return this.densityFunctionWrapper != null ? function.mapAll(this.densityFunctionWrapper) : function;
	}
}
