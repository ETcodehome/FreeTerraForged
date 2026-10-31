package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.PreviewGrid;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.TerrainCeiling;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.search.BiomeLocateSearch;
import etcodehome.freeterraforged.world.worldgen.search.BiomeSearchTarget;
import etcodehome.freeterraforged.world.worldgen.search.LocateSearchSettings;
import etcodehome.freeterraforged.world.worldgen.search.TerrainLocateSearch;
import etcodehome.freeterraforged.world.worldgen.search.TerrainSearchTargets;
import net.minecraft.core.BlockPos;

public class Preview2D extends Button implements IPreviewHandler {
    public static final int SIZE = IPreviewHandler.SIZE;
    static final int LEGEND_TOP_OFFSET = 30;
    static final int LEGEND_LINE_HEIGHT = 10;
    static final int LEGEND_FOOTER_HEIGHT = LEGEND_TOP_OFFSET + LEGEND_LINE_HEIGHT * 4;
    // Statically cached backing texture shared across widget instances
    private static DynamicTexture STATIC_TEXTURE;
    private static ResourceLocation STATIC_TEXTURE_ID;

    private final PreviewController page;
    private final PreviewState state = new PreviewState();
    private PendingSearch pendingSearch;
    private PreviewCancellation searchCancellation;
    private CompletableFuture<SearchResult> searchFuture;

    public Preview2D(PreviewController parent, int x, int y, int width, int height) {
        super(x, y, width, height, CommonComponents.EMPTY, IPreviewHandler.onPress(), DEFAULT_NARRATION);
        this.page = parent;
        // Ensure static texture is initialized
        getOrCreateTexture();
    }

    private static ResourceLocation getOrCreateTexture() {
        if (STATIC_TEXTURE == null) {
            STATIC_TEXTURE = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
            STATIC_TEXTURE_ID = Minecraft.getInstance().getTextureManager().register(
                    FTFCommon.MOD_ID + "-preview-framebuffer",
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
    public PreviewController page() {
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
        return "2D";
    }

    @Override
    public RenderMode getRenderMode() {
        return this.page.renderMode2D().getValue();
    }

    @Override
    public int getZoom() {
        return NoiseUtil.round(1.5F * (101 - (float) this.page.zoom2D().getLerpedValue()));
    }

    @Override
    public boolean hasZoomControl() {
        return this.page.zoom2D() != null;
    }

    @Override
    public double getZoomValue() {
        return this.page.zoom2D().getValue();
    }

    @Override
    public void setZoomValue(double value) {
        this.page.zoom2D().setValue(value);
    }

    @Override
    public void applyZoomValue() {
        this.page.zoom2D().applyValue();
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
    public int[] createRasterData(PreviewGrid tile, BiomePreview.Sidecar biomes, RenderMode mode, Levels levels, WorldSettings.Properties properties, RasterParams params) {
        int stroke = 2;
        int tileWidth = tile.size();
        int heightLimit = TerrainCeiling.buildLimitHeight(properties, TerrainCeiling.isEnabled(this.page.preset()));
        int[] pixels = new int[tileWidth * tileWidth];
        tile.iterate((cell, bx, bz) -> {
            int color;
            if (bx < stroke || bz < stroke || bx >= tileWidth - stroke || bz >= tileWidth - stroke) {
                color = 0xFF000000;
            } else if (levels.scale(cell.height) > heightLimit) {
                color = 0xFFFF00FF;
            } else {
                int biomeColor = biomes == null ? 0xFFFF00FF : biomes.color(bx, bz);
                color = mode.getColor(cell, levels, biomeColor);
                if (mode == RenderMode.BIOME && biomes != null && biomes.hatchAt(bx, bz)) {
                    color = BiomePreviewColors.hatch(color);
                }
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
        if (STATIC_TEXTURE == null || pixelData == null) return;
        NativeImage pixels = STATIC_TEXTURE.getPixels();
        if (pixels == null) return;

        int tileWidth = (int) Math.sqrt(pixelData.length);
        for (int bz = 0; bz < tileWidth; bz++) {
            int rowOffset = bz * tileWidth;
            for (int bx = 0; bx < tileWidth; bx++) {
                pixels.setPixelRGBA(bx, bz, pixelData[rowOffset + bx]);
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
        this.cancelPendingSearch();
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

        drawCrosshair(guiGraphics, xPos + this.width / 2, yPos + this.height / 2);
        updateLegend(mx, my);
        renderLegend(
            guiGraphics, mx, my, this.state.legendLabels, this.state.legendValues,
            xPos, yPos + this.width + LEGEND_TOP_OFFSET, LEGEND_LINE_HEIGHT, 0xFFFFFF
        );
    }

    void navigateToTerrain(String requestedName, Consumer<NavigationResult> result) {
        if (this.state.grid == null || this.state.contextLease == null) {
            this.pendingSearch = new PendingSearch(false, requestedName, result);
            return;
        }
        String target = requestedName.trim().toLowerCase(java.util.Locale.ROOT);
        this.searchSeed(false, target, result);
    }

    void cancelPendingSearch() {
        this.pendingSearch = null;
        PreviewCancellation cancellation = this.searchCancellation;
        this.searchCancellation = null;
        if (cancellation != null) {
            cancellation.cancel();
        }
        CompletableFuture<SearchResult> future = this.searchFuture;
        this.searchFuture = null;
        if (future != null) {
            future.cancel(true);
        }
    }

    void navigateToBiome(String requestedId, Consumer<NavigationResult> result) {
        if (this.state.grid == null || this.state.contextLease == null) {
            this.pendingSearch = new PendingSearch(true, requestedId, result);
            return;
        }
        String target = requestedId.trim().toLowerCase(java.util.Locale.ROOT);
        this.searchSeed(true, target, result);
    }

    @Override
    public void onFrameApplied() {
        PendingSearch search = this.pendingSearch;
        if (search == null) {
            return;
        }
        if (this.state.previewFailure != null) {
            this.pendingSearch = null;
            search.result.accept(NavigationResult.failed(Component.translatableWithFallback(
				"freeterraforged.gui.preset_preview.search_unavailable",
				"The preview is unavailable, so it cannot be searched."
			)));
            return;
        }
        this.pendingSearch = null;
        Minecraft.getInstance().execute(() -> {
            if (search.biome) {
                this.navigateToBiome(search.value, search.result);
            } else {
                this.navigateToTerrain(search.value, search.result);
            }
        });
    }

    private void searchSeed(boolean biome, String target, Consumer<NavigationResult> result) {
        this.cancelPendingSearch();
        IPreviewHandler.PreparedContext.Lease contextLease = this.state.contextLease.owner().acquire();
        PreviewCancellation cancellation = new PreviewCancellation();
        this.searchCancellation = cancellation;
        int centerX = this.state.centerX;
        int centerZ = this.state.centerZ;

		CompletableFuture<SearchResult> search = biome
			? this.searchBiomeSeed(contextLease.owner(), cancellation, target, centerX, centerZ)
			: this.searchTerrainSeed(contextLease.owner(), cancellation, target, centerX, centerZ);
		this.searchFuture = search;
		search.whenComplete((match, failure) -> {
            contextLease.close();
            Minecraft.getInstance().execute(() -> {
                if (this.searchCancellation != cancellation) {
                    return;
                }
                this.searchCancellation = null;
                this.searchFuture = null;
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
                if (cause != null) {
                    if (!PreviewCancellation.isCancellation(cause)) {
						FTFCommon.LOGGER.error("Failed searching preset preview", cause);
						result.accept(NavigationResult.failed(Component.translatableWithFallback(
							"freeterraforged.gui.preset_preview.search_error",
							"The search failed unexpectedly. Check the game log for details."
						)));
                    }
                    return;
                }
                if (match == null || match.match() == null) {
                    result.accept(NavigationResult.failed(match == null
						? Component.translatableWithFallback(
							"freeterraforged.gui.preset_preview.search_failed",
							"No matching location was found."
						)
						: match.failure()));
                    return;
                }
                this.applySearchMatch(match.match(), result);
            });
        });
    }

	private CompletableFuture<SearchResult> searchBiomeSeed(
		IPreviewHandler.PreparedContext context,
		PreviewCancellation cancellation,
		String target,
		int centerX,
		int centerZ
	) {
		BiomeSearchTarget predicate;
		try {
			predicate = BiomeSearchTarget.parse(target);
		} catch (RuntimeException failure) {
			return CompletableFuture.failedFuture(failure);
		}
		AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
		CompletableFuture<BiomeLocateSearch.Query> prepared = CompletableFuture.supplyAsync(() -> {
			cancellation.check();
			context.ensureBiomePreview();
			cancellation.check();
			return context.biomePreview().biomeLocateQuery(() -> !cancellation.isCancelled());
		}, net.minecraft.Util.backgroundExecutor());
		active.set(prepared);
		CompletableFuture<SearchResult> pipeline = prepared.thenCompose(query -> {
			cancellation.check();
			CompletableFuture<BiomeLocateSearch.Result> locate = BiomeLocateSearch.findFirst(
				query,
				predicate,
				new BlockPos(centerX, context.context.levels.waterLevel, centerZ)
			);
			active.set(locate);
			if (cancellation.isCancelled()) {
				locate.cancel(true);
			}
			return locate;
		}).thenApply(search -> {
			if (!search.targetPossible()) {
				return SearchResult.failed(Component.translatableWithFallback(
					"freeterraforged.gui.preset_preview.biome_impossible",
					"This preset does not generate %s.",
					target
				));
			}
			if (search.match() == null) {
				if (search.timedOut()) {
					return SearchResult.failed(Component.translatableWithFallback(
						"freeterraforged.gui.preset_preview.biome_search_timed_out",
						"Could not find biome %s within %s seconds.",
						target,
						LocateSearchSettings.DEFAULT_TIMEOUT_SECONDS
					));
				}
				return SearchResult.failed(Component.translatableWithFallback(
					"freeterraforged.gui.preset_preview.biome_not_found",
					"Could not find biome %s before reaching Minecraft's world limits.",
					target
				));
			}
			return SearchResult.found(new SearchMatch(
				search.match().position().getX(), search.match().position().getZ()
			));
		});
		return cancellable(pipeline, () -> {
			prepared.cancel(true);
			CompletableFuture<?> running = active.get();
			if (running != null) {
				running.cancel(true);
			}
		});
	}

	private CompletableFuture<SearchResult> searchTerrainSeed(
		IPreviewHandler.PreparedContext context,
		PreviewCancellation cancellation,
		String target,
		int centerX,
		int centerZ
	) {
		Terrain terrain = TerrainSearchTargets.get(target);
		if (terrain == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown terrain type: " + target + "."));
		}
		cancellation.check();
		CompletableFuture<TerrainLocateSearch.Result> locate = TerrainLocateSearch.findFirst(
			context.context,
			terrain,
			new BlockPos(centerX, context.context.levels.waterLevel, centerZ)
		);
		return mapCancellable(locate, search -> {
			if (search.match() == null) {
				if (search.timedOut()) {
					return SearchResult.failed(Component.translatableWithFallback(
						"freeterraforged.gui.preset_preview.terrain_search_timed_out",
						"Could not find terrain %s within %s seconds.",
						target,
						LocateSearchSettings.DEFAULT_TIMEOUT_SECONDS
					));
				}
				return SearchResult.failed(Component.translatableWithFallback(
					"freeterraforged.gui.preset_preview.terrain_not_found",
					"Could not find terrain %s before reaching Minecraft's world limits.",
					target
				));
			}
			return SearchResult.found(new SearchMatch(search.match().x(), search.match().z()));
		});
	}

	private static <S, T> CompletableFuture<T> mapCancellable(
		CompletableFuture<S> source,
		Function<S, T> mapper
	) {
		CompletableFuture<T> mapped = source.thenApply(mapper);
		return cancellable(mapped, () -> source.cancel(true));
	}

	private static <T> CompletableFuture<T> cancellable(
		CompletableFuture<T> source,
		Runnable cancel
	) {
		CompletableFuture<T> result = new CompletableFuture<>() {
			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				cancel.run();
				source.cancel(mayInterruptIfRunning);
				return super.cancel(mayInterruptIfRunning);
			}
		};
		source.whenComplete((value, failure) -> {
			if (failure == null) {
				result.complete(value);
			} else {
				result.completeExceptionally(failure);
			}
		});
		return result;
	}

    private void applySearchMatch(SearchMatch match, Consumer<NavigationResult> result) {
		// A successful editor search is a spawn selection, just like clicking the preview map.
		// The controller owns the context-specific behavior and recenters as part of that update.
		this.page.selectPreviewPoint(match.x(), match.z());
        result.accept(NavigationResult.success());
    }

    record NavigationResult(boolean found, Component failure) {
		static NavigationResult success() {
			return new NavigationResult(true, CommonComponents.EMPTY);
		}

		static NavigationResult failed(Component failure) {
			return new NavigationResult(false, failure);
		}
    }

    private record PendingSearch(boolean biome, String value, Consumer<NavigationResult> result) {
    }

    private record SearchMatch(int x, int z) {
    }

	private record SearchResult(SearchMatch match, Component failure) {
		static SearchResult found(SearchMatch match) {
			return new SearchResult(match, CommonComponents.EMPTY);
		}

		static SearchResult failed(Component failure) {
			return new SearchResult(null, failure);
		}
	}

    private static void drawCrosshair(GuiGraphics guiGraphics, int x, int y) {
        int size = 5;
        int color = 0xFFFFFFFF;
        int shadow = 0xFF000000;

        guiGraphics.fill(x - size + 1, y + 1, x + size + 2, y + 2, shadow);
        guiGraphics.fill(x - size, y, x + size + 1, y + 1, color);
        guiGraphics.fill(x + 1, y - size + 1, x + 2, y + size + 2, shadow);
        guiGraphics.fill(x, y - size, x + 1, y + size + 1, color);
    }

    @Override
    public boolean updateLegend(int mx, int my) {
        Levels levels = this.state.frameLevels;
        if (this.state.grid != null && levels != null) {
            int left = this.getX();
            int top = this.getY();
            float size = this.width;

            int currentZoom = this.getZoom();
            int width = Math.max(1, this.state.grid.size() * currentZoom);
            int height = Math.max(1, this.state.grid.size() * currentZoom);
            this.state.legendValues[0] = width + "x" + height;
            if (mx >= left && mx <= left + size && my >= top && my <= top + size) {
                float fx = (mx - left) / size;
                float fz = (my - top) / size;
                int maxIndex = this.state.grid.size() - 1;
                int ix = Math.max(0, Math.min(maxIndex, NoiseUtil.round(fx * maxIndex)));
                int iz = Math.max(0, Math.min(maxIndex, NoiseUtil.round(fz * maxIndex)));
                Cell cell = this.state.grid.lookup(ix, iz);
                this.state.legendValues[1] = IPreviewHandler.getTerrainName(cell);
                String biomeId = this.state.biomes == null ? null : this.state.biomes.legendId(ix, iz);
                PreviewDetails.Detail detail = PreviewDetails.forCell(
                        getRenderMode(), cell, levels, biomeId
                );
                this.state.legendLabels[2] = detail.label();
                this.state.legendValues[2] = detail.value();
                this.state.legendValues[3] = getSpawnCoords();

                int dx = (ix - (this.state.grid.size() / 2)) * currentZoom;
                int dz = (iz - (this.state.grid.size() / 2)) * currentZoom;

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
