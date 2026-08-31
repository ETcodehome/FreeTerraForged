package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.ClimatePointCache;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeClimatePolicy;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlan;
import raccoonman.reterraforged.world.worldgen.runtime.BiomeCellCache;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;

@Mixin(Climate.Sampler.class)
@Implements(@Interface(iface = RTFClimateSampler.class, prefix = "reterraforged$RTFClimateSampler$"))
class MixinClimateSampler {
	private volatile BlockPos spawnSearchCenter = BlockPos.ZERO;
	private volatile Preset undergroundBiomeBandingPreset;
	private volatile long undergroundBiomeBandingSeed;
	private volatile GeneratorContext undergroundBiomeSurfaceContext;
	private volatile WorldgenPlan worldgenPlan;
	private volatile WorldgenRuntimeBinding worldgenBinding;
	@Unique private final ClimatePointCache reterraforged$climatePointCache = new ClimatePointCache();
	@Unique private volatile Object reterraforged$climateCacheOwner = new Object();
	@Unique private final UndergroundBiomeSurfaceProtection.Cache reterraforged$surfaceCache =
		new UndergroundBiomeSurfaceProtection.Cache();
	@Unique private final BiomeCellCache<WorldgenPlan> reterraforged$biomeCellCache = new BiomeCellCache<>();

	@Inject(method = "sample", at = @At("HEAD"), cancellable = true)
	private void reterraforged$reuseClimatePoint(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> callback) {
		Object owner = this.reterraforged$climateCacheOwner;
		Climate.TargetPoint target = this.reterraforged$climatePointCache.find(owner, x, y, z);
		if (target != null) {
			callback.setReturnValue(target);
		}
	}

	@Inject(method = "sample", at = @At("RETURN"), cancellable = true)
	private void reterraforged$cacheClimatePoint(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> callback) {
		Object owner = this.reterraforged$climateCacheOwner;
		Climate.TargetPoint target = UndergroundBiomeClimatePolicy.apply(
			(Climate.Sampler) (Object) this,
			callback.getReturnValue(),
			x,
			y,
			z
		);
		callback.setReturnValue(target);
		if (owner == this.reterraforged$climateCacheOwner) {
			this.reterraforged$climatePointCache.store(owner, x, y, z, target);
		}
	}
	
	public void reterraforged$RTFClimateSampler$setSpawnSearchCenter(BlockPos spawnSearchCenter) {
		this.spawnSearchCenter = spawnSearchCenter;
	}
	
	public BlockPos reterraforged$RTFClimateSampler$getSpawnSearchCenter() {
		return this.spawnSearchCenter;
	}

	public void reterraforged$RTFClimateSampler$setUndergroundBiomeBandingPreset(Preset preset, long seed) {
		if (this.undergroundBiomeBandingPreset != preset || this.undergroundBiomeBandingSeed != seed) {
			this.reterraforged$climateCacheOwner = new Object();
		}
		this.undergroundBiomeBandingPreset = preset;
		this.undergroundBiomeBandingSeed = seed;
	}

	public Preset reterraforged$RTFClimateSampler$getUndergroundBiomeBandingPreset() {
		return this.undergroundBiomeBandingPreset;
	}

	public long reterraforged$RTFClimateSampler$getUndergroundBiomeBandingSeed() {
		return this.undergroundBiomeBandingSeed;
	}

	public void reterraforged$RTFClimateSampler$setUndergroundBiomeSurfaceContext(GeneratorContext context) {
		if (this.undergroundBiomeSurfaceContext != context) {
			this.reterraforged$climateCacheOwner = new Object();
			this.reterraforged$surfaceCache.clear();
		}
		this.undergroundBiomeSurfaceContext = context;
	}

	public GeneratorContext reterraforged$RTFClimateSampler$getUndergroundBiomeSurfaceContext() {
		return this.undergroundBiomeSurfaceContext;
	}

	public float reterraforged$RTFClimateSampler$minimumSurfaceY(
		GeneratorContext context,
		int quartX,
		int quartZ
	) {
		return this.reterraforged$surfaceCache.minimumSurfaceY(context, quartX, quartZ);
	}

	public void reterraforged$RTFClimateSampler$setWorldgenPlan(WorldgenPlan plan) {
		if (this.worldgenPlan != plan || this.worldgenBinding != null) {
			this.reterraforged$climateCacheOwner = new Object();
			this.reterraforged$biomeCellCache.clear();
		}
		this.worldgenBinding = null;
		this.worldgenPlan = plan;
	}

	public void reterraforged$RTFClimateSampler$setWorldgenBinding(WorldgenRuntimeBinding binding) {
		if (this.worldgenBinding != binding || this.worldgenPlan != null) {
			this.reterraforged$climateCacheOwner = new Object();
			this.reterraforged$biomeCellCache.clear();
		}
		this.worldgenPlan = null;
		this.worldgenBinding = binding;
	}

	public WorldgenPlan reterraforged$RTFClimateSampler$getWorldgenPlan() {
		WorldgenRuntimeBinding binding = this.worldgenBinding;
		return binding == null ? this.worldgenPlan : binding.plan();
	}

	public BiomeCellCache<WorldgenPlan> reterraforged$RTFClimateSampler$getBiomeCellCache() {
		return this.reterraforged$biomeCellCache;
	}
}
