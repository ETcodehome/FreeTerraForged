package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.ClimatePointCache;
import etcodehome.freeterraforged.world.worldgen.biome.FTFClimateSampler;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeClimatePolicy;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.ClimateQueryPolicy;
import etcodehome.freeterraforged.world.worldgen.biome.ClimateQuerySemantics;
import etcodehome.freeterraforged.world.worldgen.biome.FTFClimateSampler;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeClimatePolicy;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeCellCache;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenRuntimeBinding;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenQueryCaches;

@Mixin(Climate.Sampler.class)
@Implements(@Interface(iface = FTFClimateSampler.class, prefix = "freeterraforged$FTFClimateSampler$"))
class MixinClimateSampler {
	@Unique
	private static final FTFClimateSampler.SpawnSearch freeterraforged$defaultSpawnSearch = new FTFClimateSampler.SpawnSearch(
		etcodehome.freeterraforged.data.worldgen.preset.settings.SpawnType.WORLD_ORIGIN,
		BlockPos.ZERO
	);
	private volatile FTFClimateSampler.SpawnSearch spawnSearch = freeterraforged$defaultSpawnSearch;
	@Unique private volatile ClimateQuerySemantics freeterraforged$querySemantics =
		ClimateQuerySemantics.passthrough();
	private volatile WorldgenPlan worldgenPlan;
	private volatile WorldgenRuntimeBinding worldgenBinding;
	@Unique private volatile WorldgenQueryCaches freeterraforged$queryCaches;

	@WrapMethod(method = "sample")
	private Climate.TargetPoint freeterraforged$sampleOnce(
		int x,
		int y,
		int z,
		Operation<Climate.TargetPoint> original
	) {
		ClimateQuerySemantics semantics = this.freeterraforged$querySemantics;
		ClimateQueryPolicy policy = semantics.policy();
		if (!policy.cachesClimatePoints() && !policy.appliesUndergroundBanding()) {
			return original.call(x, y, z);
		}
		WorldgenQueryCaches caches = policy.cachesClimatePoints()
			? this.freeterraforged$queryCaches()
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
			semantics.seed()
		);
		if (caches != null
			&& semantics == this.freeterraforged$querySemantics
			&& caches == this.freeterraforged$queryCaches
			&& queryOwner == semantics.cacheOwner()) {
			caches.climatePoints().store(queryOwner, x, y, z, target);
		}
		return target;
	}

	public void freeterraforged$FTFClimateSampler$setSpawnSearch(FTFClimateSampler.SpawnSearch spawnSearch) {
		this.spawnSearch = java.util.Objects.requireNonNull(spawnSearch, "spawnSearch");
	}

	public FTFClimateSampler.SpawnSearch freeterraforged$FTFClimateSampler$getSpawnSearch() {
		return this.spawnSearch;
	}

	public synchronized void freeterraforged$FTFClimateSampler$setClimateQuerySemantics(
		ClimateQueryPolicy policy,
		Preset preset,
		long seed,
		GeneratorContext context
	) {
		java.util.Objects.requireNonNull(policy, "climate query policy");
		ClimateQuerySemantics current = this.freeterraforged$querySemantics;
		if (current.policy() != policy || current.preset() != preset || current.seed() != seed
			|| current.surfaceContext() != context) {
			this.freeterraforged$querySemantics = new ClimateQuerySemantics(
				policy, preset, seed, context, new Object()
			);
			this.freeterraforged$discardLocalQueryCacheStorage();
		}
	}

	public ClimateQuerySemantics freeterraforged$FTFClimateSampler$climateQuerySemantics() {
		return this.freeterraforged$querySemantics;
	}

	public void freeterraforged$FTFClimateSampler$setWorldgenPlan(WorldgenPlan plan) {
		if (this.worldgenPlan != plan || this.worldgenBinding != null) {
			this.freeterraforged$queryCaches = null;
		}
		this.worldgenBinding = null;
		this.worldgenPlan = plan;
	}

	public void freeterraforged$FTFClimateSampler$setWorldgenBinding(WorldgenRuntimeBinding binding) {
		if (this.worldgenBinding != binding || this.worldgenPlan != null) {
			this.freeterraforged$queryCaches = binding == null ? null : binding.queryCaches();
		}
		this.worldgenPlan = null;
		this.worldgenBinding = binding;
	}

	public WorldgenPlan freeterraforged$FTFClimateSampler$getWorldgenPlan() {
		WorldgenRuntimeBinding binding = this.worldgenBinding;
		return binding == null ? this.worldgenPlan : binding.plan();
	}

	public BiomeCellCache<WorldgenPlan> freeterraforged$FTFClimateSampler$getBiomeCellCache() {
		return this.freeterraforged$queryCaches().biomeCells();
	}

	@Unique
	private WorldgenQueryCaches freeterraforged$queryCaches() {
		WorldgenQueryCaches caches = this.freeterraforged$queryCaches;
		if (caches == null) {
			synchronized (this) {
				caches = this.freeterraforged$queryCaches;
				if (caches == null) {
					WorldgenRuntimeBinding binding = this.worldgenBinding;
					caches = binding == null ? new WorldgenQueryCaches() : binding.queryCaches();
					this.freeterraforged$queryCaches = caches;
				}
			}
		}
		return caches;
	}

	@Unique
	private void freeterraforged$discardLocalQueryCacheStorage() {
		if (this.worldgenBinding == null) {
			this.freeterraforged$queryCaches = null;
		}
	}
}
