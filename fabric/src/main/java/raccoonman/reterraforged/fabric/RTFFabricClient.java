package raccoonman.reterraforged.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import raccoonman.reterraforged.client.debug.FlowFieldDebugRenderer;

public class RTFFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

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