package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private static final long REFRESH_DEBOUNCE_MILLIS = 75L;

	public static RenderMode currentMode = RenderMode.BIOME;

    // Static cache fields to bridge across instances during page preset updates
    private static Tile LAST_GLOBAL_TILE = null;
    private static BiomePreview.Sidecar LAST_GLOBAL_BIOMES = null;
    private static BiomePreview.CacheKey LAST_GLOBAL_KEY = null;
    private static int LAST_GLOBAL_CENTER_X = 0;
    private static int LAST_GLOBAL_CENTER_Z = 0;

    private PresetEditorPage page;
    private Tile tile;
    private BiomePreview.Sidecar biomes;
    private BiomePreview.CacheKey cacheKey;
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

    private DynamicTexture textureCache;
    private ResourceLocation cacheLocation;
    private boolean needsTextureRefresh = false;

    private int lastHoveredIx = -1;
    private int lastHoveredIz = -1;

    private final float[] hsbCache = new float[3];

    private CompletableFuture<FrameResult> pendingGeneration = null;
    private volatile PreparedContext preparedContext;

    // Concurrency Gates
    private boolean isRunning = false;
    private boolean isDirty = false;
    private boolean closed = false;
    private long refreshRequestNanos = 0L;

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

                    Preview2D.globalNavigated = false;
                    self.page.regenerate();
                }
            }
        }, DEFAULT_NARRATION);

        this.page = page;
        this.cacheKey = BiomePreview.cacheKey(page.getScreen().getSettings(), page.preset.getPreset());
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
        currentMode = mode;
        Tile activeTile = this.tile != null ? this.tile : LAST_GLOBAL_TILE;
        BiomePreview.Sidecar activeBiomes = this.tile != null ? this.biomes : LAST_GLOBAL_BIOMES;
        if (activeTile == null || (mode == RenderMode.BIOME && activeBiomes == null)) {
            this.regenerate();
            return;
        }
        this.needsTextureRefresh = true;
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
        Preset currentPreset = requestedPreset;
        if (reusable == null || !Objects.equals(reusable.cacheKey, requestedKey)) {
            RegistryAccess.Frozen registries = settings.worldgenLoadContext();
			provider = requestedPreset.buildFullPatch(registries);
            HolderGetter<Preset> presets = provider.lookupOrThrow(RTFRegistries.PRESET);
            noises = provider.lookupOrThrow(RTFRegistries.NOISE);
            currentPreset = presets.getOrThrow(Preset.KEY).value();
        }
        HolderLookup.Provider preparedProvider = provider;
        HolderGetter<Noise> preparedNoises = noises;
        Preset preparedPreset = currentPreset;
        if (!Objects.equals(this.cacheKey, requestedKey)) {
            this.cacheKey = requestedKey;
        }

        int seed = (int) settings.options().seed();
        int zoomLevel = this.getZoom();
        int localOffsetX = Preview2D.globalOffsetX;
        int localOffsetZ = Preview2D.globalOffsetZ;
        boolean localNavigated = Preview2D.globalNavigated;
        RenderMode requestedMode = this.page.renderMode3D == null ? currentMode : this.page.renderMode3D.getValue();

        // Step 1: Offload disk IO and heavy context calculations to background executor
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
            WorldSettings.Properties properties = preparedPreset.world().properties;
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

        // Step 2: Compose into the chunk generator's pipeline
        this.pendingGeneration = setupStage.thenCompose(preGen ->
                preGen.context.generator.generateZoomed(preGen.cx, preGen.cz, preGen.zoomLevel, false)
                        .thenApply(tile -> {
                            WorldSettings.Properties properties = preparedPreset.world().properties;
                            Levels levels = new Levels(properties.terrainScaler(), properties.worldDepth, properties.seaLevel);
                            BiomePreview.Sidecar biomes = null;
                            if (requestedMode == RenderMode.BIOME) {
                                biomes = preGen.biomePreview.resolve(
                                    tile, preGen.cx, preGen.cz, preGen.zoomLevel, levels
                                );
                            }
                            return new FrameResult(tile, biomes, preGen.cx, preGen.cz);
                        })
        );

        // Step 3: Handle execution complete back on the client main render thread
        this.pendingGeneration.whenCompleteAsync((result, throwable) -> {
            this.isRunning = false;

            if (this.closed) return;

            if (throwable != null) {
                RTFCommon.LOGGER.error("Failed handling 3D preview generation pipeline", throwable);
            } else if (!this.isDirty && result != null && result.tile != null
                    && Objects.equals(requestedKey, this.cacheKey)
                    && requestedMode == this.page.renderMode3D.getValue()) {
                Tile previousTile = this.tile != null ? this.tile : LAST_GLOBAL_TILE;
                this.tile = result.tile;
                this.biomes = result.biomes;
                this.centerX = result.centerX;
                this.centerZ = result.centerZ;

                // Sync context parameters out to global tracking pointers
                LAST_GLOBAL_TILE = result.tile;
                LAST_GLOBAL_BIOMES = result.biomes;
                LAST_GLOBAL_KEY = this.cacheKey;
                LAST_GLOBAL_CENTER_X = result.centerX;
                LAST_GLOBAL_CENTER_Z = result.centerZ;

                this.legendValues[3] = getSpawnCoords();
                this.needsTextureRefresh = true;

                this.lastHoveredIx = -1;
                this.lastHoveredIz = -1;
                if (previousTile != null && previousTile != result.tile) {
                    previousTile.close();
                }
            } else if (result != null && result.tile != null) {
                result.tile.close();
            }

            // If the user modified values while this task was running, consume the change state immediately
            if (this.isDirty) {
                this.scheduleRegeneration();
            }
        }, Minecraft.getInstance());
    }

    private void rebuildTexture() {
        Tile activeTile = this.tile != null ? this.tile : LAST_GLOBAL_TILE;
        BiomePreview.Sidecar activeBiomes = this.tile != null ? this.biomes : LAST_GLOBAL_BIOMES;
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
        Levels levels = new Levels(properties.terrainScaler(), properties.worldDepth, properties.seaLevel);

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

        float maxCellHeight = properties.worldHeight * levels.unit;

        for (int iz = 0; iz < tileSize; iz++) {
            for (int ix = 0; ix < tileSize; ix++) {
                Cell cell = activeTile.lookup(ix, iz);

                // Clamp the geometry height if it exceeds world height boundaries
                float effectiveHeight = cell.height;
                int color;
                if (levels.scale(cell.height) > properties.worldHeight) {
                    color = 0xFFFF00FF; // Missing asset purple (#FF00FF)
                    effectiveHeight = maxCellHeight;
                } else {
                    color = mode.getColor(cell, levels, activeBiomes == null ? 0xFFFF00FF : activeBiomes.color(ix, iz));
                }

                int r = color & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = (color >> 16) & 0xFF;
                Color.RGBtoHSB(r, g, b, this.hsbCache);

                int hash = ix * 31 + iz * 17;
                float jitter = ((hash % 100) / 100.0f) * 0.06f - 0.03f;
                this.hsbCache[2] = Math.max(0.0f, Math.min(1.0f, this.hsbCache[2] + jitter));
                int jitteredRgb = Color.HSBtoRGB(this.hsbCache[0], this.hsbCache[1], this.hsbCache[2]);
                int jitteredColor = (color & 0xFF000000)
                    | (jitteredRgb >> 16 & 0xFF)
                    | (jitteredRgb >> 8 & 0xFF) << 8
                    | (jitteredRgb & 0xFF) << 16;
                int dx = ix - halfTile;
                int dz = iz - halfTile;
                int isoX = centerVisualX + (dx - dz) * halfW;
                int isoY = centerVisualY + (dx + dz) * halfH;

                // We use effectiveHeight instead of cell.height to show when world height limits are truncating peaks
                int renderY = isoY - Math.round(effectiveHeight * heightScale);

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

        // Left click set spawn coords
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
        BiomePreview.Sidecar activeBiomes = this.tile != null ? this.biomes : LAST_GLOBAL_BIOMES;
        if (activeBiomes != null && activeBiomes.warning() != null) {
            guiGraphics.drawCenteredString(
                Minecraft.getInstance().font,
                activeBiomes.warning(),
                x + this.width / 2,
                y + 4,
                0xFFFF5555
            );
        }
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
            this.needsTextureRefresh = true;
        }
    }

    private boolean updateLegend(int mx, int my) {
        Tile activeTile = this.tile != null ? this.tile : LAST_GLOBAL_TILE;
        BiomePreview.Sidecar activeBiomes = this.tile != null ? this.biomes : LAST_GLOBAL_BIOMES;
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
                    String biomeId = activeBiomes == null ? null : activeBiomes.id(ix, iz);
                    WorldSettings.Properties properties = this.page.preset.getPreset().world().properties;
                    PreviewDetails.Detail detail = PreviewDetails.forCell(
                        this.page.renderMode3D.getValue(), cell,
                        new Levels(properties.terrainScaler(), properties.worldDepth, properties.seaLevel),
                        biomeId
                    );
                    this.legendLabels[2] = detail.label();
                    this.legendValues[2] = detail.value();

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

        FrameResult(Tile tile, BiomePreview.Sidecar biomes, int centerX, int centerZ) {
            this.tile = tile;
            this.biomes = biomes;
            this.centerX = centerX;
            this.centerZ = centerZ;
        }
    }
}
