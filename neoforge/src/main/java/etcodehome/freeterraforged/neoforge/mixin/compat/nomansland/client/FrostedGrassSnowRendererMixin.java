package etcodehome.freeterraforged.neoforge.mixin.compat.nomansland.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import etcodehome.freeterraforged.neoforge.compat.nomansland.SnowDepth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.farcr.nomansland.client.event.ClientEvents$FrostedGrassAdditionalGeoRenderer", remap = false)
public abstract class FrostedGrassSnowRendererMixin {
    @WrapOperation(method = "drawSnow", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateWithAO(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V"))
    private static void snowpatch$avoidBuriedGrassAmbientOcclusion(ModelBlockRenderer renderer,
            BlockAndTintGetter level, BakedModel model, BlockState snow, BlockPos pos,
            PoseStack poses, VertexConsumer vertices, boolean checkSides, RandomSource random,
            long seed, int overlay, ModelData modelData, RenderType renderType, Operation<Void> original) {
        snow = SnowDepth.snowState(level, pos);
        model = Minecraft.getInstance().getBlockRenderer().getBlockModel(snow);
        if (snow.getValue(SnowLayerBlock.LAYERS) >= 5) {
            // Keep world light and directional shading, but avoid AO darkening the buried plant's snow surface.
            renderer.tesselateWithoutAO(level, model, snow, pos, poses, vertices, checkSides,
                    random, seed, overlay, modelData, renderType);
        } else {
            original.call(renderer, level, model, snow, pos, poses, vertices, checkSides,
                    random, seed, overlay, modelData, renderType);
        }
    }
}
