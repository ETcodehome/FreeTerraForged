package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.config.PerformanceConfig;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.SpawnType;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

/**
 * Everything Preview2D and Preview3D share:
 * the async regeneration pipeline (prepare context ->
 * generate/fetch tile -> rasterize -> upload), on-demand re-rasterization when only the render
 * mode changes, legend rendering, and click/scroll handling.
 *
 * Both implementations still extend Button, so this isn't an abstract base class -
 * instead the pieces of per-instance state live in PreviewState, which each implementer
 * owns and exposes via state().
 *
 * The methods below that have no default body are exactly
 * the places where 2D and 3D genuinely differ (how a tile becomes pixels, the zoom formula,
 * legend text layout, etc.) - everything else is implemented once, here.
 */
public interface IPreviewHandler {
    int FACTOR = 4;
    int SIZE = (1 << 4) << FACTOR;
    float[] LEGEND_SCALES = { 1, 0.9F, 0.75F, 0.6F };
    long REFRESH_DEBOUNCE_MILLIS = 75L;

    PreviewState state();

    PresetEditorPage page();

    /** Routes inherited widget calls through Minecraft's remapped type in production jars. */
    Button widget();

    /** Plays the widget's click sound. Implemented per-class since {@code playDownSound} is protected on Button. */
    void playClickSound();

    /** "2D" / "3D" - used to label log messages for the two failure paths. */
    String getFailureLabel();

    RenderMode getRenderMode();

    int getZoom();

    boolean hasZoomControl();

    double getZoomValue();

    void setZoomValue(double value);

    void applyZoomValue();

    /** Snapshot of whatever extra sizing info rasterization needs, captured on the render thread. */
    RasterParams captureRasterParams();

    /** Turns a generated tile into pixels. For 2D this is a flat top-down buffer; for 3D an isometric projection. */
    int[] createRasterData(Tile tile, BiomePreview.Sidecar biomes, RenderMode mode, Levels levels, WorldSettings.Properties properties, RasterParams params);

    /** Applies a freshly generated frame's raster data (only called when the frame has no failure). */
    void applyGeneratedFrame(FrameResult result);

    /** Applies pixels produced by an on-demand {@link #requestRasterization()} call. */
    void applyRasterizedPixels(int[] pixels, RasterParams params);

    /** True if rasterization can't currently produce anything useful (e.g. zero widget size). */
    boolean rasterizationBlocked();

    boolean updateLegend(int mx, int my);

    String buildLegendLine(String labelStr, String value);

    int legendLineX(Font font, String line, float maxWidth);

    /** Releases mode-specific GPU resources (textures). Called from {@link #close()}. */
    default void closeResources() {
    }

    /** Hook for state that must track the render mode outside of {@code page()} (3D's fallback mode). */
    default void onRenderModeChanged(RenderMode mode) {
    }

    /** Hook for per-mode bookkeeping once a generated frame has been adopted (3D clears its hover cache). */
    default void onFrameApplied() {
    }

    // ------------------------------------------------------------------
    // Shared behavior
    // ------------------------------------------------------------------

    default void regenerate() {
        PreviewState state = state();
        PreviewCancellation previous = state.generationCancellation;
        if (previous != null) {
            previous.cancel();
        }
		PreviewCancellation raster = state.rasterCancellation;
		if (raster != null) {
			raster.cancel();
		}
		state.rasterRequestVersion.incrementAndGet();
        state.generationCancellation = new PreviewCancellation();
        state.isDirty = true;
        state.refreshRequestNanos = System.nanoTime();
        scheduleRegeneration();
    }

    default void scheduleRegeneration() {
        PreviewState state = state();
        long requestNanos = state.refreshRequestNanos;
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestNanos);
        long delayMillis = Math.max(0L, REFRESH_DEBOUNCE_MILLIS - elapsedMillis);
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(() ->
                Minecraft.getInstance().execute(() -> {
                    if (!state.closed && requestNanos == state.refreshRequestNanos && !state.isRunning) {
                        executeRegenerate();
                    }
                })
        );
    }

    default void refreshRenderMode(RenderMode mode) {
        onRenderModeChanged(mode);
        PreviewState state = state();
        boolean biomePipeline = mode == RenderMode.BIOME;
        if (state.tile == null
                || (mode == RenderMode.BIOME && state.biomes == null)
                || state.generatedWithBiomePipeline != biomePipeline) {
            regenerate();
            return;
        }
        requestRasterization();
    }

    default void executeRegenerate() {
        PreviewState state = state();
        if (state.closed) {
            return;
        }

        PreviewCancellation request = state.generationCancellation;
        if (request == null) {
            request = new PreviewCancellation();
            state.generationCancellation = request;
        }
        final PreviewCancellation cancellation = request;

        state.isRunning = true;
        state.isDirty = false;

        PresetEditorPage page = page();
        WorldCreationContext settings;
        PreviewRequestKeyFactory.Input requestedInput;
        RenderMode mode;
        boolean biomePipeline;
        try {
            settings = page.getScreen().getSettings();
            requestedInput = page.getScreen().previewRequestKeys().capture(
                    settings, page.preset.getPreset()
            );
            mode = getRenderMode();
            biomePipeline = mode == RenderMode.BIOME;
        } catch (RuntimeException | LinkageError error) {
            state.isRunning = false;
            state.previewFailure = PreviewFailure.log("Failed to prepare " + getFailureLabel() + " preview provider", error);
            if (state.isDirty) {
                scheduleRegeneration();
            }
            return;
        }
        int seed = (int) settings.options().seed();
        int zoomLevel = getZoom();
        int localOffsetX = page.previewNavigationX();
        int localOffsetZ = page.previewNavigationZ();
        boolean localNavigated = page.previewNavigated();
        RasterParams params = captureRasterParams();

        // Stage 1: prepare context, run config loading and structure lookups off the main thread
        CompletableFuture<PreGenContext> setupStage = CompletableFuture.supplyAsync(() -> {
            PreparedContext.Lease lease = null;
            try {
				cancellation.check();
				BiomePreview.CacheKey requestedKey = page.getScreen().previewRequestKeys().create(
					requestedInput, cancellation::isCancelled
				);
				page.previewCache().advance(requestedKey);
				page.getScreen().previewRequests().advance(requestedKey);
                lease = page.getScreen().previewRequests().acquire(
					requestedKey,
					cancellation::isCancelled,
					poolCancelled -> {
					if (poolCancelled.getAsBoolean()) {
						throw new java.util.concurrent.CancellationException(
							"Preview owner construction was superseded"
						);
					}
					RegistryAccess.Frozen registries = requestedKey.registrySnapshot();
					HolderLookup.Provider provider = requestedInput.preset().buildPreviewLookups(registries);
                    HolderGetter<Preset> presets = provider.lookupOrThrow(RTFRegistries.PRESET);
                    HolderGetter<Noise> noises = provider.lookupOrThrow(RTFRegistries.NOISE);
                    Preset preparedPreset = presets.getOrThrow(Preset.KEY).value();
                    PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
                            .resultOrPartial(RTFCommon.LOGGER::error)
                            .orElseGet(PerformanceConfig::makeDefault);
					GeneratorContext generatorContext = GeneratorContext.makeUncached(
                            preparedPreset, noises, seed, FACTOR, 0, config.batchCount()
                    );
					try {
						if (poolCancelled.getAsBoolean()) {
							throw new java.util.concurrent.CancellationException(
								"Preview owner construction was superseded"
							);
						}
						return new PreparedContext(requestedKey, generatorContext, provider, preparedPreset);
					} catch (RuntimeException | Error failure) {
						try {
							generatorContext.close();
						} catch (RuntimeException | Error cleanupFailure) {
							failure.addSuppressed(cleanupFailure);
						}
						throw failure;
					}
					}
				);
				cancellation.check();
                PreparedContext prepared = lease.owner();
                BiomePreview biomePreview = null;
                PreviewFailure biomeFailure = null;
                if (biomePipeline) {
					try {
						prepared.ensureBiomePreview();
						biomePreview = prepared.biomePreview();
					} catch (RuntimeException | LinkageError error) {
						if (PreviewCancellation.isCancellation(error)) {
							throw error;
						}
						biomeFailure = PreviewFailure.log("Failed to prepare " + getFailureLabel() + " biome preview", error);
					}
                }
                GeneratorContext generatorContext = prepared.context;
                Preset preparedPreset = prepared.preset;
                WorldSettings.Properties properties = preparedPreset.world().properties.copy();
                Levels levels = new Levels(
                        properties.terrainScaler(), properties.worldHeight, properties.worldDepth, properties.seaLevel
                );
                if (properties.spawnType == SpawnType.CONTINENT_CENTER) {
                    long baseContinentCenter = generatorContext.lookup.getHeightmap().continent().getNearestCenter(0, 0);
                    properties.spawnX = PosUtil.unpackLeft(baseContinentCenter);
                    properties.spawnZ = PosUtil.unpackRight(baseContinentCenter);
                }

                int cx;
                int cz;
                if (properties.spawnType == SpawnType.CONTINENT_CENTER) {
                    long nearestContinentCenter = generatorContext.lookup.getHeightmap().continent().getNearestCenter(
                            localNavigated ? localOffsetX : 0,
                            localNavigated ? localOffsetZ : 0
                    );
                    cx = PosUtil.unpackLeft(nearestContinentCenter);
                    cz = PosUtil.unpackRight(nearestContinentCenter);
                } else {
                    cx = localNavigated ? localOffsetX : (properties.spawnType == SpawnType.USER_SELECTED ? properties.spawnX : 0);
                    cz = localNavigated ? localOffsetZ : (properties.spawnType == SpawnType.USER_SELECTED ? properties.spawnZ : 0);
                }

                PreviewComputationCache.TileKey tileKey = new PreviewComputationCache.TileKey(
                        requestedKey, cx, cz, zoomLevel, SIZE, biomePipeline
                );
                return new PreGenContext(
                        generatorContext, biomePreview, cx, cz, zoomLevel, tileKey,
                        properties, levels, biomeFailure, lease
                );
            } catch (RuntimeException | Error failure) {
                if (lease != null) {
                    try {
                        lease.close();
                    } catch (RuntimeException | Error cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }, net.minecraft.Util.backgroundExecutor());

        // Stage 2: fetch/generate the tile and rasterize it, entirely on the worker pool
        state.pendingGeneration = setupStage.thenCompose(preGen -> {
            try {
                cancellation.check();
                CompletableFuture<PreviewComputationCache.TileLease> tileFuture =
                        page.previewCache().acquireOrGenerate(
                                preGen.tileKey,
                                cancellation::isCancelled,
                                cacheCancelled -> preGen.context.generator.generateZoomed(
                                        preGen.cx, preGen.cz, preGen.zoomLevel, biomePipeline,
                                        cacheCancelled
                                )
                        );
                return tileFuture.thenApply(lease -> {
                    try {
                        cancellation.check();
                        Tile generatedTile = lease.tile();
                        BiomePreview.Sidecar biomes = null;
                        PreviewFailure failure = preGen.biomeFailure;
                        if (failure == null && biomePipeline) {
                            biomes = preGen.biomePreview.resolveCached(
                                    page.previewCache(), generatedTile, preGen.cx, preGen.cz, preGen.zoomLevel,
                                    preGen.levels, cancellation::isCancelled
                            );
							cancellation.check();
                        }
                        int[] rasterData = failure == null
                                ? createRasterData(
                                        generatedTile, biomes, mode, preGen.levels, preGen.properties, params
                                )
                                : null;
                        return new FrameResult(
                                lease, biomes, preGen.cx, preGen.cz,
                                preGen.properties, preGen.levels,
                                rasterData, params.width, params.height, failure
                        );
                    } catch (RuntimeException | Error throwable) {
                        try {
                            lease.close();
                        } catch (RuntimeException | Error cleanupFailure) {
                            throwable.addSuppressed(cleanupFailure);
                        }
                        throw throwable;
                    }
                }).whenComplete((result, failure) -> {
					try {
						preGen.close();
					} catch (RuntimeException | Error cleanupFailure) {
						if (failure != null) {
							failure.addSuppressed(cleanupFailure);
						} else {
							throw cleanupFailure;
						}
					}
				});
            } catch (RuntimeException | Error failure) {
				try {
					preGen.close();
				} catch (RuntimeException | Error cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
                throw failure;
            }
        });

        state.pendingGeneration.whenComplete((result, throwable) -> {
            if (result != null && !state.pendingFrame.compareAndSet(null, result)) {
                IllegalStateException ownershipFailure = new IllegalStateException(
                        "Preview pipeline completed while another frame awaited adoption"
                );
                try {
                    result.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    ownershipFailure.addSuppressed(cleanupFailure);
                }
                state.isRunning = false;
                RTFCommon.LOGGER.error("Preview frame ownership invariant failed", ownershipFailure);
                return;
            }
            try {
                Minecraft.getInstance().execute(() -> completeGeneration(
                        result, throwable, cancellation, mode, biomePipeline
                ));
            } catch (RuntimeException | Error dispatchFailure) {
                if (result != null && state.pendingFrame.compareAndSet(result, null)) {
                    try {
                        result.close();
                    } catch (RuntimeException | Error cleanupFailure) {
                        dispatchFailure.addSuppressed(cleanupFailure);
                    }
                }
                state.isRunning = false;
                if (!state.closed) {
                    state.previewFailure = PreviewFailure.log(
                            "Failed dispatching " + getFailureLabel() + " preview frame",
                            dispatchFailure
                    );
                }
            }
        });
    }

    private void completeGeneration(
            FrameResult result,
            Throwable throwable,
            PreviewCancellation cancellation,
            RenderMode mode,
            boolean biomePipeline
    ) {
        PreviewState state = state();
        if (result != null && !state.pendingFrame.compareAndSet(result, null)) {
            return;
        }
        state.isRunning = false;
        boolean adopted = false;
        try {
            if (state.closed) {
                return;
            }

            if (throwable != null) {
                if (!PreviewCancellation.isCancellation(throwable)) {
                    state.previewFailure = PreviewFailure.log("Failed handling " + getFailureLabel() + " preview generation pipeline", throwable);
                }
            } else if (!state.isDirty && result != null && result.tile != null
                    && !cancellation.isCancelled()
                    && mode == getRenderMode()) {
                PreviewComputationCache.TileLease previousLease = state.tileLease;
                try {
                    state.tileLease = result.lease;
                    state.tile = result.lease.tile();
                    state.biomes = result.biomes;
                    state.frameProperties = result.properties;
                    state.frameLevels = result.levels;
                    state.generatedWithBiomePipeline = biomePipeline;
                    state.centerX = result.centerX;
                    state.centerZ = result.centerZ;
                    state.previewFailure = result.failure;
                    state.legendValues[3] = getSpawnCoords();
                    adopted = true;

                    if (result.failure == null) {
                        try {
                            applyGeneratedFrame(result);
                            onFrameApplied();
                        } catch (RuntimeException | LinkageError failure) {
                            state.previewFailure = PreviewFailure.log(
                                    "Failed applying " + getFailureLabel() + " preview frame", failure
                            );
                        }
                    } else {
                        onFrameApplied();
                    }
                } finally {
                    if (previousLease != null) previousLease.close();
                }
            }

            if (state.isDirty) {
                scheduleRegeneration();
            }
        } finally {
            if (result != null && !adopted) {
                result.close();
            }
        }
    }

    /** Re-rasterizes the current tile without a full regenerate - used when only the render mode changes. */
    default void requestRasterization() {
        PreviewState state = state();
        PreviewComputationCache.TileLease current = state.tileLease;
        if (current == null || state.closed || rasterizationBlocked()) {
            return;
        }
        PreviewCancellation previous = state.rasterCancellation;
        if (previous != null) previous.cancel();
        PreviewCancellation cancellation = new PreviewCancellation();
        state.rasterCancellation = cancellation;
        long version = state.rasterRequestVersion.incrementAndGet();
        PreviewComputationCache.TileLease retained = current.retain();
        RenderMode mode = getRenderMode();
        WorldSettings.Properties properties = state.frameProperties;
        Levels levels = state.frameLevels;
        if (properties == null || levels == null) {
            retained.close();
            return;
        }
        RasterParams params = captureRasterParams();
        BiomePreview.Sidecar sidecar = state.biomes;
        state.pendingRasterization = CompletableFuture.supplyAsync(() -> {
            cancellation.check();
            return createRasterData(retained.tile(), sidecar, mode, levels, properties, params);
        }, net.minecraft.Util.backgroundExecutor()).whenComplete((pixels, throwable) -> {
            Throwable completionFailure = throwable;
            try {
                retained.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (completionFailure == null) {
                    completionFailure = cleanupFailure;
                } else {
                    completionFailure.addSuppressed(cleanupFailure);
                }
            }
            Throwable rasterFailure = completionFailure;
            try {
                Minecraft.getInstance().execute(() -> {
                    if (!state.closed && rasterFailure != null && !PreviewCancellation.isCancellation(rasterFailure)
                            && version == state.rasterRequestVersion.get() && mode == getRenderMode()) {
                        state.previewFailure = PreviewFailure.log("Failed rasterizing " + getFailureLabel() + " preview", rasterFailure);
                    } else if (!state.closed && rasterFailure == null && !cancellation.isCancelled()
                            && version == state.rasterRequestVersion.get() && mode == getRenderMode()) {
                        try {
                            state.previewFailure = null;
                            applyRasterizedPixels(pixels, params);
                        } catch (RuntimeException | LinkageError failure) {
                            state.previewFailure = PreviewFailure.log(
                                    "Failed applying " + getFailureLabel() + " preview raster",
                                    failure
                            );
                        }
                    }
                });
            } catch (RuntimeException | Error dispatchFailure) {
                if (!state.closed) {
                    state.previewFailure = PreviewFailure.log(
                            "Failed dispatching " + getFailureLabel() + " preview raster",
                            dispatchFailure
                    );
                }
            }
        });
    }

    default void close() {
        PreviewState state = state();
        state.closed = true;
        PreviewCancellation generation = state.generationCancellation;
        if (generation != null) generation.cancel();
        PreviewCancellation raster = state.rasterCancellation;
        if (raster != null) raster.cancel();
		state.pendingGeneration = null;
		state.pendingRasterization = null;
		Throwable failure = null;
		FrameResult pendingFrame = state.pendingFrame.getAndSet(null);
		if (pendingFrame != null) {
			try {
				pendingFrame.close();
			} catch (RuntimeException | Error frameFailure) {
				failure = frameFailure;
			}
		}
		try {
			closeResources();
		} catch (RuntimeException | Error resourceFailure) {
			failure = mergeFailure(failure, resourceFailure);
		}
		try {
			if (state.tileLease != null) {
				state.tileLease.close();
			}
		} catch (RuntimeException | Error leaseFailure) {
			failure = mergeFailure(failure, leaseFailure);
		} finally {
			state.tileLease = null;
			state.tile = null;
			state.biomes = null;
			state.frameProperties = null;
			state.frameLevels = null;
			state.previewFailure = null;
			state.generatedWithBiomePipeline = false;
		}
		if (failure instanceof RuntimeException runtime) {
			throw runtime;
		}
		if (failure instanceof Error error) {
			throw error;
        }
    }

	private static Throwable mergeFailure(Throwable current, Throwable next) {
		if (current == null) {
			return next;
		}
		if (next instanceof Error && !(current instanceof Error)) {
			next.addSuppressed(current);
			return next;
		}
		current.addSuppressed(next);
		return current;
	}

    default float getLegendScale() {
        int index = page().getScreen().minecraft.options.guiScale().get() - 1;
        if (index < 0 || index >= LEGEND_SCALES.length) {
            index = LEGEND_SCALES.length - 1;
        }
        return LEGEND_SCALES[index];
    }

    default void renderLegend(GuiGraphics guiGraphics, int mx, int my, Component[] labels, String[] values, int left, int top, int lineHeight, int color) {
        float scale = getLegendScale();
        PoseStack pose = guiGraphics.pose();

        pose.pushPose();
        pose.translate(left + 3.75F * scale, top - lineHeight * (3.2F * scale), 0);
        pose.scale(scale, scale, 1);

        Font renderer = Minecraft.getInstance().font;
        float maxWidth = (widget().getWidth() - 4) / scale;

        for (int i = 0; i < labels.length && i < values.length; i++) {
            Component label = labels[i];
            String value = values[i];

            String labelStr = label.getString();
            if (labelStr.endsWith(": ")) {
                labelStr = labelStr.substring(0, labelStr.length() - 2);
            } else if (labelStr.endsWith(":")) {
                labelStr = labelStr.substring(0, labelStr.length() - 1);
            }

            String line = buildLegendLine(labelStr, value);
            while (line.length() > 0 && renderer.width(line) > maxWidth) {
                line = line.substring(0, line.length() - 1);
            }

            int x = legendLineX(renderer, line, maxWidth);
            guiGraphics.drawString(renderer, line, x, i * lineHeight, color);
        }

        pose.popPose();

        String hoveredCoords = state().hoveredCoords;
        if (!hoveredCoords.isEmpty()) {
            guiGraphics.drawCenteredString(renderer, hoveredCoords, mx, my - 10, 0xFFFFFF);
        }
    }

    default String getSpawnCoords() {
        PreviewState state = state();
        return getSpawnCoords(state.centerX, state.centerZ);
    }

    default String getSpawnCoords(int cx, int cz) {
        WorldSettings.Properties props = page().preset.getPreset().world().properties;
        if (props.spawnType == SpawnType.USER_SELECTED) {
            return "x" + props.spawnX + " z" + props.spawnZ;
        }
        if (props.spawnType == SpawnType.CONTINENT_CENTER || props.spawnType == SpawnType.ISLANDS) {
            return "~x" + cx + " ~z" + cz;
        }
        return "x0 z0";
    }

    static String getTerrainName(Cell cell) {
        if (cell.terrain.isRiver()) {
            return "river";
        }
        return cell.terrain.getName().toLowerCase();
    }

    /** Shared right/middle-click handling. Returns true if the click was handled (caller should not fall through to super). */
    default boolean handleClick(double mouseX, double mouseY, int button) {
        if (!widget().isMouseOver(mouseX, mouseY)) {
            return false;
        }
        PreviewState state = state();
        // Right click: navigate to specific coordinates
        if (button == 1) {
            if (updateLegend((int) mouseX, (int) mouseY) && !state.hoveredCoords.isEmpty()) {
                playClickSound();
                WorldSettings.Properties props = page().preset.getPreset().world().properties;
                if (props.spawnType == SpawnType.CONTINENT_CENTER) {
                    props.spawnType = SpawnType.USER_SELECTED;
                    if (page() instanceof WorldSettingsPage worldPage) {
                        worldPage.spawnType.setValue(SpawnType.USER_SELECTED);
                    }
                }
                page().setPreviewNavigation(state.hoveredCoordX, state.hoveredCoordZ);
                regenerate();
                return true;
            }
        }
        // Middle click: reset to current spawn coordinates
        else if (button == 2) {
            playClickSound();
            page().resetPreviewNavigation();
            regenerate();
            return true;
        }
        return false;
    }

    /** Shared scroll-to-zoom handling. Returns true if the scroll was consumed. */
    default boolean handleScroll(double mouseX, double mouseY, double scrollY) {
        if (!widget().isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (hasZoomControl()) {
            double currentVal = getZoomValue();
            double step = 0.05;
            double newVal = currentVal;

            if (scrollY > 0) {
                newVal = Math.min(1.0, currentVal + step);
            } else if (scrollY < 0) {
                newVal = Math.max(0.0, currentVal - step);
            }

            setZoomValue(newVal);
            applyZoomValue();
            regenerate();
        }
        return true;
    }

    /** Shared onPress handler for the underlying Button - left click sets spawn to the hovered cell. */
    static Button.OnPress onPress() {
        return (b) -> {
            if (b instanceof IPreviewHandler self) {
                Minecraft mc = Minecraft.getInstance();
                double guiX = mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getWidth();
                double guiY = mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getHeight();

                PreviewState state = self.state();
                if (self.updateLegend((int) guiX, (int) guiY) && !state.hoveredCoords.isEmpty()) {
                    self.playClickSound();
                    WorldSettings.Properties props = self.page().preset.getPreset().world().properties;
                    props.spawnType = SpawnType.USER_SELECTED;
                    props.spawnX = state.hoveredCoordX;
                    props.spawnZ = state.hoveredCoordZ;

                    if (self.page() instanceof WorldSettingsPage worldPage) {
                        worldPage.spawnType.setValue(SpawnType.USER_SELECTED);
                    }

                    self.page().resetPreviewNavigation();
                    self.page().regenerate();
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // Shared value types
    // ------------------------------------------------------------------

    final class RasterParams {
        final int width;
        final int height;
        final double zoom;

        RasterParams(int width, int height, double zoom) {
            this.width = width;
            this.height = height;
            this.zoom = zoom;
        }
    }

    final class PreGenContext implements AutoCloseable {
        final GeneratorContext context;
        final BiomePreview biomePreview;
        final int cx;
        final int cz;
        final int zoomLevel;
        final PreviewComputationCache.TileKey tileKey;
        final WorldSettings.Properties properties;
        final Levels levels;
        final PreviewFailure biomeFailure;
        final PreparedContext.Lease lease;

        PreGenContext(
                GeneratorContext context,
                BiomePreview biomePreview,
                int cx,
                int cz,
                int zoomLevel,
                PreviewComputationCache.TileKey tileKey,
                WorldSettings.Properties properties,
                Levels levels,
                PreviewFailure biomeFailure,
                PreparedContext.Lease lease
        ) {
            this.context = context;
            this.biomePreview = biomePreview;
            this.cx = cx;
            this.cz = cz;
            this.zoomLevel = zoomLevel;
            this.tileKey = tileKey;
            this.properties = properties;
            this.levels = levels;
            this.biomeFailure = biomeFailure;
            this.lease = lease;
        }

        @Override
        public void close() {
            this.lease.close();
        }
    }

    final class PreparedContext implements AutoCloseable {
        final BiomePreview.CacheKey cacheKey;
        final GeneratorContext context;
        final HolderLookup.Provider provider;
        final Preset preset;
        private BiomePreview biomePreview;
        private int leases;
		private final java.util.concurrent.atomic.AtomicBoolean closeRequested =
			new java.util.concurrent.atomic.AtomicBoolean();
		private boolean resourcesClosed;

        PreparedContext(BiomePreview.CacheKey cacheKey, GeneratorContext context, HolderLookup.Provider provider, Preset preset) {
            this.cacheKey = cacheKey;
            this.context = context;
            this.provider = provider;
            this.preset = preset;
        }

        boolean matches(BiomePreview.CacheKey key) {
            return Objects.equals(this.cacheKey, key);
        }

        synchronized Lease acquire() {
			if (this.closeRequested.get()) {
                throw new java.util.concurrent.CancellationException("Preview request owner is closing");
            }
            this.leases++;
            return new Lease(this);
        }

        synchronized void ensureBiomePreview() {
			if (this.closeRequested.get()) {
				throw new java.util.concurrent.CancellationException("Preview request owner is closing");
			}
            if (this.biomePreview == null) {
                this.biomePreview = BiomePreview.create(
					this.provider, this.preset, this.context, this.cacheKey,
					this.closeRequested::get
                );
            }
        }

        synchronized BiomePreview biomePreview() {
            return this.biomePreview;
        }

        @Override
        public void close() {
			if (!this.closeRequested.compareAndSet(false, true)) {
				return;
			}
			OwnedResources closing;
            synchronized (this) {
				closing = this.leases == 0 ? this.detachResources() : null;
            }
			closeResources(closing);
        }

        private void release() {
			OwnedResources closing;
            synchronized (this) {
                if (this.leases <= 0) {
                    throw new IllegalStateException("Preview request lease was released more than once");
                }
                this.leases--;
				closing = this.closeRequested.get() && this.leases == 0 ? this.detachResources() : null;
            }
			closeResources(closing);
        }

		private OwnedResources detachResources() {
			if (this.resourcesClosed) {
				return null;
			}
			this.resourcesClosed = true;
            BiomePreview preview = this.biomePreview;
            this.biomePreview = null;
			return new OwnedResources(preview, this.context);
        }

		private static void closeResources(OwnedResources resources) {
			if (resources == null) {
				return;
			}
			Throwable failure = null;
			if (resources.preview != null) {
                try {
					resources.preview.close();
				} catch (RuntimeException | Error closeFailure) {
					failure = closeFailure;
                }
            }
			if (resources.context != null) {
				try {
					resources.context.close();
				} catch (RuntimeException | Error closeFailure) {
					failure = mergeFailure(failure, closeFailure);
				}
			}
			if (failure instanceof Error error) {
				throw error;
			}
			if (failure != null) {
				RTFCommon.LOGGER.error("Failed closing request-owned preview resources", failure);
			}
        }

		private record OwnedResources(BiomePreview preview, GeneratorContext context) {
		}

        static final class Lease implements AutoCloseable {
            private final PreparedContext owner;
            private final java.util.concurrent.atomic.AtomicBoolean closed =
                    new java.util.concurrent.atomic.AtomicBoolean();

            private Lease(PreparedContext owner) {
                this.owner = owner;
            }

            PreparedContext owner() {
                return this.owner;
            }

            @Override
            public void close() {
                if (this.closed.compareAndSet(false, true)) {
                    this.owner.release();
                }
            }
        }
    }

    final class FrameResult implements AutoCloseable {
        final PreviewComputationCache.TileLease lease;
        final Tile tile;
        final BiomePreview.Sidecar biomes;
        final int centerX;
        final int centerZ;
        final WorldSettings.Properties properties;
        final Levels levels;
        final int[] rasterPayload;
        final int rasterWidth;
        final int rasterHeight;
        final PreviewFailure failure;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();

        FrameResult(
                PreviewComputationCache.TileLease lease,
                BiomePreview.Sidecar biomes,
                int centerX,
                int centerZ,
                WorldSettings.Properties properties,
                Levels levels,
                int[] rasterPayload,
                int rasterWidth,
                int rasterHeight,
                PreviewFailure failure
        ) {
            this.lease = lease;
            this.tile = lease.tile();
            this.biomes = biomes;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.properties = properties;
            this.levels = levels;
            this.rasterPayload = rasterPayload;
            this.rasterWidth = rasterWidth;
            this.rasterHeight = rasterHeight;
            this.failure = failure;
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.lease.close();
            }
        }
    }
}
