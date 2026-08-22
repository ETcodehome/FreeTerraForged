package raccoonman.reterraforged.client.gui.screen.presetconfig;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.SpawnType;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public class Preview2D extends Button implements IPreviewHandler {
    public static final int SIZE = IPreviewHandler.SIZE;

    // Statically cached backing texture shared across widget instances
    private static DynamicTexture STATIC_TEXTURE;
    private static ResourceLocation STATIC_TEXTURE_ID;

    private final PresetEditorPage page;
    private final PreviewState state = new PreviewState();

    public Preview2D(PresetEditorPage parent, int x, int y, int width, int height) {
        super(x, y, width, height, CommonComponents.EMPTY, IPreviewHandler.onPress(), DEFAULT_NARRATION);
        this.page = parent;
        this.state.cacheKey = BiomePreview.cacheKey(parent.getScreen().getSettings(), parent.preset.getPreset());

        // Ensure static texture is initialized
        getOrCreateTexture();
    }

    private static ResourceLocation getOrCreateTexture() {
        if (STATIC_TEXTURE == null) {
            STATIC_TEXTURE = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
            STATIC_TEXTURE_ID = Minecraft.getInstance().getTextureManager().register(
                    RTFCommon.MOD_ID + "-preview-framebuffer",
                    STATIC_TEXTURE
            );

            NativeImage pixels = STATIC_TEXTURE.getPixels();
            if (pixels != null) {
                pixels.fillRect(0, 0, SIZE, SIZE, 0xFF000000);
                STATIC_TEXTURE.upload();
            }
        }
        return STATIC_TEXTURE_ID;
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
    public void playClickSound() {
        this.playDownSound(Minecraft.getInstance().getSoundManager());
    }

    @Override
    public String getFailureLabel() {
        return "2D";
    }

    @Override
    public RenderMode getRenderMode() {
        return this.page.renderMode2D.getValue();
    }

    @Override
    public int getZoom() {
        return NoiseUtil.round(1.5F * (101 - (float) this.page.zoom2D.getLerpedValue()));
    }

    @Override
    public boolean hasZoomControl() {
        return this.page.zoom2D != null;
    }

    @Override
    public double getZoomValue() {
        return this.page.zoom2D.getValue();
    }

    @Override
    public void setZoomValue(double value) {
        this.page.zoom2D.setValue(value);
    }

    @Override
    public void applyZoomValue() {
        this.page.zoom2D.applyValue();
    }

    @Override
    public RasterParams captureRasterParams() {
        return new RasterParams(SIZE, SIZE, 0);
    }

    @Override
    public boolean rasterizationBlocked() {
        return false;
    }

    @Override
    public int[] createRasterData(Tile tile, BiomePreview.Sidecar biomes, RenderMode mode, Levels levels, WorldSettings.Properties properties, RasterParams params) {
        int stroke = 2;
        int tileWidth = tile.getBlockSize().size();
        int[] pixels = new int[tileWidth * tileWidth];
        tile.iterate((cell, bx, bz) -> {
            int color;
            if (bx < stroke || bz < stroke || bx >= tileWidth - stroke || bz >= tileWidth - stroke) {
                color = 0xFF000000;
            } else if (levels.scale(cell.height) > properties.worldHeight) {
                color = 0xFFFF00FF;
            } else {
                int biomeColor = biomes == null ? 0xFFFF00FF : biomes.color(bx, bz);
                color = mode.getColor(cell, levels, biomeColor);
            }
            pixels[bz * tileWidth + bx] = color;
        });
        return pixels;
    }

    @Override
    public void applyGeneratedFrame(FrameResult result) {
        uploadPixelData(result.rasterPayload);
    }

    @Override
    public void applyRasterizedPixels(int[] pixels, RasterParams params) {
        uploadPixelData(pixels);
    }

    private void uploadPixelData(int[] pixelData) {
        if (STATIC_TEXTURE == null) {
            getOrCreateTexture();
        }
        NativeImage pixels = STATIC_TEXTURE.getPixels();
        if (pixels == null || pixelData == null) return;

        int tileWidth = (int) Math.sqrt(pixelData.length);
        for (int bz = 0; bz < tileWidth; bz++) {
            for (int bx = 0; bx < tileWidth; bx++) {
                pixels.setPixelRGBA(bx, bz, pixelData[bz * tileWidth + bx]);
            }
        }
        STATIC_TEXTURE.upload();
    }

    @Override
    public String buildLegendLine(String labelStr, String value) {
        return "\u00a77(" + labelStr + ")\u00a7r " + value;
    }

    @Override
    public int legendLineX(Font font, String line, float maxWidth) {
        return 0;
    }

    @Override
    public void closeResources() {
        // Keep STATIC_TEXTURE alive across page rebuilds.
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
        int xPos = this.getX();
        int yPos = this.getY();

        if (this.state.previewFailure != null) {
            PreviewFailure.renderUnavailable(guiGraphics, xPos, yPos, this.width, this.height);
            return;
        }

        // Always blit the static texture (preserves the previous page's rendered frame
        // while the new page tile generates in the background)
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        guiGraphics.blit(getOrCreateTexture(), xPos, yPos, 0, 0, this.width, this.height, this.width, this.height);

        renderSpawnMarker(guiGraphics);
        if (this.state.biomes != null && this.state.biomes.warning() != null) {
            guiGraphics.drawCenteredString(
                    Minecraft.getInstance().font,
                    this.state.biomes.warning(),
                    xPos + this.width / 2,
                    yPos + 4,
                    0xFFFF5555
            );
        }
        updateLegend(mx, my);
        renderLegend(guiGraphics, mx, my, this.state.legendLabels, this.state.legendValues, xPos, yPos + this.width + 30, 10, 0xFFFFFF);
    }

    private void renderSpawnMarker(GuiGraphics guiGraphics) {
        WorldSettings.Properties props = this.page.preset.getPreset().world().properties;

        if (props.spawnType == SpawnType.USER_SELECTED || props.spawnType == SpawnType.CONTINENT_CENTER) {
            int currentZoom = this.getZoom();

            if (this.state.tile != null) {
                float relX = (float) (props.spawnX - this.state.centerX) / (this.state.tile.getBlockSize().size() * currentZoom);
                float relZ = (float) (props.spawnZ - this.state.centerZ) / (this.state.tile.getBlockSize().size() * currentZoom);

                int markerX = this.getX() + (this.width / 2) + (int) (relX * this.width);
                int markerY = this.getY() + (this.height / 2) + (int) (relZ * this.height);

                if (markerX >= this.getX() && markerX <= this.getX() + this.width &&
                        markerY >= this.getY() && markerY <= this.getY() + this.height) {

                    int size = 5;
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

    @Override
    public boolean updateLegend(int mx, int my) {
        if (this.state.tile != null) {
            int left = this.getX();
            int top = this.getY();
            float size = this.width;

            int currentZoom = this.getZoom();
            int width = Math.max(1, this.state.tile.getBlockSize().size() * currentZoom);
            int height = Math.max(1, this.state.tile.getBlockSize().size() * currentZoom);
            this.state.legendValues[0] = width + "x" + height;
            if (mx >= left && mx <= left + size && my >= top && my <= top + size) {
                float fx = (mx - left) / size;
                float fz = (my - top) / size;
                int maxIndex = this.state.tile.getBlockSize().size() - 1;
                int ix = Math.max(0, Math.min(maxIndex, NoiseUtil.round(fx * maxIndex)));
                int iz = Math.max(0, Math.min(maxIndex, NoiseUtil.round(fz * maxIndex)));
                Cell cell = this.state.tile.lookup(ix, iz);
                this.state.legendValues[1] = IPreviewHandler.getTerrainName(cell);
                String biomeId = this.state.biomes == null ? null : this.state.biomes.id(ix, iz);
                WorldSettings.Properties properties = this.page.preset.getPreset().world().properties;
                PreviewDetails.Detail detail = PreviewDetails.forCell(
                        getRenderMode(), cell, new Levels(properties.terrainScaler(), properties.worldHeight, properties.worldDepth, properties.seaLevel), biomeId
                );
                this.state.legendLabels[2] = detail.label();
                this.state.legendValues[2] = detail.value();
                this.state.legendValues[3] = getSpawnCoords();

                int dx = (ix - (this.state.tile.getBlockSize().size() / 2)) * currentZoom;
                int dz = (iz - (this.state.tile.getBlockSize().size() / 2)) * currentZoom;

                this.state.hoveredCoords = (this.state.centerX + dx) + ":" + (this.state.centerZ + dz);
                this.state.hoveredCoordX = this.state.centerX + dx;
                this.state.hoveredCoordZ = this.state.centerZ + dz;
                return true;
            } else {
                this.state.hoveredCoords = "";
            }
        }
        return false;
    }

    public static void resetToBlack() {
        if (STATIC_TEXTURE != null) {
            NativeImage pixels = STATIC_TEXTURE.getPixels();
            if (pixels != null) {
                pixels.fillRect(0, 0, SIZE, SIZE, 0xFF000000);
                STATIC_TEXTURE.upload();
            }
        }
    }
}