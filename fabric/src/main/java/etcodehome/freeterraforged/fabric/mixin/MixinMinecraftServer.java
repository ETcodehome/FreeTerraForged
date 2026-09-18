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
import etcodehome.freeterraforged.world.worldgen.feature.ore.DynamicOrePlan;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.FeatureTemplateManager;

@Implements(@Interface(iface = FTFMinecraftServer.class, prefix = "freeterraforged$FTFMinecraftServer$"))
@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {
	private FeatureTemplateManager templateManager;
	private volatile DynamicOrePlan dynamicOrePlan = DynamicOrePlan.empty();

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

	public DynamicOrePlan freeterraforged$FTFMinecraftServer$getDynamicOrePlan() {
		return this.dynamicOrePlan;
	}

	public void freeterraforged$FTFMinecraftServer$publishDynamicOrePlan(DynamicOrePlan plan) {
		this.dynamicOrePlan = plan;
	}

	@Inject(
		method = { "method_29440" },
		require = 0,
		at = @At("TAIL"),
		remap = false
	)
	private void method_29440(CallbackInfo callback) {
		this.templateManager.onReload(this.getResourceManager());
	}

	@Shadow
	private ResourceManager getResourceManager() {
		throw new UnsupportedOperationException();
	}
}
