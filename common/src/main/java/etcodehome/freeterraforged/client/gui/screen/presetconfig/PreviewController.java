package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import etcodehome.freeterraforged.client.gui.widget.Slider;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import net.minecraft.client.gui.components.CycleButton;

/** The mutable screen controls and immutable generation inputs shared by preview widgets. */
interface PreviewController {
	Preset preset();

	PreviewRequestKeyFactory.Input capturePreviewInput();

	PreviewRequestKeyFactory previewRequestKeys();

	PreviewRequestPool previewRequests();

	PreviewComputationCache previewCache();

	Slider zoom2D();

	Slider zoom3D();

	CycleButton<RenderMode> renderMode2D();

	CycleButton<RenderMode> renderMode3D();

	int previewNavigationX();

	int previewNavigationZ();

	boolean previewNavigated();

	void setPreviewNavigation(int x, int z);

	void resetPreviewNavigation();

	void selectPreviewPoint(int x, int z);

	void regeneratePreviews();
}
