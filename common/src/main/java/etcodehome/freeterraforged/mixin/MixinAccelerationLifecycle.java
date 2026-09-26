package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import etcodehome.freeterraforged.world.worldgen.FTFAcceleration;
import net.minecraft.server.MinecraftServer;

@Mixin(MinecraftServer.class)
public class MixinAccelerationLifecycle {

	@Inject(
		method = "stopServer",
		at = @At("HEAD")
	)
	private void freeterraforged$resetAccelerationStatus(CallbackInfo callback) {
		FTFAcceleration.reset();
	}
}
