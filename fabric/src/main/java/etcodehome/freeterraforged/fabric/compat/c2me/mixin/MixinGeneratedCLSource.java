package etcodehome.freeterraforged.fabric.compat.c2me.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import etcodehome.freeterraforged.fabric.compat.c2me.dfc.FTFGpuTerrain;
import it.unimi.dsi.fastutil.objects.Reference2IntLinkedOpenHashMap;

@Mixin(targets = "com.ishland.c2me.opts.accel.opencl.common.compiler.GeneratedCLSource", remap = false)
public class MixinGeneratedCLSource {

	@Inject(
		method = "getGlobalDynamicDataOffsets",
		at = @At("RETURN"),
		cancellable = true,
		remap = false,
		require = 0
	)
	private void freeterraforged$substituteCellData(CallbackInfoReturnable<Reference2IntLinkedOpenHashMap<Object>> callback) {
		Reference2IntLinkedOpenHashMap<Object> substituted = FTFGpuTerrain.substituted();
		if(substituted != null) {
			callback.setReturnValue(substituted);
		}
	}
}
