package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.GuiGraphics;
import org.apache.commons.io.file.PathUtils;

import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;

import net.minecraft.Util;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.DataGenerator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.levelgen.WorldOptions;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.data.worldgen.Datapacks;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public class PresetConfigScreen extends LinkedPageScreen {
	private CreateWorldScreen parent;
	private final PreviewComputationCache previewCache = new PreviewComputationCache();
	private String seed;
	private boolean seedInitialized;
	private boolean applySeedOnClose;
	private boolean isDfcActive;

	public PresetConfigScreen(CreateWorldScreen parent) {
		this.parent = parent;
		this.isDfcActive = !isDfcDisabledSafely();

		RTFCommon.LOGGER.info("[RTF Debug] DFC Active Status (From Config Only): {}", this.isDfcActive);

		if (!this.isDfcActive) {
			this.currentPage = new PresetListPage(this);
		}
	}

	@Override
	public void init() {
		if (this.isDfcActive) {
			RTFCommon.LOGGER.info("[RTF Debug] Hijacking init() to show C2ME incompatibility screen.");
			this.clearWidgets();
			int centerX = this.width / 2;
			int centerY = this.height / 2;

			// Button 1: Open Config Folder (Wider layout, stacked on top)
			this.addRenderableWidget(Button.builder(Component.literal("Open Config Folder"), button -> {
				Path configFolder = Path.of("config");
				Util.ioPool().execute(() -> Util.getPlatform().openUri(configFolder.toUri()));
			}).bounds(centerX - 100, centerY + 30, 200, 20).build());

			// Button 2: Back to Menu (Wider layout, stacked on bottom)
			this.addRenderableWidget(Button.builder(Component.literal("Back to Menu"), button -> {
				this.minecraft.setScreen(this.parent);
			}).bounds(centerX - 100, centerY + 55, 200, 20).build());

			return;
		}

		super.init();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		if (this.isDfcActive) {
			// 1. Draw base background and let parent LinkedPageScreen finish rendering its background layers/widgets
			this.renderBackground(guiGraphics, mouseX, mouseY, delta);
			super.render(guiGraphics, mouseX, mouseY, delta);

			// 2. Render translucent dark backdrop panel over parent screen layers
			guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

			int centerX = this.width / 2;
			int centerY = this.height / 2;

			// Fallback to Minecraft instance font if screen font is uninitialized
			Font font = this.font != null ? this.font : net.minecraft.client.Minecraft.getInstance().font;

			// 3. Draw centered warning text with full 0xFF (255) Alpha channels on top layer
			guiGraphics.drawCenteredString(font, Component.literal("§c§lCRITICAL INCOMPATIBILITY DETECTED"), centerX, centerY - 65, 0xFFFFFFFF);
			guiGraphics.drawCenteredString(font, Component.literal("FreeTerraForged has detected that C2ME's 'Density Function Compiler' is active."), centerX, centerY - 40, 0xFFDDDDDD);
			guiGraphics.drawCenteredString(font, Component.literal("This performance optimization completely breaks custom generation loops."), centerX, centerY - 25, 0xFFDDDDDD);

			guiGraphics.drawCenteredString(font, Component.literal("§ePlease modify your configuration layout to continue safely:"), centerX, centerY, 0xFFFFFFFF);
			guiGraphics.drawCenteredString(font, Component.literal("§7Open §fconfig/c2me.toml§7 and set §fuseDensityFunctionCompiler = false"), centerX, centerY + 13, 0xFFFFFFFF);

			return;
		}

		super.render(guiGraphics, mouseX, mouseY, delta);
	}

	@Override
	public void onClose() {
		this.previewCache.close();
		super.onClose();
		if(this.applySeedOnClose) {
			this.applySeedToParent();
		}

		this.minecraft.setScreen(this.parent);
	}

	PreviewComputationCache previewCache() {
		return this.previewCache;
	}

	public <T extends GuiEventListener & Renderable & NarratableEntry> T addWidgetToScreen(T widget) {
		return this.addRenderableWidget(widget);
	}

	public void removeWidgetFromScreen(AbstractWidget widget) {
		this.removeWidget(widget);
	}

	public void setSeed(String seed) {
		this.seed = seed;
		this.seedInitialized = true;
	}

	public String getSeed() {
		if(!this.seedInitialized) {
			String parentSeed = this.parent.getUiState().getSeed();
			this.seed = parentSeed == null || parentSeed.trim().isEmpty() ? String.valueOf(this.parent.getUiState().getSettings().options().seed()) : parentSeed;
			this.seedInitialized = true;
		}
		return this.seed;
	}

	public WorldCreationContext getSettings() {
		WorldCreationContext settings = this.parent.getUiState().getSettings();
		if(this.seedInitialized && this.seed != null && !this.seed.trim().isEmpty()) {
			settings = settings.withOptions((options) -> options.withSeed(WorldOptions.parseSeed(this.seed)));
		}
		return settings;
	}

	@Override
	public void onDone() {
		this.applySeedOnClose = true;
		this.applySeedToParent();
		super.onDone();
		this.applySeedToParent();
	}

	private void applySeedToParent() {
		this.parent.getUiState().setSeed(this.getSeed());
	}

	public void applyPreset(PresetEntry preset) throws IOException {
		Pair<Path, PackRepository> path = this.parent.getDataPackSelectionSettings(this.parent.getUiState().getSettings().dataConfiguration());
		Path exportPath = path.getFirst().resolve("reterraforged-preset.zip");
		this.exportAsDatapack(exportPath, preset);
		PackRepository repository = path.getSecond();
		repository.reload();
		if(repository.addPack("file/" + exportPath.getFileName())) {
			this.parent.tryApplyNewDataPacks(repository, false, (data) -> {
			});
		}
	}

	public void exportAsDatapack(Path outputPath, PresetEntry presetEntry) throws IOException {
		Path datagenPath = Files.createTempDirectory("datagen-target-");
		Path datagenOutputPath = datagenPath.resolve("output");

		RegistryAccess registryAccess = this.getSettings().worldgenLoadContext();

		Preset preset = presetEntry.getPreset();
		Component presetName = presetEntry.getName();

		DataGenerator dataGenerator = Datapacks.makePreset(preset, registryAccess, datagenPath, datagenOutputPath, presetName.getString());
		dataGenerator.run();
		copyToZip(datagenOutputPath, outputPath);
		PathUtils.deleteDirectory(datagenPath);

		RTFCommon.LOGGER.info("Exported datapack to {}", outputPath);
	}

	private static void copyToZip(Path input, Path output) {
		Map<String, String> env = ImmutableMap.of("create", "true");
		URI uri = URI.create("jar:" + output.toUri());
		try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
			PathUtils.copyDirectory(input, fs.getPath("/"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static boolean isDfcDisabledSafely() {
		try {
			String value = getC2MEDensityCompilerSetting();
			RTFCommon.LOGGER.info("[RTF Debug] Result of getC2MEDensityCompilerSetting(): {}", value);
			return "false".equalsIgnoreCase(value);
		} catch (Exception e) {
			RTFCommon.LOGGER.error("[RTF Debug] Exception caught in isDfcDisabledSafely() wrapper:", e);
			return false;
		}
	}

	private static String getC2MEDensityCompilerSetting() {
		try {
			Path configFolder = Path.of("config");
			Path configPath = configFolder.resolve("c2me.toml");

			RTFCommon.LOGGER.info("[RTF Debug] Checking existence of c2me.toml at: {}", configPath.toAbsolutePath());

			if (!Files.exists(configPath)) {
				RTFCommon.LOGGER.info("[RTF Debug] config/c2me.toml does not exist. Assuming DFC module is disabled/default.");
				return "false";
			}

			RTFCommon.LOGGER.info("[RTF Debug] config/c2me.toml found. Beginning line-by-line walk.");
			try (BufferedReader reader = Files.newBufferedReader(configPath)) {
				String line;
				boolean inTargetSection = false;

				while ((line = reader.readLine()) != null) {
					String rawLine = line;
					line = line.trim();

					if (line.startsWith("[") && line.endsWith("]")) {
						inTargetSection = line.equalsIgnoreCase("[vanillaWorldGenOptimizations]");
						RTFCommon.LOGGER.info("[RTF Debug] Parsed Section Header: {} | Match Status: {}", line, inTargetSection);
						continue;
					}

					if (inTargetSection && line.startsWith("useDensityFunctionCompiler")) {
						RTFCommon.LOGGER.info("[RTF Debug] Found target key line: {}", rawLine);
						String[] parts = line.split("=", 2);
						if (parts.length == 2) {
							String parsedValue = parts[1].split("#")[0].trim().replace("\"", "").replace("'", "").toLowerCase();
							RTFCommon.LOGGER.info("[RTF Debug] Successfully matched and extracted value: \"{}\"", parsedValue);
							return parsedValue;
						}
					}
				}
			}
		} catch (Exception e) {
			RTFCommon.LOGGER.error("[RTF Debug] Exception caught inside getC2MEDensityCompilerSetting() line loop:", e);
			return "error_fallback";
		}
		RTFCommon.LOGGER.info("[RTF Debug] Walk finished. Target section or configuration key was not explicitly found in file.");
		return "false";
	}
}

