package etcodehome.freeterraforged.fabric.mixin;

import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import etcodehome.freeterraforged.server.FTFMinecraftServer;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.FeatureTemplateManager;

@Implements(@Interface(iface = FTFMinecraftServer.class, prefix = "freeterraforged$FTFMinecraftServer$"))
@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {
	private FeatureTemplateManager templateManager;

	@Inject(
		method = "<init>",
		at = @At("TAIL")
	)
	public void MinecraftServer(CallbackInfo callback) {
		this.templateManager = new FeatureTemplateManager(this.getResourceManager());
	}

	public FeatureTemplateManager freeterraforged$FTFMinecraftServer$getFeatureTemplateManager() {
		return this.templateManager;
	}

	@Shadow
	private ResourceManager getResourceManager() {
		throw new UnsupportedOperationException();
	}
}
