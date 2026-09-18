package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.ClimatePointCache;
import etcodehome.freeterraforged.world.worldgen.biome.FTFClimateSampler;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeClimatePolicy;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeSurfaceQuery;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;

@Mixin(Climate.Sampler.class)
@Implements(@Interface(iface = FTFClimateSampler.class, prefix = "freeterraforged$FTFClimateSampler$"))
class MixinClimateSampler {
	private BlockPos spawnSearchCenter = BlockPos.ZERO;
	private Preset undergroundBiomeBandingPreset;
	private long undergroundBiomeBandingSeed;
	private GeneratorContext undergroundBiomeSurfaceContext;

	@Inject(method = "sample", at = @At("HEAD"), cancellable = true)
	private void freeterraforged$reuseClimatePoint(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> callback) {
		Climate.TargetPoint target = ClimatePointCache.find(this, x, y, z);
		if (target != null) {
			UndergroundBiomeSurfaceQuery.record((Climate.Sampler) (Object) this, target, x, y, z);
			callback.setReturnValue(target);
		}
	}

	@Inject(method = "sample", at = @At("RETURN"), cancellable = true)
	private void freeterraforged$cacheClimatePoint(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> callback) {
		Climate.TargetPoint target = UndergroundBiomeClimatePolicy.apply(
			(Climate.Sampler) (Object) this,
			callback.getReturnValue(),
			x,
			y,
			z
		);
		callback.setReturnValue(target);
		ClimatePointCache.store(this, x, y, z, target);
		UndergroundBiomeSurfaceQuery.record((Climate.Sampler) (Object) this, target, x, y, z);
	}
	
	public void freeterraforged$FTFClimateSampler$setSpawnSearchCenter(BlockPos spawnSearchCenter) {
		this.spawnSearchCenter = spawnSearchCenter;
	}
	
	public BlockPos freeterraforged$FTFClimateSampler$getSpawnSearchCenter() {
		return this.spawnSearchCenter;
	}

	public void freeterraforged$FTFClimateSampler$setUndergroundBiomeBandingPreset(Preset preset, long seed) {
		this.undergroundBiomeBandingPreset = preset;
		this.undergroundBiomeBandingSeed = seed;
	}

	public Preset freeterraforged$FTFClimateSampler$getUndergroundBiomeBandingPreset() {
		return this.undergroundBiomeBandingPreset;
	}

	public long freeterraforged$FTFClimateSampler$getUndergroundBiomeBandingSeed() {
		return this.undergroundBiomeBandingSeed;
	}

	public void freeterraforged$FTFClimateSampler$setUndergroundBiomeSurfaceContext(GeneratorContext context) {
		this.undergroundBiomeSurfaceContext = context;
	}

	public GeneratorContext freeterraforged$FTFClimateSampler$getUndergroundBiomeSurfaceContext() {
		return this.undergroundBiomeSurfaceContext;
	}
}
