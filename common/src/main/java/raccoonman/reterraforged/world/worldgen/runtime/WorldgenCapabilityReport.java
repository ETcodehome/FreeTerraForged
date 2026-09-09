package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class WorldgenCapabilityReport {
	private final List<CapabilityNodeReport> nodes;
	private final Map<WorldgenFacet, List<CapabilityNodeReport>> byFacet;
	private final WorldgenExecution execution;
	private final List<WorldgenProviderDiagnostic> providerDiagnostics;

	public WorldgenCapabilityReport(List<CapabilityNodeReport> nodes) {
		this(nodes, WorldgenExecution.serial(), List.of());
	}

	public WorldgenCapabilityReport(
		List<CapabilityNodeReport> nodes,
		WorldgenExecution execution
	) {
		this(nodes, execution, List.of());
	}

	public WorldgenCapabilityReport(
		List<CapabilityNodeReport> nodes,
		WorldgenExecution execution,
		List<WorldgenProviderDiagnostic> providerDiagnostics
	) {
		this.nodes = nodes.stream()
			.map(Objects::requireNonNull)
			.sorted(Comparator.comparing((CapabilityNodeReport value) -> value.facet().ordinal())
				.thenComparing(value -> value.id().toString()))
			.toList();
		EnumMap<WorldgenFacet, List<CapabilityNodeReport>> index = new EnumMap<>(WorldgenFacet.class);
		for (WorldgenFacet facet : WorldgenFacet.values()) {
			index.put(facet, this.nodes.stream().filter(node -> node.facet() == facet).toList());
		}
		this.byFacet = Map.copyOf(index);
		this.execution = Objects.requireNonNull(execution, "execution");
		this.providerDiagnostics = providerDiagnostics.stream()
			.map(Objects::requireNonNull)
			.sorted(Comparator.comparing(WorldgenProviderDiagnostic::source)
				.thenComparing(value -> value.provider().map(Object::toString).orElse(""))
				.thenComparing(value -> value.facet().map(Enum::name).orElse("")))
			.toList();
	}

	public List<CapabilityNodeReport> nodes() {
		return this.nodes;
	}

	public List<CapabilityNodeReport> facet(WorldgenFacet facet) {
		return this.byFacet.getOrDefault(facet, List.of());
	}

	public Optional<CapabilityFailure> firstCause(WorldgenFacet facet) {
		return this.facet(facet).stream().flatMap(node -> node.firstCause().stream()).findFirst();
	}

	public WorldgenExecution execution() {
		return this.execution;
	}

	public List<WorldgenProviderDiagnostic> providerDiagnostics() {
		return this.providerDiagnostics;
	}

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("schema_version", 3);
		JsonObject queryModes = new JsonObject();
		for (WorldgenFacet facet : WorldgenFacet.values()) {
			queryModes.addProperty(
				facet.name().toLowerCase(),
				this.execution.queryMode(facet).name().toLowerCase()
			);
		}
		root.add("query_modes", queryModes);
		JsonArray nodes = new JsonArray();
		for (CapabilityNodeReport node : this.nodes) {
			JsonObject value = new JsonObject();
			value.addProperty("id", node.id().toString());
			value.addProperty("facet", node.facet().name().toLowerCase());
			value.addProperty("state", node.state().name().toLowerCase());
			value.addProperty("mechanism", node.mechanism());
			value.addProperty("owner", node.ownerType().name().toLowerCase());
			value.addProperty("detail", node.detail());
			node.firstCause().ifPresent(failure -> {
				JsonObject cause = new JsonObject();
				cause.addProperty("code", failure.code());
				cause.addProperty("message", failure.message());
				cause.addProperty("exception_type", failure.exceptionType());
				JsonArray chain = new JsonArray();
				failure.causeChain().forEach(chain::add);
				cause.add("cause_chain", chain);
				value.add("first_cause", cause);
			});
			nodes.add(value);
		}
		root.add("nodes", nodes);
		JsonArray providerDiagnostics = new JsonArray();
		for (WorldgenProviderDiagnostic diagnostic : this.providerDiagnostics) {
			JsonObject value = new JsonObject();
			value.addProperty("source", diagnostic.source());
			diagnostic.provider().ifPresent(provider -> value.addProperty("provider", provider.toString()));
			diagnostic.facet().ifPresent(facet -> value.addProperty("facet", facet.name().toLowerCase()));
			CapabilityFailure failure = diagnostic.failure();
			value.addProperty("code", failure.code());
			value.addProperty("message", failure.message());
			value.addProperty("exception_type", failure.exceptionType());
			JsonArray chain = new JsonArray();
			failure.causeChain().forEach(chain::add);
			value.add("cause_chain", chain);
			providerDiagnostics.add(value);
		}
		root.add("provider_diagnostics", providerDiagnostics);
		return root;
	}
}
