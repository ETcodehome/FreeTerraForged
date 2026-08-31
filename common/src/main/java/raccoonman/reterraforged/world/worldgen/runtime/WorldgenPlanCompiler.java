package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/** Deterministic capability negotiation and independently failed facet compilation. */
public final class WorldgenPlanCompiler {
	private final List<WorldgenCapabilityProvider> providers;

	public WorldgenPlanCompiler(List<? extends WorldgenCapabilityProvider> providers) {
		this.providers = orderProviders(providers);
	}

	public List<WorldgenCapabilityProvider> providers() {
		return this.providers;
	}

	public WorldgenPlan compile(WorldgenPlan base) {
		return this.compile(base, WorldgenCompilationPurpose.WORLDGEN);
	}

	public WorldgenPlan compile(WorldgenPlan base, WorldgenCompilationPurpose purpose) {
		Objects.requireNonNull(base, "base");
		Objects.requireNonNull(purpose, "purpose");
		WorldgenCompilationContext context = new WorldgenCompilationContext(base.owner(), purpose);
		EnumMap<WorldgenFacet, WorldgenPlans.DomainPlan> plans = new EnumMap<>(WorldgenFacet.class);
		for (WorldgenFacet facet : WorldgenFacet.values()) {
			plans.put(facet, base.facet(facet));
		}
		EnumMap<WorldgenFacet, ResourceLocation> contributors = new EnumMap<>(WorldgenFacet.class);
		EnumMap<WorldgenFacet, List<PlanDescriptor>> contributionDescriptors =
			new EnumMap<>(WorldgenFacet.class);
		WorldgenExecution execution = base.execution();
		Set<WorldgenFacet> failedFacets = new HashSet<>();

		for (WorldgenCapabilityProvider provider : this.providers) {
			if (!provider.ownerTypes().contains(base.owner().type())) {
				continue;
			}
			for (WorldgenFacet facet : provider.facets().stream().sorted().toList()) {
				if (!purpose.includes(facet)) {
					continue;
				}
				if (failedFacets.contains(facet)) {
					continue;
				}
				try {
					WorldgenApplicability applicability = Objects.requireNonNull(
						provider.applicability(facet, context), "provider applicability"
					);
					if (applicability == WorldgenApplicability.NOT_APPLICABLE) {
						continue;
					}
					Optional<? extends WorldgenPlans.DomainPlan> result = Objects.requireNonNull(
						provider.compile(facet, context), "provider compile result"
					);
					if (result.isEmpty()) {
						continue;
					}
					WorldgenPlans.DomainPlan plan = Objects.requireNonNull(result.get(), "provider plan");
					if (plan.facet() != facet) {
						throw new IllegalArgumentException(
							"Provider " + provider.id() + " returned " + plan.facet() + " while compiling " + facet
						);
					}
					if (facet == WorldgenFacet.BIOME_COMPOSITION) {
						WorldgenPlans.BiomeComposition previous =
							(WorldgenPlans.BiomeComposition) plans.get(facet);
						WorldgenPlans.BiomeComposition contribution =
							(WorldgenPlans.BiomeComposition) plan;
						if (contribution.descriptor().state() == CapabilityState.UNAVAILABLE) {
							plans.put(facet, contribution);
							execution = execution.withQueryMode(facet, WorldgenQueryMode.OWNER_SERIAL);
							contributionDescriptors.remove(facet);
							failedFacets.add(facet);
							continue;
						}
						if (contribution.entries().isEmpty() && contribution.stages().isEmpty()) {
							throw new IllegalArgumentException(
								"Biome-composition provider " + provider.id()
									+ " supplied neither a candidate root nor candidate stages"
							);
						}
						plans.put(facet, composeCandidateStages(previous, contribution));
						WorldgenQueryMode queryMode = Objects.requireNonNull(
							provider.queryMode(facet, context), "provider query mode"
						);
						execution = execution.withQueryMode(
							facet,
							previous.stages().isEmpty()
								? queryMode
								: restrict(execution.queryMode(facet), queryMode)
						);
						contributionDescriptors.computeIfAbsent(facet, ignored -> new ArrayList<>())
							.add(contribution.descriptor());
						continue;
					}
					if (facet == WorldgenFacet.SELECTION_DECORATION) {
						WorldgenPlans.SelectionDecoration previous =
							(WorldgenPlans.SelectionDecoration) plans.get(facet);
						WorldgenPlans.SelectionDecoration contribution =
							(WorldgenPlans.SelectionDecoration) plan;
						if (contribution.stages().isEmpty()
							&& contribution.descriptor().state() != CapabilityState.UNAVAILABLE) {
							throw new IllegalArgumentException(
								"Selection-decoration provider " + provider.id() + " supplied no executable stages"
							);
						}
						if (contribution.descriptor().state() == CapabilityState.UNAVAILABLE) {
							plans.put(facet, contribution);
							execution = execution.withQueryMode(facet, WorldgenQueryMode.OWNER_SERIAL);
							contributionDescriptors.remove(facet);
							failedFacets.add(facet);
							continue;
						}
						WorldgenQueryMode queryMode = Objects.requireNonNull(
							provider.queryMode(facet, context), "provider query mode"
						);
						plans.put(facet, composeSelectionDecorators(previous, contribution));
						if (!contribution.stages().isEmpty()) {
							execution = execution.withQueryMode(
								facet,
								previous.executable()
									? restrict(execution.queryMode(facet), queryMode)
									: queryMode
							);
						}
						contributionDescriptors.computeIfAbsent(facet, ignored -> new ArrayList<>())
							.add(contribution.descriptor());
						continue;
					}
					ResourceLocation previous = contributors.putIfAbsent(facet, provider.id());
					if (previous != null) {
						CapabilityFailure failure = CapabilityFailure.unavailable(
							"provider_conflict",
							"Facet " + facet + " was supplied by both " + previous + " and " + provider.id()
						);
						plans.put(facet, unavailable(plans.get(facet), provider.id(), failure));
						execution = execution.withQueryMode(facet, WorldgenQueryMode.OWNER_SERIAL);
						contributionDescriptors.remove(facet);
						failedFacets.add(facet);
						continue;
					}
						WorldgenQueryMode queryMode = Objects.requireNonNull(
						provider.queryMode(facet, context), "provider query mode"
					);
					plans.put(facet, plan);
					execution = execution.withQueryMode(facet, queryMode);
					contributionDescriptors.computeIfAbsent(facet, ignored -> new ArrayList<>())
						.add(plan.descriptor());
				} catch (RuntimeException | LinkageError failure) {
					contributionDescriptors.remove(facet);
					failedFacets.add(facet);
					plans.put(facet, unavailable(
						plans.get(facet), provider.id(), CapabilityFailure.of("provider_compile_failed", failure)
					));
					execution = execution.withQueryMode(facet, WorldgenQueryMode.OWNER_SERIAL);
				} catch (Exception failure) {
					contributionDescriptors.remove(facet);
					failedFacets.add(facet);
					plans.put(facet, unavailable(
						plans.get(facet), provider.id(), CapabilityFailure.of("provider_compile_failed", failure)
					));
					execution = execution.withQueryMode(facet, WorldgenQueryMode.OWNER_SERIAL);
				}
			}
		}
		List<CapabilityNodeReport> reports = new ArrayList<>();
		for (WorldgenFacet facet : WorldgenFacet.values()) {
			WorldgenPlans.DomainPlan plan = plans.get(facet);
			if (plan == base.facet(facet)) {
				reports.addAll(base.report().facet(facet));
			} else {
				CapabilityNodeReport baseRoot = base.facet(facet).descriptor().report(base.owner());
				base.report().facet(facet).stream()
					.filter(node -> node.firstCause().isPresent())
					.filter(node -> !node.equals(baseRoot))
					.forEach(reports::add);
				List<PlanDescriptor> descriptors = contributionDescriptors.getOrDefault(facet, List.of());
				descriptors.stream().map(descriptor -> descriptor.report(base.owner())).forEach(reports::add);
				if (descriptors.stream().noneMatch(plan.descriptor()::equals)) {
					reports.add(plan.descriptor().report(base.owner()));
				}
			}
		}
		return new WorldgenPlan(
			base.owner(),
			require(plans, WorldgenFacet.BIOME_COMPOSITION, WorldgenPlans.BiomeComposition.class),
			require(plans, WorldgenFacet.PROVIDER_SELECTION, WorldgenPlans.ProviderSelection.class),
			require(plans, WorldgenFacet.SELECTION_DECORATION, WorldgenPlans.SelectionDecoration.class),
			require(plans, WorldgenFacet.SPATIAL_OWNERSHIP, WorldgenPlans.SpatialOwnership.class),
			require(plans, WorldgenFacet.SAMPLER_DECORATION, WorldgenPlans.SamplerDecoration.class),
			require(plans, WorldgenFacet.DENSITY_SETTINGS, WorldgenPlans.DensitySettings.class),
			require(plans, WorldgenFacet.SURFACE, WorldgenPlans.Surface.class),
			require(plans, WorldgenFacet.CARVERS, WorldgenPlans.Carvers.class),
			require(plans, WorldgenFacet.PLACED_FEATURES, WorldgenPlans.PlacedFeatures.class),
			require(plans, WorldgenFacet.STRUCTURES, WorldgenPlans.Structures.class),
			execution,
			new WorldgenCapabilityReport(reports, execution)
		);
	}

	private static WorldgenPlans.SelectionDecoration composeSelectionDecorators(
		WorldgenPlans.SelectionDecoration previous,
		WorldgenPlans.SelectionDecoration contribution
	) {
		if (previous.descriptor().state() == CapabilityState.UNAVAILABLE || previous.stages().isEmpty()) {
			return contribution;
		}
		if (contribution.stages().isEmpty()) {
			return previous;
		}
		PlanDescriptor descriptor = new PlanDescriptor(
			ResourceLocation.fromNamespaceAndPath("reterraforged", "selection_mechanism_pipeline"),
			WorldgenFacet.SELECTION_DECORATION,
			CapabilityState.PROVIDER_CONTRACT,
			"ordered_selection_pipeline",
			"Selection decorators from independent mechanism contracts execute in declared provider order",
			Optional.empty()
		);
		return previous.append(contribution, descriptor);
	}

	private static WorldgenPlans.BiomeComposition composeCandidateStages(
		WorldgenPlans.BiomeComposition previous,
		WorldgenPlans.BiomeComposition contribution
	) {
		List<WorldgenPlans.CandidateCompositionStage> stages = new ArrayList<>(previous.stages());
		stages.addAll(contribution.stages());
		List<com.mojang.datafixers.util.Pair<
			net.minecraft.world.level.biome.Climate.ParameterPoint,
			net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>
		>> entries = previous.entries();
		if (!contribution.entries().isEmpty()) {
			if (!previous.entries().isEmpty()) {
				throw new IllegalStateException("Multiple providers supplied candidate roots");
			}
			entries = contribution.entries();
		}
		if (entries.isEmpty()) {
			return new WorldgenPlans.BiomeComposition(previous.descriptor(), entries, stages);
		}
		PlanDescriptor descriptor = new PlanDescriptor(
			ResourceLocation.fromNamespaceAndPath("reterraforged", "candidate_mechanism_pipeline"),
			WorldgenFacet.BIOME_COMPOSITION,
			CapabilityState.NORMALIZED,
			"ordered_candidate_pipeline",
			"Candidate additions and removals execute in declared mechanism order before selection",
			Optional.empty()
		);
		return new WorldgenPlans.BiomeComposition(descriptor, entries, stages);
	}

	private static WorldgenQueryMode restrict(WorldgenQueryMode first, WorldgenQueryMode second) {
		return first.supportsIsolatedParallelRead() && second.supportsIsolatedParallelRead()
			? WorldgenQueryMode.ISOLATED_PARALLEL_READ
			: WorldgenQueryMode.OWNER_SERIAL;
	}

	private static List<WorldgenCapabilityProvider> orderProviders(
		List<? extends WorldgenCapabilityProvider> input
	) {
		Map<ResourceLocation, WorldgenCapabilityProvider> providers = new HashMap<>();
		for (WorldgenCapabilityProvider provider : List.copyOf(input)) {
			Objects.requireNonNull(provider, "provider");
			ResourceLocation id = Objects.requireNonNull(provider.id(), "provider id");
			Objects.requireNonNull(provider.facets(), "provider facets");
			Objects.requireNonNull(provider.ownerTypes(), "provider ownerTypes");
			if (provider.version() <= 0) {
				throw new PlanCompilationException("Provider " + id + " declares a non-positive version");
			}
			if (provider.facets().stream().anyMatch(Objects::isNull)
				|| provider.ownerTypes().stream().anyMatch(Objects::isNull)) {
				throw new PlanCompilationException("Provider " + id + " declares a null facet or owner type");
			}
			if (providers.putIfAbsent(id, provider) != null) {
				throw new PlanCompilationException("Duplicate worldgen capability provider ID: " + id);
			}
		}

		Map<ResourceLocation, Set<ResourceLocation>> outgoing = new HashMap<>();
		Map<ResourceLocation, Integer> incoming = new HashMap<>();
		providers.keySet().forEach(id -> {
			outgoing.put(id, new HashSet<>());
			incoming.put(id, 0);
		});
		for (WorldgenCapabilityProvider provider : providers.values()) {
			for (ProviderOrder order : List.copyOf(provider.ordering())) {
				if (!providers.containsKey(order.before()) || !providers.containsKey(order.after())) {
					if (!order.required()) {
						continue;
					}
					throw new PlanCompilationException(
						"Provider " + provider.id() + " declares an order against an unknown provider: " + order
					);
				}
				if (outgoing.get(order.before()).add(order.after())) {
					incoming.compute(order.after(), (ignored, count) -> count + 1);
				}
			}
		}

		PriorityQueue<ResourceLocation> ready = new PriorityQueue<>(Comparator.comparing(ResourceLocation::toString));
		incoming.forEach((id, count) -> {
			if (count == 0) {
				ready.add(id);
			}
		});
		List<WorldgenCapabilityProvider> ordered = new ArrayList<>();
		while (!ready.isEmpty()) {
			ResourceLocation id = ready.remove();
			ordered.add(providers.get(id));
			outgoing.get(id).stream().sorted(Comparator.comparing(ResourceLocation::toString)).forEach(next -> {
				int count = incoming.compute(next, (ignored, value) -> value - 1);
				if (count == 0) {
					ready.add(next);
				}
			});
		}
		if (ordered.size() != providers.size()) {
			List<String> cycle = incoming.entrySet().stream()
				.filter(entry -> entry.getValue() > 0)
				.map(entry -> entry.getKey().toString())
				.sorted()
				.toList();
			throw new PlanCompilationException("Cycle in worldgen capability provider order: " + cycle);
		}
		return List.copyOf(ordered);
	}

	private static WorldgenPlans.DomainPlan unavailable(
		WorldgenPlans.DomainPlan previous,
		ResourceLocation provider,
		CapabilityFailure failure
	) {
		PlanDescriptor descriptor = new PlanDescriptor(
			provider, previous.facet(), CapabilityState.UNAVAILABLE, "provider_contract",
			"Capability provider could not supply a sound plan", Optional.of(failure)
		);
		return switch (previous) {
			case WorldgenPlans.BiomeComposition ignored -> new WorldgenPlans.BiomeComposition(descriptor, List.of());
			case WorldgenPlans.ProviderSelection ignored -> new WorldgenPlans.ProviderSelection(
				descriptor, 0L, List.of(), Optional.empty(), Optional.empty(), Optional.empty()
			);
			case WorldgenPlans.SelectionDecoration ignored -> new WorldgenPlans.SelectionDecoration(
				descriptor, List.of()
			);
			case WorldgenPlans.SpatialOwnership ignored -> new WorldgenPlans.SpatialOwnership(descriptor, Optional.empty());
			case WorldgenPlans.SamplerDecoration ignored -> new WorldgenPlans.SamplerDecoration(descriptor, Optional.empty());
			case WorldgenPlans.DensitySettings ignored -> new WorldgenPlans.DensitySettings(descriptor, Optional.empty());
			case WorldgenPlans.Surface ignored -> new WorldgenPlans.Surface(descriptor, Optional.empty());
			case WorldgenPlans.Carvers ignored -> new WorldgenPlans.Carvers(descriptor, List.of());
			case WorldgenPlans.PlacedFeatures ignored -> new WorldgenPlans.PlacedFeatures(
				descriptor, List.of(), List.of(), Map.of(),
				raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.empty()
			);
			case WorldgenPlans.Structures ignored -> new WorldgenPlans.Structures(descriptor, List.of(), List.of(), List.of(), List.of());
		};
	}

	private static <T extends WorldgenPlans.DomainPlan> T require(
		Map<WorldgenFacet, WorldgenPlans.DomainPlan> plans,
		WorldgenFacet facet,
		Class<T> type
	) {
		WorldgenPlans.DomainPlan value = plans.get(facet);
		if (!type.isInstance(value)) {
			throw new PlanCompilationException("Facet " + facet + " did not compile to " + type.getSimpleName());
		}
		return type.cast(value);
	}
}
