package etcodehome.freeterraforged.forge.client;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.debug.FlowFieldDebugRenderer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FTFCommon.MOD_ID, value = Dist.CLIENT)
public class ForgeFlowFieldDebugRenderer {

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