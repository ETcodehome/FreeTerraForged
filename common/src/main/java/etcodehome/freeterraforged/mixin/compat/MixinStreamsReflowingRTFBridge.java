package etcodehome.freeterraforged.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "dev.streamsreflowing.worldgen.compat.RTFBridge", remap = false)
abstract class MixinStreamsReflowingRTFBridge {
	private static final String RTF_RANDOM_STATE =
		"raccoonman.reterraforged.world.worldgen.RTFRandomState";
	private static final String FTF_RANDOM_STATE =
		"etcodehome.freeterraforged.world.worldgen.FTFRandomState";

	@ModifyArg(
		method = "handleFor",
		at = @At(
			value = "INVOKE",
			target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"
		),
		index = 0
	)
	private static String freeterraforged$resolveRandomState(String className) {
		return RTF_RANDOM_STATE.equals(className) ? FTF_RANDOM_STATE : className;
	}
}
