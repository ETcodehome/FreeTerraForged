package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

public record WorldgenProviderMetadata(
	ResourceLocation id,
	int protocolVersion,
	int adapterVersion,
	String implementationClass,
	Map<WorldgenFacet, WorldgenContributionKind> contributions,
	Map<WorldgenFacet, WorldgenQueryMode> queryModes,
	Set<WorldgenOwnerType> ownerTypes,
	List<ProviderOrder> ordering,
	List<WorldgenMechanismRequirement> mechanisms,
	boolean previewFactory,
	boolean preServerFinalizer,
	boolean contributionRevision
) {
	public static final int CURRENT_PROTOCOL = 1;

	public WorldgenProviderMetadata {
		id = Objects.requireNonNull(id, "id");
		implementationClass = Objects.requireNonNull(implementationClass, "implementationClass").trim();
		if (contributions.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)
			|| ownerTypes.isEmpty() || ownerTypes.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Provider metadata contains an empty owner or contribution");
		}
		EnumMap<WorldgenFacet, WorldgenContributionKind> copied = new EnumMap<>(WorldgenFacet.class);
		copied.putAll(contributions);
		contributions = Map.copyOf(copied);
		EnumMap<WorldgenFacet, WorldgenQueryMode> copiedQueryModes = new EnumMap<>(WorldgenFacet.class);
		copiedQueryModes.putAll(queryModes);
		queryModes = Map.copyOf(copiedQueryModes);
		if (!queryModes.keySet().equals(contributions.keySet())
			|| queryModes.values().stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Provider metadata must declare one query mode per contribution");
		}
		ownerTypes = Set.copyOf(ownerTypes);
		ordering = List.copyOf(ordering);
		if (Set.copyOf(ordering).size() != ordering.size()) {
			throw new IllegalArgumentException("Provider metadata contains duplicate ordering edges");
		}
		for (ProviderOrder edge : ordering) {
			if (!edge.before().equals(id) && !edge.after().equals(id)) {
				throw new IllegalArgumentException("Provider ordering edge must include its declaring provider ID");
			}
		}
		mechanisms = List.copyOf(mechanisms);
		if (mechanisms.stream().map(WorldgenMechanismRequirement::modId).distinct().count()
			!= mechanisms.size()) {
			throw new IllegalArgumentException("Provider metadata contains duplicate mechanism requirements");
		}
		if (protocolVersion <= 0 || adapterVersion <= 0 || implementationClass.isEmpty()) {
			throw new IllegalArgumentException("Provider metadata has an invalid protocol, adapter, or implementation");
		}
		if (contributions.isEmpty() && !previewFactory && !preServerFinalizer && !contributionRevision) {
			throw new IllegalArgumentException("Provider metadata declares no capability");
		}
	}

	public Set<WorldgenFacet> facets() {
		return this.contributions.keySet();
	}

	public WorldgenContributionKind contributionKind(WorldgenFacet facet) {
		return this.contributions.getOrDefault(facet, WorldgenContributionKind.UNSUPPORTED);
	}

	public WorldgenQueryMode queryMode(WorldgenFacet facet) {
		return this.queryModes.getOrDefault(facet, WorldgenQueryMode.OWNER_SERIAL);
	}
}
