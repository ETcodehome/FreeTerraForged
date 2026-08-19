package raccoonman.reterraforged.mixin;

import java.util.Objects;
import java.util.function.Function;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.RTFMultiNoiseBiomeSource;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import raccoonman.reterraforged.world.worldgen.biome.PreviewBiomeQueryContext;
import raccoonman.reterraforged.world.worldgen.terrablender.TerraBlenderParameterList;

/**
 * Composes RTF underground banding with the result chosen by the active biome-selection stack.
 * A third-party replacement remains authoritative; an unchanged base result receives banding.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MixinMultiNoiseBiomeSource implements RTFMultiNoiseBiomeSource {
    @Shadow
    @Final
    private Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> parameters;

    @Unique
    private volatile UndergroundBiomeBanding.Layout<Holder<Biome>> rtf$undergroundBanding;
    @Unique
    private Preset rtf$undergroundBandingPreset;
    @Unique
    private long rtf$undergroundBandingSeed;

    @Shadow
    protected abstract Climate.ParameterList<Holder<Biome>> parameters();

    @Override
    public Climate.ParameterList<Holder<Biome>> reterraforged$getParameters() {
        return this.parameters.map(Function.identity(), holder -> holder.value().parameters());
    }

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

        // The parameter-list mixin already composed this exact result.  A
        // replacement biome that changed the return value intentionally falls
        // through so the replacement remains authoritative.
        if (PreviewBiomeQueryContext.matches(x, y, z, selected)) {
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
        long seed = rtfSampler.getUndergroundBiomeBandingSeed();
        if (banding == null || this.rtf$undergroundBandingPreset != preset || this.rtf$undergroundBandingSeed != seed) {
            synchronized (this) {
                banding = this.rtf$undergroundBanding;
                if (banding == null || this.rtf$undergroundBandingPreset != preset || this.rtf$undergroundBandingSeed != seed) {
                    banding = UndergroundBiomeBanding.apply(
						preset, parameters.values(), seed
					);
                    this.rtf$undergroundBandingPreset = preset;
					this.rtf$undergroundBandingSeed = seed;
                    this.rtf$undergroundBanding = banding;
                }
            }
        }
        if (banding.appliesAt(target)) {
            cir.setReturnValue(banding.findValue(target, x, z));
        }
    }
}
