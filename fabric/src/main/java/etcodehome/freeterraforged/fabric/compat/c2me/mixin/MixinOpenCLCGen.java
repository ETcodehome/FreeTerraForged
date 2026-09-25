package etcodehome.freeterraforged.fabric.compat.c2me.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ishland.c2me.opts.accel.opencl.common.Config;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.fabric.compat.c2me.dfc.FTFGpuTerrain;
import etcodehome.freeterraforged.world.worldgen.FTFAcceleration;
import etcodehome.freeterraforged.world.worldgen.densityfunction.FTFDensityFunctionScanner;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;

@Mixin(targets = "com.ishland.c2me.opts.accel.opencl.common.compiler.OpenCLCGen", remap = false)
public class MixinOpenCLCGen {

	@Inject(
		method = "compile",
		at = @At("HEAD"),
		cancellable = true,
		remap = false,
		require = 0
	)
	private static void freeterraforged$skipFTFTerrain(NoiseRouter router, NoiseSettings noiseSettings, Reference2ReferenceMap<?, ?> cache, DensityFunction finalDensity, BiomeSource biomeSource, CallbackInfoReturnable<Object> callback) {
		if(!FTFDensityFunctionScanner.usesFTFTerrain(router, finalDensity)) {
			return;
		}

		if(FTFGpuTerrain.isEnabled()) {
			FTFAcceleration.markGpuAccelerated();
			FTFCommon.LOGGER.info("Compiling a FreeTerraForged dimension to an OpenCL kernel. Terrain cell data will be uploaded per area; set -D" + FTFGpuTerrain.ENABLE_PROPERTY + "=false to return to CPU generation");
			return;
		}

		FTFCommon.LOGGER.info("Skipping C2ME OpenCL codegen for a FreeTerraForged dimension. FTF terrain is generated from tiled heightmaps rather than a density function graph, so it cannot be compiled to an OpenCL kernel. This dimension will generate on the CPU; other dimensions are unaffected");

		if(!Config.allowIncompatibilityFallback) {
			FTFCommon.LOGGER.error("C2ME will now abort world loading because openclAccel.allowIncompatibilityFallback is false. Set it to true in config/c2me.toml to let FreeTerraForged dimensions generate on the CPU, or set openclAccel.enabled to false to turn OpenCL acceleration off entirely");
		}

		FTFAcceleration.markOpenCLSkipped();
		callback.setReturnValue(null);
	}
}
