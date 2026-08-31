package raccoonman.reterraforged.world.worldgen.lithostitched;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.platform.ModLoaderUtil;
import raccoonman.reterraforged.world.worldgen.runtime.CapabilityFailure;
import raccoonman.reterraforged.world.worldgen.runtime.CapabilityState;
import raccoonman.reterraforged.world.worldgen.runtime.CellRendezvous;
import raccoonman.reterraforged.world.worldgen.runtime.MinecraftBiomeSourceGraphs;
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

public final class LithostitchedCapabilityProvider implements WorldgenCapabilityProvider {
	private static final ResourceLocation ID = RTFCommon.location("lithostitched_injectors");
	private static final ResourceLocation BIOLITH = RTFCommon.location("biolith_placements");
	private static final long REGION_SALT = 0x61C8E4A2793DB50FL;

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
		return EnumSet.of(
			WorldgenFacet.BIOME_COMPOSITION,
			WorldgenFacet.SELECTION_DECORATION
		);
	}

	@Override
	public Set<WorldgenOwnerType> ownerTypes() {
		return EnumSet.allOf(WorldgenOwnerType.class);
	}

	@Override
	public List<ProviderOrder> ordering() {
		return List.of(ProviderOrder.optional(BIOLITH, ID));
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
		if (!ModLoaderUtil.isLoaded("lithostitched")) {
			return WorldgenApplicability.NOT_APPLICABLE;
		}
		Acquisition acquisition = acquisition(context);
		if (acquisition.snapshot().isEmpty()) {
			return acquisition.declarativeContribution()
				? WorldgenApplicability.APPLICABLE
				: WorldgenApplicability.NOT_APPLICABLE;
		}
		LithostitchedInjectionBridge.Snapshot snapshot = acquisition.snapshot().orElseThrow();
		boolean applies = switch (facet) {
			case BIOME_COMPOSITION -> snapshot.injectors().stream()
					.anyMatch(injector -> injector.kind() == LithostitchedInjectionBridge.Kind.ADD_POINTS);
			case SELECTION_DECORATION -> snapshot.injectors().stream()
				.anyMatch(injector -> injector.kind() != LithostitchedInjectionBridge.Kind.ADD_POINTS);
			default -> false;
		};
		return applies ? WorldgenApplicability.APPLICABLE : WorldgenApplicability.NOT_APPLICABLE;
	}

	@Override
	public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
		if (!ModLoaderUtil.isLoaded("lithostitched")) {
			return Optional.empty();
		}
		LithostitchedInjectionBridge.Snapshot captured = LithostitchedInjectionBridge
			.snapshot(context.realizedSource())
			.orElse(null);
		if (captured == null) {
			return Optional.empty();
		}
		if (!LithostitchedInjectionBridge.SUPPORTED_VERSIONS.contains(captured.mechanismVersion())
			|| !captured.cloneFailures().isEmpty()
			|| captured.injectors().stream().anyMatch(value -> value.kind() == LithostitchedInjectionBridge.Kind.UNKNOWN)
			|| captured.seed() != context.seed()) {
			throw new IllegalStateException("The finalized Lithostitched snapshot cannot create an isolated preview request");
		}
		LithostitchedInjectionBridge.Snapshot rebound = LithostitchedInjectionBridge.rebind(
			captured, captured.root(), context.lookups(), context.noiseSettings().value()
		);
		BiomeSource preview = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(
			rebound.baseEntries()
		));
		rebound = rebound.withRoot(preview);
		LithostitchedInjectionBridge.bind(preview, rebound);
		return Optional.of(new RequestOwnedBiomeSource(
			preview, () -> LithostitchedInjectionBridge.release(preview)
		));
	}

	@Override
	public Optional<? extends WorldgenPlans.DomainPlan> compile(
		WorldgenFacet facet,
		WorldgenCompilationContext context
	) {
		if (!ModLoaderUtil.isLoaded("lithostitched")) {
			return Optional.empty();
		}
		BiomeSource selected = MinecraftBiomeSourceGraphs.acquisitionSource(
			context.owner().selectedStem().generator()
		);
		Acquisition acquisition = acquisition(context);
		Optional<LithostitchedInjectionBridge.Snapshot> found = acquisition.snapshot();
		if (found.isEmpty()) {
			if (acquisition.mechanismSource()) {
				return Optional.of(unavailable(
					facet,
					"lithostitched_final_snapshot_missing",
					"The selected creation graph contains a Lithostitched injector source without its "
						+ "version-qualified finalized snapshot"
				));
			}
			if (acquisition.declarativeContribution()) {
				return Optional.of(unavailable(
					facet,
					"lithostitched_version_contract_changed",
					"The selected creation graph contains declarative Lithostitched injectors outside the proven "
						+ "bridge versions " + LithostitchedInjectionBridge.SUPPORTED_VERSIONS
				));
			}
			return Optional.empty();
		}
		LithostitchedInjectionBridge.Snapshot snapshot = found.orElseThrow();
		if (!LithostitchedInjectionBridge.SUPPORTED_VERSIONS.contains(snapshot.mechanismVersion())) {
			return Optional.of(unavailable(
				facet,
				"lithostitched_version_contract_changed",
				"Captured Lithostitched " + snapshot.mechanismVersion()
					+ " outside the proven bridge versions " + LithostitchedInjectionBridge.SUPPORTED_VERSIONS
			));
		}
		if (!snapshot.cloneFailures().isEmpty()) {
			return Optional.of(unavailable(
				facet,
				"lithostitched_snapshot_clone_failed",
				"Finalized injectors could not be copied through the public codec: "
					+ String.join("; ", snapshot.cloneFailures())
			));
		}
		if (snapshot.seed() != context.owner().seed()) {
			return Optional.of(unavailable(
				facet,
				"lithostitched_snapshot_owner_mismatch",
				"The finalized snapshot seed does not match the selected creation graph"
			));
		}
		var expectedDimension = context.owner().dimension();
		if (snapshot.injectors().stream()
			.anyMatch(injector -> !injector.dimension().equals(expectedDimension))
			|| snapshot.regions().stream().anyMatch(region -> !region.dimension().equals(expectedDimension))) {
			return Optional.of(unavailable(
				facet,
				"lithostitched_snapshot_dimension_mismatch",
				"The finalized injector snapshot does not belong to the selected dimension"
			));
		}
		List<LithostitchedInjectionBridge.CapturedInjector> unknown = snapshot.injectors().stream()
			.filter(value -> value.kind() == LithostitchedInjectionBridge.Kind.UNKNOWN)
			.toList();
		if (!unknown.isEmpty()) {
			return Optional.of(unavailable(
				facet,
				"lithostitched_injector_type_unsupported",
				"Unknown finalized injector types: " + unknown.stream()
					.map(value -> value.id() + "=" + value.codec()).toList()
			));
		}
		return switch (facet) {
			case BIOME_COMPOSITION -> Optional.of(candidateComposition(
				snapshot, selected instanceof MultiNoiseBiomeSource
			));
			case SELECTION_DECORATION -> Optional.of(selectionDecoration(snapshot, context.owner().seed()));
			default -> Optional.empty();
		};
	}

	private static Acquisition acquisition(WorldgenCompilationContext context) {
		try {
			return context.snapshot(ID, Acquisition.class, () -> {
				BiomeSource selected = MinecraftBiomeSourceGraphs.acquisitionSource(
					context.owner().selectedStem().generator()
				);
				Optional<LithostitchedInjectionBridge.Snapshot> captured =
					LithostitchedInjectionBridge.snapshot(selected);
				boolean mechanismSource = LithostitchedInjectionBridge.isInjectorSource(selected);
				if (captured.isPresent()) {
					return new Acquisition(captured, mechanismSource, true);
				}
				boolean declarative = LithostitchedInjectionBridge.hasDeclarativeInjectors(
					context.owner().lookups(), context.owner().dimension()
				);
				if (!declarative || !LithostitchedInjectionBridge.SUPPORTED_VERSIONS.contains(
					ModLoaderUtil.version("lithostitched").orElse("unknown")
				)) {
					return new Acquisition(
						Optional.empty(), mechanismSource, declarative
					);
				}
				if (!(context.owner().selectedStem().generator()
					instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generator)) {
					return new Acquisition(Optional.empty(), mechanismSource, true);
				}
				Optional<LithostitchedInjectionBridge.Snapshot> snapshot =
					LithostitchedInjectionBridge.captureDeclarative(
						selected,
						context.owner().lookups(),
						context.owner().dimension(),
						generator.generatorSettings().value(),
						context.owner().seed()
					);
				return new Acquisition(snapshot, mechanismSource, true);
			});
		} catch (RuntimeException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new IllegalStateException("Failed acquiring owner-local Lithostitched data", failure);
		}
	}

	private record Acquisition(
		Optional<LithostitchedInjectionBridge.Snapshot> snapshot,
		boolean mechanismSource,
		boolean declarativeContribution
	) {
	}

	private static WorldgenPlans.BiomeComposition candidateComposition(
		LithostitchedInjectionBridge.Snapshot snapshot,
		boolean rootAlreadyDeclarative
	) {
		if (snapshot.baseEntries().isEmpty()) {
			return (WorldgenPlans.BiomeComposition) unavailable(
				WorldgenFacet.BIOME_COMPOSITION,
				"lithostitched_candidate_root_opaque",
				"The finalized wrapper's root is not a public multi-noise candidate table"
			);
		}
		List<LithostitchedInjectionBridge.CapturedInjector> additions = ordered(
			snapshot, LithostitchedInjectionBridge.Kind.ADD_POINTS
		);
		WorldgenPlans.BiomeCandidateComposer composer = candidates -> {
			LinkedHashSet<Pair<Climate.ParameterPoint, Holder<Biome>>> result = new LinkedHashSet<>(candidates);
			for (LithostitchedInjectionBridge.CapturedInjector injector : additions) {
				result.addAll(injector.points());
			}
			return List.copyOf(result);
		};
		return new WorldgenPlans.BiomeComposition(
			descriptor(
				WorldgenFacet.BIOME_COMPOSITION,
				"The public root candidate table and codec-cloned add-points injectors are immutable"
			),
			rootAlreadyDeclarative ? List.of() : snapshot.baseEntries(),
			List.of(new WorldgenPlans.CandidateCompositionStage(ID, 100, composer))
		);
	}

	private static WorldgenPlans.SelectionDecoration selectionDecoration(
		LithostitchedInjectionBridge.Snapshot snapshot,
		long seed
	) {
		List<LithostitchedInjectionBridge.CapturedInjector> forces = ordered(
			snapshot, LithostitchedInjectionBridge.Kind.FORCE
		);
		List<LithostitchedInjectionBridge.CapturedInjector> dispatches = ordered(
			snapshot, LithostitchedInjectionBridge.Kind.DISPATCH
		);
		List<LithostitchedInjectionBridge.CapturedInjector> partials = ordered(
			snapshot, LithostitchedInjectionBridge.Kind.REPLACE_PARTIALLY
		);
		List<LithostitchedInjectionBridge.CapturedInjector> full = ordered(
			snapshot, LithostitchedInjectionBridge.Kind.REPLACE_FULLY
		);
		Set<Holder<Biome>> possibleOutputs = java.util.stream.Stream.of(
			forces.stream(), partials.stream(), full.stream()
		).flatMap(java.util.function.Function.identity())
			.map(LithostitchedInjectionBridge.CapturedInjector::output)
			.flatMap(Optional::stream)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		dispatches.stream()
			.flatMap(injector -> injector.points().stream())
			.map(Pair::getSecond)
			.forEach(possibleOutputs::add);
		return new WorldgenPlans.SelectionDecoration(
			descriptor(
				WorldgenFacet.SELECTION_DECORATION,
				"Finalized force, dispatch, partial, and full rules retain priority order while regions use FTF cells"
			),
			List.of(new WorldgenPlans.SelectionDecoratorStage(
				ID,
				(selection, spatial, target, quartX, quartY, quartZ, sampler) -> {
					Holder<Biome> biome = selection.biome();
					ResourceLocation region = selectRegion(
						snapshot.regions(), snapshot.nativeRegionFunctionPresent(), biome,
						seed, spatial.cellX(), spatial.cellZ()
					);
					int blockX = QuartPos.toBlock(quartX);
					int blockY = QuartPos.toBlock(quartY);
					int blockZ = QuartPos.toBlock(quartZ);
					for (LithostitchedInjectionBridge.CapturedInjector injector : forces) {
						if (injector.criteria().orElseThrow().matches(blockX, blockY, blockZ, target, region)) {
							return injector.output().orElseThrow();
						}
					}
					for (LithostitchedInjectionBridge.CapturedInjector injector : dispatches) {
						if (injector.criteria().orElseThrow().matches(blockX, blockY, blockZ, target, region)) {
							biome = new Climate.ParameterList<>(injector.points()).findValue(target);
							break;
						}
					}
					for (LithostitchedInjectionBridge.CapturedInjector injector : partials) {
						if (injector.targets().contains(biome)
							&& injector.criteria().orElseThrow().matches(blockX, blockY, blockZ, target, region)) {
							biome = injector.output().orElseThrow();
							break;
						}
					}
					for (LithostitchedInjectionBridge.CapturedInjector injector : full) {
						if (injector.targets().contains(biome)) {
							return injector.output().orElseThrow();
						}
					}
					return biome;
				}
			)),
			possibleOutputs
		);
	}

	private static List<LithostitchedInjectionBridge.CapturedInjector> ordered(
		LithostitchedInjectionBridge.Snapshot snapshot,
		LithostitchedInjectionBridge.Kind kind
	) {
		return snapshot.injectors().stream()
			.filter(value -> value.kind() == kind)
			.sorted(executionOrder())
			.toList();
	}

	static Comparator<LithostitchedInjectionBridge.CapturedInjector> executionOrder() {
		return Comparator.comparingInt(LithostitchedInjectionBridge.CapturedInjector::priority)
			.thenComparing(LithostitchedInjectionBridge.CapturedInjector::id);
	}

	private static ResourceLocation selectRegion(
		List<LithostitchedInjectionBridge.CapturedRegion> regions,
		boolean enabled,
		Holder<Biome> biome,
		long seed,
		long cellX,
		long cellZ
	) {
		if (!enabled) {
			return LithostitchedInjectionBridge.noRegion();
		}
		List<CellRendezvous.Choice<ResourceLocation>> choices = new ArrayList<>();
		for (LithostitchedInjectionBridge.CapturedRegion region : regions) {
			if (region.weight() > 0 && region.biomes().contains(biome)) {
				choices.add(new CellRendezvous.Choice<>(
					region.id(), region.weight(), region.id()
				));
			}
		}
		return choices.isEmpty()
			? LithostitchedInjectionBridge.noRegion()
			: CellRendezvous.select(seed ^ REGION_SALT, cellX, cellZ, choices);
	}

	private static PlanDescriptor descriptor(WorldgenFacet facet, String detail) {
		return new PlanDescriptor(
			ID, facet, CapabilityState.NORMALIZED,
			"lithostitched_finalization_bridge", detail,
			Optional.empty()
		);
	}

	private static WorldgenPlans.DomainPlan unavailable(
		WorldgenFacet facet,
		String code,
		String message
	) {
		PlanDescriptor descriptor = new PlanDescriptor(
			ID, facet, CapabilityState.UNAVAILABLE, "lithostitched_finalization_bridge", message,
			Optional.of(CapabilityFailure.unavailable(code, message))
		);
		return switch (facet) {
			case BIOME_COMPOSITION -> new WorldgenPlans.BiomeComposition(descriptor, List.of());
			case SELECTION_DECORATION -> new WorldgenPlans.SelectionDecoration(descriptor, List.of());
			default -> throw new IllegalArgumentException("Unsupported Lithostitched facet: " + facet);
		};
	}
}
