package etcodehome.freeterraforged.mixin.compat;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.runtime.UnifiedBiomeSource;

@Pseudo
@Mixin(targets = "terrablender.util.LevelUtils", remap = false)
public abstract class MixinTerraBlenderLevelUtils {

	@Redirect(
		method = "shouldApplyToChunkGenerator",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getBiomeSource()Lnet/minecraft/world/level/biome/BiomeSource;",
			remap = true
		),
		remap = false,
		require = 1
	)
	private static BiomeSource ftf$unwrapShouldApply(ChunkGenerator chunkGenerator) {
		return chunkGenerator instanceof TerraForgedChunkGenerator terraForged
			? terraForged.acquisitionBiomeSource()
			: chunkGenerator.getBiomeSource();
	}

	@Redirect(
		method = "initializeBiomes",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getBiomeSource()Lnet/minecraft/world/level/biome/BiomeSource;",
			remap = true
		),
		remap = false,
		require = 1
	)
	private static BiomeSource ftf$unwrapInitializeBiomes(ChunkGenerator chunkGenerator) {
		return chunkGenerator instanceof TerraForgedChunkGenerator terraForged
			? terraForged.acquisitionBiomeSource()
			: chunkGenerator.getBiomeSource();
	}

	@Inject(
		method = "shouldApplyToBiomeSource",
		at = @At("HEAD"),
		cancellable = true,
		remap = false
	)
	private static void ftf$supportUnifiedBiomeSource(BiomeSource biomeSource, CallbackInfoReturnable<Boolean> cir) {
		if (biomeSource instanceof UnifiedBiomeSource unified) {
			cir.setReturnValue(unified.acquisitionSource() instanceof MultiNoiseBiomeSource);
		}
	}
}
