package etcodehome.freeterraforged.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import etcodehome.freeterraforged.client.debug.FlowFieldDebugRenderer;
import etcodehome.freeterraforged.fabric.network.FTFFabricClientNetworking;

public class FTFFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FTFFabricClientNetworking.init();

        Minecraft mc = Minecraft.getInstance();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            FlowFieldDebugRenderer.render(
                    context.matrixStack(),
                    context.camera(),
                    mc.renderBuffers().bufferSource()
            );
        });
    }
}
