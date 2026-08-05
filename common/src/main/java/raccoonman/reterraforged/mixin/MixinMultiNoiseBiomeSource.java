package raccoonman.reterraforged.mixin;

import java.util.Objects;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import raccoonman.reterraforged.world.worldgen.terrablender.TerraBlenderParameterList;

/**
 * Composes RTF underground banding with the result chosen by the active biome-selection stack.
 * A third-party replacement remains authoritative; an unchanged base result receives banding.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MixinMultiNoiseBiomeSource {
    @Unique
    private volatile UndergroundBiomeBanding.Layout<Holder<Biome>> rtf$undergroundBanding;
    @Unique
    private Preset rtf$undergroundBandingPreset;

    @Shadow
    protected abstract Climate.ParameterList<Holder<Biome>> parameters();

    @Inject(
            method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("RETURN"),
            cancellable = true)
    private void rtf$composeUndergroundBanding(final int x, final int y, final int z,
                                                final Climate.Sampler sampler,
                                                final CallbackInfoReturnable<Holder<Biome>> cir) {
        Holder<Biome> selected = cir.getReturnValue();
        if (selected == null) {
            return;
        }

        Climate.TargetPoint target = sampler.sample(x, y, z);
        Climate.ParameterList<Holder<Biome>> parameters = this.parameters();
        if ((Object) parameters instanceof TerraBlenderParameterList<?> terraBlenderParameters
                && terraBlenderParameters.reterraforged$isTerraBlenderInitialized()) {
            @SuppressWarnings("unchecked")
            Holder<Biome> composed = ((TerraBlenderParameterList<Holder<Biome>>) terraBlenderParameters)
                    .reterraforged$applyUndergroundBanding(target, x, y, z, selected);
            cir.setReturnValue(composed);
            return;
        }

        if (!((Object) sampler instanceof RTFClimateSampler rtfSampler)) {
            return;
        }
        Preset preset = rtfSampler.getUndergroundBiomeBandingPreset();
        if (preset == null || !Objects.equals(selected, parameters.findValue(target))) {
            return;
        }

        UndergroundBiomeBanding.Layout<Holder<Biome>> banding = this.rtf$undergroundBanding;
        if (banding == null || this.rtf$undergroundBandingPreset != preset) {
            synchronized (this) {
                banding = this.rtf$undergroundBanding;
                if (banding == null || this.rtf$undergroundBandingPreset != preset) {
                    banding = UndergroundBiomeBanding.apply(preset, parameters.values());
                    this.rtf$undergroundBandingPreset = preset;
                    this.rtf$undergroundBanding = banding;
                }
            }
        }
        if (banding.appliesAt(target)) {
            cir.setReturnValue(banding.findValue(target));
        }
    }
}
