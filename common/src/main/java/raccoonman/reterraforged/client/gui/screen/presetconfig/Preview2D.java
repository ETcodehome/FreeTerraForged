package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    private final PresetEditorPage page;
    private final DynamicTexture texture = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
    private final ResourceLocation textureId = Minecraft.getInstance().getTextureManager().register(RTFCommon.MOD_ID + "-preview-framebuffer", this.texture);

    private Tile tile;
    private PreviewComputationCache.TileLease tileLease;
    private BiomePreview.Sidecar biomes;
    private boolean generatedWithBiomePipeline;
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

    private CompletableFuture<FrameResult> pendingGeneration = null;
    private volatile PreparedContext preparedContext;
    private volatile PreviewCancellation generationCancellation;
    private volatile PreviewFailure previewFailure;
    private final AtomicLong pixelRequestVersion = new AtomicLong();
    private volatile PreviewCancellation pixelCancellation;
    private CompletableFuture<?> pendingPixelRasterization;

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

                    self.page.resetPreviewNavigation();
                    self.page.regenerate();
                }
            }
        }, DEFAULT_NARRATION);
        this.page = parent;

        this.cacheKey = BiomePreview.cacheKey(parent.getScreen().getSettings(), parent.preset.getPreset());

        NativeImage pixels = this.texture.getPixels();
        if (pixels != null) {
            pixels.fillRect(0, 0, SIZE, SIZE, 0xFF000000);
            this.texture.upload();
        }
    }

    public void regenerate() {
        PreviewCancellation previous = this.generationCancellation;
        if (previous != null) {
            previous.cancel();
        }
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
        boolean biomePipeline = mode == RenderMode.BIOME;
        if (this.tile == null
                || (mode == RenderMode.BIOME && this.biomes == null)
                || this.generatedWithBiomePipeline != biomePipeline) {
            this.regenerate();
            return;
        }
        WorldSettings.Properties properties = this.page.preset.getPreset().world().properties;
        Levels levels = new Levels(properties.terrainScaler(), properties.worldDepth, properties.seaLevel);
        this.requestPixelRasterization(mode, levels, properties);
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

        WorldCreationContext settings;
        Preset requestedPreset;
        BiomePreview.CacheKey requestedKey;
        RenderMode mode;
        boolean biomePipeline;
        PreparedContext reusable;
        HolderLookup.Provider provider;
        HolderGetter<Noise> noises;
        Preset presetObj;
        try {
            settings = this.page.getScreen().getSettings();
            requestedPreset = this.page.preset.getPreset();
            requestedKey = BiomePreview.cacheKey(settings, requestedPreset);
            mode = this.page.renderMode2D.getValue();
            biomePipeline = mode == RenderMode.BIOME;
            reusable = this.preparedContext;
            provider = null;
            noises = null;
            presetObj = requestedPreset;
            if (reusable == null
                    || !Objects.equals(reusable.cacheKey, requestedKey)
                    || reusable.biomePipeline != biomePipeline) {
                RegistryAccess.Frozen registries = settings.worldgenLoadContext();
                provider = biomePipeline
                        ? requestedPreset.buildFullPatch(registries)
                        : requestedPreset.buildPatch(registries);
                HolderGetter<Preset> presets = provider.lookupOrThrow(RTFRegistries.PRESET);
                noises = provider.lookupOrThrow(RTFRegistries.NOISE);
                presetObj = presets.getOrThrow(Preset.KEY).value();
            }
        } catch (RuntimeException | LinkageError error) {
            this.isRunning = false;
            this.previewFailure = PreviewFailure.log("Failed to prepare 2D preview provider", error);
            if (this.isDirty) {
                this.scheduleRegeneration();
            }
            return;
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
        int localOffsetX = this.page.previewNavigationX();
        int localOffsetZ = this.page.previewNavigationZ();
        boolean localNavigated = this.page.previewNavigated();
        Levels levels = new Levels(properties.terrainScaler(), properties.worldDepth, properties.seaLevel);

        // Stage 1: Run clear, config loading, and structure lookups off the main thread
        CompletableFuture<PreGenContext> setupStage = CompletableFuture.supplyAsync(() -> {
            PreparedContext prepared = reusable;
            if (prepared == null
                    || !Objects.equals(prepared.cacheKey, requestedKey)
                    || prepared.biomePipeline != biomePipeline) {
                PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
                        .resultOrPartial(RTFCommon.LOGGER::error)
                        .orElseGet(PerformanceConfig::makeDefault);
                GeneratorContext generatorContext = GeneratorContext.makeUncached(
                    preparedPreset, preparedNoises, seed, FACTOR, 0, config.batchCount()
                );
                prepared = new PreparedContext(requestedKey, generatorContext, preparedProvider, preparedPreset, biomePipeline);
                this.preparedContext = prepared;
            }
            BiomePreview biomePreview = null;
            PreviewFailure biomeFailure = null;
            if (biomePipeline) {
                try {
                    prepared.ensureBiomePreview(settings);
                    biomePreview = prepared.biomePreview;
                } catch (RuntimeException | LinkageError error) {
                    biomeFailure = PreviewFailure.log("Failed to prepare 2D biome preview", error);
                }
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

            PreviewComputationCache.TileKey tileKey = new PreviewComputationCache.TileKey(
                requestedKey, cx, cz, zoomLevel, Preview2D.SIZE, biomePipeline
            );
            return new PreGenContext(generatorContext, biomePreview, cx, cz, zoomLevel, tileKey, biomeFailure);
        }, net.minecraft.Util.backgroundExecutor());

        // Stage 2: Handle calculation maps and evaluate visual color tables entirely on worker pool
        this.pendingGeneration = setupStage.thenCompose(preGen -> {
            cancellation.check();
            PreviewComputationCache.TileLease cached = this.page.previewCache().acquire(preGen.tileKey);
            CompletableFuture<PreviewComputationCache.TileLease> tileFuture;
            if (cached != null) {
                tileFuture = CompletableFuture.completedFuture(cached);
            } else {
                tileFuture = preGen.context.generator.generateZoomed(
					preGen.cx, preGen.cz, preGen.zoomLevel, biomePipeline, cancellation::isCancelled
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
                    BiomePreview.Sidecar biomes = null;
                    PreviewFailure failure = preGen.biomeFailure;
                    if (failure == null && biomePipeline) {
                        biomes = preGen.biomePreview.resolveCached(
                            this.page.previewCache(), generatedTile, preGen.cx, preGen.cz, preGen.zoomLevel, levels, cancellation
                        );
                    }

                    int[] bufferedPixels = failure == null
                        ? this.createPixelData(generatedTile, biomes, mode, levels, properties)
                        : null;
                    return new FrameResult(lease, biomes, preGen.cx, preGen.cz, bufferedPixels, failure);
                } catch (Throwable throwable) {
                    lease.close();
                    throw throwable;
                }
            });
        });

        // Stage 3: Return safely back onto the primary Minecraft render thread for GL transfers
        this.pendingGeneration.whenCompleteAsync((result, throwable) -> {
            this.isRunning = false;

            if (this.closed) {
                if (result != null) result.lease.close();
                return;
            }

            if (throwable != null) {
                if (!PreviewCancellation.isCancellation(throwable)) {
                    this.previewFailure = PreviewFailure.log("Failed handling 2D preview generation pipeline", throwable);
                }
            } else if (!this.isDirty && result != null && result.tile != null
                    && !cancellation.isCancelled()
                    && Objects.equals(requestedKey, this.cacheKey)
                    && mode == this.page.renderMode2D.getValue()) {
                PreviewComputationCache.TileLease previousLease = this.tileLease;
                this.tileLease = result.lease;
                this.tile = result.lease.tile();
                this.biomes = result.biomes;
                this.generatedWithBiomePipeline = biomePipeline;
                this.centerX = result.centerX;
                this.centerZ = result.centerZ;
                this.previewFailure = result.failure;
                this.legendValues[3] = getSpawnCoords();

                // Safe structural upload across to GPU
                if (result.failure == null) {
                    this.uploadPixelData(result.pixelData);
                }
                if (previousLease != null) previousLease.close();
            } else if (result != null && result.tile != null) {
                result.lease.close();
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
        for (int bz = 0; bz < tileWidth; bz++) {
            for (int bx = 0; bx < tileWidth; bx++) {
                pixels.setPixelRGBA(bx, bz, pixelData[bz * tileWidth + bx]);
            }
        }
        this.texture.upload();
    }

    private void requestPixelRasterization(RenderMode mode, Levels levels, WorldSettings.Properties properties) {
        PreviewComputationCache.TileLease current = this.tileLease;
        if (current == null || this.closed) return;
        PreviewCancellation previous = this.pixelCancellation;
        if (previous != null) previous.cancel();
        PreviewCancellation cancellation = new PreviewCancellation();
        this.pixelCancellation = cancellation;
        long version = this.pixelRequestVersion.incrementAndGet();
        PreviewComputationCache.TileLease retained = current.retain();
        BiomePreview.Sidecar sidecar = this.biomes;
        this.pendingPixelRasterization = CompletableFuture.supplyAsync(() -> {
            cancellation.check();
            return this.createPixelData(retained.tile(), sidecar, mode, levels, properties);
        }, net.minecraft.Util.backgroundExecutor()).whenCompleteAsync((pixels, throwable) -> {
            try {
                if (!this.closed && throwable != null && !PreviewCancellation.isCancellation(throwable)
                        && version == this.pixelRequestVersion.get()
                        && mode == this.page.renderMode2D.getValue()) {
                    this.previewFailure = PreviewFailure.log("Failed rasterizing 2D preview", throwable);
                } else if (!this.closed && throwable == null && !cancellation.isCancelled()
                        && version == this.pixelRequestVersion.get()
                        && mode == this.page.renderMode2D.getValue()) {
                    this.previewFailure = null;
                    this.uploadPixelData(pixels);
                }
            } finally {
                retained.close();
            }
        }, Minecraft.getInstance());
    }

    public void close() throws Exception {
        this.closed = true;
        PreviewCancellation generation = this.generationCancellation;
        if (generation != null) generation.cancel();
        PreviewCancellation pixels = this.pixelCancellation;
        if (pixels != null) pixels.cancel();
        this.texture.close();
        if (this.tileLease != null) {
            this.tileLease.close();
            this.tileLease = null;
        }
        this.tile = null;
        this.previewFailure = null;
        this.generatedWithBiomePipeline = false;
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

                    this.page.setPreviewNavigation(this.hoveredCoordX, this.hoveredCoordZ);
                    this.regenerate();
                    return true;
                }
            }
            // Middle Click: Reset to current spawn coordinates
            else if (button == 2) {
                this.playDownSound(Minecraft.getInstance().getSoundManager());
                this.page.resetPreviewNavigation();
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
                double newVal = currentVal;

                if (scrollY > 0) {
                    newVal = Math.min(1.0, currentVal + step);
                } else if (scrollY < 0) {
                    newVal = Math.max(0.0, currentVal - step);
                }

                this.page.zoom2D.setValue(newVal);
                this.page.zoom2D.applyValue();
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

        if (this.previewFailure != null) {
            PreviewFailure.renderUnavailable(guiGraphics, xPos, yPos, this.width, this.height);
            return;
        }

        if (this.tile == null) {
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
        final PreviewComputationCache.TileKey tileKey;

        final PreviewFailure biomeFailure;

        PreGenContext(GeneratorContext context, BiomePreview biomePreview, int cx, int cz, int zoomLevel, PreviewComputationCache.TileKey tileKey, PreviewFailure biomeFailure) {
            this.context = context;
            this.biomePreview = biomePreview;
            this.cx = cx;
            this.cz = cz;
            this.zoomLevel = zoomLevel;
            this.tileKey = tileKey;
            this.biomeFailure = biomeFailure;
        }
    }

    private static class PreparedContext {
        final BiomePreview.CacheKey cacheKey;
        final GeneratorContext context;
        final HolderLookup.Provider provider;
        final Preset preset;
        private BiomePreview biomePreview;

        final boolean biomePipeline;

        PreparedContext(BiomePreview.CacheKey cacheKey, GeneratorContext context, HolderLookup.Provider provider, Preset preset, boolean biomePipeline) {
            this.cacheKey = cacheKey;
            this.context = context;
            this.provider = provider;
            this.preset = preset;
            this.biomePipeline = biomePipeline;
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
        final int[] pixelData;
        final PreviewFailure failure;

        FrameResult(PreviewComputationCache.TileLease lease, BiomePreview.Sidecar biomes, int centerX, int centerZ, int[] pixelData, PreviewFailure failure) {
            this.lease = lease;
            this.tile = lease.tile();
            this.biomes = biomes;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.pixelData = pixelData;
            this.failure = failure;
        }
    }
}
