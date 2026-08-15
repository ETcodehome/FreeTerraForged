package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

	private RenderMode currentMode = RenderMode.BIOME;

    private PresetEditorPage page;
    private Tile tile;
    private PreviewComputationCache.TileLease tileLease;
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
    private volatile int[] pendingTexturePixels;
    private volatile int pendingTextureWidth;
    private volatile int pendingTextureHeight;
    private final AtomicLong textureRequestVersion = new AtomicLong();
    private volatile PreviewCancellation textureCancellation;
    private CompletableFuture<?> pendingTextureRasterization;

    private int lastHoveredIx = -1;
    private int lastHoveredIz = -1;

    private CompletableFuture<FrameResult> pendingGeneration = null;
    private volatile PreparedContext preparedContext;
    private volatile PreviewCancellation generationCancellation;

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
        PreviewCancellation previous = this.generationCancellation;
        if (previous != null) previous.cancel();
        this.generationCancellation = new PreviewCancellation();
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
        Tile activeTile = this.tile;
        BiomePreview.Sidecar activeBiomes = this.biomes;
        if (activeTile == null || (mode == RenderMode.BIOME && activeBiomes == null)) {
            this.regenerate();
            return;
        }
        this.requestTextureRasterization();
    }

    private void executeRegenerate() {
        if (this.closed) return;

        PreviewCancellation request = this.generationCancellation;
        if (request == null) {
            request = new PreviewCancellation();
            this.generationCancellation = request;
        }
        final PreviewCancellation cancellation = request;

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
        int renderWidth = this.width;
        int renderHeight = this.height;
        double renderZoom = this.page.zoom3D == null ? 95.0D : this.page.zoom3D.getLerpedValue();

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
                prepared = new PreparedContext(requestedKey, generatorContext, preparedProvider, preparedPreset);
                this.preparedContext = prepared;
            }
            if (requestedMode == RenderMode.BIOME) {
                prepared.ensureBiomePreview(settings);
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

            PreviewComputationCache.TileKey tileKey = new PreviewComputationCache.TileKey(
                requestedKey, cx, cz, zoomLevel, Preview3D.SIZE
            );
            return new PreGenContext(generatorContext, prepared.biomePreview(), cx, cz, zoomLevel, tileKey);
        }, net.minecraft.Util.backgroundExecutor());

        // Step 2: Compose into the chunk generator's pipeline
        this.pendingGeneration = setupStage.thenCompose(preGen -> {
            cancellation.check();
            PreviewComputationCache.TileLease cached = this.page.previewCache().acquire(preGen.tileKey);
            CompletableFuture<PreviewComputationCache.TileLease> tileFuture;
            if (cached != null) {
                tileFuture = CompletableFuture.completedFuture(cached);
            } else {
                tileFuture = preGen.context.generator.generateZoomed(
                    preGen.cx, preGen.cz, preGen.zoomLevel, false, cancellation::isCancelled
                ).thenApply(newTile -> {
                    PreviewComputationCache.TileLease stored = this.page.previewCache().store(preGen.tileKey, newTile);
                    if (stored == null) {
                        throw new java.util.concurrent.CancellationException("Preview cache closed");
                    }
                    return stored;
                });
            }
            return tileFuture.thenApply(lease -> {
                cancellation.check();
                Tile generatedTile = lease.tile();
                try {
                    WorldSettings.Properties properties = preparedPreset.world().properties;
                    Levels levels = new Levels(properties.terrainScaler(), properties.worldDepth, properties.seaLevel);
                    BiomePreview.Sidecar biomes = null;
                    if (requestedMode == RenderMode.BIOME) {
                        biomes = preGen.biomePreview.resolveCached(
                            this.page.previewCache(), generatedTile, preGen.cx, preGen.cz, preGen.zoomLevel, levels, cancellation
                        );
                    }
                    int[] texturePixels = createTexturePixels(
                        generatedTile, biomes, requestedMode, levels, properties, renderWidth, renderHeight, renderZoom
                    );
                    return new FrameResult(lease, biomes, preGen.cx, preGen.cz, texturePixels, renderWidth, renderHeight);
                } catch (Throwable throwable) {
                    lease.close();
                    throw throwable;
                }
            });
        });

        // Step 3: Handle execution complete back on the client main render thread
        this.pendingGeneration.whenCompleteAsync((result, throwable) -> {
            this.isRunning = false;

            if (this.closed) {
                if (result != null) result.lease.close();
                return;
            }

            if (throwable != null) {
                if (!PreviewCancellation.isCancellation(throwable)) {
                    RTFCommon.LOGGER.error("Failed handling 3D preview generation pipeline", throwable);
                }
            } else if (!this.isDirty && result != null && result.tile != null
                    && !cancellation.isCancelled()
                    && Objects.equals(requestedKey, this.cacheKey)
                    && requestedMode == this.page.renderMode3D.getValue()) {
                PreviewComputationCache.TileLease previousLease = this.tileLease;
                this.tileLease = result.lease;
                this.tile = result.lease.tile();
                this.biomes = result.biomes;
                this.centerX = result.centerX;
                this.centerZ = result.centerZ;

                this.legendValues[3] = getSpawnCoords();
                if (result.textureWidth == this.width && result.textureHeight == this.height) {
                    this.pendingTexturePixels = result.texturePixels;
                    this.pendingTextureWidth = result.textureWidth;
                    this.pendingTextureHeight = result.textureHeight;
                    this.needsTextureRefresh = true;
                } else {
                    this.requestTextureRasterization();
                }

                this.lastHoveredIx = -1;
                this.lastHoveredIz = -1;
                if (previousLease != null) previousLease.close();
            } else if (result != null && result.tile != null) {
                result.lease.close();
            }

            // If the user modified values while this task was running, consume the change state immediately
            if (this.isDirty) {
                this.scheduleRegeneration();
            }
        }, Minecraft.getInstance());
    }

    private int[] createTexturePixels(
        Tile activeTile,
        BiomePreview.Sidecar activeBiomes,
        RenderMode mode,
        Levels levels,
        WorldSettings.Properties properties,
        int width,
        int height,
        double zoomValue
    ) {
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
        float heightScale = getHeightScale((float) blockW, zoomValue);
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

    private void requestTextureRasterization() {
        PreviewComputationCache.TileLease current = this.tileLease;
        if (current == null || this.closed || this.width <= 0 || this.height <= 0) return;
        PreviewCancellation previous = this.textureCancellation;
        if (previous != null) previous.cancel();
        PreviewCancellation cancellation = new PreviewCancellation();
        this.textureCancellation = cancellation;
        long version = this.textureRequestVersion.incrementAndGet();
        PreviewComputationCache.TileLease retained = current.retain();
        RenderMode mode = this.page.renderMode3D == null ? currentMode : this.page.renderMode3D.getValue();
        WorldSettings.Properties properties = this.page.preset.getPreset().world().properties;
        Levels levels = new Levels(properties.terrainScaler(), properties.worldDepth, properties.seaLevel);
        int width = this.width;
        int height = this.height;
        double zoomValue = this.page.zoom3D == null ? 95.0D : this.page.zoom3D.getLerpedValue();
        BiomePreview.Sidecar sidecar = this.biomes;
        this.pendingTextureRasterization = CompletableFuture.supplyAsync(() -> {
            cancellation.check();
            return this.createTexturePixels(retained.tile(), sidecar, mode, levels, properties, width, height, zoomValue);
        }, net.minecraft.Util.backgroundExecutor()).whenCompleteAsync((pixels, throwable) -> {
            try {
                if (!this.closed && throwable == null && !cancellation.isCancelled()
                        && version == this.textureRequestVersion.get()
                        && mode == (this.page.renderMode3D == null ? currentMode : this.page.renderMode3D.getValue())) {
                    this.pendingTexturePixels = pixels;
                    this.pendingTextureWidth = width;
                    this.pendingTextureHeight = height;
                    this.needsTextureRefresh = true;
                }
            } finally {
                retained.close();
            }
        }, Minecraft.getInstance());
    }

    private void uploadPendingTexture() {
        int[] pixels = this.pendingTexturePixels;
        if (pixels == null || this.pendingTextureWidth <= 0 || this.pendingTextureHeight <= 0) return;
        if (this.textureCache == null || this.textureCache.getPixels().getWidth() != this.pendingTextureWidth
                || this.textureCache.getPixels().getHeight() != this.pendingTextureHeight) {
            if (this.textureCache != null) {
                this.textureCache.close();
                Minecraft.getInstance().getTextureManager().release(this.cacheLocation);
            }
            this.textureCache = new DynamicTexture(new NativeImage(this.pendingTextureWidth, this.pendingTextureHeight, true));
            this.cacheLocation = Minecraft.getInstance().getTextureManager().register("rtf_preview_cache_" + this.hashCode(), this.textureCache);
        }
        NativeImage image = this.textureCache.getPixels();
        for (int y = 0; y < this.pendingTextureHeight; y++) {
            for (int x = 0; x < this.pendingTextureWidth; x++) {
                image.setPixelRGBA(x, y, pixels[y * this.pendingTextureWidth + x]);
            }
        }
        this.textureCache.upload();
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

    public void close() throws Exception {
        this.closed = true;
        PreviewCancellation generation = this.generationCancellation;
        if (generation != null) generation.cancel();
        PreviewCancellation texture = this.textureCancellation;
        if (texture != null) texture.cancel();
        if (this.textureCache != null) {
            this.textureCache.close();
            Minecraft.getInstance().getTextureManager().release(this.cacheLocation);
            this.textureCache = null;
            this.cacheLocation = null;
        }
        if (this.tileLease != null) {
            this.tileLease.close();
            this.tileLease = null;
        }
        this.tile = null;
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

        if (this.tile != null && this.needsTextureRefresh) {
            this.uploadPendingTexture();
        }

        if (this.cacheLocation != null) {
            guiGraphics.blit(this.cacheLocation, x, y, 0.0F, 0.0F, this.width, this.height, this.width, this.height);
        } else {
            guiGraphics.fill(x, y, x + this.width, y + this.height, 0xFF000000);
        }

        renderSpawnMarker(guiGraphics);
        BiomePreview.Sidecar activeBiomes = this.biomes;
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
            Tile activeTile = this.tile;
            int tileSize = activeTile != null ? activeTile.getBlockSize().size() : 0;

            if (tileSize > 0) {
                int activeCX = this.centerX;
                int activeCZ = this.centerZ;

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
            this.requestTextureRasterization();
        }
    }

    private boolean updateLegend(int mx, int my) {
        Tile activeTile = this.tile;
        BiomePreview.Sidecar activeBiomes = this.biomes;
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

                    int activeCX = this.centerX;
                    int activeCZ = this.centerZ;
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
        int activeCX = this.centerX;
        int activeCZ = this.centerZ;
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
        final PreviewComputationCache.TileKey tileKey;

        PreGenContext(GeneratorContext context, BiomePreview biomePreview, int cx, int cz, int zoomLevel, PreviewComputationCache.TileKey tileKey) {
            this.context = context;
            this.biomePreview = biomePreview;
            this.cx = cx;
            this.cz = cz;
            this.zoomLevel = zoomLevel;
            this.tileKey = tileKey;
        }
    }

    private static class PreparedContext {
        final BiomePreview.CacheKey cacheKey;
        final GeneratorContext context;
        final HolderLookup.Provider provider;
        final Preset preset;
        private BiomePreview biomePreview;

        PreparedContext(BiomePreview.CacheKey cacheKey, GeneratorContext context, HolderLookup.Provider provider, Preset preset) {
            this.cacheKey = cacheKey;
            this.context = context;
            this.provider = provider;
            this.preset = preset;
        }

        synchronized void ensureBiomePreview(WorldCreationContext settings) {
            if (this.biomePreview == null) {
                this.biomePreview = BiomePreview.create(settings, this.provider, this.preset, this.context);
            }
        }

        BiomePreview biomePreview() {
            return this.biomePreview;
        }
    }

    private static class FrameResult {
        final PreviewComputationCache.TileLease lease;
        final Tile tile;
        final BiomePreview.Sidecar biomes;
        final int centerX;
        final int centerZ;
        final int[] texturePixels;
        final int textureWidth;
        final int textureHeight;

        FrameResult(PreviewComputationCache.TileLease lease, BiomePreview.Sidecar biomes, int centerX, int centerZ, int[] texturePixels, int textureWidth, int textureHeight) {
            this.lease = lease;
            this.tile = lease.tile();
            this.biomes = biomes;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.texturePixels = texturePixels;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
        }
    }
}
