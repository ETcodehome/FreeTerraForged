package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
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
                    self.page.regenerate();
                }
            }
        }, DEFAULT_NARRATION);
        this.page = parent;
    }

    public void regenerate() {
        WorldCreationContext settings = this.page.getScreen().getSettings();
        RegistryAccess.Frozen registries = settings.worldgenLoadContext();
        HolderLookup.Provider provider = this.page.preset.getPreset().buildPatch(registries);
        HolderGetter<Preset> presets = provider.lookupOrThrow(RTFRegistries.PRESET);
        HolderGetter<Noise> noises = provider.lookupOrThrow(RTFRegistries.NOISE);
        Preset presetObj = presets.getOrThrow(Preset.KEY).value();
        WorldSettings world = presetObj.world();
        WorldSettings.Properties properties = world.properties;

        try {
            CacheManager.clear();
        } catch (Exception e) {
            e.printStackTrace();
        }
        PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
                .resultOrPartial(RTFCommon.LOGGER::error)
                .orElseGet(PerformanceConfig::makeDefault);
        GeneratorContext generatorContext = GeneratorContext.makeUncached(presetObj, noises, (int) settings.options().seed(), FACTOR, 0, config.batchCount());

        this.centerX = 0;
        this.centerZ = 0;

        if (presetObj.world().properties.spawnType == SpawnType.CONTINENT_CENTER) {
            long nearestContinentCenter = generatorContext.lookup.getHeightmap().continent().getNearestCenter(this.offsetX, this.offsetZ);
            this.centerX = PosUtil.unpackLeft(nearestContinentCenter);
            this.centerZ = PosUtil.unpackRight(nearestContinentCenter);
        } else if (presetObj.world().properties.spawnType == SpawnType.USER_SELECTED) {
            this.centerX = presetObj.world().properties.spawnX;
            this.centerZ = presetObj.world().properties.spawnZ;
        } else {
            this.centerX = 0;
            this.centerZ = 0;
        }
        this.legendValues[0] = getSpawnCoords();

        this.tile = generatorContext.generator.generateZoomed(this.centerX, this.centerZ, this.getZoom(), false).join();
        RenderMode mode = this.page.renderMode2D.getValue();
        Levels levels = new Levels(properties.terrainScaler(), properties.seaLevel);

        int stroke = 2;
        int width = this.tile.getBlockSize().size();

        NativeImage pixels = this.texture.getPixels();
        this.tile.iterate((cell, bx, bz) -> {
            if (bx < stroke || bz < stroke || bx >= width - stroke || bz >= width - stroke) {
                pixels.setPixelRGBA(bx, bz, Color.BLACK.getRGB());
            } else {
                pixels.setPixelRGBA(bx, bz, mode.getColor(cell, levels));
            }
        });
        this.texture.upload();
    }

    public void close() throws Exception {
        this.texture.close();
        try {
            CacheManager.clear();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mx, int my, float partialTicks) {
        int xPos = this.getX();
        int yPos = this.getY();

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        guiGraphics.blit(this.textureId, xPos, yPos, 0, 0, this.width, this.height, this.width, this.height);

        renderSpawnMarker(guiGraphics);
        this.updateLegend(mx, my);
        this.renderLegend(guiGraphics, mx, my, this.legendLabels, this.legendValues, xPos, yPos + this.width + 40, 10, 0xFFFFFF);
    }

    private void renderSpawnMarker(GuiGraphics guiGraphics) {
        WorldSettings.Properties props = this.page.preset.getPreset().world().properties;

        if (props.spawnType == SpawnType.USER_SELECTED || props.spawnType == SpawnType.CONTINENT_CENTER) {
            int currentZoom = this.getZoom();

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
        if (props.spawnType == SpawnType.WORLD_ORIGIN) {
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