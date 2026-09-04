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
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import net.minecraft.resources.ResourceLocation;

/** Deterministic capability negotiation and independently failed facet compilation. */
public final class WorldgenPlanCompiler {
	private final List<WorldgenCapabilityProvider> providers;
	private final WorldgenProviderCatalog catalog;

	public WorldgenPlanCompiler(List<? extends WorldgenCapabilityProvider> providers) {
		this.providers = orderProviders(providers);
		this.catalog = null;
	}

	public WorldgenPlanCompiler(WorldgenProviderCatalog catalog) {
		this.providers = List.of();
		this.catalog = Objects.requireNonNull(catalog, "catalog");
	}

	public List<WorldgenCapabilityProvider> providers() {
		return this.providers;
	}

	public WorldgenPlan compile(WorldgenPlan base) {
		return this.compile(base, WorldgenCompilationPurpose.WORLDGEN);
	}

	public WorldgenPlan compile(WorldgenPlan base, WorldgenCompilationPurpose purpose) {
		return this.compile(base, purpose, () -> false);
	}

	public WorldgenPlan compile(
		WorldgenPlan base,
		WorldgenCompilationPurpose purpose,
		BooleanSupplier cancelled
	) {
		Objects.requireNonNull(base, "base");
		Objects.requireNonNull(purpose, "purpose");
		Objects.requireNonNull(cancelled, "cancelled");
		return this.catalog == null
			? this.compileAcquired(base, purpose, cancelled)
			: this.catalog.inAcquisitionSession(
				cancelled, () -> this.compileAcquired(base, purpose, cancelled)
			);
	}

	private WorldgenPlan compileAcquired(
		WorldgenPlan base,
		WorldgenCompilationPurpose purpose,
		BooleanSupplier cancelled
	) {
		checkCancelled(cancelled);
		WorldgenProviderCatalog.Resolution resolution = this.catalog == null
			? new WorldgenProviderCatalog.Resolution(
				this.providers.stream().map(provider -> new WorldgenProviderCatalog.ProviderBinding(
					directMetadata(provider), provider
				)).toList(),
				List.of(),
				List.of()
			)
			: this.catalog.resolveCompile(base.owner().type(), purpose.facets());
		List<WorldgenProviderCatalog.FailedProvider> providerFailures = new ArrayList<>(resolution.failures());
		List<WorldgenProviderCatalog.ProviderBinding> activeBindings = new ArrayList<>();
		for (WorldgenProviderCatalog.ProviderBinding binding : resolution.providers()) {
			Optional<CapabilityFailure> revisionFailure = base.owner().contributionRevision()
				.failure(binding.metadata().id());
			Optional<CapabilityFailure> preServerFailure = this.catalog == null
				|| base.owner().selectedStem() == null
				|| base.owner().selectedStem().generator() == null
				? Optional.empty()
				: WorldgenPreServerFinalizer.failure(
					base.owner().selectedStem().generator(), binding.metadata().id()
				);
			if (revisionFailure.isPresent()) {
				providerFailures.add(new WorldgenProviderCatalog.FailedProvider(
					binding.metadata(), revisionFailure.orElseThrow()
				));
			} else if (preServerFailure.isPresent()) {
				providerFailures.add(new WorldgenProviderCatalog.FailedProvider(
					binding.metadata(), preServerFailure.orElseThrow()
				));
			} else {
				activeBindings.add(binding);
			}
		}
		List<WorldgenCapabilityProvider> activeProviders = this.catalog == null
			? orderProviders(activeBindings.stream()
				.map(WorldgenProviderCatalog.ProviderBinding::provider).toList())
			: activeBindings.stream().map(WorldgenProviderCatalog.ProviderBinding::provider).toList();
		Map<ResourceLocation, WorldgenProviderMetadata> metadataById = new HashMap<>();
		activeBindings.forEach(binding -> metadataById.put(binding.metadata().id(), binding.metadata()));
		checkCancelled(cancelled);
		WorldgenCompilationContext context = new WorldgenCompilationContext(
			base.owner(), purpose, cancelled
		);
		EnumMap<WorldgenFacet, WorldgenPlans.DomainPlan> plans = new EnumMap<>(WorldgenFacet.class);
		for (WorldgenFacet facet : WorldgenFacet.values()) {
			plans.put(facet, base.facet(facet));
		}
		EnumMap<WorldgenFacet, ResourceLocation> contributors = new EnumMap<>(WorldgenFacet.class);
		EnumMap<WorldgenFacet, List<PlanDescriptor>> contributionDescriptors =
			new EnumMap<>(WorldgenFacet.class);
		WorldgenExecution execution = base.execution();
		Set<WorldgenFacet> failedFacets = new HashSet<>();
		for (WorldgenProviderCatalog.FailedProvider failed : providerFailures) {
			for (WorldgenFacet facet : failed.metadata().facets().stream().sorted().toList()) {
				if (!purpose.includes(facet)) {
					continue;
				}
				plans.put(facet, unavailable(plans.get(facet), failed.metadata().id(), failed.failure()));
				execution = execution.withQueryMode(facet, WorldgenQueryMode.OWNER_SERIAL);
				failedFacets.add(facet);
			}
		}

		for (WorldgenCapabilityProvider provider : activeProviders) {
			context.checkCancelled();
			if (!provider.ownerTypes().contains(base.owner().type())) {
				continue;
			}
			for (WorldgenFacet facet : provider.facets().stream().sorted().toList()) {
				context.checkCancelled();
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
					context.checkCancelled();
					if (applicability == WorldgenApplicability.NOT_APPLICABLE) {
						continue;
					}
					Optional<? extends WorldgenPlans.DomainPlan> result = Objects.requireNonNull(
						provider.compile(facet, context), "provider compile result"
					);
					context.checkCancelled();
					if (result.isEmpty()) {
						throw new IllegalStateException(
							"Applicable provider " + provider.id() + " supplied no plan for " + facet
						);
					}
					WorldgenPlans.DomainPlan plan = Objects.requireNonNull(result.get(), "provider plan");
					if (plan.facet() != facet) {
						throw new IllegalArgumentException(
							"Provider " + provider.id() + " returned " + plan.facet() + " while compiling " + facet
						);
					}
					WorldgenContributionKind contributionKind = Objects.requireNonNull(
						provider.contributionKind(facet), "provider contribution kind"
					);
					if (!WorldgenFacetAlgebra.supports(facet, contributionKind)) {
						throw new IllegalArgumentException(
							"Provider " + provider.id() + " declares " + contributionKind + " for " + facet
								+ " but the supported algebra is " + WorldgenFacetAlgebra.supportedKinds(facet)
						);
					}
					if (contributionKind == WorldgenContributionKind.ORDERED_TRANSFORM
						&& facet == WorldgenFacet.BIOME_COMPOSITION) {
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
						if (!contribution.entries().isEmpty()) {
							throw new IllegalArgumentException(
								"Candidate transform provider " + provider.id() + " supplied a unique candidate root"
							);
						}
						if (contribution.stages().isEmpty()) {
							throw new IllegalArgumentException(
								"Candidate transform provider " + provider.id() + " supplied no candidate stages"
							);
						}
						plans.put(facet, composeCandidateStages(previous, contribution));
						WorldgenQueryMode queryMode = queryMode(provider, metadataById.get(provider.id()), facet, context);
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
					if (contributionKind == WorldgenContributionKind.ORDERED_TRANSFORM
						&& facet == WorldgenFacet.SELECTION_DECORATION) {
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
						WorldgenQueryMode queryMode = queryMode(provider, metadataById.get(provider.id()), facet, context);
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
					if (contributionKind == WorldgenContributionKind.ORDERED_TRANSFORM
						&& facet == WorldgenFacet.SAMPLER_DECORATION) {
						WorldgenPlans.SamplerDecoration previous =
							(WorldgenPlans.SamplerDecoration) plans.get(facet);
						WorldgenPlans.SamplerDecoration contribution =
							(WorldgenPlans.SamplerDecoration) plan;
						if (contribution.stages().isEmpty()
							&& contribution.descriptor().state() != CapabilityState.UNAVAILABLE) {
							throw new IllegalArgumentException(
								"Sampler-decoration provider " + provider.id() + " supplied no executable stages"
							);
						}
						if (contribution.descriptor().state() == CapabilityState.UNAVAILABLE) {
							plans.put(facet, contribution);
							execution = execution.withQueryMode(facet, WorldgenQueryMode.OWNER_SERIAL);
							contributionDescriptors.remove(facet);
							failedFacets.add(facet);
							continue;
						}
						WorldgenQueryMode queryMode = queryMode(provider, metadataById.get(provider.id()), facet, context);
						plans.put(facet, composeSamplerDecorators(previous, contribution));
						if (!contribution.stages().isEmpty()) {
							execution = execution.withQueryMode(
								facet,
								previous.stages().isEmpty()
									? queryMode
									: restrict(execution.queryMode(facet), queryMode)
							);
						}
						contributionDescriptors.computeIfAbsent(facet, ignored -> new ArrayList<>())
							.add(contribution.descriptor());
						continue;
					}
					if (contributionKind == WorldgenContributionKind.ORDERED_TRANSFORM
						&& facet == WorldgenFacet.SURFACE) {
						WorldgenPlans.Surface previous = (WorldgenPlans.Surface) plans.get(facet);
						WorldgenPlans.Surface contribution = (WorldgenPlans.Surface) plan;
						if (contribution.descriptor().state() == CapabilityState.UNAVAILABLE) {
							plans.put(facet, contribution);
							execution = execution.withQueryMode(facet, WorldgenQueryMode.OWNER_SERIAL);
							contributionDescriptors.remove(facet);
							failedFacets.add(facet);
							continue;
						}
						if (contribution.root().isPresent() || contribution.transforms().isEmpty()) {
							throw new IllegalArgumentException(
								"Surface transform provider " + provider.id() + " must supply stages and no root"
							);
						}
						PlanDescriptor descriptor = pipelineDescriptor(
							WorldgenFacet.SURFACE, "surface_rule_pipeline",
							"Surface-rule transformations execute once during plan compilation"
						);
						plans.put(facet, previous.append(contribution, descriptor));
						WorldgenQueryMode queryMode = queryMode(provider, metadataById.get(provider.id()), facet, context);
						execution = execution.withQueryMode(
							facet,
							previous.transforms().isEmpty()
								? queryMode
								: restrict(execution.queryMode(facet), queryMode)
						);
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
					WorldgenQueryMode queryMode = queryMode(provider, metadataById.get(provider.id()), facet, context);
					if (facet == WorldgenFacet.SURFACE) {
						WorldgenPlans.Surface current = (WorldgenPlans.Surface) plans.get(facet);
						WorldgenPlans.Surface root = (WorldgenPlans.Surface) plan;
						if (root.root().isEmpty() || !root.transforms().isEmpty()) {
							throw new IllegalArgumentException(
								"Surface root provider " + provider.id() + " must supply one root and no transforms"
							);
						}
						plans.put(facet, current.withRoot(root));
					} else {
						plans.put(facet, plan);
					}
					execution = execution.withQueryMode(facet, queryMode);
					contributionDescriptors.computeIfAbsent(facet, ignored -> new ArrayList<>())
						.add(plan.descriptor());
				} catch (CancellationException failure) {
					throw failure;
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
		WorldgenPlans.Surface pendingSurface = (WorldgenPlans.Surface) plans.get(WorldgenFacet.SURFACE);
		if (!failedFacets.contains(WorldgenFacet.SURFACE) && !pendingSurface.transforms().isEmpty()) {
			try {
				context.checkCancelled();
				plans.put(WorldgenFacet.SURFACE, pendingSurface.materialize(pipelineDescriptor(
					WorldgenFacet.SURFACE, "materialized_surface_rule_pipeline",
					"All surface-rule transformations were applied transactionally during plan compilation"
				)));
				context.checkCancelled();
			} catch (CancellationException failure) {
				throw failure;
			} catch (RuntimeException | LinkageError failure) {
				plans.put(WorldgenFacet.SURFACE, unavailable(
					pendingSurface, pendingSurface.descriptor().id(),
					CapabilityFailure.of("surface_transform_failed", failure)
				));
				execution = execution.withQueryMode(WorldgenFacet.SURFACE, WorldgenQueryMode.OWNER_SERIAL);
				failedFacets.add(WorldgenFacet.SURFACE);
			}
		}
		List<CapabilityNodeReport> reports = new ArrayList<>();
		for (WorldgenFacet facet : WorldgenFacet.values()) {
			context.checkCancelled();
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
			new WorldgenCapabilityReport(reports, execution, resolution.diagnostics())
		);
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Worldgen plan acquisition was superseded");
		}
	}

	private static WorldgenProviderMetadata directMetadata(WorldgenCapabilityProvider provider) {
		EnumMap<WorldgenFacet, WorldgenContributionKind> contributions = new EnumMap<>(WorldgenFacet.class);
		EnumMap<WorldgenFacet, WorldgenQueryMode> queryModes = new EnumMap<>(WorldgenFacet.class);
		provider.facets().forEach(facet -> contributions.put(facet, provider.contributionKind(facet)));
		provider.facets().forEach(facet -> queryModes.put(facet, provider.declaredQueryMode(facet)));
		return new WorldgenProviderMetadata(
			provider.id(), WorldgenProviderMetadata.CURRENT_PROTOCOL, provider.version(),
			provider.getClass().getName(), contributions, queryModes, provider.ownerTypes(), provider.ordering(),
			List.of(), provider.providesPreviewFactory(), provider.requiresPreServerFinalization(),
			provider.providesContributionRevision()
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

	private static WorldgenPlans.SamplerDecoration composeSamplerDecorators(
		WorldgenPlans.SamplerDecoration previous,
		WorldgenPlans.SamplerDecoration contribution
	) {
		if (previous.descriptor().state() == CapabilityState.UNAVAILABLE || previous.stages().isEmpty()) {
			return new WorldgenPlans.SamplerDecoration(
				contribution.descriptor(), previous.queryPolicy(), contribution.stages()
			);
		}
		if (contribution.stages().isEmpty()) {
			return previous;
		}
		PlanDescriptor descriptor = new PlanDescriptor(
			ResourceLocation.fromNamespaceAndPath("reterraforged", "sampler_mechanism_pipeline"),
			WorldgenFacet.SAMPLER_DECORATION,
			CapabilityState.PROVIDER_CONTRACT,
			"ordered_sampler_pipeline",
			"Sampler decorators from independent mechanism contracts execute in declared stage order",
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
			return new WorldgenPlans.BiomeComposition(
				previous.descriptor(), entries, stages, previous.candidateRoot()
			);
		}
		PlanDescriptor descriptor = new PlanDescriptor(
			ResourceLocation.fromNamespaceAndPath("reterraforged", "candidate_mechanism_pipeline"),
			WorldgenFacet.BIOME_COMPOSITION,
			CapabilityState.NORMALIZED,
			"ordered_candidate_pipeline",
			"Candidate additions and removals execute in declared mechanism order before selection",
			Optional.empty()
		);
		return new WorldgenPlans.BiomeComposition(
			descriptor, entries, stages, previous.candidateRoot()
		);
	}

	private static WorldgenQueryMode restrict(WorldgenQueryMode first, WorldgenQueryMode second) {
		return first.supportsIsolatedParallelRead() && second.supportsIsolatedParallelRead()
			? WorldgenQueryMode.ISOLATED_PARALLEL_READ
			: WorldgenQueryMode.OWNER_SERIAL;
	}

	private static PlanDescriptor pipelineDescriptor(
		WorldgenFacet facet,
		String mechanism,
		String detail
	) {
		return new PlanDescriptor(
			ResourceLocation.fromNamespaceAndPath("reterraforged", mechanism),
			facet, CapabilityState.PROVIDER_CONTRACT, mechanism, detail, Optional.empty()
		);
	}

	private static WorldgenQueryMode queryMode(
		WorldgenCapabilityProvider provider,
		WorldgenProviderMetadata metadata,
		WorldgenFacet facet,
		WorldgenCompilationContext context
	) {
		WorldgenQueryMode actual = Objects.requireNonNull(
			provider.queryMode(facet, context), "provider query mode"
		);
		WorldgenQueryMode declared = Objects.requireNonNull(metadata, "provider metadata").queryMode(facet);
		if (actual.supportsIsolatedParallelRead() && !declared.supportsIsolatedParallelRead()) {
			throw new IllegalArgumentException(
				"Provider " + provider.id() + " returned query mode " + actual
					+ " beyond its metadata declaration " + declared + " for " + facet
			);
		}
		return actual;
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
			case WorldgenPlans.SamplerDecoration sampler -> new WorldgenPlans.SamplerDecoration(
				descriptor, sampler.queryPolicy(), Optional.empty()
			);
			case WorldgenPlans.DensitySettings ignored -> new WorldgenPlans.DensitySettings(descriptor, Optional.empty());
			case WorldgenPlans.Surface ignored -> new WorldgenPlans.Surface(descriptor, Optional.empty());
			case WorldgenPlans.Carvers ignored -> new WorldgenPlans.Carvers(descriptor, List.of());
			case WorldgenPlans.PlacedFeatures ignored -> new WorldgenPlans.PlacedFeatures(
				descriptor, List.of(), List.of(), Map.of(),
				raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.empty()
			);
			case WorldgenPlans.Structures ignored -> new WorldgenPlans.Structures(
				descriptor, List.of(), List.of(), List.of(), List.of(), List.of()
			);
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
