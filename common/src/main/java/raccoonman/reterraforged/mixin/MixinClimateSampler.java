package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;

@Mixin(Climate.Sampler.class)
@Implements(@Interface(iface = RTFClimateSampler.class, prefix = "reterraforged$RTFClimateSampler$"))
class MixinClimateSampler {
	private BlockPos spawnSearchCenter = BlockPos.ZERO;
	private Preset undergroundBiomeBandingPreset;
	
	public void reterraforged$RTFClimateSampler$setSpawnSearchCenter(BlockPos spawnSearchCenter) {
		this.spawnSearchCenter = spawnSearchCenter;
	}
	
	public BlockPos reterraforged$RTFClimateSampler$getSpawnSearchCenter() {
		return this.spawnSearchCenter;
	}

	public void reterraforged$RTFClimateSampler$setUndergroundBiomeBandingPreset(Preset preset) {
		this.undergroundBiomeBandingPreset = preset;
	}

	public Preset reterraforged$RTFClimateSampler$getUndergroundBiomeBandingPreset() {
		return this.undergroundBiomeBandingPreset;
	}
}
