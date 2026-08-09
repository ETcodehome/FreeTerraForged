package raccoonman.reterraforged.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

public class FlowFieldDebugRenderer {

    public static boolean ENABLED = true;
    private static final int RADIUS_BLOCKS = 64;

    public static void render(PoseStack poseStack, Camera camera, MultiBufferSource.BufferSource bufferSource) {
        if (!ENABLED) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;

        Vec3 camPos = camera.getPosition();
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose lastPose = poseStack.last();

        BlockPos playerPos = player.blockPosition();

        for (int x = -RADIUS_BLOCKS; x <= RADIUS_BLOCKS; x++) {
            for (int z = -RADIUS_BLOCKS; z <= RADIUS_BLOCKS; z++) {
                int worldX = playerPos.getX() + x;
                int worldZ = playerPos.getZ() + z;

                ChunkAccess chunk = level.getChunk(worldX >> 4, worldZ >> 4);
                if (!(chunk instanceof IFlowFieldHolder holder)) continue;

                ChunkFlowField flowField = holder.reterraforged$getFlowField();
                int localX = worldX & 15;
                int localZ = worldZ & 15;

                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                BlockPos samplePos = new BlockPos(worldX, surfaceY, worldZ);

                boolean hasFlow = flowField.hasFlow(localX, localZ);
                boolean isRiver = level.getBiome(samplePos).is(BiomeTags.IS_RIVER);

                float startX = worldX + 0.5f;
                float startY = surfaceY + 0.1f;
                float startZ = worldZ + 0.5f;

                // Case 1: River biome with no flow direction -> Render red exclamation mark
                if (!hasFlow) {
                    if (isRiver) {
                        //drawRedDot(lastPose, buffer, startX, startY, startZ);
                    }
                    continue;
                }

                // Case 2: Tile has flow -> Render direction vector arrow
                double radians = flowField.getAngleRadians(localX, localZ);
                float strength = flowField.getNormalizedMagnitude(localX, localZ);

                // Max radius from center (0.5) to edge is 0.5f. Keeping line length <= 0.38f ensures headroom for cell padding.
                float lineLength = 0.10f + (strength * 0.28f);
                float endX = startX + (float) Math.cos(radians) * lineLength;
                float endZ = startZ + (float) Math.sin(radians) * lineLength;

                float r = strength;
                float g = 1.0f - (strength * 0.5f);
                float b = 1.0f - strength;

                drawLine(lastPose, buffer, startX, startY, startZ, endX, startY, endZ, r, g, b, 1.0f);

                double headAngle1 = radians + Math.toRadians(150);
                double headAngle2 = radians - Math.toRadians(150);
                // Scale arrowhead barb length proportionally to shaft length
                float arrowLen = lineLength * 0.30f;

                drawLine(lastPose, buffer, endX, startY, endZ,
                        endX + (float) Math.cos(headAngle1) * arrowLen, startY,
                        endZ + (float) Math.sin(headAngle1) * arrowLen, r, g, b, 1.0f);

                drawLine(lastPose, buffer, endX, startY, endZ,
                        endX + (float) Math.cos(headAngle2) * arrowLen, startY,
                        endZ + (float) Math.sin(headAngle2) * arrowLen, r, g, b, 1.0f);
            }
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    private static void drawRedDot(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z) {
        float r = 1.0f, g = 0.0f, b = 0.0f, a = 1.0f;

        // Dot at bottom
        drawLine(pose, buffer, x, y + 0.08f, z, x, y + 0.18f, z, r, g, b, a);
        // Dot cross cap
        drawLine(pose, buffer, x - 0.05f, y + 0.13f, z, x + 0.05f, y + 0.13f, z, r, g, b, a);
        drawLine(pose, buffer, x, y + 0.13f, z - 0.05f, x, y + 0.13f, z + 0.05f, r, g, b, a);
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