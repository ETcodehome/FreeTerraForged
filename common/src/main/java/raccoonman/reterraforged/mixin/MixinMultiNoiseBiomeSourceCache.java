package raccoonman.reterraforged.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.biome.ClimatePointCache;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import raccoonman.reterraforged.world.worldgen.terrablender.TBCompat;

/**
 * Memoizes {@code MultiNoiseBiomeSource.getNoiseBiome(x, y, z, sampler)} per thread.
 * Bypasses expensive multi-noise density function stacks for redundant world-gen queries.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MixinMultiNoiseBiomeSourceCache {
    @Unique
    private volatile Climate.ParameterList<Holder<Biome>> rtf$undergroundBandedParameters;
    @Unique
    private Preset rtf$undergroundBandingPreset;

    @Shadow
    protected abstract Climate.ParameterList<Holder<Biome>> parameters();

    @Inject(
            method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("HEAD"),
            cancellable = true)
    private void rtf$cachedNoiseBiome(final int x, final int y, final int z, final Climate.Sampler sampler,
                                      final CallbackInfoReturnable<Holder<Biome>> cir) {
        final Holder<Biome> hit = ClimatePointCache.find(this, sampler, x, y, z);
        if (hit != null) {
            cir.setReturnValue(hit);
        }
    }

    @Inject(
            method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("HEAD"),
            cancellable = true)
    private void rtf$bandedNoiseBiome(final int x, final int y, final int z, final Climate.Sampler sampler,
                                      final CallbackInfoReturnable<Holder<Biome>> cir) {
        if (TBCompat.isEnabled() || !((Object) sampler instanceof RTFClimateSampler rtfSampler)) {
            return;
        }

        Preset preset = rtfSampler.getUndergroundBiomeBandingPreset();
        if (preset == null) {
            return;
        }

        Climate.TargetPoint target = sampler.sample(x, y, z);
        if (!UndergroundBiomeBanding.appliesAt(target)) {
            return;
        }

        Climate.ParameterList<Holder<Biome>> banded = this.rtf$undergroundBandedParameters;
        if (banded == null || this.rtf$undergroundBandingPreset != preset) {
            synchronized (this) {
                banded = this.rtf$undergroundBandedParameters;
                if (banded == null || this.rtf$undergroundBandingPreset != preset) {
                    banded = new Climate.ParameterList<>(UndergroundBiomeBanding.apply(preset, this.parameters().values()));
                    this.rtf$undergroundBandingPreset = preset;
                    this.rtf$undergroundBandedParameters = banded;
                }
            }
        }

        cir.setReturnValue(banded.findValue(target));
    }

    @Inject(
            method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("RETURN"))
    private void rtf$storeNoiseBiome(final int x, final int y, final int z, final Climate.Sampler sampler,
                                     final CallbackInfoReturnable<Holder<Biome>> cir) {
        final Holder<Biome> value = cir.getReturnValue();
        if (value != null) {
            ClimatePointCache.store(this, sampler, x, y, z, value);
        }
    }
}
