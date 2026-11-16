package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.Objects;

/** Immutable content captured at a preview or worldgen owner boundary. */
public record BiomeReplacementConfigSnapshot(
	long revision,
	String fingerprint,
	String path,
	Origin origin,
	String content,
	BiomeReplacementConfig config,
	boolean legacyFilePresent
) {
	public BiomeReplacementConfigSnapshot {
		if (revision < 0L) {
			throw new IllegalArgumentException("Biome replacement revision must be non-negative");
		}
		fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
		path = Objects.requireNonNull(path, "path");
		origin = Objects.requireNonNull(origin, "origin");
		content = Objects.requireNonNull(content, "content");
		config = Objects.requireNonNull(config, "config");
	}

	public enum Origin {
		NATIVE,
		MIGRATED_LEGACY,
		CREATED_TEMPLATE
	}

	public boolean sameContent(BiomeReplacementConfigSnapshot other) {
		return other != null
			&& this.fingerprint.equals(other.fingerprint)
			&& this.path.equals(other.path);
	}
}
