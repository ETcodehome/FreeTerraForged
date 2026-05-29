package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
import java.io.IOException;
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
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
import raccoonman.reterraforged.client.gui.screen.page.BisectedPage;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.client.gui.widget.Slider;
import raccoonman.reterraforged.client.gui.widget.ValueButton;
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

public abstract class PresetEditorPage extends BisectedPage<PresetConfigScreen, AbstractWidget, AbstractWidget> {
	private Slider zoom;
	private CycleButton<RenderMode> renderMode;
	private ValueButton<Integer> seed;
	private Preview preview;
	protected PresetEntry preset;
	
	public PresetEditorPage(PresetConfigScreen screen, PresetEntry preset) {
		super(screen);
		
		this.preset = preset;
	}
	
	protected void regenerate() {
		this.preview.regenerate();
	}

	@Override
	public void init() {
		super.init();

		// Cleanup previous preview instance if it exists
		if (this.preview != null) {
			this.screen.removeWidgetFromScreen(this.preview);
			try {
				this.preview.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		// Initialize controls
		this.zoom = PresetWidgets.createIntSlider(Optional.ofNullable(this.zoom).map(Slider::getLerpedValue).orElse(68.0D).intValue(), 1, 100, RTFTranslationKeys.GUI_SLIDER_ZOOM, (slider, value) -> {
			this.regenerate();
			return value;
		});

		this.renderMode = PresetWidgets.createCycle(ImmutableList.copyOf(RenderMode.values()), this.renderMode != null ? this.renderMode.getValue() : RenderMode.BIOME_TYPE, RTFTranslationKeys.GUI_BUTTON_RENDER_MODE, (button, value) -> {
			this.regenerate();
		}, RenderMode::name);

		this.seed = PresetWidgets.createRandomButton(RTFTranslationKeys.GUI_BUTTON_SEED, (int) this.screen.getSettings().options().seed(), (i) -> {
			this.screen.setSeed(i);
			this.regenerate();
		});

		// Add control widgets to the list
		this.right.addWidget(this.zoom);
		this.right.addWidget(this.renderMode);
		this.right.addWidget(this.seed);

		// Calculate dynamic layout values
		int elementWidth = this.right.getRowWidth();
		int paddingX = ((this.right.getWidth() - elementWidth) / 2);
		int forceOffset = 2;
		int x = this.right.getX() + paddingX + forceOffset;

		// Use BisectedPage's getTotalListHeight to determine how much vertical space buttons occupy
		int gap = 10;
		int y = this.right.getY() + this.getTotalListHeight(this.right) + gap;

		// Initialize the Preview square
		this.preview = new Preview(x, y, elementWidth, elementWidth);
		this.preview.regenerate();

		/*
		 * Register the preview directly to the screen to bypass WidgetList's
		 * slot-height clipping, ensuring the full square is clickable.
		 */
		this.screen.addWidgetToScreen(this.preview);
	}
	
	@Override
	public void onClose() {
		super.onClose();
	
		try {
			this.preset.save();
			this.preview.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void onDone() {
		super.onDone();
		
		try {
			this.screen.applyPreset(this.preset);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public class Preview extends Button {
	    private static final int FACTOR = 4;
	    public static final int SIZE = (1 << 4) << FACTOR;
	    private static final float[] LEGEND_SCALES = { 1, 0.9F, 0.75F, 0.6F };
	    private DynamicTexture texture = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
	    private ResourceLocation textureId = Minecraft.getInstance().getTextureManager().register(RTFCommon.MOD_ID + "-preview-framebuffer", this.texture); 
	    private Tile tile;
	    private int centerX, centerZ;

		private int hoveredCoordX = 0;
		private int hoveredCoordZ = 0;
	    private String hoveredCoords = "";
	    //TODO maybe make this a map or something instead?
	    private String[] legendValues = {"", "", "", ""};
	    private Component[] legendLabels = { Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_AREA), Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_TERRAIN), Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_BIOME), Component.translatable(RTFTranslationKeys.GUI_LABEL_PREVIEW_SPAWN) };

	    private int offsetX, offsetZ;

	    public Preview(int x, int y, int width, int height) {
			super(x, y, width, height, CommonComponents.EMPTY, (b) -> {
				if (b instanceof Preview self) {
					Minecraft mc = Minecraft.getInstance();
					// Convert raw mouse pixels to scaled GUI coordinates
					double guiX = mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getWidth();
					double guiY = mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getHeight();

					if (self.updateLegend((int) guiX, (int) guiY) && !self.hoveredCoords.isEmpty()) {
						// copy the coords to the clipboard and play click sound
			            self.playDownSound(Minecraft.getInstance().getSoundManager());
			            PresetEditorPage.this.screen.minecraft.keyboardHandler.setClipboard(self.hoveredCoords);

						// set the spawn point by clicking
						WorldSettings.Properties props = preset.getPreset().world().properties;
						props.spawnType = SpawnType.USER_SELECTED;
						props.spawnX = self.hoveredCoordX;
						props.spawnZ = self.hoveredCoordZ;

						// Synchronize the UI button if we are on the WorldSettingsPage
						if (PresetEditorPage.this instanceof WorldSettingsPage worldPage) {
							if (worldPage.spawnType != null) {
								worldPage.spawnType.setValue(SpawnType.USER_SELECTED);
							}
						}

						self.regenerate();
			        }
	        	}
	        }, DEFAULT_NARRATION);
	    }

	    public void regenerate() {
			WorldCreationContext settings = PresetEditorPage.this.screen.getSettings();
	        RegistryAccess.Frozen registries = settings.worldgenLoadContext();
	        HolderLookup.Provider provider = PresetEditorPage.this.preset.getPreset().buildPatch(registries);
	        HolderGetter<Preset> presets = provider.lookupOrThrow(RTFRegistries.PRESET);
	        HolderGetter<Noise> noises = provider.lookupOrThrow(RTFRegistries.NOISE);
	        Preset preset = presets.getOrThrow(Preset.KEY).value();
	        WorldSettings world = preset.world();
	        WorldSettings.Properties properties = world.properties;
	        
	        try {
				CacheManager.clear();
			} catch (Exception e) {
				e.printStackTrace();
			}
			PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
					.resultOrPartial(RTFCommon.LOGGER::error)
					.orElseGet(PerformanceConfig::makeDefault);
			GeneratorContext generatorContext = GeneratorContext.makeUncached(preset, noises, (int) settings.options().seed(), FACTOR, 0, config.batchCount());

			/*
			// single threaded diagnostic config
			GeneratorContext generatorContext = GeneratorContext.makeUncached(
					preset, noises, (int) settings.options().seed(), FACTOR, 0, 1
			);
			 */

			this.centerX = 0;
			this.centerZ = 0;

			if(preset.world().properties.spawnType == SpawnType.CONTINENT_CENTER) {
				long nearestContinentCenter = generatorContext.lookup.getHeightmap().continent().getNearestCenter(this.offsetX, this.offsetZ);
				this.centerX = PosUtil.unpackLeft(nearestContinentCenter);
				this.centerZ = PosUtil.unpackRight(nearestContinentCenter);
			} else if (preset.world().properties.spawnType == SpawnType.USER_SELECTED){
				this.centerX = preset.world().properties.spawnX;
				this.centerZ = preset.world().properties.spawnZ;
	        } else {
	        	this.centerX = 0;
	        	this.centerZ = 0;
	        }

	        this.tile = generatorContext.generator.generateZoomed(this.centerX, this.centerZ, this.getZoom(), false).join();
	        RenderMode renderMode = PresetEditorPage.this.renderMode.getValue();
	        Levels levels = new Levels(properties.terrainScaler(), properties.seaLevel);

	        int stroke = 2;
	        int width = this.tile.getBlockSize().size();

		private void rebuildTexture() {
			if (this.tile == null) return;

			if (this.textureCache != null) {
				this.textureCache.close();
				Minecraft.getInstance().getTextureManager().release(this.cacheLocation);
			}

			if (this.width <= 0 || this.height <= 0) return;

			NativeImage img = new NativeImage(this.width, this.height, true);

			for (int y = 0; y < img.getHeight(); y++) {
				for (int x = 0; x < img.getWidth(); x++) {
					img.setPixelRGBA(x, y, 0xFF000000);
				}
			}

			RenderMode renderMode = PresetEditorPage.this.renderMode.getValue();
			WorldSettings.Properties properties = preset.getPreset().world().properties;
			Levels levels = new Levels(properties.terrainScaler(), properties.seaLevel);

			int tileSize = this.tile.getBlockSize().size();

			float rawBlockW = (float) this.width / (float) tileSize * 0.85f;
			int halfW = Math.max(1, (int) (rawBlockW / 2.0f));
			int halfH = Math.max(1, halfW / 2);

			int blockW = halfW * 2;
			int blockH = halfH * 2;

			// MINIMAL UPDATE: True geometric center in local texture space
			int centerVisualX = this.width / 2;
			int centerVisualY = this.height / 2;

			float heightScale = getHeightScale((float) blockW);

			// Center the data indices around (0,0) to align the world center with centerVisualX/Y
			int halfTile = tileSize / 2;

			for (int iz = 0; iz < tileSize; iz++) {
				for (int ix = 0; ix < tileSize; ix++) {
					Cell cell = this.tile.lookup(ix, iz);
					int color = renderMode.getColor(cell, levels);

					// Extract RGB components from ARGB format
					int r = (color >> 16) & 0xFF;
					int g = (color >> 8) & 0xFF;
					int b = color & 0xFF;

					// Convert to HSB space to isolate brightness
					float[] hsb = Color.RGBtoHSB(r, g, b, null);

					// Generate a stable, pseudo-random variation between -0.03 and +0.03 based on coordinates
					int hash = ix * 31 + iz * 17;
					float jitter = ((hash % 100) / 100.0f) * 0.06f - 0.03f;

					// Apply variance to brightness and clamp strictly between 0.0f and 1.0f
					hsb[2] = Math.max(0.0f, Math.min(1.0f, hsb[2] + jitter));

					// Pack back into standard ARGB color space
					int jitteredColor = (color & 0xFF000000) | (Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0x00FFFFFF);

					// Offset world loop indices relative to center index
					int dx = ix - halfTile;
					int dz = iz - halfTile;

					int isoX = centerVisualX + (dx - dz) * halfW;
					int isoY = centerVisualY + (dx + dz) * halfH;
					int renderY = isoY - Math.round(cell.height * heightScale);

					// Use the newly jittered color for the face calculations
					int topColor = toNativeABGR(jitteredColor);
					int leftColor = getSideColor(jitteredColor, 0.75f, true, ix, iz, tileSize);
					int rightColor = getSideColor(jitteredColor, 0.60f, false, ix, iz, tileSize);

					fillPixelRect(img, isoX, renderY, isoX + blockW, renderY + blockH, topColor);
					fillPixelRect(img, isoX, renderY + blockH, isoX + halfW, isoY + blockH, leftColor);
					fillPixelRect(img, isoX + halfW, renderY + blockH, isoX + blockW, isoY + blockH, rightColor);
				}
			}

			this.textureCache = new DynamicTexture(img);
			this.cacheLocation = Minecraft.getInstance().getTextureManager().register("rtf_preview_cache_" + this.hashCode(), this.textureCache);
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

		private int toNativeABGR(int argb) {
			int a = (argb >> 24) & 0xFF;
			int r = (argb >> 16) & 0xFF;
			int g = (argb >> 8) & 0xFF;
			int b = argb & 0xFF;
			return (a << 24) | (b << 16) | (g << 8) | r;
		}

		private int darkenColor(int argb, float factor) {
			int a = (argb >> 24) & 0xFF;
			int r = Math.max(0, (int) (((argb >> 16) & 0xFF) * factor));
			int g = Math.max(0, (int) (((argb >> 8) & 0xFF) * factor));
			int b = Math.max(0, (int) ((argb & 0xFF) * factor));
			return (a << 24) | (r << 16) | (g << 8) | b;
		}

		public void close() throws Exception {
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
	    public void renderWidget(GuiGraphics guiGraphics, int mx, int my, float partialTicks) {
	    	int x = this.getX();
	    	int y = this.getY();

			if (this.needsTextureRefresh || this.textureCache == null) {
				this.rebuildTexture();
			}

			if (this.cacheLocation != null) {
				guiGraphics.blit(this.cacheLocation, x, y, 0.0F, 0.0F, this.width, this.height, this.width, this.height);
			} else {
				guiGraphics.fill(x, y, x + this.width, y + this.height, 0xFF000000);
			}

			renderSpawnMarker(guiGraphics);

	    	this.updateLegend(mx, my);

	    	this.renderLegend(guiGraphics, mx, my, this.legendLabels, this.legendValues, x, y + this.width + 40, 10, 0xFFFFFF);
	    }

		private float getHeightScale(float blockW) {
			float zoomProgress = (float) (PresetEditorPage.this.zoom.getLerpedValue() - 1.0D) / 99.0f;
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
			return toNativeABGR(darkenColor(baseColor, shadeFactor));
		}

		private void renderSpawnMarker(GuiGraphics guiGraphics) {
			WorldSettings.Properties props = preset.getPreset().world().properties;

			// Check if the current spawn type should be displayed
			if (props.spawnType == SpawnType.USER_SELECTED || props.spawnType == SpawnType.CONTINENT_CENTER) {
				int zoom = this.getZoom();

				// Map world coordinates to the tile's relative center
				float relX = (float) (props.spawnX - this.centerX) / (this.tile.getBlockSize().size() * zoom);
				float relZ = (float) (props.spawnZ - this.centerZ) / (this.tile.getBlockSize().size() * zoom);

				// Convert relative ratio to screen pixel coordinates
				int markerX = this.getX() + (this.width / 2) + (int) (relX * this.width);
				int markerY = this.getY() + (this.height / 2) + (int) (relZ * this.height);

					float rawBlockW = (float) this.width / (float) tileSize * 0.85f;
					int halfW = Math.max(1, (int) (rawBlockW / 2.0f));
					int halfH = Math.max(1, halfW / 2);

					int blockW = halfW * 2;
					int blockH = halfH * 2;

					// MINIMAL UPDATE: Calculate absolute widget coordinate space correctly
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

					// Draw a horizontal line with a 1-pixel black shadow for better visibility
					// Shadow (1px offset)
					guiGraphics.fill(markerX - size + 1, markerY + 1, markerX + size + 2, markerY + 2, shadow);
					// Main Line
					guiGraphics.fill(markerX - size, markerY, markerX + size + 1, markerY + 1, color);

					// Draw a vertical line
					// Shadow (1px offset)
					guiGraphics.fill(markerX + 1, markerY - size + 1, markerX + 2, markerY + size + 2, shadow);
					// Main Line
					guiGraphics.fill(markerX, markerY - size, markerX + 1, markerY + size + 1, color);
				}
			}
		}

		private boolean updateLegend(int mx, int my) {
			if (this.tile != null) {
				int left = this.getX();
				int top = this.getY();

				int zoom = this.getZoom();
				int tileSize = this.tile.getBlockSize().size();

				int width = Math.max(1, tileSize * zoom);
				int height = Math.max(1, tileSize * zoom);
				this.legendValues[0] = width + "x" + height;

				float rawBlockW = (float) this.width / (float) tileSize * 0.85f;
				int halfW = Math.max(1, (int) (rawBlockW / 2.0f));
				int halfH = Math.max(1, halfW / 2);

				// MINIMAL UPDATE: Align absolute cursor metrics into local coordinate offsets
				int centerVisualX = left + (this.width / 2);
				int centerVisualY = top + (this.height / 2);

				float relMouseX = mx - centerVisualX;
				float relMouseY = my - centerVisualY;

				// Invert isometric projection tracking with center offset adjustment
				int dx = NoiseUtil.round((relMouseX / halfW + relMouseY / halfH) / 2.0f);
				int dz = NoiseUtil.round((relMouseY / halfH - relMouseX / halfW) / 2.0f);

				int ix = dx + (tileSize / 2);
				int iz = dz + (tileSize / 2);

				if (ix >= 0 && ix < tileSize && iz >= 0 && iz < tileSize) {
					Cell cell = this.tile.lookup(ix, iz);
					this.legendValues[1] = getTerrainName(cell);
					this.legendValues[2] = getBiomeName(cell);
					this.legendValues[3] = getSpawnCoords();

					int worldOffsetX = (ix - (tileSize / 2)) * zoom;
					int worldOffsetZ = (iz - (tileSize / 2)) * zoom;

					this.hoveredCoords = (this.centerX + worldOffsetX) + ":" + (this.centerZ + worldOffsetZ);
					this.hoveredCoordX = this.centerX + worldOffsetX;
					this.hoveredCoordZ = this.centerZ + worldOffsetZ;
					return true;
				} else {
					this.hoveredCoords = "";
				}
			}
			return false;
		}

	    private float getLegendScale() {
	        int index = PresetEditorPage.this.screen.minecraft.options.guiScale().get() - 1;
	        if (index < 0 || index >= LEGEND_SCALES.length) {
	            // index=-1 == GuiScale(AUTO) which is the same as GuiScale(4)
	            // values above 4 don't exist but who knows what mods might try set it to
	            // in both cases use the smallest acceptable scale
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
	        int spacing = 0;
	        for (Component s : labels) {
	            spacing = Math.max(spacing, renderer.width(s));
	        }
	
	        float maxWidth = (this.width - 4) / scale;
	        for (int i = 0; i < labels.length && i < values.length; i++) {
	        	Component label = labels[i];
	            String value = values[i];
	
	            while (value.length() > 0 && spacing + renderer.width(value) > maxWidth) {
	                value = value.substring(0, value.length() - 1);
	            }
	
	            guiGraphics.drawString(renderer, label, 0, i * lineHeight, color);
	            guiGraphics.drawString(renderer, value, spacing, i * lineHeight, color);
	        }
	
	        pose.popPose();
	
	        if (!this.hoveredCoords.isEmpty()) {
	        	guiGraphics.drawCenteredString(renderer, this.hoveredCoords, mx, my - 10, 0xFFFFFF);
	        }
	    }
	
	    private int getZoom() {
	        return NoiseUtil.round(1.5F * (101 - (float) PresetEditorPage.this.zoom.getLerpedValue()));
	    }
	
	    private static String getTerrainName(Cell cell) {
	        if (cell.terrain.isRiver()) {
	            return "river";
	        }
	        return cell.terrain.getName().toLowerCase();
	    }

		private String getSpawnCoords() {

			if (WorldSettings.Properties.spawnType == SpawnType.USER_SELECTED) {
				return "x" + WorldSettings.Properties.spawnX + " z" + WorldSettings.Properties.spawnZ;
			}

			if (WorldSettings.Properties.spawnType == SpawnType.CONTINENT_CENTER) {
				return "~x" + this.centerX + " ~z" + this.centerZ;
			}

			if (WorldSettings.Properties.spawnType == SpawnType.ISLANDS) {
				return "~x" + this.centerX + " ~z" + this.centerZ;
			}

			if (WorldSettings.Properties.spawnType == SpawnType.WORLD_ORIGIN) {
				return "x0 z0";
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
	}
}
