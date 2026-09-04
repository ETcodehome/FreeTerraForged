package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WorldgenExecution {
	private final Map<WorldgenFacet, WorldgenQueryMode> queryModes;
	private final Object ownerSerialGate;

	public WorldgenExecution(Map<WorldgenFacet, WorldgenQueryMode> queryModes) {
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
		this.queryModes = Collections.unmodifiableMap(copy);
		this.ownerSerialGate = new Object();
	}

	private WorldgenExecution(
		Map<WorldgenFacet, WorldgenQueryMode> queryModes,
		Object ownerSerialGate
	) {
		this.queryModes = queryModes;
		this.ownerSerialGate = ownerSerialGate;
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
		return new WorldgenExecution(Collections.unmodifiableMap(modes), this.ownerSerialGate);
	}

	public Map<WorldgenFacet, WorldgenQueryMode> queryModes() {
		return this.queryModes;
	}

	public boolean supportsIsolatedParallelRead(Set<WorldgenFacet> facets) {
		Objects.requireNonNull(facets, "facets");
		if (facets.isEmpty()) {
			return false;
		}
		for (WorldgenFacet facet : facets) {
			if (!this.queryMode(facet).supportsIsolatedParallelRead()) {
				return false;
			}
		}
		return true;
	}

	public <T> T execute(Set<WorldgenFacet> facets, java.util.function.Supplier<T> query) {
		Objects.requireNonNull(query, "query");
		if (this.supportsIsolatedParallelRead(facets)) {
			return query.get();
		}
			synchronized (this.ownerSerialGate) {
			return query.get();
		}
	}

	@Override
	public boolean equals(Object value) {
		return this == value || value instanceof WorldgenExecution other
			&& this.queryModes.equals(other.queryModes);
	}

	@Override
	public int hashCode() {
		return this.queryModes.hashCode();
	}

	@Override
	public String toString() {
		return "WorldgenExecution[queryModes=" + this.queryModes + "]";
	}
}
