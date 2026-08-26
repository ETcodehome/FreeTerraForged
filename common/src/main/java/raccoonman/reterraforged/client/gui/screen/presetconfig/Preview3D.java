package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import raccoonman.reterraforged.data.worldgen.preset.settings.SpawnType;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public class Preview3D extends Button implements IPreviewHandler {
    public static final int SIZE = IPreviewHandler.SIZE;

    // Statically cached backing texture shared across widget instances
    private static DynamicTexture STATIC_TEXTURE_CACHE;
    private static ResourceLocation STATIC_CACHE_LOCATION;

    private final PresetEditorPage page;
    private final PreviewState state = new PreviewState();

    private RenderMode currentMode = RenderMode.BIOME;

    private boolean needsTextureRefresh = false;
    private volatile int[] pendingTexturePixels;
    private volatile int pendingTextureWidth;
    private volatile int pendingTextureHeight;

    private int lastHoveredIx = -1;
    private int lastHoveredIz = -1;

    public Preview3D(PresetEditorPage page, int x, int y, int width, int height) {
        super(x, y, width, height, CommonComponents.EMPTY, IPreviewHandler.onPress(), DEFAULT_NARRATION);
        this.page = page;
        this.state.cacheKey = BiomePreview.cacheKey(page.getScreen().getSettings(), page.preset.getPreset());
    }

    @Override
    public PreviewState state() {
        return this.state;
    }

    @Override
    public PresetEditorPage page() {
        return this.page;
    }

    @Override
    public Button widget() {
        return this;
    }

    @Override
    public void playClickSound() {
        this.playDownSound(Minecraft.getInstance().getSoundManager());
    }

    @Override
    public String getFailureLabel() {
        return "3D";
    }

    @Override
    public void onRenderModeChanged(RenderMode mode) {
        this.currentMode = mode;
    }

    @Override
    public RenderMode getRenderMode() {
        return this.page.renderMode3D == null ? this.currentMode : this.page.renderMode3D.getValue();
    }

    @Override
    public int getZoom() {
        return NoiseUtil.round(1.5F * (101 - (float) this.page.zoom3D.getLerpedValue()));
    }

    @Override
    public boolean hasZoomControl() {
        return this.page.zoom3D != null;
    }

    @Override
    public double getZoomValue() {
        return this.page.zoom3D.getValue();
    }

    @Override
    public void setZoomValue(double value) {
        this.page.zoom3D.setValue(value);
    }

    @Override
    public void applyZoomValue() {
        this.page.zoom3D.applyValue();
    }

    @Override
    public RasterParams captureRasterParams() {
        double zoomValue = this.page.zoom3D == null ? 95.0D : this.page.zoom3D.getLerpedValue();
        return new RasterParams(this.width, this.height, zoomValue);
    }

    @Override
    public boolean rasterizationBlocked() {
        return this.width <= 0 || this.height <= 0;
    }

    @Override
    public int[] createRasterData(Tile activeTile, BiomePreview.Sidecar activeBiomes, RenderMode mode, Levels levels, WorldSettings.Properties properties, RasterParams params) {
        int width = params.width;
        int height = params.height;
        if (activeTile == null || width <= 0 || height <= 0) return new int[0];
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, 0xFF000000);

        int tileSize = activeTile.getBlockSize().size();
        float rawBlockW = (float) width / (float) tileSize * 0.85f;
        int halfW = Math.max(1, (int) (rawBlockW / 2.0f));
        int halfH = Math.max(1, halfW / 2);
        int blockW = halfW * 2;
        int blockH = halfH * 2;
        int centerVisualX = width / 2;
        int centerVisualY = height / 2;
        float heightScale = getHeightScale((float) blockW, params.zoom);
        int halfTile = tileSize / 2;
        float maxCellHeight = properties.worldHeight * levels.unit;
        float[] hsb = new float[3];

        for (int iz = 0; iz < tileSize; iz++) {
            for (int ix = 0; ix < tileSize; ix++) {
                Cell cell = activeTile.lookup(ix, iz);
                float effectiveHeight = cell.height;
                int color;
                if (levels.scale(cell.height) > properties.worldHeight) {
                    color = 0xFFFF00FF;
                    effectiveHeight = maxCellHeight;
                } else {
                    color = mode.getColor(cell, levels, activeBiomes == null ? 0xFFFF00FF : activeBiomes.color(ix, iz));
                }
                int r = color & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = (color >> 16) & 0xFF;
                Color.RGBtoHSB(r, g, b, hsb);
                int hash = ix * 31 + iz * 17;
                float jitter = ((hash % 100) / 100.0f) * 0.06f - 0.03f;
                hsb[2] = Math.max(0.0f, Math.min(1.0f, hsb[2] + jitter));
                int jitteredRgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
                int jitteredColor = (color & 0xFF000000)
                        | (jitteredRgb >> 16 & 0xFF)
                        | (jitteredRgb >> 8 & 0xFF) << 8
                        | (jitteredRgb & 0xFF) << 16;
                int dx = ix - halfTile;
                int dz = iz - halfTile;
                int isoX = centerVisualX + (dx - dz) * halfW;
                int isoY = centerVisualY + (dx + dz) * halfH;
                int renderY = isoY - Math.round(effectiveHeight * heightScale);
                int topColor = jitteredColor;
                int leftColor = getSideColor(jitteredColor, 0.75f, true, ix, iz, tileSize);
                int rightColor = getSideColor(jitteredColor, 0.60f, false, ix, iz, tileSize);
                fillPixelRect(pixels, width, isoX, renderY, isoX + blockW, renderY + blockH, topColor);
                fillPixelRect(pixels, width, isoX, renderY + blockH, isoX + halfW, isoY + blockH, leftColor);
                fillPixelRect(pixels, width, isoX + halfW, renderY + blockH, isoX + blockW, isoY + blockH, rightColor);
            }
        }
        return pixels;
    }

    @Override
    public void applyGeneratedFrame(FrameResult result) {
        if (result.rasterWidth == this.width && result.rasterHeight == this.height) {
            this.pendingTexturePixels = result.rasterPayload;
            this.pendingTextureWidth = result.rasterWidth;
            this.pendingTextureHeight = result.rasterHeight;
            this.needsTextureRefresh = true;
        } else {
            requestRasterization();
        }
    }

    @Override
    public void applyRasterizedPixels(int[] pixels, RasterParams params) {
        this.pendingTexturePixels = pixels;
        this.pendingTextureWidth = params.width;
        this.pendingTextureHeight = params.height;
        this.needsTextureRefresh = true;
    }

    @Override
    public void onFrameApplied() {
        this.lastHoveredIx = -1;
        this.lastHoveredIz = -1;
    }

    private static void fillPixelRect(int[] pixels, int width, int xStart, int yStart, int xEnd, int yEnd, int nativeColor) {
        int startX = Math.max(0, xStart);
        int endX = Math.min(width, xEnd);
        int startY = Math.max(0, yStart);
        int endY = Math.min(pixels.length / width, yEnd);

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                pixels[y * width + x] = nativeColor;
            }
        }
    }

    private void uploadPendingTexture() {
        int[] pixels = this.pendingTexturePixels;
        if (pixels == null || this.pendingTextureWidth <= 0 || this.pendingTextureHeight <= 0) return;

        if (STATIC_TEXTURE_CACHE == null
                || STATIC_TEXTURE_CACHE.getPixels().getWidth() != this.pendingTextureWidth
                || STATIC_TEXTURE_CACHE.getPixels().getHeight() != this.pendingTextureHeight) {

            if (STATIC_TEXTURE_CACHE != null) {
                STATIC_TEXTURE_CACHE.close();
                Minecraft.getInstance().getTextureManager().release(STATIC_CACHE_LOCATION);
            }
            STATIC_TEXTURE_CACHE = new DynamicTexture(new NativeImage(this.pendingTextureWidth, this.pendingTextureHeight, true));
            STATIC_CACHE_LOCATION = Minecraft.getInstance().getTextureManager().register("rtf_preview_cache_3d", STATIC_TEXTURE_CACHE);
        }

        NativeImage image = STATIC_TEXTURE_CACHE.getPixels();
        for (int y = 0; y < this.pendingTextureHeight; y++) {
            for (int x = 0; x < this.pendingTextureWidth; x++) {
                image.setPixelRGBA(x, y, pixels[y * this.pendingTextureWidth + x]);
            }
        }
        STATIC_TEXTURE_CACHE.upload();
        this.pendingTexturePixels = null;
        this.needsTextureRefresh = false;
    }

    private static int darkenColor(int argb, float factor) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.max(0, (int) (((argb >> 16) & 0xFF) * factor));
        int g = Math.max(0, (int) (((argb >> 8) & 0xFF) * factor));
        int b = Math.max(0, (int) ((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public void closeResources() {
        // Keep STATIC_TEXTURE_CACHE alive across page rebuilds.
    }

    @Override
    public String buildLegendLine(String labelStr, String value) {
        return value + " \u00a77(" + labelStr + ")";
    }

    @Override
    public int legendLineX(Font font, String line, float maxWidth) {
        return (int) (maxWidth - font.width(line));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (handleClick(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (handleScroll(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mx, int my, float partialTicks) {
        int x = this.getX();
        int y = this.getY();

        if (this.state.previewFailure != null) {
            PreviewFailure.renderUnavailable(guiGraphics, x, y, this.width, this.height);
            return;
        }

        if (this.state.tile != null && this.needsTextureRefresh) {
            this.uploadPendingTexture();
        }

        if (STATIC_CACHE_LOCATION != null) {
            guiGraphics.blit(STATIC_CACHE_LOCATION, x, y, 0.0F, 0.0F, this.width, this.height, this.width, this.height);
        } else {
            guiGraphics.fill(x, y, x + this.width, y + this.height, 0xFF000000);
        }

        renderSpawnMarker(guiGraphics);
        BiomePreview.Sidecar activeBiomes = this.state.biomes;
        if (activeBiomes != null && activeBiomes.warning() != null) {
            guiGraphics.drawCenteredString(
                    Minecraft.getInstance().font,
                    activeBiomes.warning(),
                    x + this.width / 2,
                    y + 4,
                    0xFFFF5555
            );
        }
        updateLegend(mx, my);
        renderLegend(guiGraphics, mx, my, this.state.legendLabels, this.state.legendValues, x, y + this.width + 30, 10, 0xFFFFFF);
    }

    private static float getHeightScale(float blockW, double zoomValue) {
        float zoomProgress = (float) (zoomValue - 1.0D) / 99.0f;
        float biasedProgress = zoomProgress * zoomProgress;
        float minBlockScale = 3.0f;
        float maxBlockScale = 35.0f;
        return blockW * (minBlockScale + (biasedProgress * (maxBlockScale - minBlockScale)));
    }

    private static int getSideColor(int cellColor, float shadeFactor, boolean isLeftFace, int ix, int iz, int tileSize) {
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
            Tile activeTile = this.state.tile;
            int tileSize = activeTile != null ? activeTile.getBlockSize().size() : 0;

            if (tileSize > 0) {
                int activeCX = this.state.centerX;
                int activeCZ = this.state.centerZ;

                int ix = NoiseUtil.round(((float) (props.spawnX - activeCX) / zoomValue) + (tileSize / 2.0f));
                int iz = NoiseUtil.round(((float) (props.spawnZ - activeCZ) / zoomValue) + (tileSize / 2.0f));

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
                    int isoY = centerVisualY + (dx + dz) * halfH - Math.round(cell.height * getHeightScale((float) blockW, this.page.zoom3D.getLerpedValue()));

                    int markerX = isoX + halfW;
                    int markerY = isoY + halfH;

                    int size = 6;
                    int color = 0xFFFFFFFF;
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
            this.height = height;
            requestRasterization();
        }
    }

    @Override
    public boolean updateLegend(int mx, int my) {
        Tile activeTile = this.state.tile;
        BiomePreview.Sidecar activeBiomes = this.state.biomes;
        if (activeTile != null) {
            int left = this.getX();
            int top = this.getY();

            int zoomValue = this.getZoom();
            int tileSize = activeTile.getBlockSize().size();

            int totalWidth = Math.max(1, tileSize * zoomValue);
            int totalHeight = Math.max(1, tileSize * zoomValue);
            this.state.legendValues[0] = totalWidth + "x" + totalHeight;

            if (mx < left || mx >= left + this.width || my < top || my >= top + this.height) {
                this.state.hoveredCoords = "";
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
                    this.state.legendValues[1] = IPreviewHandler.getTerrainName(cell);
                    String biomeId = activeBiomes == null ? null : activeBiomes.id(ix, iz);
                    WorldSettings.Properties properties = this.page.preset.getPreset().world().properties;
                    PreviewDetails.Detail detail = PreviewDetails.forCell(
                            getRenderMode(), cell,
                            new Levels(properties.terrainScaler(), properties.worldHeight, properties.worldDepth, properties.seaLevel),
                            biomeId
                    );
                    this.state.legendLabels[2] = detail.label();
                    this.state.legendValues[2] = detail.value();

                    int activeCX = this.state.centerX;
                    int activeCZ = this.state.centerZ;
                    this.state.legendValues[3] = getSpawnCoords(activeCX, activeCZ);

                    int worldOffsetX = (ix - (tileSize / 2)) * zoomValue;
                    int worldOffsetZ = (iz - (tileSize / 2)) * zoomValue;

                    this.state.hoveredCoords = (activeCX + worldOffsetX) + ":" + (activeCZ + worldOffsetZ);
                    this.state.hoveredCoordX = activeCX + worldOffsetX;
                    this.state.hoveredCoordZ = activeCZ + worldOffsetZ;
                }
                return true;
            } else {
                this.state.hoveredCoords = "";
                this.lastHoveredIx = -1;
                this.lastHoveredIz = -1;
            }
        }
        return false;
    }

    public static void resetToBlack() {
        if (STATIC_TEXTURE_CACHE != null) {
            NativeImage pixels = STATIC_TEXTURE_CACHE.getPixels();
            if (pixels != null) {
                pixels.fillRect(0, 0, pixels.getWidth(), pixels.getHeight(), 0xFF000000);
                STATIC_TEXTURE_CACHE.upload();
            }
        }
    }
}
