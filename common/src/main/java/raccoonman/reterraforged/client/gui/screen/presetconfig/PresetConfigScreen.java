package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId;
import org.apache.commons.io.file.PathUtils;

import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.DataGenerator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.levelgen.WorldOptions;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.gui.Toasts;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import raccoonman.reterraforged.data.worldgen.Datapacks;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

public class PresetConfigScreen extends LinkedPageScreen {
	private CreateWorldScreen parent;
	private final PreviewComputationCache previewCache = new PreviewComputationCache();
	private final PreviewRequestPool previewRequests = new PreviewRequestPool();
	private final PreviewRequestKeyFactory previewRequestKeys = new PreviewRequestKeyFactory();
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
			this.applySeedToParent();
			this.releaseResources();
		}
		return result;
	}

	private void applySeedToParent() {
		this.parent.getUiState().setSeed(this.getSeed());
	}

	public SaveResult applyPreset(PresetEntry preset) throws IOException {
		Pair<Path, PackRepository> path = this.parent.getDataPackSelectionSettings(this.parent.getUiState().getSettings().dataConfiguration());
		if(path == null) {
			throw new IOException("Unable to create the temporary datapack repository");
		}
		Path exportPath = path.getFirst().resolve("reterraforged-preset.zip");
		this.exportAsDatapack(exportPath, preset);
		PackRepository repository = path.getSecond();
		repository.reload();
		if(!repository.addPack("file/" + exportPath.getFileName())) {
			throw new IOException("The generated ReTerraForged datapack was not discovered by Minecraft");
		}
		this.parent.tryApplyNewDataPacks(repository, false, (data) -> {
		});
		return SaveResult.SCREEN_TRANSITION;
	}

	public SaveResult reportPresetApplyFailure(IOException exception) {
		RTFCommon.LOGGER.error("Failed to stage the ReTerraForged preset datapack", exception);
		Component message = exception.getMessage() == null ? Component.literal(exception.getClass().getSimpleName()) : Component.literal(exception.getMessage());
		Toasts.notify("dataPack.validation.failed", message, SystemToastId.PACK_LOAD_FAILURE);
		return SaveResult.STAY_OPEN;
	}
	
	public void exportAsDatapack(Path outputPath, PresetEntry presetEntry) throws IOException {
		Path datagenPath = Files.createTempDirectory("datagen-target-");
		Path datagenOutputPath = datagenPath.resolve("output");
		try {
			RegistryAccess registryAccess = this.getSettings().worldgenLoadContext();
			Preset preset = presetEntry.getPreset();
			Component presetName = presetEntry.getName();
			DataGenerator dataGenerator = Datapacks.makePreset(preset, registryAccess, datagenPath, datagenOutputPath, presetName.getString());
			dataGenerator.run();
			writeZipAtomically(datagenOutputPath, outputPath);
		} finally {
			PathUtils.deleteDirectory(datagenPath);
		}
		RTFCommon.LOGGER.info("Exported datapack to {}", outputPath);
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
			RTFCommon.LOGGER.error("Failed closing preset-screen resources", failure);
		}
	}

	private static void writeZipAtomically(Path input, Path output) throws IOException {
		Path parent = output.getParent();
		if(parent == null) {
			throw new IOException("Preset datapack output has no parent directory: " + output);
		}
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
		try {
			Files.delete(temporary);
			copyToZip(input, temporary);
			try {
				Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch(AtomicMoveNotSupportedException exception) {
				Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void copyToZip(Path input, Path output) throws IOException {
		Map<String, String> env = ImmutableMap.of("create", "true");
	    URI uri = URI.create("jar:" + output.toUri());
	    try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
	        PathUtils.copyDirectory(input, fs.getPath("/"), StandardCopyOption.REPLACE_EXISTING);
	    }
	}
}
