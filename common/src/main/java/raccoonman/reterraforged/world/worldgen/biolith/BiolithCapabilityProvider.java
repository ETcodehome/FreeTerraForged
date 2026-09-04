package raccoonman.reterraforged.world.worldgen.biolith;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.platform.ModLoaderUtil;
import raccoonman.reterraforged.world.worldgen.runtime.CapabilityFailure;
import raccoonman.reterraforged.world.worldgen.runtime.CapabilityState;
import raccoonman.reterraforged.world.worldgen.runtime.CellIntervalSelector;
import raccoonman.reterraforged.world.worldgen.runtime.PlanDescriptor;
import raccoonman.reterraforged.world.worldgen.runtime.ProviderOrder;
import raccoonman.reterraforged.world.worldgen.runtime.PreviewSourceContext;
import raccoonman.reterraforged.world.worldgen.runtime.RequestOwnedBiomeSource;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenCapabilityProvider;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenCompilationContext;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenContributionKind;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenFacet;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenApplicability;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenOwnerType;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlans;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenQueryMode;

public final class BiolithCapabilityProvider implements WorldgenCapabilityProvider {
	private static final ResourceLocation ID = RTFCommon.location("biolith_placements");
	private static final ResourceLocation LITHOSTITCHED = RTFCommon.location("lithostitched_injectors");
	private static final ResourceKey<Biome> VANILLA_PLACEHOLDER = ResourceKey.create(
		Registries.BIOME, ResourceLocation.fromNamespaceAndPath("biolith", "vanilla")
	);
	private static final long REPLACEMENT_SALT = 0x4A2E7C6195D3B80FL;
	private static final Comparator<BiolithPlacementBridge.Placement> PLACEMENT_ORDER = Comparator
		.comparingLong((BiolithPlacementBridge.Placement entry) -> entry.point().temperature().min())
		.thenComparingLong(entry -> entry.point().temperature().max())
		.thenComparingLong(entry -> entry.point().humidity().min())
		.thenComparingLong(entry -> entry.point().humidity().max())
		.thenComparingLong(entry -> entry.point().continentalness().min())
		.thenComparingLong(entry -> entry.point().continentalness().max())
		.thenComparingLong(entry -> entry.point().erosion().min())
		.thenComparingLong(entry -> entry.point().erosion().max())
		.thenComparingLong(entry -> entry.point().depth().min())
		.thenComparingLong(entry -> entry.point().depth().max())
		.thenComparingLong(entry -> entry.point().weirdness().min())
		.thenComparingLong(entry -> entry.point().weirdness().max())
		.thenComparingLong(entry -> entry.point().offset())
		.thenComparing(entry -> entry.biome().location().toString());

	@Override
	public ResourceLocation id() {
		return ID;
	}

	@Override
	public int version() {
		return 1;
	}

	@Override
	public Set<WorldgenFacet> facets() {
		return EnumSet.of(WorldgenFacet.BIOME_COMPOSITION, WorldgenFacet.SELECTION_DECORATION);
	}

	@Override
	public Set<WorldgenOwnerType> ownerTypes() {
		return EnumSet.allOf(WorldgenOwnerType.class);
	}

	@Override
	public List<ProviderOrder> ordering() {
		return List.of(ProviderOrder.optional(ID, LITHOSTITCHED));
	}

	@Override
	public WorldgenContributionKind contributionKind(WorldgenFacet facet) {
		return WorldgenContributionKind.ORDERED_TRANSFORM;
	}

	@Override
	public boolean providesContributionRevision() {
		return true;
	}

	@Override
	public OptionalLong contributionRevision(ResourceKey<LevelStem> dimension) {
		return OptionalLong.of(BiolithPlacementBridge.revision(dimension));
	}

	@Override
	public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
		return Optional.empty();
	}

	@Override
	public WorldgenQueryMode declaredQueryMode(WorldgenFacet facet) {
		return WorldgenQueryMode.ISOLATED_PARALLEL_READ;
	}

	@Override
	public WorldgenQueryMode queryMode(WorldgenFacet facet, WorldgenCompilationContext context) {
		return WorldgenQueryMode.ISOLATED_PARALLEL_READ;
	}

	@Override
	public WorldgenApplicability applicability(
		WorldgenFacet facet,
		WorldgenCompilationContext context
	) {
		if (!ModLoaderUtil.isLoaded("biolith")) {
			return WorldgenApplicability.NOT_APPLICABLE;
		}
		Optional<BiolithPlacementBridge.Snapshot> found = BiolithPlacementBridge.snapshot(
			context.owner().dimension()
		);
		if (found.isEmpty()) {
			return WorldgenApplicability.NOT_APPLICABLE;
		}
		BiolithPlacementBridge.Snapshot snapshot = found.orElseThrow();
		boolean applies = switch (facet) {
			case BIOME_COMPOSITION -> !snapshot.placements().isEmpty()
				|| !snapshot.removals().isEmpty();
			case SELECTION_DECORATION -> snapshot.replacements().values().stream()
				.anyMatch(values -> !values.isEmpty())
				|| snapshot.subBiomes().values().stream().anyMatch(values -> !values.isEmpty());
			default -> false;
		};
		return applies ? WorldgenApplicability.APPLICABLE : WorldgenApplicability.NOT_APPLICABLE;
	}

	@Override
	public Optional<? extends WorldgenPlans.DomainPlan> compile(
		WorldgenFacet facet,
		WorldgenCompilationContext context
	) {
		context.checkCancelled();
		if (!ModLoaderUtil.isLoaded("biolith")) {
			return Optional.empty();
		}
		String mechanismVersion = ModLoaderUtil.version("biolith").orElse("unknown");
		if (!BiolithPlacementBridge.SUPPORTED_VERSIONS.contains(mechanismVersion)) {
			return Optional.of(unavailable(
				facet,
				"biolith_version_contract_changed",
				"Loaded Biolith " + mechanismVersion
					+ " but the proven registration/finalization contract is "
					+ BiolithPlacementBridge.SUPPORTED_VERSIONS
			));
		}
		Optional<BiolithPlacementBridge.Snapshot> found = BiolithPlacementBridge.snapshot(
			context.owner().dimension()
		);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		BiolithPlacementBridge.Snapshot snapshot = found.orElseThrow();
		if (!BiolithPlacementBridge.SUPPORTED_VERSIONS.contains(snapshot.mechanismVersion())) {
			return Optional.of(unavailable(
				facet,
				"biolith_version_contract_changed",
				"Captured Biolith " + snapshot.mechanismVersion()
					+ " but the proven registration/finalization contract is "
					+ BiolithPlacementBridge.SUPPORTED_VERSIONS
			));
		}
		Registry<Biome> biomes = context.owner().registries().registryOrThrow(Registries.BIOME);
		Optional<? extends WorldgenPlans.DomainPlan> result = switch (facet) {
			case BIOME_COMPOSITION -> Optional.of(candidateComposition(snapshot, biomes));
			case SELECTION_DECORATION -> Optional.of(replacementDecoration(
				snapshot, biomes, context
			));
			default -> Optional.empty();
		};
		context.checkCancelled();
		return result;
	}

	private static WorldgenPlans.BiomeComposition candidateComposition(
		BiolithPlacementBridge.Snapshot snapshot,
		Registry<Biome> biomes
	) {
		Set<ResourceKey<Biome>> removals = snapshot.removals().stream()
			.map(BiolithPlacementBridge.Removal::biome)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> additions = snapshot.placements().stream()
			.sorted(PLACEMENT_ORDER)
			.map(value -> Pair.<Climate.ParameterPoint, Holder<Biome>>of(
				value.point(), biomes.getHolderOrThrow(value.biome())
			))
			.toList();

		WorldgenPlans.BiomeCandidateComposer composer = candidates -> {
			LinkedHashSet<Pair<Climate.ParameterPoint, Holder<Biome>>> result = candidates.stream()
				.filter(entry -> entry.getSecond().unwrapKey().map(key -> !removals.contains(key)).orElse(true))
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			result.addAll(additions);
			return List.copyOf(result);
		};
		return new WorldgenPlans.BiomeComposition(
			descriptor(
				WorldgenFacet.BIOME_COMPOSITION,
				"biolith_public_registration_capture",
				"Accepted additions, removals, and replacement outputs are immutable candidate operations"
			),
			List.of(),
			List.of(new WorldgenPlans.CandidateCompositionStage(ID, 200, composer))
		);
	}

	static Comparator<BiolithPlacementBridge.Placement> placementOrder() {
		return PLACEMENT_ORDER;
	}

	private static WorldgenPlans.SelectionDecoration replacementDecoration(
		BiolithPlacementBridge.Snapshot snapshot,
		Registry<Biome> biomes,
		WorldgenCompilationContext context
	) {
		List<BiolithPlacementBridge.SubBiome> unsupported = snapshot.subBiomes().values().stream()
			.flatMap(List::stream)
			.filter(value -> value.criterion().failure().isPresent())
			.toList();
		if (!unsupported.isEmpty()) {
			String failures = unsupported.stream()
				.map(value -> value.criterion().failure().orElseThrow())
				.distinct()
				.sorted()
				.collect(java.util.stream.Collectors.joining("; "));
			return (WorldgenPlans.SelectionDecoration) unavailable(
				WorldgenFacet.SELECTION_DECORATION,
				"biolith_criterion_contract_unsupported",
				"Biolith sub-biome criteria could not be normalized: " + failures
			);
		}
		Map<ResourceKey<Biome>, List<CellIntervalSelector.Choice<Holder<Biome>>>> replacementChoices = snapshot.replacements()
			.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(key -> key.location().toString())))
			.collect(java.util.stream.Collectors.toUnmodifiableMap(
				Map.Entry::getKey,
				entry -> replacementChoices(entry.getKey(), entry.getValue(), biomes)
			));
		Map<ResourceKey<Biome>, List<BiolithPlacementBridge.SubBiome>> subBiomes = snapshot.subBiomes()
			.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(key -> key.location().toString())))
			.collect(java.util.stream.Collectors.toUnmodifiableMap(
				Map.Entry::getKey,
				entry -> entry.getValue().stream()
					.map(value -> new BiolithPlacementBridge.SubBiome(
						value.target(), value.biome(), value.criterion().bindTags(biomes), value.fromData()
					))
					.sorted(Comparator.comparing(value -> value.biome().location().toString()))
					.toList()
			));
		Set<Holder<Biome>> possibleOutputs = replacementChoices.values().stream()
			.flatMap(List::stream)
			.map(CellIntervalSelector.Choice::value)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		subBiomes.values().stream().flatMap(List::stream)
			.map(BiolithPlacementBridge.SubBiome::biome)
			.map(biomes::getHolderOrThrow)
			.forEach(possibleOutputs::add);
		long seed = context.owner().seed();
		Map<ResourceKey<Biome>, CellIntervalSelector<Holder<Biome>>> replacements = replacementChoices
			.entrySet().stream()
			.collect(java.util.stream.Collectors.toUnmodifiableMap(
				Map.Entry::getKey,
				entry -> new CellIntervalSelector<>(seed ^ REPLACEMENT_SALT, entry.getValue())
			));
		int minY = context.owner().selectedStem().type().value().minY();
		int topY = minY + context.owner().selectedStem().type().value().height();
		int seaLevel = context.owner().selectedStem().generator().getSeaLevel();
		return new WorldgenPlans.SelectionDecoration(
				descriptor(
				WorldgenFacet.SELECTION_DECORATION,
				"biolith_immutable_criterion_snapshot",
				"Direct replacements retain an FTF-cell interval and sample; built-in sub-biome criteria use immutable FTF candidate metadata"
			),
			List.of(new WorldgenPlans.SelectionDecoratorStage(
				ID,
				100,
				(selection, spatial, target, quartX, quartY, quartZ, sampler, surfaceContext) -> {
					double replacementSample = CellIntervalSelector.sample(
						seed ^ REPLACEMENT_SALT, spatial.cellX(), spatial.cellZ()
					);
					ReplacementSelection direct = replacement(
						selection.biome(), replacementSample, replacements
					);
					ResourceKey<Biome> directKey = direct.biome().unwrapKey().orElse(null);
					List<BiolithPlacementBridge.SubBiome> requests = directKey == null
						? List.of()
						: subBiomes.getOrDefault(directKey, List.of());
					if (requests.isEmpty()) {
						return direct.biome();
					}
					BiolithCriterionBridge.Evaluation evaluation = new BiolithCriterionBridge.Evaluation(
						selection, target, quartY, minY, topY, seaLevel,
						alternate -> replacement(
							biomes.getHolderOrThrow(alternate), replacementSample, replacements
						).biome(),
						direct.context()
					);
					for (BiolithPlacementBridge.SubBiome request : requests) {
						if (BiolithCriterionBridge.matches(
							request.criterion().node().orElseThrow(), evaluation
						)) {
							return biomes.getHolderOrThrow(request.biome());
						}
					}
					return direct.biome();
				}
			)),
			possibleOutputs
		);
	}

	private static ReplacementSelection replacement(
		Holder<Biome> target,
		double sample,
		Map<ResourceKey<Biome>, CellIntervalSelector<Holder<Biome>>> replacements
	) {
		CellIntervalSelector<Holder<Biome>> selector = target.unwrapKey()
			.map(replacements::get)
			.orElse(null);
		if (selector == null) {
			return new ReplacementSelection(target, null);
		}
		CellIntervalSelector.Selection<Holder<Biome>> selected = selector.select(sample);
		float min = (float) selected.minInclusive();
		float max = selected.maxInclusive() > 0.9999D
			? 1.0F
			: (float) selected.maxInclusive();
		return new ReplacementSelection(
			selected.value(),
			new BiolithCriterionBridge.ReplacementContext(min, max, (float) selected.sample())
		);
	}

	static List<CellIntervalSelector.Choice<Holder<Biome>>> replacementChoices(
		ResourceKey<Biome> target,
		List<BiolithPlacementBridge.Replacement> requests,
		Registry<Biome> biomes
	) {
		double maximum = requests.stream().mapToDouble(BiolithPlacementBridge.Replacement::proportion)
			.max().orElse(0.0D);
		double baseWeight = Math.clamp(1.0D - maximum, 0.0D, 1.0D);
		List<BiolithPlacementBridge.Replacement> ordered = requests.stream()
			.filter(request -> request.proportion() > 0.0D)
			.sorted(Comparator
				.comparing((BiolithPlacementBridge.Replacement request) -> request.biome().location().toString())
				.thenComparingDouble(BiolithPlacementBridge.Replacement::proportion))
			.toList();
		List<CellIntervalSelector.Choice<Holder<Biome>>> choices = new java.util.ArrayList<>(
			ordered.size() + (baseWeight > 0.0D ? 1 : 0)
		);
		if (baseWeight > 0.0D) {
			choices.add(new CellIntervalSelector.Choice<>(
				choiceId("vanilla", target, baseWeight), baseWeight, biomes.getHolderOrThrow(target)
			));
		}
		for (BiolithPlacementBridge.Replacement request : ordered) {
			ResourceKey<Biome> output = request.biome().equals(VANILLA_PLACEHOLDER)
				? target
				: request.biome();
			choices.add(new CellIntervalSelector.Choice<>(
				choiceId("request", request.biome(), request.proportion()),
				request.proportion(),
				biomes.getHolderOrThrow(output)
			));
		}
		return List.copyOf(choices);
	}

	private static ResourceLocation choiceId(
		String kind,
		ResourceKey<Biome> biome,
		double weight
	) {
		ResourceLocation location = biome.location();
		return RTFCommon.location(
			"biolith/replacement/" + kind + "/" + location.getNamespace() + "/"
				+ location.getPath() + "/"
				+ Long.toUnsignedString(Double.doubleToLongBits(weight), 16)
		);
	}

	private record ReplacementSelection(
		Holder<Biome> biome,
		BiolithCriterionBridge.ReplacementContext context
	) {
	}

	private static PlanDescriptor descriptor(WorldgenFacet facet, String mechanism, String detail) {
		return new PlanDescriptor(
			ID, facet, CapabilityState.NORMALIZED, mechanism, detail, Optional.empty()
		);
	}

	private static WorldgenPlans.DomainPlan unavailable(
		WorldgenFacet facet,
		String code,
		String message
	) {
		PlanDescriptor descriptor = new PlanDescriptor(
			ID, facet, CapabilityState.UNAVAILABLE, "biolith_version_qualified_bridge", message,
			Optional.of(CapabilityFailure.unavailable(code, message))
		);
		return switch (facet) {
			case BIOME_COMPOSITION -> new WorldgenPlans.BiomeComposition(descriptor, List.of());
			case SELECTION_DECORATION -> new WorldgenPlans.SelectionDecoration(descriptor, List.of());
			default -> throw new IllegalArgumentException("Unsupported Biolith facet: " + facet);
		};
	}
}
