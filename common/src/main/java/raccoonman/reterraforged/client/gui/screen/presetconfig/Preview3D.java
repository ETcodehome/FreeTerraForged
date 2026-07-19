package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.platform.NativeImage;

import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.concurrent.cache.CacheManager;
import raccoonman.reterraforged.config.PerformanceConfig;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.SpawnType;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

public class Preview3D extends Button {
    private static final int FACTOR = 4;
    public static final int SIZE = (1 << 4) << FACTOR;
    private static final float[] LEGEND_SCALES = { 1, 0.9F, 0.75F, 0.6F };

    public static RenderMode currentMode = RenderMode.BIOME_TYPE;

    // Static cache fields to bridge across instances during page preset updates
    private static Tile LAST_GLOBAL_TILE = null;
    private static int LAST_GLOBAL_CENTER_X = 0;
    private static int LAST_GLOBAL_CENTER_Z = 0;

    private PresetEditorPage page;
    private Tile tile;
    private int centerX, centerZ;

    private int hoveredCoordX = 0;
    private int hoveredCoordZ = 0;
    private String hoveredCoords = "";
    private String[] legendValues = {"", "", "", ""};
    private final Component[] legendLabels = {
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_AREA),
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_TERRAIN),
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_BIOME),
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_SPAWN)
    };

    private int offsetX, offsetZ;

    private DynamicTexture textureCache;
    private ResourceLocation cacheLocation;
    private boolean needsTextureRefresh = false;

    private int lastHoveredIx = -1;
    private int lastHoveredIz = -1;

    private final float[] hsbCache = new float[3];

    private CompletableFuture<FrameResult> pendingGeneration = null;

    // Concurrency Gates
    private boolean isRunning = false;
    private boolean isDirty = false;
    private boolean closed = false;

    public Preview3D(PresetEditorPage page, int x, int y, int width, int height) {
        super(x, y, width, height, CommonComponents.EMPTY, (b) -> {
            if (b instanceof Preview3D self) {
                Minecraft mc = Minecraft.getInstance();
                double guiX = mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getWidth();
                double guiY = mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getHeight();

                if (self.updateLegend((int) guiX, (int) guiY) && !self.hoveredCoords.isEmpty()) {
                    self.playDownSound(Minecraft.getInstance().getSoundManager());
                    WorldSettings.Properties props = self.page.preset.getPreset().world().properties;
                    props.spawnType = SpawnType.USER_SELECTED;
                    props.spawnX = self.hoveredCoordX;
                    props.spawnZ = self.hoveredCoordZ;

                    if (self.page instanceof WorldSettingsPage worldPage) {
                        worldPage.spawnType.setValue(SpawnType.USER_SELECTED);
                    }

                    self.page.regenerate();
                }
            }
        }, DEFAULT_NARRATION);

        this.page = page;
    }

    public void regenerate() {
        this.isDirty = true;

        // If the worker is already running, it will automatically consume the dirty flag upon completion
        if (!this.isRunning) {
            this.executeRegenerate();
        }
    }

    private void executeRegenerate() {
        if (this.closed) return;

        this.isRunning = true;
        this.isDirty = false;

        WorldCreationContext settings = this.page.getScreen().getSettings();
        RegistryAccess.Frozen registries = settings.worldgenLoadContext();
        HolderLookup.Provider provider = this.page.preset.getPreset().buildPatch(registries);
        HolderGetter<Preset> presets = provider.lookupOrThrow(RTFRegistries.PRESET);
        HolderGetter<Noise> noises = provider.lookupOrThrow(RTFRegistries.NOISE);
        Preset currentPreset = presets.getOrThrow(Preset.KEY).value();

        int seed = (int) settings.options().seed();
        int zoomLevel = this.getZoom();
        int localOffsetX = this.offsetX;
        int localOffsetZ = this.offsetZ;

        // Step 1: Offload disk IO and heavy context calculations to background executor
        CompletableFuture<PreGenContext> setupStage = CompletableFuture.supplyAsync(() -> {
            try {
                CacheManager.clear();
            } catch (Exception e) {
                e.printStackTrace();
            }
            PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
                    .resultOrPartial(RTFCommon.LOGGER::error)
                    .orElseGet(PerformanceConfig::makeDefault);

            GeneratorContext generatorContext = GeneratorContext.makeUncached(currentPreset, noises, seed, FACTOR, 0, config.batchCount());

            int cx = 0;
            int cz = 0;
            if (currentPreset.world().properties.spawnType == SpawnType.CONTINENT_CENTER) {
                long nearestContinentCenter = generatorContext.lookup.getHeightmap().continent().getNearestCenter(localOffsetX, localOffsetZ);
                cx = PosUtil.unpackLeft(nearestContinentCenter);
                cz = PosUtil.unpackRight(nearestContinentCenter);
            } else if (currentPreset.world().properties.spawnType == SpawnType.USER_SELECTED) {
                cx = currentPreset.world().properties.spawnX;
                cz = currentPreset.world().properties.spawnZ;
            }

            return new PreGenContext(generatorContext, cx, cz, zoomLevel);
        }, net.minecraft.Util.backgroundExecutor());

        // Step 2: Compose into the chunk generator's pipeline
        this.pendingGeneration = setupStage.thenCompose(preGen ->
                preGen.context.generator.generateZoomed(preGen.cx, preGen.cz, preGen.zoomLevel, false)
                        .thenApply(tile -> new FrameResult(tile, preGen.cx, preGen.cz))
        );

        // Step 3: Handle execution complete back on the client main render thread
        this.pendingGeneration.whenCompleteAsync((result, throwable) -> {
            this.isRunning = false;

            if (this.closed) return;

            if (throwable != null) {
                RTFCommon.LOGGER.error("Failed handling 3D preview generation pipeline", throwable);
            } else if (result != null && result.tile != null) {
                this.tile = result.tile;
                this.centerX = result.centerX;
                this.centerZ = result.centerZ;

                // Sync context parameters out to global tracking pointers
                LAST_GLOBAL_TILE = result.tile;
                LAST_GLOBAL_CENTER_X = result.centerX;
                LAST_GLOBAL_CENTER_Z = result.centerZ;

                this.legendValues[3] = getSpawnCoords();
                this.needsTextureRefresh = true;

                this.lastHoveredIx = -1;
                this.lastHoveredIz = -1;
            }

            // If the user modified values while this task was running, consume the change state immediately
            if (this.isDirty) {
                this.executeRegenerate();
            }
        }, Minecraft.getInstance());
    }

    private void rebuildTexture() {
        Tile activeTile = this.tile != null ? this.tile : LAST_GLOBAL_TILE;
        if (activeTile == null || this.width <= 0 || this.height <= 0) return;

        if (this.textureCache == null || this.textureCache.getPixels().getWidth() != this.width || this.textureCache.getPixels().getHeight() != this.height) {
            if (this.textureCache != null) {
                this.textureCache.close();
                Minecraft.getInstance().getTextureManager().release(this.cacheLocation);
            }
            NativeImage img = new NativeImage(this.width, this.height, true);
            this.textureCache = new DynamicTexture(img);
            this.cacheLocation = Minecraft.getInstance().getTextureManager().register("rtf_preview_cache_" + this.hashCode(), this.textureCache);
        }

        NativeImage img = this.textureCache.getPixels();

        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setPixelRGBA(x, y, 0xFF000000);
            }
        }

        if (this.page.renderMode3D != null) {
            currentMode = this.page.renderMode3D.getValue();
        }
        RenderMode mode = currentMode;

        WorldSettings.Properties properties = this.page.preset.getPreset().world().properties;
        Levels levels = new Levels(properties.terrainScaler(), properties.seaLevel);

        int tileSize = activeTile.getBlockSize().size();
        float rawBlockW = (float) this.width / (float) tileSize * 0.85f;
        int halfW = Math.max(1, (int) (rawBlockW / 2.0f));
        int halfH = Math.max(1, halfW / 2);

        int blockW = halfW * 2;
        int blockH = halfH * 2;

        int centerVisualX = this.width / 2;
        int centerVisualY = this.height / 2;
        float heightScale = getHeightScale((float) blockW);
        int halfTile = tileSize / 2;

        for (int iz = 0; iz < tileSize; iz++) {
            for (int ix = 0; ix < tileSize; ix++) {
                Cell cell = activeTile.lookup(ix, iz);
                int color = mode.getColor(cell, levels);

                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;

                Color.RGBtoHSB(r, g, b, this.hsbCache);

                int hash = ix * 31 + iz * 17;
                float jitter = ((hash % 100) / 100.0f) * 0.06f - 0.03f;

                this.hsbCache[2] = Math.max(0.0f, Math.min(1.0f, this.hsbCache[2] + jitter));

                int jitteredColor = (color & 0xFF000000) | (Color.HSBtoRGB(this.hsbCache[0], this.hsbCache[1], this.hsbCache[2]) & 0x00FFFFFF);

                int dx = ix - halfTile;
                int dz = iz - halfTile;

                int isoX = centerVisualX + (dx - dz) * halfW;
                int isoY = centerVisualY + (dx + dz) * halfH;
                int renderY = isoY - Math.round(cell.height * heightScale);

                int topColor = jitteredColor;
                int leftColor = getSideColor(jitteredColor, 0.75f, true, ix, iz, tileSize);
                int rightColor = getSideColor(jitteredColor, 0.60f, false, ix, iz, tileSize);

                fillPixelRect(img, isoX, renderY, isoX + blockW, renderY + blockH, topColor);
                fillPixelRect(img, isoX, renderY + blockH, isoX + halfW, isoY + blockH, leftColor);
                fillPixelRect(img, isoX + halfW, renderY + blockH, isoX + blockW, isoY + blockH, rightColor);
            }
        }

        this.textureCache.upload();
        this.needsTextureRefresh = false;
    }

    private void fillPixelRect(NativeImage img, int xStart, int yStart, int xEnd, int yEnd, int nativeColor) {
        int startX = Math.max(0, xStart);
        int endX = Math.min(img.getWidth(), xEnd);
        int startY = Math.max(0, yStart);
        int endY = Math.min(img.getHeight(), yEnd);

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                img.setPixelRGBA(x, y, nativeColor);
            }
        }
    }

    private int darkenColor(int argb, float factor) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.max(0, (int) (((argb >> 16) & 0xFF) * factor));
        int g = Math.max(0, (int) (((argb >> 8) & 0xFF) * factor));
        int b = Math.max(0, (int) ((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public void close() throws Exception {
        this.closed = true;
        if (this.pendingGeneration != null) {
            this.pendingGeneration.cancel(true);
        }
        if (this.textureCache != null) {
            this.textureCache.close();
            Minecraft.getInstance().getTextureManager().release(this.cacheLocation);
            this.textureCache = null;
            this.cacheLocation = null;
        }
        try {
            CacheManager.clear();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.isMouseOver(mouseX, mouseY)) {
            if (this.page.zoom3D != null) {
                double currentVal = this.page.zoom3D.getValue();
                double step = 0.05;
                if (scrollY > 0) {
                    this.page.zoom3D.setValue(Math.min(1.0, currentVal + step));
                } else if (scrollY < 0) {
                    this.page.zoom3D.setValue(Math.max(0.0, currentVal - step));
                }
                this.regenerate();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mx, int my, float partialTicks) {
        int x = this.getX();
        int y = this.getY();

        // Check if either local tile or fallback history data exists to drive structural generation
        if (this.tile != null || LAST_GLOBAL_TILE != null) {
            if (this.needsTextureRefresh || this.textureCache == null) {
                this.rebuildTexture();
            }
        }

        if (this.cacheLocation != null) {
            guiGraphics.blit(this.cacheLocation, x, y, 0.0F, 0.0F, this.width, this.height, this.width, this.height);
        } else {
            guiGraphics.fill(x, y, x + this.width, y + this.height, 0xFF000000);
        }

        renderSpawnMarker(guiGraphics);
        this.updateLegend(mx, my);
        this.renderLegend(guiGraphics, mx, my, this.legendLabels, this.legendValues, x, y + this.width + 30, 10, 0xFFFFFF);
    }

    private float getHeightScale(float blockW) {
        float zoomProgress = (float) (this.page.zoom3D.getLerpedValue() - 1.0D) / 99.0f;
        float biasedProgress = zoomProgress * zoomProgress;
        float minBlockScale = 3.0f;
        float maxBlockScale = 35.0f;
        return blockW * (minBlockScale + (biasedProgress * (maxBlockScale - minBlockScale)));
    }

    private int getSideColor(int cellColor, float shadeFactor, boolean isLeftFace, int ix, int iz, int tileSize) {
        int baseColor = cellColor;
        if ((isLeftFace && iz == tileSize - 1) || (!isLeftFace && ix == tileSize - 1)) {
            baseColor = 0xFF4A3525;
        }
        return darkenColor(baseColor, shadeFactor);
    }

    private void renderSpawnMarker(GuiGraphics guiGraphics) {
        WorldSettings.Properties props = this.page.preset.getPreset().world().properties;

        if (props.spawnType == SpawnType.USER_SELECTED || props.spawnType == SpawnType.CONTINENT_CENTER) {
            int zoomValue = this.getZoom();
            Tile activeTile = this.tile != null ? this.tile : LAST_GLOBAL_TILE;
            int tileSize = activeTile != null ? activeTile.getBlockSize().size() : 0;

            if (tileSize > 0) {
                int activeCX = this.tile != null ? this.centerX : LAST_GLOBAL_CENTER_X;
                int activeCZ = this.tile != null ? this.centerZ : LAST_GLOBAL_CENTER_Z;

                int ix = NoiseUtil.round(((float)(props.spawnX - activeCX) / zoomValue) + (tileSize / 2.0f));
                int iz = NoiseUtil.round(((float)(props.spawnZ - activeCZ) / zoomValue) + (tileSize / 2.0f));

                if (ix >= 0 && ix < tileSize && iz >= 0 && iz < tileSize) {
                    Cell cell = activeTile.lookup(ix, iz);

                    float rawBlockW = (float) this.width / (float) tileSize * 0.85f;
                    int halfW = Math.max(1, (int) (rawBlockW / 2.0f));
                    int halfH = Math.max(1, halfW / 2);

                    int blockW = halfW * 2;
                    int blockH = halfH * 2;

                    int centerVisualX = this.getX() + (this.width / 2);
                    int centerVisualY = this.getY() + (this.height / 2);

                    int dx = ix - (tileSize / 2);
                    int dz = iz - (tileSize / 2);

                    int isoX = centerVisualX + (dx - dz) * halfW;
                    int isoY = centerVisualY + (dx + dz) * halfH - Math.round(cell.height * getHeightScale((float) blockW));

                    int markerX = isoX + halfW;
                    int markerY = isoY + halfH;

                    int size = 6;
                    int color = 0xFFFF2222;
                    int shadow = 0xFF000000;

                    guiGraphics.fill(markerX - size + 1, markerY + 1, markerX + size + 2, markerY + 2, shadow);
                    guiGraphics.fill(markerX - size, markerY, markerX + size + 1, markerY + 1, color);

                    guiGraphics.fill(markerX + 1, markerY - size + 1, markerX + 2, markerY + size + 2, shadow);
                    guiGraphics.fill(markerX, markerY - size, markerX + 1, markerY + size + 1, color);
                }
            }
        }
    }

    public void updateBounds(int x, int y, int width, int height) {
        this.setX(x);
        this.setY(y);

        if (this.width != width || this.height != height) {
            this.width = width;
            this.needsTextureRefresh = true;
        }
    }

    private boolean updateLegend(int mx, int my) {
        Tile activeTile = this.tile != null ? this.tile : LAST_GLOBAL_TILE;
        if (activeTile != null) {
            int left = this.getX();
            int top = this.getY();

            int zoomValue = this.getZoom();
            int tileSize = activeTile.getBlockSize().size();

            int totalWidth = Math.max(1, tileSize * zoomValue);
            int totalHeight = Math.max(1, tileSize * zoomValue);
            this.legendValues[0] = totalWidth + "x" + totalHeight;

            if (mx < left || mx >= left + this.width || my < top || my >= top + this.height) {
                this.hoveredCoords = "";
                this.lastHoveredIx = -1;
                this.lastHoveredIz = -1;
                return false;
            }

            float rawBlockW = (float) this.width / (float) tileSize * 0.85f;
            int halfW = Math.max(1, (int) (rawBlockW / 2.0f));
            int halfH = Math.max(1, halfW / 2);

            int centerVisualX = left + (this.width / 2);
            int centerVisualY = top + (this.height / 2);

            float relMouseX = mx - centerVisualX;
            float relMouseY = my - centerVisualY;

            int dx = NoiseUtil.round((relMouseX / halfW + relMouseY / halfH) / 2.0f);
            int dz = NoiseUtil.round((relMouseY / halfH - relMouseX / halfW) / 2.0f);

            int ix = dx + (tileSize / 2);
            int iz = dz + (tileSize / 2);

            if (ix >= 0 && ix < tileSize && iz >= 0 && iz < tileSize) {
                if (ix != this.lastHoveredIx || iz != this.lastHoveredIz) {
                    this.lastHoveredIx = ix;
                    this.lastHoveredIz = iz;

                    Cell cell = activeTile.lookup(ix, iz);
                    this.legendValues[1] = getTerrainName(cell);
                    this.legendValues[2] = getBiomeName(cell);

                    int activeCX = this.tile != null ? this.centerX : LAST_GLOBAL_CENTER_X;
                    int activeCZ = this.tile != null ? this.centerZ : LAST_GLOBAL_CENTER_Z;
                    this.legendValues[3] = getSpawnCoords(activeCX, activeCZ);

                    int worldOffsetX = (ix - (tileSize / 2)) * zoomValue;
                    int worldOffsetZ = (iz - (tileSize / 2)) * zoomValue;

                    this.hoveredCoords = (activeCX + worldOffsetX) + ":" + (activeCZ + worldOffsetZ);
                    this.hoveredCoordX = activeCX + worldOffsetX;
                    this.hoveredCoordZ = activeCZ + worldOffsetZ;
                }
                return true;
            } else {
                this.hoveredCoords = "";
                this.lastHoveredIx = -1;
                this.lastHoveredIz = -1;
            }
        }
        return false;
    }

    private float getLegendScale() {
        int index = this.page.getScreen().minecraft.options.guiScale().get() - 1;
        if (index < 0 || index >= LEGEND_SCALES.length) {
            index = LEGEND_SCALES.length - 1;
        }
        return LEGEND_SCALES[index];
    }

    private void renderLegend(GuiGraphics guiGraphics, int mx, int my, Component[] labels, String[] values, int left, int top, int lineHeight, int color) {
        float scale = this.getLegendScale();
        PoseStack pose = guiGraphics.pose();

        pose.pushPose();
        pose.translate(left + 3.75F * scale, top - lineHeight * (3.2F * scale), 0);
        pose.scale(scale, scale, 1);

        Minecraft mc = Minecraft.getInstance();
        Font renderer = mc.font;

        float maxWidth = (this.width - 4) / scale;

        for (int i = 0; i < labels.length && i < values.length; i++) {
            Component label = labels[i];
            String value = values[i];

            String labelStr = label.getString();
            if (labelStr.endsWith(": ")) {
                labelStr = labelStr.substring(0, labelStr.length() - 2);
            } else if (labelStr.endsWith(":")) {
                labelStr = labelStr.substring(0, labelStr.length() - 1);
            }

            String line = value + " \u00a77(" + labelStr + ")";

            while (line.length() > 0 && renderer.width(line) > maxWidth) {
                line = line.substring(0, line.length() - 1);
            }

            int x = (int) (maxWidth - renderer.width(line));
            guiGraphics.drawString(renderer, line, x, i * lineHeight, color);
        }

        pose.popPose();

        if (!this.hoveredCoords.isEmpty()) {
            guiGraphics.drawCenteredString(renderer, this.hoveredCoords, mx, my - 10, 0xFFFFFF);
        }
    }

    private int getZoom() {
        return NoiseUtil.round(1.5F * (101 - (float) this.page.zoom3D.getLerpedValue()));
    }

    private static String getTerrainName(Cell cell) {
        if (cell.terrain.isRiver()) {
            return "river";
        }
        return cell.terrain.getName().toLowerCase();
    }

    private String getSpawnCoords() {
        int activeCX = this.tile != null ? this.centerX : LAST_GLOBAL_CENTER_X;
        int activeCZ = this.tile != null ? this.centerZ : LAST_GLOBAL_CENTER_Z;
        return getSpawnCoords(activeCX, activeCZ);
    }

    private String getSpawnCoords(int cx, int cz) {
        WorldSettings.Properties props = this.page.preset.getPreset().world().properties;
        if (props.spawnType == SpawnType.USER_SELECTED) {
            return "x" + props.spawnX + " z" + props.spawnZ;
        }
        if (props.spawnType == SpawnType.CONTINENT_CENTER || props.spawnType == SpawnType.ISLANDS) {
            return "~x" + cx + " ~z" + cz;
        }
        return "x0 z0";
    }

    private static String getBiomeName(Cell cell) {
        String terrain = cell.terrain.getName().toLowerCase();
        if (terrain.contains("ocean")) {
            if (cell.temperature < 0.3F) {
                return "cold_" + terrain;
            }
            if (cell.temperature > 0.6F) {
                return "warm_" + terrain;
            }
            return terrain;
        }
        if (terrain.contains("river")) {
            return "river";
        }
        return cell.biome.name().toLowerCase();
    }

    private static class PreGenContext {
        final GeneratorContext context;
        final int cx;
        final int cz;
        final int zoomLevel;

        PreGenContext(GeneratorContext context, int cx, int cz, int zoomLevel) {
            this.context = context;
            this.cx = cx;
            this.cz = cz;
            this.zoomLevel = zoomLevel;
        }
    }

    private static class FrameResult {
        final Tile tile;
        final int centerX;
        final int centerZ;

        FrameResult(Tile tile, int centerX, int centerZ) {
            this.tile = tile;
            this.centerX = centerX;
            this.centerZ = centerZ;
        }
    }
}