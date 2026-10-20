package etcodehome.freeterraforged.client.data;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import etcodehome.freeterraforged.platform.ConfigUtil;

public final class DefaultPresetArchive {
	private static final Path SAVED_ARCHIVE = ConfigUtil.ftf("default-preset.zip");

	private DefaultPresetArchive() {
	}

	public static Path path() {
		return SAVED_ARCHIVE;
	}

	public static boolean exists() {
		return Files.isRegularFile(SAVED_ARCHIVE);
	}

	public static void copyTo(Path dataPackDirectory) throws IOException {
		if (!exists()) {
			throw new IOException("No saved default preset datapack exists at " + SAVED_ARCHIVE);
		}

		Files.createDirectories(dataPackDirectory);
		Path target = dataPackDirectory.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
		Path temporary = Files.createTempFile(dataPackDirectory, "freeterraforged-default-", ".tmp");
		try {
			Files.copy(SAVED_ARCHIVE, temporary, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
