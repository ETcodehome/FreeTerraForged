package etcodehome.freeterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import etcodehome.freeterraforged.FTFCommon;

class BiomeReplacementConfigStoreTest {
	@TempDir
	Path configRoot;

	@Test
	void createsDocumentedNativeTemplateWhenNoLegacyFileExists() throws IOException {
		BiomeReplacementConfigSnapshot snapshot = BiomeReplacementConfigStore.capture(this.configRoot);
		Path destination = this.destination();

		assertTrue(Files.isRegularFile(destination));
		assertEquals(BiomeReplacementConfigSnapshot.Origin.CREATED_TEMPLATE, snapshot.origin());
		assertEquals(BiomeReplacementConfig.ValidationMode.STRICT, snapshot.config().validationMode());
		assertTrue(snapshot.content().contains("standard FTF world type"));
		assertTrue(snapshot.content().contains("config/biome_replacer.properties"));
		assertTrue(snapshot.content().contains("minecraft:dark_forest > minecraft:cherry_grove"));
		assertFalse(snapshot.content().contains("validation=strict"));
		assertFalse(snapshot.content().contains("placement=preserve"));
	}

	@Test
	void migratesLegacyContentOnceAndMakesDestinationAuthoritative() throws IOException {
		Path legacy = this.configRoot.resolve(BiomeReplacementConfigStore.LEGACY_FILE_NAME);
		String legacyContent = "minecraft:plains > minecraft:desert\n";
		Files.writeString(legacy, legacyContent, StandardCharsets.UTF_8);

		BiomeReplacementConfigSnapshot migrated = BiomeReplacementConfigStore.capture(this.configRoot);
		assertEquals(BiomeReplacementConfigSnapshot.Origin.MIGRATED_LEGACY, migrated.origin());
		assertTrue(migrated.content().startsWith(legacyContent));
		assertEquals(
			BiomeReplacementConfig.ValidationMode.COMPATIBILITY,
			migrated.config().validationMode()
		);
		assertEquals(1, migrated.config().rules().size());

		Files.writeString(legacy, "minecraft:forest > minecraft:badlands\n", StandardCharsets.UTF_8);
		BiomeReplacementConfigSnapshot unchanged = BiomeReplacementConfigStore.capture(this.configRoot);
		assertSame(migrated, unchanged);
		assertTrue(Files.readString(this.destination()).startsWith(legacyContent));
	}

	@Test
	void existingNativeFileWinsEvenWhenIntentionallyEmpty() throws IOException {
		Files.createDirectories(this.destination().getParent());
		Files.writeString(this.destination(), "", StandardCharsets.UTF_8);
		Files.writeString(
			this.configRoot.resolve(BiomeReplacementConfigStore.LEGACY_FILE_NAME),
			"minecraft:plains > minecraft:desert\n",
			StandardCharsets.UTF_8
		);

		BiomeReplacementConfigSnapshot snapshot = BiomeReplacementConfigStore.capture(this.configRoot);
		assertEquals(BiomeReplacementConfigSnapshot.Origin.NATIVE, snapshot.origin());
		assertEquals("", snapshot.content());
		assertEquals(0, snapshot.config().rules().size());
		assertTrue(snapshot.legacyFilePresent());
	}

	@Test
	void newlyPresentLegacyFileAdvancesDiagnosticsButCannotReplaceNativeContent() throws IOException {
		BiomeReplacementConfigSnapshot nativeSnapshot = BiomeReplacementConfigStore.capture(this.configRoot);
		Files.writeString(
			this.configRoot.resolve(BiomeReplacementConfigStore.LEGACY_FILE_NAME),
			"minecraft:plains > minecraft:desert\n",
			StandardCharsets.UTF_8
		);

		BiomeReplacementConfigSnapshot withLegacy = BiomeReplacementConfigStore.capture(this.configRoot);
		assertTrue(withLegacy.revision() > nativeSnapshot.revision());
		assertTrue(withLegacy.legacyFilePresent());
		assertEquals(nativeSnapshot.content(), withLegacy.content());
	}

	@Test
	void rejectsInvalidUtf8WithoutPublishingASnapshot() throws IOException {
		Files.createDirectories(this.destination().getParent());
		Files.write(this.destination(), new byte[] {(byte) 0xC3, (byte) 0x28});

		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> BiomeReplacementConfigStore.capture(this.configRoot)
		);
		assertTrue(failure.getMessage().contains("not valid UTF-8"));
	}

	@Test
	void rejectsAnExistingNonRegularDestination() throws IOException {
		Files.createDirectories(this.destination());

		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> BiomeReplacementConfigStore.capture(this.configRoot)
		);
		assertTrue(failure.getMessage().contains("not a regular file"));
	}

	@Test
	void unchangedBytesReuseRevisionAndChangedBytesAdvanceIt() throws IOException {
		BiomeReplacementConfigSnapshot first = BiomeReplacementConfigStore.capture(this.configRoot);
		BiomeReplacementConfigSnapshot same = BiomeReplacementConfigStore.capture(this.configRoot);
		assertSame(first, same);

		Files.writeString(
			this.destination(),
			first.content() + "\nminecraft:plains > minecraft:desert\n",
			StandardCharsets.UTF_8
		);
		BiomeReplacementConfigSnapshot changed = BiomeReplacementConfigStore.capture(this.configRoot);
		assertTrue(changed.revision() > first.revision());

		BiomeReplacementConfigSnapshot otherPath = BiomeReplacementConfigStore.capture(
			this.configRoot.resolve("other-config-root")
		);
		assertTrue(otherPath.revision() > changed.revision());
	}

	private Path destination() {
		return this.configRoot.resolve(FTFCommon.MOD_ID)
			.resolve(BiomeReplacementConfigStore.NATIVE_FILE_NAME);
	}
}
