package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable per-facet execution capabilities compiled with a worldgen plan. */
public record WorldgenExecution(Map<WorldgenFacet, WorldgenQueryMode> queryModes) {
	public WorldgenExecution {
		EnumMap<WorldgenFacet, WorldgenQueryMode> copy = new EnumMap<>(WorldgenFacet.class);
		copy.putAll(Objects.requireNonNull(queryModes, "queryModes"));
		if (!copy.keySet().equals(EnumSet.allOf(WorldgenFacet.class))) {
			EnumSet<WorldgenFacet> missing = EnumSet.allOf(WorldgenFacet.class);
			missing.removeAll(copy.keySet());
			throw new IllegalArgumentException("Worldgen execution modes are missing facets: " + missing);
		}
		if (copy.values().stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Worldgen execution modes cannot contain null values");
		}
		queryModes = Collections.unmodifiableMap(copy);
	}

	public static WorldgenExecution serial() {
		EnumMap<WorldgenFacet, WorldgenQueryMode> modes = new EnumMap<>(WorldgenFacet.class);
		for (WorldgenFacet facet : WorldgenFacet.values()) {
			modes.put(facet, WorldgenQueryMode.OWNER_SERIAL);
		}
		return new WorldgenExecution(modes);
	}

	public WorldgenQueryMode queryMode(WorldgenFacet facet) {
		return this.queryModes.get(Objects.requireNonNull(facet, "facet"));
	}

	public WorldgenExecution withQueryMode(WorldgenFacet facet, WorldgenQueryMode mode) {
		EnumMap<WorldgenFacet, WorldgenQueryMode> modes = new EnumMap<>(this.queryModes);
		modes.put(Objects.requireNonNull(facet, "facet"), Objects.requireNonNull(mode, "mode"));
		return new WorldgenExecution(modes);
	}

	public boolean supportsIsolatedParallelRead(Set<WorldgenFacet> facets) {
		Objects.requireNonNull(facets, "facets");
		return !facets.isEmpty() && facets.stream()
			.allMatch(facet -> this.queryMode(facet).supportsIsolatedParallelRead());
	}
}
