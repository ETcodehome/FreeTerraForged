package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * Loader-neutral, mechanism-oriented capability SPI. Implementations expose public snapshots or
 * factories; they must not infer private state from fields, namespaces, or implementation names.
 */
public interface WorldgenCapabilityProvider {
	ResourceLocation id();

	int version();

	Set<WorldgenFacet> facets();

	Set<WorldgenOwnerType> ownerTypes();

	List<ProviderOrder> ordering();

	default WorldgenContributionKind contributionKind(WorldgenFacet facet) {
		return switch (facet) {
			case BIOME_COMPOSITION, SELECTION_DECORATION, SAMPLER_DECORATION ->
				WorldgenContributionKind.ORDERED_TRANSFORM;
			default -> WorldgenContributionKind.UNIQUE_ROOT;
		};
	}

	default WorldgenQueryMode declaredQueryMode(WorldgenFacet facet) {
		return WorldgenQueryMode.OWNER_SERIAL;
	}

	default boolean providesPreviewFactory() {
		return false;
	}

	default boolean requiresPreServerFinalization() {
		return false;
	}

	default boolean providesContributionRevision() {
		return false;
	}

	default OptionalLong contributionRevision(ResourceKey<LevelStem> dimension) {
		return OptionalLong.empty();
	}

	/**
	 * Optionally resolves immutable mechanism inputs from a completed pre-server creation graph.
	 * The default is intentionally inert for providers whose public data is already request-readable.
	 */
	default void finalizePreServer(PreServerWorldgenContext context) throws Exception {
	}

	WorldgenApplicability applicability(
		WorldgenFacet facet,
		WorldgenCompilationContext context
	) throws Exception;

	Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) throws Exception;

	Optional<? extends WorldgenPlans.DomainPlan> compile(
		WorldgenFacet facet,
		WorldgenCompilationContext context
	) throws Exception;

	/**
	 * Declares the query concurrency of a facet actually supplied by this provider. The conservative
	 * default prevents an unknown executable closure from being queried concurrently. Parallel mode
	 * requires immutable shared plan state and worker-confined mutable query state.
	 */
	WorldgenQueryMode queryMode(
		WorldgenFacet facet,
		WorldgenCompilationContext context
	);
}
