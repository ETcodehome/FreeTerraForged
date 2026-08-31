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
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.tags.RTFDensityFunctionTags;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.MarkerFunction;
import raccoonman.reterraforged.world.worldgen.densityfunction.NoiseFunction;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenEpoch;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlan;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlans;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;

@Mixin(RandomState.class)
@Implements(@Interface(iface = RTFRandomState.class, prefix = "reterraforged$RTFRandomState$"))
class MixinRandomState {

	private DensityFunction.Visitor densityFunctionWrapper;
	private long seed;
	private boolean hasContext;
	@Shadow	@Final private Climate.Sampler sampler;
	@Unique private boolean reterraforged$isRTFDimension = false; // Tracks if the BASE router belongs to RTF
	@Nullable private volatile GeneratorContext generatorContext;
	@Nullable private volatile Preset preset;
	@Nullable private volatile WorldgenEpoch worldgenEpoch;
	@Nullable private volatile WorldgenRuntimeBinding worldgenBinding;

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

		this.densityFunctionWrapper = new DensityFunction.Visitor() {

			@Override
			public DensityFunction apply(DensityFunction function) {

				if(function instanceof NoiseFunction.Marker marker) {
					return new NoiseFunction(marker.noise(), (int) seed);
				}

				if(function instanceof CellSampler.Marker marker) {
					MixinRandomState.this.hasContext = true;
					return new CellSampler(Suppliers.memoize(() -> MixinRandomState.this.generatorContext.lookup), marker.field());
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
		if (this.hasContext) {
			this.reterraforged$isRTFDimension = true;
		}
		return mappedRouter;
	}

	public synchronized void reterraforged$RTFRandomState$initialize(WorldgenEpoch epoch) {
		if (this.worldgenEpoch != null) {
			if (this.worldgenEpoch.id().equals(epoch.id())) {
				return;
			}
			throw new IllegalStateException("RandomState is already owned by worldgen epoch " + this.worldgenEpoch.id());
		}
		RegistryAccess registries = epoch.registries();
		RegistryLookup<Preset> presets = registries.lookupOrThrow(RTFRegistries.PRESET);

		presets.get(Preset.KEY).ifPresent((presetHolder) -> {
			this.preset = presetHolder.value();
		});

		if (this.reterraforged$isRTFDimension) {
			if (this.preset == null) {
				throw new IllegalStateException("RTF density graph is active but the selected preset is unavailable");
			}
			if ((Object) this.sampler instanceof RTFClimateSampler rtfClimateSampler) {
				rtfClimateSampler.setUndergroundBiomeBandingPreset(this.preset, this.seed);
			}

			RegistryLookup<Noise> noises = registries.lookupOrThrow(RTFRegistries.NOISE);
			RegistryLookup<DensityFunction> functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);

			functions.get(RTFDensityFunctionTags.ADDITIONAL_NOISE_ROUTER_FUNCTIONS).ifPresent((set) -> {
				set.forEach((function) -> function.value().mapAll(this.densityFunctionWrapper));
			});

			PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
					.resultOrPartial(RTFCommon.LOGGER::error)
					.orElseGet(PerformanceConfig::makeDefault);
			this.generatorContext = GeneratorContext.makeCached(this.preset, noises, (int) this.seed, config.tileSize(), config.batchCount(), ThreadPools.availableProcessors() > 4);
			if ((Object) this.sampler instanceof RTFClimateSampler rtfClimateSampler) {
				rtfClimateSampler.setUndergroundBiomeSurfaceContext(this.generatorContext);
			}
		}
		this.worldgenEpoch = epoch;
	}

	public synchronized void reterraforged$RTFRandomState$bindPlan(WorldgenRuntimeBinding binding) {
		WorldgenPlan plan = binding.plan();
		if (this.worldgenEpoch == null || !this.worldgenEpoch.id().equals(plan.owner().id())) {
			throw new IllegalStateException("Cannot bind a plan owned by a different worldgen epoch");
		}
		if (this.worldgenBinding != null && this.worldgenBinding != binding) {
			throw new IllegalStateException("RandomState already has a compiled worldgen plan");
		}
		this.reterraforged$decorateSampler(plan);
		((RTFClimateSampler) (Object) this.sampler).setWorldgenBinding(binding);
		this.worldgenBinding = binding;
	}

	public synchronized void reterraforged$RTFRandomState$preparePlanRebind(WorldgenEpoch epoch, WorldgenPlan plan) {
		WorldgenRuntimeBinding binding = this.worldgenBinding;
		if (binding == null || !binding.epoch().id().equals(epoch.id())) {
			throw new IllegalStateException("Cannot refresh a different worldgen epoch");
		}
		if (!plan.owner().id().equals(epoch.id())) {
			throw new IllegalStateException("Refreshed plan is owned by a different worldgen epoch");
		}
		WorldgenEpoch current = binding.epoch();
		boolean tagsAdvanced = epoch.tagEpoch().sequence() > current.tagEpoch().sequence();
		boolean contributionsAdvanced = epoch.contributionSequence() > current.contributionSequence();
		if (!tagsAdvanced && !contributionsAdvanced) {
			throw new IllegalStateException("A plan rebind must advance a worldgen input epoch");
		}
		if (epoch.tagEpoch().sequence() < current.tagEpoch().sequence()
			|| epoch.contributionSequence() < current.contributionSequence()) {
			throw new IllegalStateException("Worldgen input epochs must advance monotonically");
		}
		this.reterraforged$decorateSampler(plan);
	}

	@Unique
	private void reterraforged$decorateSampler(WorldgenPlan plan) {
		Preset currentPreset = this.preset;
		GeneratorContext currentContext = this.generatorContext;
		if (currentPreset == null || currentContext == null) {
			throw new IllegalStateException("FTF sampler state is unavailable for the active worldgen owner");
		}
		plan.samplerDecoration().decorate(
			plan,
			new WorldgenPlans.SamplerInputs(currentPreset, currentContext),
			this.sampler
		);
	}

	@Nullable
	public WorldgenEpoch reterraforged$RTFRandomState$epoch() {
		WorldgenRuntimeBinding binding = this.worldgenBinding;
		return binding == null ? this.worldgenEpoch : binding.epoch();
	}

	@Nullable
	public WorldgenPlan reterraforged$RTFRandomState$plan() {
		WorldgenRuntimeBinding binding = this.worldgenBinding;
		return binding == null ? null : binding.plan();
	}

	public boolean reterraforged$RTFRandomState$isTerraForged() {
		return this.reterraforged$isRTFDimension;
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
