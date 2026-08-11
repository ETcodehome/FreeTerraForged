package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.biome.ClimatePointCache;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;

@Mixin(Climate.Sampler.class)
@Implements(@Interface(iface = RTFClimateSampler.class, prefix = "reterraforged$RTFClimateSampler$"))
class MixinClimateSampler {
	private BlockPos spawnSearchCenter = BlockPos.ZERO;
	private Preset undergroundBiomeBandingPreset;
	private long undergroundBiomeBandingSeed;

	@Inject(method = "sample", at = @At("HEAD"), cancellable = true)
	private void reterraforged$reuseClimatePoint(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> callback) {
		Climate.TargetPoint target = ClimatePointCache.find(this, x, y, z);
		if (target != null) {
			callback.setReturnValue(target);
		}
	}

	@Inject(method = "sample", at = @At("RETURN"))
	private void reterraforged$cacheClimatePoint(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> callback) {
		ClimatePointCache.store(this, x, y, z, callback.getReturnValue());
	}
	
	public void reterraforged$RTFClimateSampler$setSpawnSearchCenter(BlockPos spawnSearchCenter) {
		this.spawnSearchCenter = spawnSearchCenter;
	}
	
	public BlockPos reterraforged$RTFClimateSampler$getSpawnSearchCenter() {
		return this.spawnSearchCenter;
	}

	public void reterraforged$RTFClimateSampler$setUndergroundBiomeBandingPreset(Preset preset, long seed) {
		this.undergroundBiomeBandingPreset = preset;
		this.undergroundBiomeBandingSeed = seed;
	}

	public Preset reterraforged$RTFClimateSampler$getUndergroundBiomeBandingPreset() {
		return this.undergroundBiomeBandingPreset;
	}

	public long reterraforged$RTFClimateSampler$getUndergroundBiomeBandingSeed() {
		return this.undergroundBiomeBandingSeed;
	}
}
