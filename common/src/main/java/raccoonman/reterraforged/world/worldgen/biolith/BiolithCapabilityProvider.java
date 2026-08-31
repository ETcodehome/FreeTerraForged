package raccoonman.reterraforged.world.worldgen.biolith;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

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
import raccoonman.reterraforged.world.worldgen.runtime.CellRendezvous;
import raccoonman.reterraforged.world.worldgen.runtime.PlanDescriptor;
import raccoonman.reterraforged.world.worldgen.runtime.ProviderOrder;
import raccoonman.reterraforged.world.worldgen.runtime.PreviewSourceContext;
import raccoonman.reterraforged.world.worldgen.runtime.RequestOwnedBiomeSource;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenCapabilityProvider;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenCompilationContext;
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
	public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
		return Optional.empty();
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
		if (!ModLoaderUtil.isLoaded("biolith")) {
			return Optional.empty();
		}
		String mechanismVersion = ModLoaderUtil.version("biolith").orElse("unknown");
		if (!BiolithPlacementBridge.SUPPORTED_VERSION.equals(mechanismVersion)) {
			return Optional.of(unavailable(
				facet,
				"biolith_version_contract_changed",
				"Loaded Biolith " + mechanismVersion
					+ " but the proven registration/finalization contract is "
					+ BiolithPlacementBridge.SUPPORTED_VERSION
			));
		}
		Optional<BiolithPlacementBridge.Snapshot> found = BiolithPlacementBridge.snapshot(
			context.owner().dimension()
		);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		BiolithPlacementBridge.Snapshot snapshot = found.orElseThrow();
		if (!BiolithPlacementBridge.SUPPORTED_VERSION.equals(snapshot.mechanismVersion())) {
			return Optional.of(unavailable(
				facet,
				"biolith_version_contract_changed",
				"Captured Biolith " + snapshot.mechanismVersion()
					+ " but the proven registration/finalization contract is "
					+ BiolithPlacementBridge.SUPPORTED_VERSION
			));
		}
		Registry<Biome> biomes = context.owner().registries().registryOrThrow(Registries.BIOME);
		return switch (facet) {
			case BIOME_COMPOSITION -> Optional.of(candidateComposition(snapshot, biomes));
			case SELECTION_DECORATION -> Optional.of(replacementDecoration(
				snapshot, biomes, context.owner().seed()
			));
			default -> Optional.empty();
		};
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
		long seed
	) {
		long subBiomeCount = snapshot.subBiomes().values().stream().mapToLong(List::size).sum();
		if (subBiomeCount > 0) {
			return (WorldgenPlans.SelectionDecoration) unavailable(
				WorldgenFacet.SELECTION_DECORATION,
				"biolith_sub_biome_factory_missing",
				"Captured " + subBiomeCount + " sub-biome registrations, but Biolith exposes no immutable "
					+ "request-owned criterion factory for their world, neighbor, alternate, and reload semantics"
			);
		}
		Map<ResourceKey<Biome>, List<CellRendezvous.Choice<Holder<Biome>>>> choices = snapshot.replacements()
			.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(key -> key.location().toString())))
			.collect(java.util.stream.Collectors.toUnmodifiableMap(
				Map.Entry::getKey,
				entry -> replacementChoices(entry.getKey(), entry.getValue(), biomes)
			));
		Set<Holder<Biome>> possibleOutputs = choices.values().stream()
			.flatMap(List::stream)
			.map(CellRendezvous.Choice::value)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		return new WorldgenPlans.SelectionDecoration(
			descriptor(
				WorldgenFacet.SELECTION_DECORATION,
				"biolith_public_registration_capture",
				"Direct replacement proportions are selected by deterministic FTF-cell rendezvous"
			),
			List.of(new WorldgenPlans.SelectionDecoratorStage(
				ID,
				(selection, spatial, target, quartX, quartY, quartZ, sampler) -> selection.biome()
					.unwrapKey()
					.map(choices::get)
					.filter(values -> !values.isEmpty())
					.map(values -> CellRendezvous.select(
						seed ^ REPLACEMENT_SALT,
						spatial.cellX(), spatial.cellZ(), values
					))
					.orElse(selection.biome())
			)),
			possibleOutputs
		);
	}

	private static List<CellRendezvous.Choice<Holder<Biome>>> replacementChoices(
		ResourceKey<Biome> target,
		List<BiolithPlacementBridge.Replacement> requests,
		Registry<Biome> biomes
	) {
		double maximum = requests.stream().mapToDouble(BiolithPlacementBridge.Replacement::proportion)
			.max().orElse(0.0D);
		double baseWeight = Math.clamp(1.0D - maximum, 0.0D, 1.0D);
		Map<ResourceKey<Biome>, Double> weights = new TreeMap<>(Comparator.comparing(key -> key.location().toString()));
		if (baseWeight > 0.0D) {
			weights.put(target, baseWeight);
		}
		for (BiolithPlacementBridge.Replacement request : requests) {
			if (request.proportion() <= 0.0D) {
				continue;
			}
			ResourceKey<Biome> output = request.biome().equals(VANILLA_PLACEHOLDER)
				? target
				: request.biome();
			weights.merge(output, request.proportion(), Double::sum);
		}
		return weights.entrySet().stream()
			.map(entry -> new CellRendezvous.Choice<Holder<Biome>>(
				entry.getKey().location(), entry.getValue(), biomes.getHolderOrThrow(entry.getKey())
			))
			.toList();
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
