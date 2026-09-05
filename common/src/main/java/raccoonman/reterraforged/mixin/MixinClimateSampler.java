package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.ClimateQueryPolicy;
import raccoonman.reterraforged.world.worldgen.biome.ClimateQuerySemantics;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeClimatePolicy;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlan;
import raccoonman.reterraforged.world.worldgen.runtime.BiomeCellCache;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenQueryCaches;

@Mixin(Climate.Sampler.class)
@Implements(@Interface(iface = RTFClimateSampler.class, prefix = "reterraforged$RTFClimateSampler$"))
class MixinClimateSampler {
	@Unique
	private static final RTFClimateSampler.SpawnSearch reterraforged$defaultSpawnSearch = new RTFClimateSampler.SpawnSearch(
		raccoonman.reterraforged.data.worldgen.preset.settings.SpawnType.WORLD_ORIGIN,
		BlockPos.ZERO
	);
	private volatile RTFClimateSampler.SpawnSearch spawnSearch = reterraforged$defaultSpawnSearch;
	@Unique private volatile ClimateQuerySemantics reterraforged$querySemantics =
		ClimateQuerySemantics.passthrough();
	private volatile WorldgenPlan worldgenPlan;
	private volatile WorldgenRuntimeBinding worldgenBinding;
	@Unique private volatile WorldgenQueryCaches reterraforged$queryCaches;

	@WrapMethod(method = "sample")
	private Climate.TargetPoint reterraforged$sampleOnce(
		int x,
		int y,
		int z,
		Operation<Climate.TargetPoint> original
	) {
		ClimateQuerySemantics semantics = this.reterraforged$querySemantics;
		ClimateQueryPolicy policy = semantics.policy();
		if (!policy.cachesClimatePoints() && !policy.appliesUndergroundBanding()) {
			return original.call(x, y, z);
		}
		WorldgenQueryCaches caches = policy.cachesClimatePoints()
			? this.reterraforged$queryCaches()
			: null;
		Object queryOwner = semantics.cacheOwner();
		if (caches != null) {
			Climate.TargetPoint cached = caches.climatePoints().find(queryOwner, x, y, z);
			if (cached != null) {
				return cached;
			}
		}

		Climate.TargetPoint target = UndergroundBiomeClimatePolicy.apply(
			(Climate.Sampler) (Object) this,
			original.call(x, y, z),
			x,
			y,
			z,
			policy,
			semantics.preset(),
			semantics.seed(),
			semantics.surfaceContext()
		);
		if (caches != null
			&& semantics == this.reterraforged$querySemantics
			&& caches == this.reterraforged$queryCaches
			&& queryOwner == semantics.cacheOwner()) {
			caches.climatePoints().store(queryOwner, x, y, z, target);
		}
		return target;
	}
	
	public void reterraforged$RTFClimateSampler$setSpawnSearch(RTFClimateSampler.SpawnSearch spawnSearch) {
		this.spawnSearch = java.util.Objects.requireNonNull(spawnSearch, "spawnSearch");
	}

	public RTFClimateSampler.SpawnSearch reterraforged$RTFClimateSampler$getSpawnSearch() {
		return this.spawnSearch;
	}

	public synchronized void reterraforged$RTFClimateSampler$setClimateQuerySemantics(
		ClimateQueryPolicy policy,
		Preset preset,
		long seed,
		GeneratorContext context
	) {
		java.util.Objects.requireNonNull(policy, "climate query policy");
		ClimateQuerySemantics current = this.reterraforged$querySemantics;
		if (current.policy() != policy || current.preset() != preset || current.seed() != seed
			|| current.surfaceContext() != context) {
			this.reterraforged$querySemantics = new ClimateQuerySemantics(
				policy, preset, seed, context, new Object()
			);
			this.reterraforged$discardLocalQueryCacheStorage();
		}
	}

	public ClimateQuerySemantics reterraforged$RTFClimateSampler$climateQuerySemantics() {
		return this.reterraforged$querySemantics;
	}

	public float reterraforged$RTFClimateSampler$minimumSurfaceY(
		GeneratorContext context,
		int quartX,
		int quartZ
	) {
		return this.reterraforged$queryCaches().surfaceProtection().minimumSurfaceY(context, quartX, quartZ);
	}

	public void reterraforged$RTFClimateSampler$setWorldgenPlan(WorldgenPlan plan) {
		if (this.worldgenPlan != plan || this.worldgenBinding != null) {
			this.reterraforged$queryCaches = null;
		}
		this.worldgenBinding = null;
		this.worldgenPlan = plan;
	}

	public void reterraforged$RTFClimateSampler$setWorldgenBinding(WorldgenRuntimeBinding binding) {
		if (this.worldgenBinding != binding || this.worldgenPlan != null) {
			this.reterraforged$queryCaches = binding == null ? null : binding.queryCaches();
		}
		this.worldgenPlan = null;
		this.worldgenBinding = binding;
	}

	public WorldgenPlan reterraforged$RTFClimateSampler$getWorldgenPlan() {
		WorldgenRuntimeBinding binding = this.worldgenBinding;
		return binding == null ? this.worldgenPlan : binding.plan();
	}

	public BiomeCellCache<WorldgenPlan> reterraforged$RTFClimateSampler$getBiomeCellCache() {
		return this.reterraforged$queryCaches().biomeCells();
	}

	@Unique
	private WorldgenQueryCaches reterraforged$queryCaches() {
		WorldgenQueryCaches caches = this.reterraforged$queryCaches;
		if (caches == null) {
			synchronized (this) {
				caches = this.reterraforged$queryCaches;
				if (caches == null) {
					WorldgenRuntimeBinding binding = this.worldgenBinding;
					caches = binding == null ? new WorldgenQueryCaches() : binding.queryCaches();
					this.reterraforged$queryCaches = caches;
				}
			}
		}
		return caches;
	}

	@Unique
	private void reterraforged$discardLocalQueryCacheStorage() {
		if (this.worldgenBinding == null) {
			this.reterraforged$queryCaches = null;
		}
	}
}
