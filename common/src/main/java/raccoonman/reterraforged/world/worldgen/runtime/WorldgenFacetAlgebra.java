package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class WorldgenFacetAlgebra {
	private static final Map<WorldgenFacet, Set<WorldgenContributionKind>> KINDS;

	static {
		EnumMap<WorldgenFacet, Set<WorldgenContributionKind>> kinds = new EnumMap<>(WorldgenFacet.class);
		for (WorldgenFacet facet : WorldgenFacet.values()) {
			kinds.put(facet, EnumSet.of(WorldgenContributionKind.UNIQUE_ROOT));
		}
		kinds.put(WorldgenFacet.BIOME_COMPOSITION, EnumSet.of(
			WorldgenContributionKind.UNIQUE_ROOT,
			WorldgenContributionKind.ORDERED_TRANSFORM
		));
		kinds.put(WorldgenFacet.SELECTION_DECORATION, EnumSet.of(
			WorldgenContributionKind.ORDERED_TRANSFORM
		));
		kinds.put(WorldgenFacet.SAMPLER_DECORATION, EnumSet.of(
			WorldgenContributionKind.ORDERED_TRANSFORM
		));
		kinds.put(WorldgenFacet.SURFACE, EnumSet.of(
			WorldgenContributionKind.UNIQUE_ROOT,
			WorldgenContributionKind.ORDERED_TRANSFORM
		));
		KINDS = Map.copyOf(kinds);
	}

	private WorldgenFacetAlgebra() {
	}

	public static boolean supports(WorldgenFacet facet, WorldgenContributionKind kind) {
		return KINDS.getOrDefault(facet, Set.of()).contains(kind);
	}

	public static Set<WorldgenContributionKind> supportedKinds(WorldgenFacet facet) {
		return KINDS.getOrDefault(facet, Set.of());
	}
}
