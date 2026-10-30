package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.io.IOException;
import java.nio.file.Path;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.levelgen.WorldOptions;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.gui.Toasts;
import etcodehome.freeterraforged.client.gui.screen.page.LinkedPageScreen;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresetDataPacks;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;

public class PresetConfigScreen extends LinkedPageScreen {
	private CreateWorldScreen parent;
	private final PreviewComputationCache previewCache = new PreviewComputationCache();
	private final PreviewRequestPool previewRequests = new PreviewRequestPool();
	private final PreviewRequestKeyFactory previewRequestKeys = new PreviewRequestKeyFactory();
	private PreviewNavigation previewNavigation;
	private String seed;
	private boolean seedInitialized;
	private boolean resourcesClosed;

	public PresetConfigScreen(CreateWorldScreen parent) {
		this.parent = parent;
		this.currentPage = new PresetListPage(this);
	}

	@Override
	public void onClose() {
		try {
			this.releaseResources();
		} finally {
			this.minecraft.setScreen(this.parent);
		}
	}

	PreviewComputationCache previewCache() {
		return this.previewCache;
	}

	PreviewRequestPool previewRequests() {
		return this.previewRequests;
	}

	PreviewRequestKeyFactory previewRequestKeys() {
		return this.previewRequestKeys;
	}

	public <T extends GuiEventListener & Renderable & NarratableEntry> T addWidgetToScreen(T widget) {
		return this.addRenderableWidget(widget);
	}

	public void removeWidgetFromScreen(AbstractWidget widget) {
		this.removeWidget(widget);
	}

	void setPreviewNavigation(PreviewNavigation navigation) {
		this.previewNavigation = navigation;
	}

	void clearPreviewNavigation(PreviewNavigation navigation) {
		if (this.previewNavigation == navigation) {
			this.previewNavigation = null;
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (this.previewNavigation != null
			&& this.previewNavigation.mouseClicked(this, mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.previewNavigation != null
			&& this.previewNavigation.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
	public SaveResult onDone() {
		SaveResult result = super.onDone();
		if(result == SaveResult.SCREEN_TRANSITION) {
			this.releaseResources();
		}
		return result;
	}

	private void applySeedToParent() {
		this.parent.getUiState().setSeed(this.getSeed());
	}

	public SaveResult applyPreset(PresetEntry preset) throws IOException {
		PresetManager.PM.ingestFromPreset(preset);
		Pair<Path, PackRepository> path = this.parent.getDataPackSelectionSettings(this.parent.getUiState().getSettings().dataConfiguration());
		if(path == null) {
			throw new IOException("Unable to create the temporary datapack repository");
		}
		Path exportPath = path.getFirst().resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
		this.exportAsDatapack(exportPath, preset);
		PackRepository repository = path.getSecond();
		repository.reload();
		String generatedPackId = FTFWorldPresets.GENERATED_PRESET_PACK_ID;
		if (!repository.isAvailable(generatedPackId)) {
			throw new IOException("The generated FreeTerraForged preset datapack was not discovered by Minecraft");
		}
		if (!repository.getSelectedIds().contains(generatedPackId) && !repository.addPack(generatedPackId)) {
			throw new IOException("The generated FreeTerraForged preset datapack could not be selected");
		}
		// Publish the seed before reload captures WorldOptions.
		this.applySeedToParent();
		this.parent.tryApplyNewDataPacks(repository, false, (data) -> {
		});
		return SaveResult.SCREEN_TRANSITION;
	}

	public SaveResult reportPresetApplyFailure(IOException exception) {
		FTFCommon.LOGGER.error("Failed to stage the FreeTerraForged preset datapack", exception);
		Component message = exception.getMessage() == null ? Component.literal(exception.getClass().getSimpleName()) : Component.literal(exception.getMessage());
		Toasts.notify("dataPack.validation.failed", message, SystemToastId.PACK_LOAD_FAILURE);
		return SaveResult.STAY_OPEN;
	}

	public void updateDefaultPresetIfSource(PresetListPage.PresetEntry presetEntry) throws IOException {
	}

	public void exportAsDatapack(Path outputPath, PresetListPage.PresetEntry presetEntry) throws IOException {
		FTFWorldPresetDataPacks.exportPresetAsDatapack(
			outputPath,
			presetEntry.getPreset(),
			presetEntry.getName().getString(),
			this.getSettings().worldgenLoadContext()
		);
	}

	private void releaseResources() {
		if(this.resourcesClosed) {
			return;
		}
		this.resourcesClosed = true;
		closeAll(
			this.currentPage::onCancel,
			this.previewRequests::close,
			this.previewCache::close,
			this.previewRequestKeys::close
		);
	}

	private static void closeAll(Runnable... operations) {
		Throwable failure = null;
		for (Runnable operation : operations) {
			try {
				operation.run();
			} catch (RuntimeException | Error closeFailure) {
				if (failure == null) {
					failure = closeFailure;
				} else if (closeFailure instanceof Error && !(failure instanceof Error)) {
					closeFailure.addSuppressed(failure);
					failure = closeFailure;
				} else {
					failure.addSuppressed(closeFailure);
				}
			}
		}
		if (failure instanceof Error error) {
			throw error;
		}
		if (failure != null) {
			FTFCommon.LOGGER.error("Failed closing preset-screen resources", failure);
		}
	}
}
