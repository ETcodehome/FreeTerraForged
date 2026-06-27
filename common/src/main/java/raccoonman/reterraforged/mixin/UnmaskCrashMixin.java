package raccoonman.reterraforged.mixin;

import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SystemReport.class)
public class UnmaskCrashMixin {
    @Inject(
            method = "setDetail(Ljava/lang/String;Ljava/lang/String;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void skipGlCapsCrash(String identifier, String value, CallbackInfo ci) {
        // Intercept the GL Caps check and cancel it before it can call the
        // uninitialized RenderSystem and wipe out our console logs.
        if ("GL Caps".equals(identifier)) {
            ci.cancel();
        }
    }
}