package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan;
import raccoonman.reterraforged.world.worldgen.feature.placement.ChunkLocalPlacementClassifier;
import raccoonman.reterraforged.world.worldgen.feature.placement.SurfacePlacementClassifier;

public final class WorldgenPlanDiagnostics {
	private WorldgenPlanDiagnostics() {
	}

	public static Snapshot snapshot(WorldgenPlan plan) {
		Objects.requireNonNull(plan, "plan");
		WorldgenOwner owner = plan.owner();

		List<Facet> facets = java.util.Arrays.stream(WorldgenFacet.values())
			.map(facet -> {
				PlanDescriptor descriptor = plan.facet(facet).descriptor();
				return new Facet(
					facet,
					descriptor.id(),
					descriptor.state(),
					descriptor.mechanism(),
					descriptor.detail(),
					plan.execution().queryMode(facet),
					descriptor.firstCause().map(Failure::of)
				);
			})
			.toList();

		List<Provider> providers = plan.providerSelection().providers().stream()
			.sorted(Comparator.comparingInt(WorldgenPlans.ProviderDomain::registrationOrder)
				.thenComparing(value -> value.id().toString()))
			.map(value -> new Provider(
				value.id(), value.registrationOrder(), value.weight(), value.candidates().values().size()
			))
			.toList();

		List<Contribution> mutableContributions = new ArrayList<>();
		plan.biomeComposition().stages().forEach(stage -> mutableContributions.add(new Contribution(
			WorldgenFacet.BIOME_COMPOSITION, stage.id(), stage.order(), "ordered_transform"
		)));
		for (WorldgenPlans.SelectionDecoratorStage stage : plan.selectionDecoration().stages()) {
			mutableContributions.add(new Contribution(
				WorldgenFacet.SELECTION_DECORATION, stage.id(), stage.order(), "ordered_transform"
			));
		}
		plan.samplerDecoration().stages().forEach(stage -> mutableContributions.add(new Contribution(
			WorldgenFacet.SAMPLER_DECORATION, stage.id(), stage.order(), "ordered_transform"
		)));
		int surfaceOrder = 0;
		for (ResourceLocation id : plan.surface().appliedTransforms()) {
			mutableContributions.add(new Contribution(
				WorldgenFacet.SURFACE, id, surfaceOrder++, "materialized_transform"
			));
		}
		List<Contribution> contributions = mutableContributions.stream()
			.sorted(Comparator.comparing((Contribution value) -> value.facet().ordinal())
				.thenComparingInt(Contribution::order)
				.thenComparing(value -> value.id().toString()))
			.toList();

		LinkedHashSet<Output> outputs = new LinkedHashSet<>();
		plan.biomeComposition().entries().forEach(entry -> outputs.add(Output.of(entry.getSecond())));
		plan.providerSelection().providers().forEach(provider -> provider.candidates().values()
			.forEach(entry -> outputs.add(Output.of(entry.getSecond()))));
		plan.providerSelection().fallback().ifPresent(fallback -> fallback.values()
			.forEach(entry -> outputs.add(Output.of(entry.getSecond()))));
		plan.providerSelection().directInput().ifPresent(input -> input.possibleOutputs()
			.forEach(holder -> outputs.add(Output.of(holder))));
		plan.selectionDecoration().possibleOutputs().forEach(holder -> outputs.add(Output.of(holder)));
		List<Output> possibleOutputs = outputs.stream()
			.sorted(Comparator.comparing(value -> value.id().map(Object::toString).orElse("")))
			.toList();

		List<Revision> revisions = owner.contributionRevision().revisions().entrySet().stream()
			.map(entry -> new Revision(
				entry.getKey().mechanism(), entry.getKey().scope(), entry.getValue()
			))
			.sorted(Comparator.comparing((Revision value) -> value.mechanism().toString())
				.thenComparing(value -> value.scope().toString()))
			.toList();
		Map<ResourceLocation, Failure> revisionFailures = new LinkedHashMap<>();
		owner.contributionRevision().failures().entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
			.forEach(entry -> revisionFailures.put(entry.getKey(), Failure.of(entry.getValue())));

		GraphCensus graph = new GraphCensus(
			plan.biomeComposition().entries().size(),
			plan.providerSelection().providers().size(),
			plan.selectionDecoration().stages().size(),
			plan.samplerDecoration().stages().size(),
			plan.carvers().pipelines().size(),
			plan.placedFeatures().pipelines().size(),
			plan.placedFeatures().steps().size(),
			plan.structures().structures().size(),
			plan.structures().sets().size(),
			plan.structures().pools().size(),
			plan.structures().processors().size(),
			plan.structures().rules().size(),
			plan.densitySettings().settings().isPresent(),
			plan.surface().root().isPresent(),
			plan.spatialOwnership().resolver().isPresent(),
			plan.providerSelection().fallback().isPresent(),
			plan.providerSelection().directInput().isPresent()
		);
		EnumMap<CapabilityState, Integer> stateCounts = new EnumMap<>(CapabilityState.class);
		plan.report().nodes().forEach(node -> stateCounts.merge(node.state(), 1, Integer::sum));
		List<ProviderDiagnostic> providerDiagnostics = plan.report().providerDiagnostics().stream()
			.map(value -> new ProviderDiagnostic(
				value.source(), value.provider(), value.facet(), Failure.of(value.failure())
			))
			.toList();
		List<UnavailableNode> unavailableNodes = plan.report().nodes().stream()
			.filter(node -> node.state() == CapabilityState.UNAVAILABLE)
			.map(node -> new UnavailableNode(
				node.id(), node.facet(), node.mechanism(), node.ownerType(), node.detail(),
				node.firstCause().map(Failure::of).orElseThrow()
			))
			.toList();
		DensityExtent densityExtent = plan.densitySettings().settings()
			.map(settings -> {
				var noise = settings.value().noiseSettings();
				return new DensityExtent(
					true, "full_configured_height", noise.minY(), noise.height(), noise.getCellHeight(),
					"bounded_density_analysis_not_enabled"
				);
			})
			.orElseGet(() -> new DensityExtent(
				false, "unavailable", 0, 0, 0, "density_settings_unavailable"
			));
		AdaptationSummary adaptations = adaptations(plan.placedFeatures());

		return new Snapshot(
			new Owner(
				owner.id().toString(), owner.type(), owner.dimension().location(), owner.settingsIdentity(),
				owner.resourceRevision(), owner.resourceLayerFingerprint(),
				owner.tagEpoch().sequence(), owner.tagEpoch().fingerprint(),
				revisions, revisionFailures
			),
			new SourceShape(
				plan.biomeComposition().descriptor().mechanism(),
				plan.biomeComposition().descriptor().state(),
				plan.providerSelection().rootCompositionDomain(),
				plan.providerSelection().directInput().map(BiomeSourcePlanInput::id)
			),
			facets,
			providers,
			contributions,
			possibleOutputs,
			plan.samplerDecoration().queryPolicy().name().toLowerCase(java.util.Locale.ROOT),
			densityExtent,
			graph,
			adaptations,
			new CapabilitySummary(
				plan.report().nodes().size(), stateCounts, unavailableNodes, providerDiagnostics
			)
		);
	}

	public record Snapshot(
		Owner owner,
		SourceShape sourceShape,
		List<Facet> facets,
		List<Provider> providers,
		List<Contribution> contributions,
		List<Output> possibleOutputs,
		String samplerQueryPolicy,
		DensityExtent densityExtent,
		GraphCensus graph,
		AdaptationSummary adaptations,
		CapabilitySummary capabilitySummary
	) {
		public Snapshot {
			owner = Objects.requireNonNull(owner, "owner");
			sourceShape = Objects.requireNonNull(sourceShape, "sourceShape");
			facets = List.copyOf(facets);
			providers = List.copyOf(providers);
			contributions = List.copyOf(contributions);
			possibleOutputs = List.copyOf(possibleOutputs);
			samplerQueryPolicy = Objects.requireNonNull(samplerQueryPolicy, "samplerQueryPolicy");
			densityExtent = Objects.requireNonNull(densityExtent, "densityExtent");
			graph = Objects.requireNonNull(graph, "graph");
			adaptations = Objects.requireNonNull(adaptations, "adaptations");
			capabilitySummary = Objects.requireNonNull(capabilitySummary, "capabilitySummary");
		}

		public JsonObject toJson() {
			JsonObject root = new JsonObject();
			root.addProperty("schema_version", 3);
			root.add("owner", this.owner.toJson());
			root.add("source_shape", this.sourceShape.toJson());
			root.add("facets", array(this.facets.stream().map(Facet::toJson).toList()));
			root.add("providers", array(this.providers.stream().map(Provider::toJson).toList()));
			root.add("contributions", array(this.contributions.stream().map(Contribution::toJson).toList()));
			root.add("possible_outputs", array(this.possibleOutputs.stream().map(Output::toJson).toList()));
			root.addProperty("sampler_query_policy", this.samplerQueryPolicy);
			root.add("density_extent", this.densityExtent.toJson());
			root.add("graph_census", this.graph.toJson());
			root.add("adaptations", this.adaptations.toJson());
			root.add("capability_summary", this.capabilitySummary.toJson());
			return root;
		}
	}

	private static AdaptationSummary adaptations(WorldgenPlans.PlacedFeatures features) {
		int surfaceAdapted = 0;
		Map<String, Integer> surfacePassthrough = new TreeMap<>();
		List<String> surfaceFailures = new ArrayList<>();
		for (SurfacePlacementClassifier.Classification classification
			: features.surfaceClassifications().values()) {
			if (classification.eligible()) {
				surfaceAdapted++;
			} else {
				surfacePassthrough.merge(classification.reasonCode(), 1, Integer::sum);
				if (classification.failure() != null) {
					surfaceFailures.add(classification.reasonCode() + " | " + classification.failure());
				}
			}
		}
		surfaceFailures.sort(String::compareTo);
		int chunkLocalAdapted = 0;
		Map<String, Integer> chunkLocalPassthrough = new TreeMap<>();
		List<String> chunkLocalFailures = new ArrayList<>();
		for (ChunkLocalPlacementClassifier.Classification classification
			: features.chunkLocalClassifications().values()) {
			if (classification.eligible()) {
				chunkLocalAdapted++;
			} else {
				chunkLocalPassthrough.merge(classification.reasonCode(), 1, Integer::sum);
				if (classification.failure() != null) {
					chunkLocalFailures.add(classification.reasonCode() + " | " + classification.failure());
				}
			}
		}
		chunkLocalFailures.sort(String::compareTo);

		DynamicOrePlan ores = features.ores();
		return new AdaptationSummary(
			new Adaptation(
				features.surfaceClassifications().size(), surfaceAdapted, 0,
				surfacePassthrough, surfaceFailures
			),
			new Adaptation(
				features.chunkLocalClassifications().size(), chunkLocalAdapted, 0,
				chunkLocalPassthrough, chunkLocalFailures
			),
			new Adaptation(
				ores.standardOres(), ores.verticalTransforms().size(), ores.delegatedFeatures(),
				ores.skippedReasons(), ores.failures()
			)
		);
	}

	public record Owner(
		String id,
		WorldgenOwnerType type,
		ResourceLocation dimension,
		String settingsIdentity,
		long resourceRevision,
		String resourceLayerFingerprint,
		long tagRevision,
		String tagFingerprint,
		List<Revision> contributionRevisions,
		Map<ResourceLocation, Failure> contributionFailures
	) {
		public Owner {
			id = Objects.requireNonNull(id, "id");
			type = Objects.requireNonNull(type, "type");
			dimension = Objects.requireNonNull(dimension, "dimension");
			settingsIdentity = Objects.requireNonNull(settingsIdentity, "settingsIdentity");
			if (resourceRevision < 0L) {
				throw new IllegalArgumentException("Resource revision must be non-negative");
			}
			resourceLayerFingerprint = Objects.requireNonNull(resourceLayerFingerprint, "resourceLayerFingerprint");
			tagFingerprint = Objects.requireNonNull(tagFingerprint, "tagFingerprint");
			contributionRevisions = List.copyOf(contributionRevisions);
			contributionFailures = Map.copyOf(contributionFailures);
		}

		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("id", this.id);
			value.addProperty("type", lower(this.type));
			value.addProperty("dimension", this.dimension.toString());
			value.addProperty("settings_identity", this.settingsIdentity);
			value.addProperty("resource_revision", this.resourceRevision);
			value.addProperty("resource_layer_fingerprint", this.resourceLayerFingerprint);
			value.addProperty("tag_revision", this.tagRevision);
			value.addProperty("tag_fingerprint", this.tagFingerprint);
			value.add("contribution_revisions", array(
				this.contributionRevisions.stream().map(Revision::toJson).toList()
			));
			JsonArray failures = new JsonArray();
			this.contributionFailures.entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
				.forEach(entry -> {
					JsonObject failure = entry.getValue().toJson();
					failure.addProperty("provider", entry.getKey().toString());
					failures.add(failure);
				});
			value.add("contribution_failures", failures);
			return value;
		}
	}

	public record SourceShape(
		String normalizedMechanism,
		CapabilityState state,
		Optional<ResourceLocation> rootProviderDomain,
		Optional<ResourceLocation> directSourceRoot
	) {
		public SourceShape {
			normalizedMechanism = Objects.requireNonNull(normalizedMechanism, "normalizedMechanism");
			state = Objects.requireNonNull(state, "state");
			rootProviderDomain = Objects.requireNonNull(rootProviderDomain, "rootProviderDomain");
			directSourceRoot = Objects.requireNonNull(directSourceRoot, "directSourceRoot");
		}

		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("normalized_mechanism", this.normalizedMechanism);
			value.addProperty("state", lower(this.state));
			this.rootProviderDomain.ifPresent(id -> value.addProperty("root_provider_domain", id.toString()));
			this.directSourceRoot.ifPresent(id -> value.addProperty("direct_source_root", id.toString()));
			return value;
		}
	}

	public record Facet(
		WorldgenFacet facet,
		ResourceLocation descriptor,
		CapabilityState state,
		String mechanism,
		String detail,
		WorldgenQueryMode queryMode,
		Optional<Failure> firstCause
	) {
		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("facet", lower(this.facet));
			value.addProperty("descriptor", this.descriptor.toString());
			value.addProperty("state", lower(this.state));
			value.addProperty("mechanism", this.mechanism);
			value.addProperty("detail", this.detail);
			value.addProperty("query_mode", lower(this.queryMode));
			this.firstCause.ifPresent(failure -> value.add("first_cause", failure.toJson()));
			return value;
		}
	}

	public record Provider(ResourceLocation id, int order, double weight, int candidateCount) {
		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("id", this.id.toString());
			value.addProperty("order", this.order);
			value.addProperty("weight", this.weight);
			value.addProperty("candidate_count", this.candidateCount);
			return value;
		}
	}

	public record Contribution(WorldgenFacet facet, ResourceLocation id, int order, String kind) {
		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("facet", lower(this.facet));
			value.addProperty("id", this.id.toString());
			value.addProperty("order", this.order);
			value.addProperty("kind", this.kind);
			return value;
		}
	}

	public record Output(Optional<ResourceLocation> id) {
		private static Output of(Holder<Biome> holder) {
			return new Output(holder.unwrapKey().map(key -> key.location()));
		}

		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("keyed", this.id.isPresent());
			this.id.ifPresent(id -> value.addProperty("id", id.toString()));
			return value;
		}
	}

	public record Revision(ResourceLocation mechanism, ResourceLocation scope, long revision) {
		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("mechanism", this.mechanism.toString());
			value.addProperty("scope", this.scope.toString());
			value.addProperty("revision", this.revision);
			return value;
		}
	}

	public record Failure(String code, String message, String exceptionType, List<String> causeChain) {
		private static Failure of(CapabilityFailure failure) {
			return new Failure(
				failure.code(), failure.message(), failure.exceptionType(), failure.causeChain()
			);
		}

		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("code", this.code);
			value.addProperty("message", this.message);
			value.addProperty("exception_type", this.exceptionType);
			JsonArray chain = new JsonArray();
			this.causeChain.forEach(chain::add);
			value.add("cause_chain", chain);
			return value;
		}
	}

	public record GraphCensus(
		int candidateEntries,
		int providerDomains,
		int selectionStages,
		int samplerStages,
		int carverPipelines,
		int placedFeaturePipelines,
		int featureSteps,
		int structures,
		int structureSets,
		int templatePools,
		int processorLists,
		int structureRules,
		boolean densityRoot,
		boolean surfaceRoot,
		boolean spatialResolver,
		boolean providerFallback,
		boolean directSourceRoot
	) {
		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("candidate_entries", this.candidateEntries);
			value.addProperty("provider_domains", this.providerDomains);
			value.addProperty("selection_stages", this.selectionStages);
			value.addProperty("sampler_stages", this.samplerStages);
			value.addProperty("carver_pipelines", this.carverPipelines);
			value.addProperty("placed_feature_pipelines", this.placedFeaturePipelines);
			value.addProperty("feature_steps", this.featureSteps);
			value.addProperty("structures", this.structures);
			value.addProperty("structure_sets", this.structureSets);
			value.addProperty("template_pools", this.templatePools);
			value.addProperty("processor_lists", this.processorLists);
			value.addProperty("structure_rules", this.structureRules);
			value.addProperty("density_root", this.densityRoot);
			value.addProperty("surface_root", this.surfaceRoot);
			value.addProperty("spatial_resolver", this.spatialResolver);
			value.addProperty("provider_fallback", this.providerFallback);
			value.addProperty("direct_source_root", this.directSourceRoot);
			return value;
		}
	}

	public record DensityExtent(
		boolean available,
		String mode,
		int minY,
		int height,
		int cellHeight,
		String fallbackReason
	) {
		public DensityExtent {
			mode = Objects.requireNonNull(mode, "mode");
			fallbackReason = Objects.requireNonNull(fallbackReason, "fallbackReason");
			if (available && (height <= 0 || cellHeight <= 0 || Math.floorMod(minY, cellHeight) != 0
				|| Math.floorMod(height, cellHeight) != 0)) {
				throw new IllegalArgumentException("Available density extent is not cell-aligned");
			}
		}

		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("available", this.available);
			value.addProperty("mode", this.mode);
			value.addProperty("fallback_reason", this.fallbackReason);
			if (this.available) {
				value.addProperty("min_y", this.minY);
				value.addProperty("height", this.height);
				value.addProperty("max_y_exclusive", Math.addExact(this.minY, this.height));
				value.addProperty("cell_height", this.cellHeight);
			}
			return value;
		}
	}

	public record AdaptationSummary(
		Adaptation surfaceRescue,
		Adaptation chunkLocalPlacement,
		Adaptation dynamicOre
	) {
		public AdaptationSummary {
			surfaceRescue = Objects.requireNonNull(surfaceRescue, "surfaceRescue");
			chunkLocalPlacement = Objects.requireNonNull(chunkLocalPlacement, "chunkLocalPlacement");
			dynamicOre = Objects.requireNonNull(dynamicOre, "dynamicOre");
		}

		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.add("surface_rescue", this.surfaceRescue.toJson());
			value.add("chunk_local_placement", this.chunkLocalPlacement.toJson());
			value.add("dynamic_ore", this.dynamicOre.toJson());
			return value;
		}
	}

	public record Adaptation(
		int eligibleInputs,
		int transformedInputs,
		int delegatedInputs,
		Map<String, Integer> passthroughReasons,
		List<String> failures
	) {
		public Adaptation {
			if (eligibleInputs < 0 || transformedInputs < 0 || delegatedInputs < 0
				|| transformedInputs + delegatedInputs > eligibleInputs) {
				throw new IllegalArgumentException("Adaptation summary contains impossible counts");
			}
			passthroughReasons = Map.copyOf(new TreeMap<>(passthroughReasons));
			failures = failures.stream().sorted().toList();
		}

		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("eligible_inputs", this.eligibleInputs);
			value.addProperty("transformed_inputs", this.transformedInputs);
			value.addProperty("delegated_inputs", this.delegatedInputs);
			JsonObject passthrough = new JsonObject();
			this.passthroughReasons.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> passthrough.addProperty(entry.getKey(), entry.getValue()));
			value.add("passthrough_reasons", passthrough);
			JsonArray failures = new JsonArray();
			this.failures.forEach(failures::add);
			value.add("failures", failures);
			return value;
		}
	}

	public record CapabilitySummary(
		int nodeCount,
		Map<CapabilityState, Integer> stateCounts,
		List<UnavailableNode> unavailableNodes,
		List<ProviderDiagnostic> providerDiagnostics
	) {
		public CapabilitySummary {
			if (nodeCount < 0 || stateCounts.values().stream().anyMatch(value -> value == null || value < 0)) {
				throw new IllegalArgumentException("Capability summary contains a negative count");
			}
			stateCounts = Map.copyOf(stateCounts);
			unavailableNodes = List.copyOf(unavailableNodes);
			providerDiagnostics = List.copyOf(providerDiagnostics);
		}

		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("node_count", this.nodeCount);
			JsonObject states = new JsonObject();
			java.util.Arrays.stream(CapabilityState.values()).forEach(state ->
				states.addProperty(lower(state), this.stateCounts.getOrDefault(state, 0))
			);
			value.add("state_counts", states);
			value.add("unavailable_nodes", array(
				this.unavailableNodes.stream().map(UnavailableNode::toJson).toList()
			));
			value.add("provider_diagnostics", array(
				this.providerDiagnostics.stream().map(ProviderDiagnostic::toJson).toList()
			));
			return value;
		}
	}

	public record UnavailableNode(
		ResourceLocation id,
		WorldgenFacet facet,
		String mechanism,
		WorldgenOwnerType owner,
		String detail,
		Failure firstCause
	) {
		private JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("id", this.id.toString());
			value.addProperty("facet", lower(this.facet));
			value.addProperty("mechanism", this.mechanism);
			value.addProperty("owner", lower(this.owner));
			value.addProperty("detail", this.detail);
			value.add("first_cause", this.firstCause.toJson());
			return value;
		}
	}

	public record ProviderDiagnostic(
		String source,
		Optional<ResourceLocation> provider,
		Optional<WorldgenFacet> facet,
		Failure failure
	) {
		private JsonObject toJson() {
			JsonObject value = this.failure.toJson();
			value.addProperty("source", this.source);
			this.provider.ifPresent(id -> value.addProperty("provider", id.toString()));
			this.facet.ifPresent(facet -> value.addProperty("facet", lower(facet)));
			return value;
		}
	}

	private static JsonArray array(List<JsonObject> values) {
		JsonArray result = new JsonArray();
		values.forEach(result::add);
		return result;
	}

	private static String lower(Enum<?> value) {
		return value.name().toLowerCase(java.util.Locale.ROOT);
	}
}
