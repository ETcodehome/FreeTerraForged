package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.world.worldgen.feature.ore.DynamicOreLifecycle;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MixinServerStarted {

    @Inject(
            method = "runServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;buildServerStatus()Lnet/minecraft/network/protocol/status/ServerStatus;"
            )
    )
    private void reterraforged$onServerStarted(CallbackInfo ci) {
        DynamicOreLifecycle.onServerStarted((MinecraftServer) (Object) this);
    }
}