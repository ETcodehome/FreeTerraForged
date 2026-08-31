package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

/**
 * Mutable state shared by every IPreviewHandler. Preview2D and Preview3D each own a
 * single instance of this class instead of duplicating the same fields, so all the shared
 * default logic on the interface has somewhere to read and write.
 */
final class PreviewState {
    final Component[] legendLabels = {
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_AREA),
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_TERRAIN),
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_BIOME),
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_SPAWN)
    };
    final String[] legendValues = {"", "", "", ""};

    Tile tile;
    PreviewComputationCache.TileLease tileLease;
    BiomePreview.Sidecar biomes;
    boolean generatedWithBiomePipeline;
    BiomePreview.CacheKey cacheKey;
    int centerX, centerZ;
    int hoveredCoordX, hoveredCoordZ;
    String hoveredCoords = "";

    CompletableFuture<IPreviewHandler.FrameResult> pendingGeneration;
    volatile PreviewCancellation generationCancellation;
    volatile PreviewFailure previewFailure;

    // On-demand rasterization (re-render current tile without a full regenerate)
    volatile PreviewCancellation rasterCancellation;
    final AtomicLong rasterRequestVersion = new AtomicLong();
    CompletableFuture<?> pendingRasterization;

    boolean isRunning;
    boolean isDirty;
    boolean closed;
    long refreshRequestNanos;
}
