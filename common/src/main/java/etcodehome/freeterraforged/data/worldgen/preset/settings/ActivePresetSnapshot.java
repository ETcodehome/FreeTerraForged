package etcodehome.freeterraforged.data.worldgen.preset.settings;

import java.util.Objects;

/**
 * Immutable, server-authoritative state needed by client-side active-preset tools.
 *
 * <p>The snapshot contains the complete effective preset plus the runtime context required to
 * reproduce its preview. Consumers decide how to present the snapshot; this type has no command or
 * screen behavior.</p>
 */
public record ActivePresetSnapshot(
	String presetJson,
	String dimension,
	long seed,
	int playerX,
	int playerZ,
	int spawnX,
	int spawnZ,
	String biomeReplacements
) {
	public ActivePresetSnapshot {
		presetJson = Objects.requireNonNull(presetJson, "presetJson");
		dimension = Objects.requireNonNull(dimension, "dimension");
		biomeReplacements = Objects.requireNonNull(biomeReplacements, "biomeReplacements");
	}
}
