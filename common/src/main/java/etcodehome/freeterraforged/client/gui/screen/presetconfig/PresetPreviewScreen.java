package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.client.gui.widget.Slider;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ActivePresetSnapshot;
import etcodehome.freeterraforged.network.PreviewLocateRequestPayload;
import etcodehome.freeterraforged.network.PreviewLocationPayload;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementConfig;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementConfigSnapshot;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Standalone, read-only version of FTF's native terrain preview. */
public final class PresetPreviewScreen extends Screen implements PreviewController {
	private static final String KEY = "freeterraforged.gui.preset_preview.";

	private final ActivePresetSnapshot snapshot;
	private final Preset preset;
	private final String presetName;
	private final PreviewRequestKeyFactory requestKeys = new PreviewRequestKeyFactory();
	private final PreviewRequestPool requests = new PreviewRequestPool();
	private final PreviewComputationCache cache = new PreviewComputationCache();
	private final BiomeReplacementConfigSnapshot replacements;

	private RegistryAccess.Frozen registries;
	private LevelStem selectedStem;
	private WorldDataConfiguration dataConfiguration;
	private Slider zoom2D;
	private Slider zoom3D;
	private CycleButton<RenderMode> renderMode2D;
	private CycleButton<RenderMode> renderMode3D;
	private Preview2D preview2D;
	private Preview3D preview3D;
	private PreviewNavigation navigation;
	private Component status = Component.empty();
	private int navigationX;
	private int navigationZ;
	private boolean navigated;
	private long latestLocateRequest;
	private boolean locatePending;
	private boolean closing;

	private PresetPreviewScreen(ActivePresetSnapshot snapshot, Preset preset) {
		super(Component.translatableWithFallback(KEY + "title", "FreeTerraForged Preview"));
		this.snapshot = snapshot;
		this.preset = preset.copy();
		String name = this.preset.presentation().presetName;
		this.presetName = name == null || name.isBlank()
			? Component.translatableWithFallback(KEY + "unnamed", "Unnamed preset").getString()
			: name;
		String content = snapshot.biomeReplacements().isBlank()
			? "format_version=1\n"
			: snapshot.biomeReplacements();
		this.replacements = new BiomeReplacementConfigSnapshot(
			0L,
			sha256(content),
			"server:" + snapshot.dimension(),
			BiomeReplacementConfigSnapshot.Origin.NATIVE,
			content,
			BiomeReplacementConfig.parse(content),
			false
		);
	}

	public static Screen create(ActivePresetSnapshot snapshot) {
		try {
			Preset preset = Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(snapshot.presetJson()))
				.getOrThrow(message -> new IllegalArgumentException("Invalid active preset snapshot: " + message));
			return new PresetPreviewScreen(snapshot, preset);
		} catch (RuntimeException failure) {
			return new PreviewUnavailableScreen(failure.getMessage());
		}
	}

	public static void handleLocation(PreviewLocationPayload location) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof PresetPreviewScreen screen) {
			screen.acceptLocation(location);
		}
	}

	@Override
	protected void init() {
		try {
			this.prepareEnvironment();
			this.createControls();
			this.layoutControls();
			this.preview2D.regenerate();
			this.preview3D.regenerate();
		} catch (RuntimeException | LinkageError failure) {
			this.status = Component.translatableWithFallback(
				KEY + "unavailable", "Preview unavailable: %s", message(failure)
			).withStyle(ChatFormatting.RED);
			this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignored -> this.onClose())
				.bounds(this.width / 2 - 75, this.height - 27, 150, 20).build());
		}
	}

	private void prepareEnvironment() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getConnection() == null) {
			throw new IllegalStateException("No active server connection");
		}
		this.registries = minecraft.getConnection().registryAccess();
		Holder<DimensionType> dimensionType = this.registries.lookupOrThrow(Registries.DIMENSION_TYPE)
			.getOrThrow(BuiltinDimensionTypes.OVERWORLD);
		Holder<NoiseGeneratorSettings> settings = this.registries.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		Holder<MultiNoiseBiomeSourceParameterList> parameters = this.registries
			.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
			.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
		this.selectedStem = new LevelStem(
			dimensionType,
			new TerraForgedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(parameters), settings)
		);
		this.dataConfiguration = WorldDataConfiguration.DEFAULT.expandFeatures(
			minecraft.getConnection().enabledFeatures()
		);
	}

	private void createControls() {
		this.zoom2D = PresetWidgets.createIntSlider(
			(int) Math.round(PresetEditorPage.staticZoom2D), PresetEditorPage.minZoom, PresetEditorPage.maxZoom,
			FTFTranslationKeys.GUI_SLIDER_ZOOM, (slider, value) -> {
				PresetEditorPage.staticZoom2D = ((Slider) slider).getLerpedValue();
				if (this.preview2D != null) this.preview2D.regenerate();
				return value;
			}
		);
		this.zoom2D.setValue((PresetEditorPage.staticZoom2D - 1.0D) / 99.0D);
		this.zoom3D = PresetWidgets.createIntSlider(
			(int) Math.round(PresetEditorPage.staticZoom3D), PresetEditorPage.minZoom, PresetEditorPage.maxZoom,
			FTFTranslationKeys.GUI_SLIDER_ZOOM, (slider, value) -> {
				PresetEditorPage.staticZoom3D = ((Slider) slider).getLerpedValue();
				if (this.preview3D != null) this.preview3D.regenerate();
				return value;
			}
		);
		this.zoom3D.setValue((PresetEditorPage.staticZoom3D - 1.0D) / 99.0D);
		this.renderMode2D = PresetWidgets.createCycle(
			ImmutableList.copyOf(RenderMode.values()), PresetEditorPage.staticMode2D,
			FTFTranslationKeys.GUI_BUTTON_RENDER_MODE,
			(button, value) -> {
				PresetEditorPage.staticMode2D = value;
				if (this.preview2D != null) this.preview2D.refreshRenderMode(value);
			}, RenderMode::displayName
		);
		this.renderMode3D = PresetWidgets.createCycle(
			ImmutableList.copyOf(RenderMode.values()), PresetEditorPage.staticMode3D,
			FTFTranslationKeys.GUI_BUTTON_RENDER_MODE,
			(button, value) -> {
				PresetEditorPage.staticMode3D = value;
				if (this.preview3D != null) this.preview3D.refreshRenderMode(value);
			}, RenderMode::displayName
		);
	}

	private void layoutControls() {
		int margin = 12;
		int gap = 12;
		int top = 42;
		PreviewNavigation.ColumnLayout layout = PreviewNavigation.ColumnLayout.fromTop(top);
		int availableWidth = this.width - margin * 2 - gap;
		int panelWidth = Math.max(100, availableWidth / 2);
		int doneY = this.height - 27;
		int previewSize = Math.max(80, Math.min(
			panelWidth,
			doneY - layout.previewY() - Preview2D.LEGEND_FOOTER_HEIGHT - 4
		));
		int leftX = Math.max(margin, this.width / 2 - gap / 2 - previewSize);
		int rightX = this.width / 2 + gap / 2;

		this.zoom2D.setRectangle(previewSize, 20, leftX, layout.zoomY());
		this.renderMode2D.setRectangle(previewSize, 20, leftX, layout.modeY());
		this.zoom3D.setRectangle(previewSize, 20, rightX, layout.zoomY());
		this.renderMode3D.setRectangle(previewSize, 20, rightX, layout.modeY());
		this.preview2D = new Preview2D(this, leftX, layout.previewY(), previewSize, previewSize);
		this.preview3D = new Preview3D(this, rightX, layout.previewY(), previewSize, previewSize);

		this.addRenderableWidget(this.preview2D);
		this.addRenderableWidget(this.preview3D);
		this.addRenderableWidget(this.zoom2D);
		this.addRenderableWidget(this.renderMode2D);
		this.addRenderableWidget(this.zoom3D);
		this.addRenderableWidget(this.renderMode3D);

		this.navigation = PreviewNavigation.forInWorldPreview(
			this.font, leftX, previewSize, layout, () -> this.registries, new PreviewNavigation.Handler() {
			@Override
			public void edited() {
				PresetPreviewScreen.this.cancelLocate();
				PresetPreviewScreen.this.status = Component.empty();
			}

			@Override
			public void here() {
				PresetPreviewScreen.this.navigateTo(
					PresetPreviewScreen.this.snapshot.playerX(), PresetPreviewScreen.this.snapshot.playerZ()
				);
			}

			@Override
			public void spawn() {
				PresetPreviewScreen.this.navigateTo(
					PresetPreviewScreen.this.snapshot.spawnX(), PresetPreviewScreen.this.snapshot.spawnZ()
				);
			}

			@Override
			public void coordinates(int x, int z) {
				PresetPreviewScreen.this.navigateTo(x, z);
			}

			@Override
			public void terrain(String name) {
				PresetPreviewScreen.this.requestLocate(PreviewLocateRequestPayload.TargetType.TERRAIN, name);
			}

			@Override
			public void biome(String id) {
				PresetPreviewScreen.this.requestLocate(PreviewLocateRequestPayload.TargetType.BIOME, id);
			}

			@Override
			public void invalid(Component reason) {
				PresetPreviewScreen.this.status = reason.copy().withStyle(ChatFormatting.RED);
			}
		});
		this.addRenderableWidget(this.navigation.input());
		this.addRenderableWidget(this.navigation.submitButton());
		this.addRenderableWidget(this.navigation.suggestionsWidget());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignored -> this.onClose())
			.bounds(this.width / 2 - 50, this.height - 27, 100, 20).build());
	}

	private void requestLocate(PreviewLocateRequestPayload.TargetType type, String target) {
		if (this.minecraft.getConnection() == null) {
			this.navigation.failed();
			this.status = Component.translatableWithFallback(KEY + "disconnected", "No active server connection")
				.withStyle(ChatFormatting.RED);
			return;
		}
		this.cancelLocate();
		long requestId = ++this.latestLocateRequest;
		this.minecraft.getConnection().send(new ServerboundCustomPayloadPacket(new PreviewLocateRequestPayload(
			requestId, type, target.trim(), this.snapshot.dimension()
		)));
		this.locatePending = true;
		this.navigation.pending();
		this.status = Component.translatableWithFallback(
			KEY + "searching", "Searching for %s %s...", type.name().toLowerCase(java.util.Locale.ROOT), target
		).withStyle(ChatFormatting.YELLOW);
	}

	private void acceptLocation(PreviewLocationPayload location) {
		if (location.requestId() != this.latestLocateRequest
			|| !location.dimension().equals(this.snapshot.dimension())) {
			return;
		}
		this.locatePending = false;
		if (!location.found()) {
			this.navigation.failed();
			this.status = Component.literal(location.message()).withStyle(ChatFormatting.RED);
			return;
		}
		this.setPreviewNavigation(location.x(), location.z());
		this.regeneratePreviews();
		this.navigation.succeeded();
		this.status = Component.literal(
			location.message() + " at x" + location.x() + " z" + location.z()
		).withStyle(ChatFormatting.GREEN);
	}

	private void navigateTo(int x, int z) {
		this.cancelLocate();
		this.setPreviewNavigation(x, z);
		this.regeneratePreviews();
		this.navigation.succeeded();
		this.status = Component.translatableWithFallback(
			KEY + "centered", "Centered on x%s z%s", x, z
		).withStyle(ChatFormatting.GREEN);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (this.navigation != null && this.navigation.mouseClicked(this, mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.navigation != null
			&& this.navigation.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
		graphics.drawCenteredString(
			this.font,
			Component.literal(this.presetName + "  •  " + this.snapshot.dimension()).withStyle(ChatFormatting.GRAY),
			this.width / 2,
			22,
			0xA0A0A0
		);
		if (!this.status.getString().isEmpty()) {
			graphics.drawCenteredString(this.font, this.status, this.width / 2, 34, 0xFFFFFF);
		}
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		this.closePreviewResources();
		this.minecraft.setScreen(null);
	}

	@Override
	public void removed() {
		this.closePreviewResources();
		super.removed();
	}

	private void closePreviewResources() {
		if (this.closing) {
			return;
		}
		this.closing = true;
		this.cancelLocate();
		if (this.preview2D != null) {
			this.preview2D.close();
			this.preview2D = null;
		}
		if (this.preview3D != null) {
			this.preview3D.close();
			this.preview3D = null;
		}
		this.requests.close();
		this.cache.close();
		this.requestKeys.close();
	}

	private void cancelLocate() {
		if (!this.locatePending) {
			return;
		}
		this.locatePending = false;
		this.latestLocateRequest++;
		if (this.minecraft.getConnection() != null) {
			this.minecraft.getConnection().send(new ServerboundCustomPayloadPacket(new PreviewLocateRequestPayload(
				this.latestLocateRequest,
				PreviewLocateRequestPayload.TargetType.CANCEL,
				"",
				this.snapshot.dimension()
			)));
		}
	}

	@Override public Preset preset() { return this.preset; }
	@Override public PreviewRequestKeyFactory.Input capturePreviewInput() {
		return this.requestKeys.capture(
			this.snapshot.seed(), this.preset, this.dataConfiguration,
			this.registries, this.selectedStem, this.replacements
		);
	}
	@Override public PreviewRequestKeyFactory previewRequestKeys() { return this.requestKeys; }
	@Override public PreviewRequestPool previewRequests() { return this.requests; }
	@Override public PreviewComputationCache previewCache() { return this.cache; }
	@Override public Slider zoom2D() { return this.zoom2D; }
	@Override public Slider zoom3D() { return this.zoom3D; }
	@Override public CycleButton<RenderMode> renderMode2D() { return this.renderMode2D; }
	@Override public CycleButton<RenderMode> renderMode3D() { return this.renderMode3D; }
	@Override public int previewNavigationX() { return this.navigationX; }
	@Override public int previewNavigationZ() { return this.navigationZ; }
	@Override public boolean previewNavigated() { return this.navigated; }
	@Override public void setPreviewNavigation(int x, int z) {
		this.navigationX = x;
		this.navigationZ = z;
		this.navigated = true;
	}
	@Override public void resetPreviewNavigation() { this.navigated = false; }
	@Override public void selectPreviewPoint(int x, int z) { this.navigateTo(x, z); }
	@Override public void regeneratePreviews() {
		if (this.preview2D != null) this.preview2D.regenerate();
		if (this.preview3D != null) this.preview3D.regenerate();
	}

	private static String sha256(String content) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))
			);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}

	private static String message(Throwable failure) {
		return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
	}

	private static final class PreviewUnavailableScreen extends Screen {
		private final String reason;

		private PreviewUnavailableScreen(String reason) {
			super(Component.translatableWithFallback(KEY + "title", "FreeTerraForged Preview"));
			this.reason = reason == null ? "Invalid preset snapshot" : reason;
		}

		@Override
		protected void init() {
			this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignored -> this.onClose())
				.bounds(this.width / 2 - 75, this.height / 2 + 20, 150, 20).build());
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			this.renderBackground(graphics, mouseX, mouseY, partialTick);
			graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 30, 0xFFFFFF);
			graphics.drawCenteredString(this.font, this.reason, this.width / 2, this.height / 2 - 8, 0xFF5555);
			super.render(graphics, mouseX, mouseY, partialTick);
		}

		@Override
		public void onClose() {
			this.minecraft.setScreen(null);
		}
	}
}
