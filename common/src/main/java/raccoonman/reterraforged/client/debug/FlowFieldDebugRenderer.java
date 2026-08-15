package raccoonman.reterraforged.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

public class FlowFieldDebugRenderer {

    private static boolean ENABLED = false;
    private static final int renderVectorRadius = 64;
    private static final int renderMagnitudeRadius = 8;
    private static final int maxDistSq = renderMagnitudeRadius * renderMagnitudeRadius;
    private static final int textColor = 0xFFFFFFFF;
    private static final float fontScale = 0.010f;

    public static void render(PoseStack poseStack, Camera camera, MultiBufferSource.BufferSource bufferSource) {

        // Only render if the flag has been set in code.
        // There is intentionally no runtime toggle.
        if (!ENABLED) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;
        BlockPos playerPos = player.blockPosition();

        // perform rendering passes
        renderLines(poseStack, camera, bufferSource, level, playerPos);
        renderTextMagnitudes(poseStack, bufferSource, level, playerPos);

        // actually render by ending batch operation
        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    private static void renderLines(PoseStack poseStack, Camera camera, MultiBufferSource.BufferSource bufferSource, Level level, BlockPos playerPos) {

        // Get the perspective to render from
        Vec3 camPos = camera.getPosition();
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose lastPose = poseStack.last();

        for (int x = -renderVectorRadius; x <= renderVectorRadius; x++) {
            for (int z = -renderVectorRadius; z <= renderVectorRadius; z++) {

                // Guard against rendering for non rtf chunks
                int worldX = playerPos.getX() + x;
                int worldZ = playerPos.getZ() + z;
                ChunkAccess chunk = level.getChunk(worldX >> 4, worldZ >> 4);
                if (!(chunk instanceof IFlowFieldHolder holder)) continue;

                // Guard against rendering zero flow flowfields
                int localX = worldX & 15;
                int localZ = worldZ & 15;
                ChunkFlowField flowField = holder.reterraforged$getFlowField();
                if (!flowField.hasFlow(localX, localZ)) continue;

                // Calculate rendering positions
                double radians = flowField.getAngleRadians(localX, localZ);
                float strength = flowField.getNormalizedMagnitude(localX, localZ);
                float lineLength = 0.10f + (strength * 0.28f);
                float startX = worldX + 0.5f;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                float startY = surfaceY + 0.1f;
                float startZ = worldZ + 0.5f;
                float endX = startX + (float) Math.cos(radians) * lineLength;
                float endZ = startZ + (float) Math.sin(radians) * lineLength;
                float r = strength;
                float g = 1.0f - (strength * 0.5f);
                float b = 1.0f - strength;
                double headAngle1 = radians + Math.toRadians(150);
                double headAngle2 = radians - Math.toRadians(150);
                float arrowLen = lineLength * 0.30f;

                // actually draw the arrow
                drawLine(lastPose, buffer, startX, startY, startZ, endX, startY, endZ, r, g, b, 1.0f);
                drawLine(lastPose, buffer, endX, startY, endZ,
                        endX + (float) Math.cos(headAngle1) * arrowLen, startY,
                        endZ + (float) Math.sin(headAngle1) * arrowLen, r, g, b, 1.0f);
                drawLine(lastPose, buffer, endX, startY, endZ,
                        endX + (float) Math.cos(headAngle2) * arrowLen, startY,
                        endZ + (float) Math.sin(headAngle2) * arrowLen, r, g, b, 1.0f);
            }
        }
    }

    private static void renderTextMagnitudes(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, Level level, BlockPos playerPos) {

        for (int x = -renderMagnitudeRadius; x <= renderMagnitudeRadius; x++) {
            for (int z = -renderMagnitudeRadius; z <= renderMagnitudeRadius; z++) {

                // Guard against corners we want clip cleanly
                if ((x * x + z * z) > maxDistSq) continue;

                // Guard against chunks that don't have any flowfields stored (no op)
                int worldX = playerPos.getX() + x;
                int worldZ = playerPos.getZ() + z;
                ChunkAccess chunk = level.getChunk(worldX >> 4, worldZ >> 4);
                if (!(chunk instanceof IFlowFieldHolder holder)) continue;

                // Guard against this specific position having no flow
                ChunkFlowField flowField = holder.reterraforged$getFlowField();
                int localX = worldX & 15;
                int localZ = worldZ & 15;
                if (!flowField.hasFlow(localX, localZ)) continue;

                // Determine the magnitude string
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                int strength = flowField.getMagnitude(localX, localZ);
                String magText = String.valueOf(strength);

                // Determine the rendering location
                float startX = worldX + 0.5f;
                float startY = surfaceY + 0.30f;
                float startZ = worldZ + 0.5f;

                // Actually render the values
                DebugRenderer.renderFloatingText(
                        poseStack,
                        bufferSource,
                        magText,
                        startX,
                        startY,
                        startZ,
                        textColor,
                        fontScale,
                        true,    // Center align
                        0.0f,    // Padding
                        true     // See-through depth check
                );
            }
        }
    }

    private static void drawLine(PoseStack.Pose pose, VertexConsumer buffer,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float r, float g, float b, float a) {
        float nx = x2 - x1;
        float ny = y2 - y1;
        float nz = z2 - z1;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) { nx /= len; ny /= len; nz /= len; }

        buffer.addVertex(pose, x1, y1, z1)
                .setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz);
        buffer.addVertex(pose, x2, y1, z2)
                .setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz);
    }
}