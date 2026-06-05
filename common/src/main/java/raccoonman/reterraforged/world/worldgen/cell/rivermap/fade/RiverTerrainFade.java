package raccoonman.reterraforged.world.worldgen.cell.rivermap.fade;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public final class RiverTerrainFade {
    public static final float MOUNTAIN_VALLEY_FADE = 0.2F;
    public static final float MOUNTAIN_BANKS_FADE = 0.0F;
    public static final float MOUNTAIN_BED_FADE = 0.0F;
    private static final float MOUNTAIN_EDGE_SOFTNESS = 0.35F;
    private static final float TAG_FADE_THRESHOLD = 0.05F;

    private RiverTerrainFade() {
    }

    public static boolean isMountain(Cell cell) {
        return cell.terrain != null && cell.terrain.isMountain();
    }

    public static float heightFade(float height, float start, float end) {
        if (height <= start) {
            return 1.0F;
        }
        if (height >= end) {
            return 0.0F;
        }
        float alpha = 1.0F - (height - start) / (end - start);
        return NoiseUtil.interpHermite(NoiseUtil.clamp(alpha, 0.0F, 1.0F));
    }

    public static float mountainFade(Cell cell) {
        if (!isMountain(cell)) {
            return 0.0F;
        }
        float edge = NoiseUtil.clamp(cell.terrainRegionEdge, 0.0F, MOUNTAIN_EDGE_SOFTNESS);
        return NoiseUtil.interpHermite(edge / MOUNTAIN_EDGE_SOFTNESS);
    }

    public static float valleyFade(Cell cell, float heightFade) {
        float mountainFade = mountainFade(cell);
        return heightFade * NoiseUtil.lerp(1.0F, MOUNTAIN_VALLEY_FADE, mountainFade);
    }

    public static float banksFade(Cell cell, float heightFade) {
        float mountainFade = mountainFade(cell);
        return heightFade * NoiseUtil.lerp(1.0F, MOUNTAIN_BANKS_FADE, mountainFade);
    }

    public static float bedFade(Cell cell, float heightFade) {
        float mountainFade = mountainFade(cell);
        return heightFade * NoiseUtil.lerp(1.0F, MOUNTAIN_BED_FADE, mountainFade);
    }

    public static boolean canTagRiver(float banksFade, float bedFade) {
        return banksFade > TAG_FADE_THRESHOLD || bedFade > TAG_FADE_THRESHOLD;
    }
}