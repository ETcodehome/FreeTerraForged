package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import etcodehome.freeterraforged.client.gui.widget.Slider;
import etcodehome.freeterraforged.data.worldgen.preset.settings.GeologySettings.Layers;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.function.Consumer;

final class StrataSettingsPage extends PresetEditorPage {
    private StrataPreview preview;
    private int span = 640;
    private int slice;
    private Integer centerY;

    StrataSettingsPage(PresetConfigScreen screen) {
        super(screen);
    }

    @Override
    protected boolean showTerrainPreviews() {
        return false;
    }

    @Override
    public void init() {
        this.closePreview();
        super.init();
        var preset = this.preset.getPreset();
        var settings = preset.miscellaneous();
        var layers = settings.geology.layers();

        this.screen.removeWidgetFromScreen(this.right);
        int gap = 12;
        this.left.setWidth((this.screen.width - 2 * this.left.getX() - gap) * 43 / 100);
        int x = this.left.getX() + this.left.getWidth() + gap;
        int width = this.screen.width - x - 10;
        int top = this.left.getY() + 4;

        this.addToggle(settings.strataDecorator, FTFTranslationKeys.GUI_BUTTON_STRATA_DECORATOR,
            value -> settings.strataDecorator = value);
        this.addToggle(settings.structuredStrata, FTFTranslationKeys.GUI_BUTTON_STRUCTURED_STRATA,
            value -> settings.structuredStrata = value);
        this.addToggle(settings.oreCompatibleStoneOnly, FTFTranslationKeys.GUI_BUTTON_ORE_COMPATIBLE_STONE_ONLY,
            value -> settings.oreCompatibleStoneOnly = value);
        this.left.addWidget(PresetWidgets.createIntSlider(settings.strataRegionSize, 50, 1000,
            FTFTranslationKeys.GUI_SLIDER_STRATA_REGION_SIZE, (slider, value) -> {
                settings.strataRegionSize = (int) slider.scaleValue(value);
                this.regenerate();
                return value;
            }));
        this.addSlider(settings.geologicalTilt, 0, 1, FTFTranslationKeys.GUI_SLIDER_GEOLOGICAL_TILT,
            value -> settings.geologicalTilt = value);
        this.addSlider(settings.geologicalFolding, 0, 2, FTFTranslationKeys.GUI_SLIDER_GEOLOGICAL_FOLDING,
            value -> settings.geologicalFolding = value);
        this.addSlider(settings.geology.faultThrow(), 0, 96, FTFTranslationKeys.GUI_SLIDER_FAULT_THROW,
            value -> settings.geology = settings.geology.withFaultThrow(value));
        this.addToggle(settings.geology.intrusions(), FTFTranslationKeys.GUI_BUTTON_GEOLOGICAL_INTRUSIONS,
            value -> settings.geology = settings.geology.withIntrusions(value));
        this.addToggle(settings.geology.unconformities(), FTFTranslationKeys.GUI_BUTTON_UNCONFORMITIES,
            value -> settings.geology = settings.geology.withUnconformities(value));
        this.addSlider(layers.bedThicknessScale(), Layers.MIN_BED_SCALE, Layers.MAX_BED_SCALE,
            FTFTranslationKeys.GUI_SLIDER_BED_THICKNESS, value ->
                settings.geology = settings.geology.withLayers(settings.geology.layers().withBedThicknessScale(value)));
        this.addSlider(layers.contrastFrequency(), 0, 1, FTFTranslationKeys.GUI_SLIDER_CONTRAST_FREQUENCY, value ->
            settings.geology = settings.geology.withLayers(settings.geology.layers().withContrastFrequency(value)));
        this.addSlider(layers.contrastThickness(), 0, Layers.MAX_CONTRAST_THICKNESS,
            FTFTranslationKeys.GUI_SLIDER_CONTRAST_THICKNESS, value ->
                settings.geology = settings.geology.withLayers(settings.geology.layers().withContrastThickness(value)));
        this.addSlider(layers.gravelThickness(), 0, Layers.MAX_GRAVEL_THICKNESS,
            FTFTranslationKeys.GUI_SLIDER_GRAVEL_THICKNESS, value ->
                settings.geology = settings.geology.withLayers(settings.geology.layers().withGravelThickness(value)));
        this.addSlider(layers.lensThickness(), 0, Layers.MAX_LENS_THICKNESS, FTFTranslationKeys.GUI_SLIDER_LENS_THICKNESS,
            value -> settings.geology = settings.geology.withLayers(settings.geology.layers().withLensThickness(value)));

        this.screen.addWidgetToScreen(Button.builder(Component.translatable(FTFTranslationKeys.GUI_BUTTON_NEXT_STRATA_SLICE),
            button -> this.slice = (this.slice + 1) % StrataPreview.SLICE_COUNT).bounds(x, top, width, 20).build());
        var spanSlider = PresetWidgets.createIntSlider(this.span, 64, 2048, FTFTranslationKeys.GUI_SLIDER_STRATA_PREVIEW_SPAN,
            (slider, value) -> {
                this.span = (int) slider.scaleValue(value);
                return value;
            });
        this.placePreviewSlider(spanSlider, x, top + 24, width);
        var world = preset.world().properties;
        if (this.centerY == null) {
            this.centerY = Math.clamp(world.seaLevel + 40, -world.worldDepth, world.worldHeight - 1);
        }
        var elevationSlider = PresetWidgets.createIntSlider(this.centerY, -world.worldDepth, world.worldHeight - 1,
            FTFTranslationKeys.GUI_SLIDER_STRATA_PREVIEW_Y, (slider, value) -> {
                this.centerY = (int) slider.scaleValue(value);
                return value;
            });
        this.placePreviewSlider(elevationSlider, x, top + 48, width);

        int previewY = top + 74;
        int height = Math.max(16, Math.min(width * 2 / 3, this.screen.height - previewY - 56));
        this.preview = new StrataPreview(preset, this.screen.getSettings().options().seed(),
            () -> this.span, () -> this.centerY, () -> this.slice, x, previewY, width, height);
        this.screen.addWidgetToScreen(this.preview);
    }

    private void placePreviewSlider(Slider slider, int x, int y, int width) {
        slider.setX(x);
        slider.setY(y);
        slider.setWidth(width);
        slider.setHeight(20);
        this.screen.addWidgetToScreen(slider);
    }

    private void addToggle(boolean initial, String key, Consumer<Boolean> setter) {
        this.left.addWidget(PresetWidgets.createToggle(initial, key, (button, value) -> {
            setter.accept(value);
            this.regenerate();
        }));
    }

    private void addSlider(float initial, float min, float max, String key, Consumer<Float> setter) {
        this.left.addWidget(PresetWidgets.createFloatSlider(initial, min, max, key, (slider, value) -> {
            setter.accept((float) slider.scaleValue(value));
            this.regenerate();
            return value;
        }));
    }

    private void closePreview() {
        if (this.preview != null) {
            this.preview.close();
            this.preview = null;
        }
    }

    @Override
    public void onCancel() {
        this.closePreview();
        super.onCancel();
    }

    @Override
    public Component title() {
        return Component.translatable(FTFTranslationKeys.GUI_STRATA_SETTINGS_TITLE);
    }

    @Override
    public Optional<Page> previous() {
        return Optional.of(new ErosionFilterSettingsPage(this.screen));
    }

    @Override
    public Optional<Page> next() {
        return Optional.of(new MiscellaneousPage(this.screen));
    }
}
