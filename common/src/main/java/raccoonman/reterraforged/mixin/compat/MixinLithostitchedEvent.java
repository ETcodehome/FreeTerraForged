package raccoonman.reterraforged.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge;

@Pseudo
@Mixin(targets = "dev.worldgen.lithostitched.impl.event.LithostitchedEvent", remap = false)
public abstract class MixinLithostitchedEvent {
	@Inject(method = "register", at = @At("TAIL"), remap = false, require = 1)
	private void rtf$invalidateResolvedCodeEvents(Object callback, CallbackInfo info) {
		LithostitchedInjectionBridge.codeEventRegistered(this);
	}
}
