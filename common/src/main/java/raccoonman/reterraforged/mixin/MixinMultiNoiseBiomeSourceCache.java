package raccoonman.reterraforged.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import raccoonman.reterraforged.world.worldgen.biome.ClimatePointCache;

/**
 * Memoizes {@code MultiNoiseBiomeSource.getNoiseBiome(x, y, z, sampler)} per thread.
 * Bypasses expensive multi-noise density function stacks for redundant world-gen queries.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MixinMultiNoiseBiomeSourceCache {

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
            at = @At("RETURN"))
    private void rtf$storeNoiseBiome(final int x, final int y, final int z, final Climate.Sampler sampler,
                                     final CallbackInfoReturnable<Holder<Biome>> cir) {
        final Holder<Biome> value = cir.getReturnValue();
        if (value != null) {
            ClimatePointCache.store(this, sampler, x, y, z, value);
        }
    }
}