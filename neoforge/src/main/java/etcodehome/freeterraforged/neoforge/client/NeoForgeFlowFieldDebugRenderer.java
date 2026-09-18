package etcodehome.freeterraforged.neoforge.client;

import etcodehome.freeterraforged.client.debug.FlowFieldDebugRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = "freeterraforged", value = Dist.CLIENT)
public class NeoForgeFlowFieldDebugRenderer {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            Minecraft mc = Minecraft.getInstance();
            FlowFieldDebugRenderer.render(
                    event.getPoseStack(),
                    event.getCamera(),
                    mc.renderBuffers().bufferSource()
            );
        }
    }
}