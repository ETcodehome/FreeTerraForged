package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;
import java.util.Set;

public record WorldgenMechanismRequirement(
	String modId,
	Set<String> supportedVersions,
	boolean optional
) {
	public WorldgenMechanismRequirement {
		modId = Objects.requireNonNull(modId, "modId").trim();
		supportedVersions = Set.copyOf(supportedVersions);
		if (modId.isEmpty() || supportedVersions.stream().anyMatch(String::isBlank)) {
			throw new IllegalArgumentException("Mechanism requirements need a mod ID and valid versions");
		}
	}
}
