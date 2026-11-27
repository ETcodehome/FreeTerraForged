package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import com.mojang.blaze3d.platform.NativeImage;
import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.data.worldgen.preset.settings.GeologySettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.geology.RockType;
import etcodehome.freeterraforged.world.worldgen.geology.StructuredGeology;
import etcodehome.freeterraforged.world.worldgen.surface.rule.GeologicalSurfaceRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;

import java.util.function.IntSupplier;

final class StrataPreview extends AbstractWidget implements AutoCloseable {
    private static final int IMAGE_WIDTH = 384;
    private static final int IMAGE_HEIGHT = 192;
    private static final int[] SLICE_OFFSETS = {0, 1, -1, 3, -3, 7, -7};
    static final int SLICE_COUNT = SLICE_OFFSETS.length * 2;

    private final Preset preset;
    private final long seed;
    private final IntSupplier span;
    private final IntSupplier centerY;
    private final IntSupplier slice;
    private final DynamicTexture texture;
    private final ResourceLocation textureId;
    private final byte[] samples = new byte[IMAGE_WIDTH * IMAGE_HEIGHT];
    private final Block[] materials = new Block[RockType.values().length];
    private Frame frame;

    StrataPreview(Preset preset, long seed, IntSupplier span, IntSupplier centerY, IntSupplier slice,
                  int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable(FTFTranslationKeys.GUI_STRATA_PREVIEW));
        this.preset = preset;
        this.seed = seed;
        this.span = span;
        this.centerY = centerY;
        this.slice = slice;
        this.texture = new DynamicTexture(new NativeImage(IMAGE_WIDTH, IMAGE_HEIGHT, false));
        this.textureId = Minecraft.getInstance().getTextureManager().register("freeterraforged-strata-preview", this.texture);
        this.active = false;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        var font = Minecraft.getInstance().font;
        var settings = this.preset.miscellaneous();
        graphics.fill(x - 1, y - 1, x + this.width + 1, y + this.height + 1, 0xFF101316);
        if (!settings.strataDecorator || !settings.structuredStrata) {
            graphics.drawWordWrap(font, Component.translatable(FTFTranslationKeys.GUI_STRATA_PREVIEW_DISABLED),
                x + 6, y + 6, this.width - 12, 0xFFE2E5E7);
            return;
        }

        var world = this.preset.world().properties;
        int minY = -Math.max(0, world.worldDepth);
        int maxY = Math.max(0, world.worldHeight - 1);
        int verticalSpan = Math.min(IMAGE_HEIGHT, maxY - minY + 1);
        int bottom = Math.clamp(this.centerY.getAsInt() - verticalSpan / 2, minY, maxY - verticalSpan + 1);
        int section = this.slice.getAsInt();
        int offset = SLICE_OFFSETS[section / 2] * Math.clamp(settings.strataRegionSize / 3, 64, 1024);
        Frame current = new Frame(settings.strataRegionSize, world.seaLevel, settings.geologicalTilt,
            settings.geologicalFolding, settings.geology, settings.oreCompatibleStoneOnly,
            this.span.getAsInt(), bottom, verticalSpan, section % 2 == 0, offset);
        if (!current.equals(this.frame)) {
            this.renderSection(current);
            this.frame = current;
        }

        graphics.blit(this.textureId, x, y, this.width, this.height,
            0.0F, 0.0F, IMAGE_WIDTH, IMAGE_HEIGHT, IMAGE_WIDTH, IMAGE_HEIGHT);
        Component caption = Component.translatable(FTFTranslationKeys.GUI_STRATA_PREVIEW_COORDINATES,
            current.alongX() ? "Z" : "X", current.fixed(), current.bottom(), current.bottom() + current.verticalSpan() - 1);
        graphics.drawWordWrap(font, caption, x, y + this.height + 5, this.width, 0xFFE2E5E7);
        if (mouseX >= x && mouseX < x + this.width && mouseY >= y && mouseY < y + this.height) {
            int column = (mouseX - x) * IMAGE_WIDTH / this.width;
            int row = (mouseY - y) * IMAGE_HEIGHT / this.height;
            Block material = this.materials[this.samples[row * IMAGE_WIDTH + column]];
            graphics.renderTooltip(font, Component.translatable(FTFTranslationKeys.GUI_STRATA_PREVIEW_SAMPLE,
                material.getName(), current.x(column), current.y(row), current.z(column)), mouseX, mouseY);
        }
    }

    private void renderSection(Frame frame) {
        var geology = StructuredGeology.create(this.seed, frame.regionSize(), frame.seaLevel(), frame.tilt(),
            frame.folding(), frame.geology());
        NativeImage pixels = this.texture.getPixels();
        boolean hasOreTag = BuiltInRegistries.BLOCK.getTag(BlockTags.STONE_ORE_REPLACEABLES).isPresent();
        int[] colors = new int[this.materials.length];
        for (RockType type : RockType.values()) {
            RockType visible = type;
            if (frame.oreCompatibleOnly()) {
                boolean oreStone = hasOreTag ? GeologicalSurfaceRules.materialFor(type, false).is(BlockTags.STONE_ORE_REPLACEABLES)
                    : type == RockType.STONE || type == RockType.GRANITE || type == RockType.ANDESITE || type == RockType.DIORITE;
                if (!oreStone) visible = RockType.STONE;
            }
            this.materials[type.ordinal()] = GeologicalSurfaceRules.materialFor(visible, false).getBlock();
            colors[type.ordinal()] = color(visible);
        }
        for (int column = 0; column < IMAGE_WIDTH; column++) {
            int x = frame.x(column);
            int z = frame.z(column);
            for (int row = 0; row < IMAGE_HEIGHT; row++) {
                int material = geology.sample(x, frame.y(row), z).type().ordinal();
                this.samples[row * IMAGE_WIDTH + column] = (byte) material;
                pixels.setPixelRGBA(column, row, colors[material]);
            }
        }
        this.texture.upload();
    }

    private static int color(RockType type) {
        int rgb = switch (type) {
            case STONE -> 0x7B7E82;
            case GRANITE -> 0xB97868;
            case DIORITE -> 0xB4B7B2;
            case ANDESITE -> 0x8B8896;
            case CALCITE -> 0xEBE6D9;
            case TUFF -> 0x636F63;
            case DRIPSTONE -> 0x8C5D51;
            case GRAVEL -> 0xA39A91;
            case PACKED_MUD -> 0xAA805F;
            case BLACKSTONE -> 0x27232B;
            case DEEPSLATE -> 0x424047;
            case BASALT -> 0x575359;
        };
        return 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | (rgb >>> 16);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        this.defaultButtonNarrationText(narration);
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(this.textureId);
    }

    private record Frame(int regionSize, int seaLevel, float tilt, float folding, GeologySettings geology,
                         boolean oreCompatibleOnly, int span, int bottom, int verticalSpan, boolean alongX, int fixed) {
        int x(int column) {
            return this.alongX ? this.along(column) : this.fixed;
        }

        int z(int column) {
            return this.alongX ? this.fixed : this.along(column);
        }

        int y(int row) {
            return this.bottom + (IMAGE_HEIGHT - 1 - row) * this.verticalSpan / IMAGE_HEIGHT;
        }

        private int along(int column) {
            return column * this.span / IMAGE_WIDTH - this.span / 2;
        }
    }
}
