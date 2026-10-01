package etcodehome.freeterraforged.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "dev.streamsreflowing.worldgen.compat.RTFBridge$Surface", remap = false)
abstract class MixinStreamsReflowingRTFBridgeSurface {
	private static final String RTF_PACKAGE = "raccoonman.reterraforged";
	private static final String FTF_PACKAGE = "etcodehome.freeterraforged";
	private static final String RTF_RANDOM_STATE = RTF_PACKAGE + ".world.worldgen.RTFRandomState";
	private static final String FTF_RANDOM_STATE = FTF_PACKAGE + ".world.worldgen.FTFRandomState";
	private static final String RTF_FLOW_FIELD_ACCESSOR = "reterraforged$getFlowField";
	private static final String FTF_FLOW_FIELD_ACCESSOR = "freeterraforged$getFlowField";

	@ModifyArg(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"
		),
		index = 0
	)
	private static String freeterraforged$resolveClass(String className) {
		if (RTF_RANDOM_STATE.equals(className)) {
			return FTF_RANDOM_STATE;
		}
		return className.startsWith(RTF_PACKAGE + ".")
			? FTF_PACKAGE + className.substring(RTF_PACKAGE.length())
			: className;
	}

	@ModifyArg(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Ljava/lang/Class;getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"
		),
		index = 0
	)
	private static String freeterraforged$resolveMethod(String methodName) {
		return RTF_FLOW_FIELD_ACCESSOR.equals(methodName) ? FTF_FLOW_FIELD_ACCESSOR : methodName;
	}
}
