package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.DataGenerator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.apache.commons.io.file.PathUtils;

import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;

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
		this.isDfcActive = C2MEChecker.isDfcActive();

		if (!this.isDfcActive) {
			this.currentPage = new PresetListPage(this);
		}
	}

	@Override
	public void init() {
		if (this.isDfcActive) {
			this.clearWidgets();
			C2MEChecker.initIncompatibilityScreen(this, this.parent);
			return;
		}

		super.init();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		if (this.isDfcActive) {
			this.renderBackground(guiGraphics, mouseX, mouseY, delta);
			super.render(guiGraphics, mouseX, mouseY, delta);
			C2MEChecker.renderIncompatibilityOverlay(guiGraphics, this.font, this.width, this.height);
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
}