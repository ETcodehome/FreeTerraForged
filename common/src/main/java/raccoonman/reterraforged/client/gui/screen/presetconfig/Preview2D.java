package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
import java.util.concurrent.CompletableFuture;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

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

public class Preview2D extends Button {
    private static final int FACTOR = 4;
    public static final int SIZE = (1 << 4) << FACTOR;
    private static final float[] LEGEND_SCALES = { 1, 0.9F, 0.75F, 0.6F };

    // Static cache to hold pixels between UI page transitions to prevent black flickering
    private static int[] LAST_SUCCESSFUL_PIXELS = null;

    private final PresetEditorPage page;
    private final DynamicTexture texture = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
    private final ResourceLocation textureId = Minecraft.getInstance().getTextureManager().register(RTFCommon.MOD_ID + "-preview-framebuffer", this.texture);

    private Tile tile;
    private int centerX, centerZ;
    private int hoveredCoordX = 0;
    private int hoveredCoordZ = 0;
    private String hoveredCoords = "";
    private final String[] legendValues = {"", "", "", ""};
    private final Component[] legendLabels = {
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_AREA),
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_TERRAIN),
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_BIOME),
            Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_SPAWN)
    };

    private int offsetX, offsetZ;

    private CompletableFuture<FrameResult> pendingGeneration = null;

    // State Gates
    private boolean isRunning = false;
    private boolean isDirty = false;
    private boolean closed = false;

    public Preview2D(PresetEditorPage parent, int x, int y, int width, int height) {
        super(x, y, width, height, CommonComponents.EMPTY, (b) -> {
            if (b instanceof Preview2D self) {
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
        this.page = parent;

        NativeImage pixels = this.texture.getPixels();
        if (pixels != null) {
            // If we have cached pixels from a previous page view, populate immediately to hide the loading window
            if (LAST_SUCCESSFUL_PIXELS != null && LAST_SUCCESSFUL_PIXELS.length == SIZE * SIZE) {
                for (int bz = 0; bz < SIZE; bz++) {
                    for (int bx = 0; bx < SIZE; bx++) {
                        pixels.setPixelRGBA(bx, bz, LAST_SUCCESSFUL_PIXELS[bz * SIZE + bx]);
                    }
                }
            } else {
                pixels.fillRect(0, 0, SIZE, SIZE, 0xFF000000);
            }
            this.texture.upload();
        }
    }

    public void regenerate() {
        this.isDirty = true;

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
        Preset presetObj = presets.getOrThrow(Preset.KEY).value();
        WorldSettings.Properties properties = presetObj.world().properties;

        int seed = (int) settings.options().seed();
        int zoomLevel = this.getZoom();
        int localOffsetX = this.offsetX;
        int localOffsetZ = this.offsetZ;
        RenderMode mode = this.page.renderMode2D.getValue();
        Levels levels = new Levels(properties.terrainScaler(), properties.seaLevel);

        // Stage 1: Run clear, config loading, and structure lookups off the main thread
        CompletableFuture<PreGenContext> setupStage = CompletableFuture.supplyAsync(() -> {
            try {
                CacheManager.clear();
            } catch (Exception e) {
                e.printStackTrace();
            }
            PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
                    .resultOrPartial(RTFCommon.LOGGER::error)
                    .orElseGet(PerformanceConfig::makeDefault);

            GeneratorContext generatorContext = GeneratorContext.makeUncached(presetObj, noises, seed, FACTOR, 0, config.batchCount());

            int cx = 0;
            int cz = 0;
            if (presetObj.world().properties.spawnType == SpawnType.CONTINENT_CENTER) {
                long nearestContinentCenter = generatorContext.lookup.getHeightmap().continent().getNearestCenter(localOffsetX, localOffsetZ);
                cx = PosUtil.unpackLeft(nearestContinentCenter);
                cz = PosUtil.unpackRight(nearestContinentCenter);
            } else if (presetObj.world().properties.spawnType == SpawnType.USER_SELECTED) {
                cx = presetObj.world().properties.spawnX;
                cz = presetObj.world().properties.spawnZ;
            }

            return new PreGenContext(generatorContext, cx, cz, zoomLevel);
        }, net.minecraft.Util.backgroundExecutor());

        // Stage 2: Handle calculation maps and evaluate visual color tables entirely on worker pool
        this.pendingGeneration = setupStage.thenCompose(preGen ->
                preGen.context.generator.generateZoomed(preGen.cx, preGen.cz, preGen.zoomLevel, false)
                        .thenApply(newTile -> {
                            int stroke = 2;
                            int tileWidth = newTile.getBlockSize().size();
                            int[] bufferedPixels = new int[tileWidth * tileWidth];

                            newTile.iterate((cell, bx, bz) -> {
                                int color;
                                if (bx < stroke || bz < stroke || bx >= tileWidth - stroke || bz >= tileWidth - stroke) {
                                    color = 0xFF000000; // Opaque Black
                                } else {
                                    color = mode.getColor(cell, levels);
                                }
                                bufferedPixels[bz * tileWidth + bx] = color;
                            });

                            return new FrameResult(newTile, preGen.cx, preGen.cz, bufferedPixels);
                        })
        );

        // Stage 3: Return safely back onto the primary Minecraft render thread for GL transfers
        this.pendingGeneration.whenCompleteAsync((result, throwable) -> {
            this.isRunning = false;

            if (this.closed) return;

            if (throwable != null) {
                RTFCommon.LOGGER.error("Failed handling 2D preview generation pipeline", throwable);
            } else if (result != null && result.tile != null) {
                this.tile = result.tile;
                this.centerX = result.centerX;
                this.centerZ = result.centerZ;
                this.legendValues[3] = getSpawnCoords();

                // Safe structural upload across to GPU
                NativeImage pixels = this.texture.getPixels();
                if (pixels != null && result.pixelData != null) {
                    int tileWidth = this.tile.getBlockSize().size();

                    // Maintain global static cache frame arrays
                    if (LAST_SUCCESSFUL_PIXELS == null || LAST_SUCCESSFUL_PIXELS.length != result.pixelData.length) {
                        LAST_SUCCESSFUL_PIXELS = new int[result.pixelData.length];
                    }
                    System.arraycopy(result.pixelData, 0, LAST_SUCCESSFUL_PIXELS, 0, result.pixelData.length);

                    for (int bz = 0; bz < tileWidth; bz++) {
                        for (int bx = 0; bx < tileWidth; bx++) {
                            pixels.setPixelRGBA(bx, bz, result.pixelData[bz * tileWidth + bx]);
                        }
                    }
                    this.texture.upload();
                }
            }

            // Consume trailing-edge loop calls if input shifted during calculations
            if (this.isDirty) {
                this.executeRegenerate();
            }
        }, Minecraft.getInstance());
    }

    public void close() throws Exception {
        this.closed = true;
        if (this.pendingGeneration != null) {
            this.pendingGeneration.cancel(true);
        }
        this.texture.close();
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
            if (this.page.zoom2D != null) {
                double currentVal = this.page.zoom2D.getValue();
                double step = 0.05;
                if (scrollY > 0) {
                    this.page.zoom2D.setValue(Math.min(1.0, currentVal + step));
                } else if (scrollY < 0) {
                    this.page.zoom2D.setValue(Math.max(0.0, currentVal - step));
                }
                this.regenerate();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mx, int my, float partialTicks) {
        int xPos = this.getX();
        int yPos = this.getY();

        // Render the image if we have local tile data OR static background frame history ready to display
        if (this.tile == null && LAST_SUCCESSFUL_PIXELS == null) {
            guiGraphics.fill(xPos, yPos, xPos + this.width, yPos + this.height, 0xFF000000);
        } else {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            guiGraphics.blit(this.textureId, xPos, yPos, 0, 0, this.width, this.height, this.width, this.height);
        }

        renderSpawnMarker(guiGraphics);
        this.updateLegend(mx, my);
        this.renderLegend(guiGraphics, mx, my, this.legendLabels, this.legendValues, xPos, yPos + this.width + 30, 10, 0xFFFFFF);
    }

    private void renderSpawnMarker(GuiGraphics guiGraphics) {
        WorldSettings.Properties props = this.page.preset.getPreset().world().properties;

        if (props.spawnType == SpawnType.USER_SELECTED || props.spawnType == SpawnType.CONTINENT_CENTER) {
            int currentZoom = this.getZoom();

            if (this.tile != null) {
                float relX = (float) (props.spawnX - this.centerX) / (this.tile.getBlockSize().size() * currentZoom);
                float relZ = (float) (props.spawnZ - this.centerZ) / (this.tile.getBlockSize().size() * currentZoom);

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

    private boolean updateLegend(int mx, int my) {
        if (this.tile != null) {
            int left = this.getX();
            int top = this.getY();
            float size = this.width;

            int currentZoom = this.getZoom();
            int width = Math.max(1, this.tile.getBlockSize().size() * currentZoom);
            int height = Math.max(1, this.tile.getBlockSize().size() * currentZoom);
            this.legendValues[0] = width + "x" + height;
            if (mx >= left && mx <= left + size && my >= top && my <= top + size) {
                float fx = (mx - left) / size;
                float fz = (my - top) / size;
                int ix = NoiseUtil.round(fx * this.tile.getBlockSize().size());
                int iz = NoiseUtil.round(fz * this.tile.getBlockSize().size());
                Cell cell = this.tile.lookup(ix, iz);
                this.legendValues[1] = getTerrainName(cell);
                this.legendValues[2] = getBiomeName(cell);
                this.legendValues[3] = getSpawnCoords();

                int dx = (ix - (this.tile.getBlockSize().size() / 2)) * currentZoom;
                int dz = (iz - (this.tile.getBlockSize().size() / 2)) * currentZoom;

                this.hoveredCoords = (this.centerX + dx) + ":" + (this.centerZ + dz);
                this.hoveredCoordX = this.centerX + dx;
                this.hoveredCoordZ = this.centerZ + dz;
                return true;
            } else {
                this.hoveredCoords = "";
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

            String line = "\u00a77(" + labelStr + ")\u00a7r " + value;

            while (line.length() > 0 && renderer.width(line) > maxWidth) {
                line = line.substring(0, line.length() - 1);
            }

            int x = 0;
            guiGraphics.drawString(renderer, line, x, i * lineHeight, color);
        }

        pose.popPose();

        if (!this.hoveredCoords.isEmpty()) {
            guiGraphics.drawCenteredString(renderer, this.hoveredCoords, mx, my - 10, 0xFFFFFF);
        }
    }

    private int getZoom() {
        return NoiseUtil.round(1.5F * (101 - (float) this.page.zoom2D.getLerpedValue()));
    }

    private static String getTerrainName(Cell cell) {
        if (cell.terrain.isRiver()) {
            return "river";
        }
        return cell.terrain.getName().toLowerCase();
    }

    private String getSpawnCoords() {
        WorldSettings.Properties props = this.page.preset.getPreset().world().properties;

        if (props.spawnType == SpawnType.USER_SELECTED) {
            return "x" + props.spawnX + " z" + props.spawnZ;
        }
        if (props.spawnType == SpawnType.CONTINENT_CENTER || props.spawnType == SpawnType.ISLANDS) {
            return "~x" + this.centerX + " ~z" + this.centerZ;
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
        final int[] pixelData;

        FrameResult(Tile tile, int centerX, int centerZ, int[] pixelData) {
            this.tile = tile;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.pixelData = pixelData;
        }
    }
}