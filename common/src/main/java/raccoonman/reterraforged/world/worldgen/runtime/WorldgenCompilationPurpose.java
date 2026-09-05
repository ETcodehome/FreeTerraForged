package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.EnumSet;
import java.util.Set;

public enum WorldgenCompilationPurpose {
	WORLDGEN(EnumSet.allOf(WorldgenFacet.class)),
	BIOME_PREVIEW(EnumSet.of(
		WorldgenFacet.BIOME_COMPOSITION,
		WorldgenFacet.PROVIDER_SELECTION,
		WorldgenFacet.SELECTION_DECORATION,
		WorldgenFacet.SPATIAL_OWNERSHIP,
		WorldgenFacet.SAMPLER_DECORATION
	));

	private final Set<WorldgenFacet> facets;

	WorldgenCompilationPurpose(Set<WorldgenFacet> facets) {
		this.facets = Set.copyOf(facets);
	}

	public boolean includes(WorldgenFacet facet) {
		return this.facets.contains(facet);
	}

	public Set<WorldgenFacet> facets() {
		return this.facets;
	}
}
