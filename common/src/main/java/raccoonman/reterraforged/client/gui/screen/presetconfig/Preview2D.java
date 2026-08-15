package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private static final long REFRESH_DEBOUNCE_MILLIS = 75L;

    // Static cache to hold pixels between UI page transitions to prevent black flickering
    private static int[] LAST_SUCCESSFUL_PIXELS = null;
    private static BiomePreview.CacheKey LAST_SUCCESSFUL_KEY = null;

    private final PresetEditorPage page;
    private final DynamicTexture texture = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
    private final ResourceLocation textureId = Minecraft.getInstance().getTextureManager().register(RTFCommon.MOD_ID + "-preview-framebuffer", this.texture);

    private Tile tile;
    private BiomePreview.Sidecar biomes;
    private BiomePreview.CacheKey cacheKey;
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

    static int globalOffsetX, globalOffsetZ;
    static boolean globalNavigated = false;

    private CompletableFuture<FrameResult> pendingGeneration = null;
    private volatile PreparedContext preparedContext;

    // State Gates
    private boolean isRunning = false;
    private boolean isDirty = false;
    private boolean closed = false;
    private long refreshRequestNanos = 0L;

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

                    self.globalNavigated = false;
                    self.page.regenerate();
                }
            }
        }, DEFAULT_NARRATION);
        this.page = parent;

        this.cacheKey = BiomePreview.cacheKey(parent.getScreen().getSettings(), parent.preset.getPreset());

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
        this.refreshRequestNanos = System.nanoTime();
        this.scheduleRegeneration();
    }

    private void scheduleRegeneration() {
        long requestNanos = this.refreshRequestNanos;
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestNanos);
        long delayMillis = Math.max(0L, REFRESH_DEBOUNCE_MILLIS - elapsedMillis);
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(() ->
            Minecraft.getInstance().execute(() -> {
                if (!this.closed && requestNanos == this.refreshRequestNanos && !this.isRunning) {
                    this.executeRegenerate();
                }
            })
        );
    }

    public void refreshRenderMode(RenderMode mode) {
        if (this.tile == null || (mode == RenderMode.BIOME && this.biomes == null)) {
            this.regenerate();
            return;
        }
        WorldSettings.Properties properties = this.page.preset.getPreset().world().properties;
        Levels levels = new Levels(properties.terrainScaler(), properties.worldDepth, properties.seaLevel);
        this.uploadPixelData(this.createPixelData(this.tile, this.biomes, mode, levels, properties));
    }

    private void executeRegenerate() {
        if (this.closed) return;

        this.isRunning = true;
        this.isDirty = false;

        WorldCreationContext settings = this.page.getScreen().getSettings();
        Preset requestedPreset = this.page.preset.getPreset();
        BiomePreview.CacheKey requestedKey = BiomePreview.cacheKey(settings, requestedPreset);
        PreparedContext reusable = this.preparedContext;
        HolderLookup.Provider provider = null;
        HolderGetter<Noise> noises = null;
        Preset presetObj = requestedPreset;
        if (reusable == null || !Objects.equals(reusable.cacheKey, requestedKey)) {
            RegistryAccess.Frozen registries = settings.worldgenLoadContext();
            provider = requestedPreset.buildPatch(registries);
            HolderGetter<Preset> presets = provider.lookupOrThrow(RTFRegistries.PRESET);
            noises = provider.lookupOrThrow(RTFRegistries.NOISE);
            presetObj = presets.getOrThrow(Preset.KEY).value();
        }
        HolderLookup.Provider preparedProvider = provider;
        HolderGetter<Noise> preparedNoises = noises;
        Preset preparedPreset = presetObj;
        if (!Objects.equals(this.cacheKey, requestedKey)) {
            this.cacheKey = requestedKey;
        }
        WorldSettings.Properties properties = presetObj.world().properties;

        int seed = (int) settings.options().seed();
        int zoomLevel = this.getZoom();
        int localOffsetX = this.globalOffsetX;
        int localOffsetZ = this.globalOffsetZ;
        boolean localNavigated = this.globalNavigated;
        RenderMode mode = this.page.renderMode2D.getValue();
        Levels levels = new Levels(properties.terrainScaler(), properties.worldDepth, properties.seaLevel);

        // Stage 1: Run clear, config loading, and structure lookups off the main thread
        CompletableFuture<PreGenContext> setupStage = CompletableFuture.supplyAsync(() -> {
            PreparedContext prepared = reusable;
            if (prepared == null || !Objects.equals(prepared.cacheKey, requestedKey)) {
                PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
                        .resultOrPartial(RTFCommon.LOGGER::error)
                        .orElseGet(PerformanceConfig::makeDefault);
                GeneratorContext generatorContext = GeneratorContext.makeUncached(
                    preparedPreset, preparedNoises, seed, FACTOR, 0, config.batchCount()
                );
                prepared = new PreparedContext(
                    requestedKey,
                    generatorContext,
                    BiomePreview.create(settings, preparedProvider, preparedPreset, generatorContext)
                );
                this.preparedContext = prepared;
            }
            GeneratorContext generatorContext = prepared.context;
            if (properties.spawnType == SpawnType.CONTINENT_CENTER) {
                long baseContinentCenter = generatorContext.lookup.getHeightmap().continent().getNearestCenter(0, 0);
                properties.spawnX = PosUtil.unpackLeft(baseContinentCenter);
                properties.spawnZ = PosUtil.unpackRight(baseContinentCenter);
            }

            int cx = 0;
            int cz = 0;

            // Generalize coordinate selection for all spawn types
            if (preparedPreset.world().properties.spawnType == SpawnType.CONTINENT_CENTER) {
                long nearestContinentCenter = generatorContext.lookup.getHeightmap().continent().getNearestCenter(
                        localNavigated ? localOffsetX : 0,
                        localNavigated ? localOffsetZ : 0
                );
                cx = PosUtil.unpackLeft(nearestContinentCenter);
                cz = PosUtil.unpackRight(nearestContinentCenter);
            } else {
                // If navigated, center on the clicked spot; otherwise fallback to spawn values or origin depending on type
                cx = localNavigated ? localOffsetX : (preparedPreset.world().properties.spawnType == SpawnType.USER_SELECTED ? preparedPreset.world().properties.spawnX : 0);
                cz = localNavigated ? localOffsetZ : (preparedPreset.world().properties.spawnType == SpawnType.USER_SELECTED ? preparedPreset.world().properties.spawnZ : 0);
            }

            return new PreGenContext(generatorContext, prepared.biomePreview, cx, cz, zoomLevel);
        }, net.minecraft.Util.backgroundExecutor());

        // Stage 2: Handle calculation maps and evaluate visual color tables entirely on worker pool
        this.pendingGeneration = setupStage.thenCompose(preGen ->
                preGen.context.generator.generateZoomed(preGen.cx, preGen.cz, preGen.zoomLevel, false)
                        .thenApply(newTile -> {
                            BiomePreview.Sidecar biomes = null;
                            if (mode == RenderMode.BIOME) {
                                biomes = preGen.biomePreview.resolve(
                                    newTile, preGen.cx, preGen.cz, preGen.zoomLevel, levels
                                );
                            }

                            int[] bufferedPixels = this.createPixelData(newTile, biomes, mode, levels, properties);

                            return new FrameResult(newTile, biomes, preGen.cx, preGen.cz, bufferedPixels);
                        })
        );

        // Stage 3: Return safely back onto the primary Minecraft render thread for GL transfers
        this.pendingGeneration.whenCompleteAsync((result, throwable) -> {
            this.isRunning = false;

            if (this.closed) return;

            if (throwable != null) {
                RTFCommon.LOGGER.error("Failed handling 2D preview generation pipeline", throwable);
            } else if (!this.isDirty && result != null && result.tile != null
                    && Objects.equals(requestedKey, this.cacheKey)
                    && mode == this.page.renderMode2D.getValue()) {
                Tile previousTile = this.tile;
                this.tile = result.tile;
                this.biomes = result.biomes;
                this.centerX = result.centerX;
                this.centerZ = result.centerZ;
                this.legendValues[3] = getSpawnCoords();

                // Safe structural upload across to GPU
                this.uploadPixelData(result.pixelData);
                if (previousTile != null && previousTile != result.tile) {
                    previousTile.close();
                }
            } else if (result != null && result.tile != null) {
                result.tile.close();
            }

            // Consume trailing-edge loop calls if input shifted during calculations
            if (this.isDirty) {
                this.scheduleRegeneration();
            }
        }, Minecraft.getInstance());
    }

    private int[] createPixelData(
        Tile tile,
        BiomePreview.Sidecar biomes,
        RenderMode mode,
        Levels levels,
        WorldSettings.Properties properties
    ) {
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

    private void uploadPixelData(int[] pixelData) {
        NativeImage pixels = this.texture.getPixels();
        if (pixels == null || pixelData == null) return;

        int tileWidth = (int) Math.sqrt(pixelData.length);
        if (LAST_SUCCESSFUL_PIXELS == null || LAST_SUCCESSFUL_PIXELS.length != pixelData.length) {
            LAST_SUCCESSFUL_PIXELS = new int[pixelData.length];
        }
        System.arraycopy(pixelData, 0, LAST_SUCCESSFUL_PIXELS, 0, pixelData.length);
        LAST_SUCCESSFUL_KEY = this.cacheKey;
        for (int bz = 0; bz < tileWidth; bz++) {
            for (int bx = 0; bx < tileWidth; bx++) {
                pixels.setPixelRGBA(bx, bz, pixelData[bz * tileWidth + bx]);
            }
        }
        this.texture.upload();
    }

    public void close() throws Exception {
        this.closed = true;
        if (this.pendingGeneration != null) {
            this.pendingGeneration.cancel(true);
        }
        this.texture.close();
        this.preparedContext = null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isMouseOver(mouseX, mouseY)) {
            // Right Click: Navigate to specific coordinates
            if (button == 1) {
                if (this.updateLegend((int) mouseX, (int) mouseY) && !this.hoveredCoords.isEmpty()) {
                    this.playDownSound(Minecraft.getInstance().getSoundManager());

                    WorldSettings.Properties props = this.page.preset.getPreset().world().properties;
                    if (props.spawnType == SpawnType.CONTINENT_CENTER) {
                        props.spawnType = SpawnType.USER_SELECTED;
                        if (this.page instanceof WorldSettingsPage worldPage) {
                            worldPage.spawnType.setValue(SpawnType.USER_SELECTED);
                        }
                    }

                    Preview2D.globalOffsetX = this.hoveredCoordX;
                    Preview2D.globalOffsetZ = this.hoveredCoordZ;
                    Preview2D.globalNavigated = true;
                    this.regenerate();
                    return true;
                }
            }
            // Middle Click: Reset to current spawn coordinates
            else if (button == 2) {
                this.playDownSound(Minecraft.getInstance().getSoundManager());
                Preview2D.globalNavigated = false;
                this.regenerate();
                return true;
            }
        }
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
        if (this.biomes != null && this.biomes.warning() != null) {
            guiGraphics.drawCenteredString(
                Minecraft.getInstance().font,
                this.biomes.warning(),
                xPos + this.width / 2,
                yPos + 4,
                0xFFFF5555
            );
        }
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
                int maxIndex = this.tile.getBlockSize().size() - 1;
                int ix = Math.max(0, Math.min(maxIndex, NoiseUtil.round(fx * maxIndex)));
                int iz = Math.max(0, Math.min(maxIndex, NoiseUtil.round(fz * maxIndex)));
                Cell cell = this.tile.lookup(ix, iz);
                this.legendValues[1] = getTerrainName(cell);
                String biomeId = this.biomes == null ? null : this.biomes.id(ix, iz);
                PreviewDetails.Detail detail = PreviewDetails.forCell(
                    this.page.renderMode2D.getValue(), cell, new Levels(
                        this.page.preset.getPreset().world().properties.terrainScaler(),
                        this.page.preset.getPreset().world().properties.worldDepth,
                        this.page.preset.getPreset().world().properties.seaLevel
                    ), biomeId
                );
                this.legendLabels[2] = detail.label();
                this.legendValues[2] = detail.value();
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

    private static class PreGenContext {
        final GeneratorContext context;
        final BiomePreview biomePreview;
        final int cx;
        final int cz;
        final int zoomLevel;

        PreGenContext(GeneratorContext context, BiomePreview biomePreview, int cx, int cz, int zoomLevel) {
            this.context = context;
            this.biomePreview = biomePreview;
            this.cx = cx;
            this.cz = cz;
            this.zoomLevel = zoomLevel;
        }
    }

    private static class PreparedContext {
        final BiomePreview.CacheKey cacheKey;
        final GeneratorContext context;
        final BiomePreview biomePreview;

        PreparedContext(BiomePreview.CacheKey cacheKey, GeneratorContext context, BiomePreview biomePreview) {
            this.cacheKey = cacheKey;
            this.context = context;
            this.biomePreview = biomePreview;
        }
    }

    private static class FrameResult {
        final Tile tile;
        final BiomePreview.Sidecar biomes;
        final int centerX;
        final int centerZ;
        final int[] pixelData;

        FrameResult(Tile tile, BiomePreview.Sidecar biomes, int centerX, int centerZ, int[] pixelData) {
            this.tile = tile;
            this.biomes = biomes;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.pixelData = pixelData;
        }
    }
}
