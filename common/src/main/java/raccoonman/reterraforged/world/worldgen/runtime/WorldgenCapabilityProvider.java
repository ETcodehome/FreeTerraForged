package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

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
