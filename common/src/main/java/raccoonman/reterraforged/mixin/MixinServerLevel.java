package raccoonman.reterraforged.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOreLifecycle;

@Mixin(ServerLevel.class)
public class MixinServerLevel {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void reterraforged$onLevelInit(CallbackInfo ci) {
        DynamicOreLifecycle.onLevelLoad((ServerLevel) (Object) this);
    }
}