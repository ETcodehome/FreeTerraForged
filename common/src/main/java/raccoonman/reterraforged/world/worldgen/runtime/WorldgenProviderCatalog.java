package raccoonman.reterraforged.world.worldgen.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class WorldgenProviderCatalog {
	private final ClassLoader classLoader;
	private final WorldgenProviderEnvironment environment;
	private final List<Registration> registrations;
	private final List<WorldgenProviderDiagnostic> diagnostics;
	private final List<FailedProvider> rejected;
	private final ReentrantLock acquisitionGate = new ReentrantLock(true);

	WorldgenProviderCatalog(
		ClassLoader classLoader,
		WorldgenProviderEnvironment environment,
		List<Registration> registrations,
		List<WorldgenProviderDiagnostic> diagnostics,
		List<FailedProvider> rejected
	) {
		this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
		this.environment = Objects.requireNonNull(environment, "environment");
		this.registrations = registrations.stream()
			.sorted(Comparator.comparing(value -> value.metadata().id().toString()))
			.toList();
		this.diagnostics = List.copyOf(diagnostics);
		this.rejected = List.copyOf(rejected);
	}

	public static WorldgenProviderCatalog of(List<? extends WorldgenCapabilityProvider> providers) {
		Map<net.minecraft.resources.ResourceLocation, List<WorldgenCapabilityProvider>> byId = new HashMap<>();
		providers.forEach(provider -> byId.computeIfAbsent(
			Objects.requireNonNull(provider, "provider").id(), ignored -> new ArrayList<>()
		).add(provider));
		List<Registration> registrations = new ArrayList<>();
		List<FailedProvider> rejected = new ArrayList<>();
		List<WorldgenProviderDiagnostic> diagnostics = new ArrayList<>();
		byId.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(Object::toString)))
			.forEach(entry -> {
				if (entry.getValue().size() == 1) {
					WorldgenCapabilityProvider provider = entry.getValue().getFirst();
					registrations.add(Registration.direct(metadata(provider), provider));
					return;
				}
				CapabilityFailure failure = CapabilityFailure.unavailable(
					"provider_metadata_duplicate_id",
					"Provider ID " + entry.getKey() + " is registered " + entry.getValue().size() + " times"
				);
				diagnostics.add(new WorldgenProviderDiagnostic(
					"direct-provider-catalog", Optional.of(entry.getKey()), Optional.empty(), failure
				));
				entry.getValue().forEach(provider -> rejected.add(new FailedProvider(metadata(provider), failure)));
			});
		return new WorldgenProviderCatalog(
			WorldgenProviderCatalog.class.getClassLoader(),
			new WorldgenProviderEnvironment() {
				@Override public boolean isLoaded(String modId) { return true; }
				@Override public Optional<String> version(String modId) { return Optional.empty(); }
			},
			registrations,
			diagnostics,
			rejected
		);
	}

	public List<WorldgenProviderDiagnostic> diagnostics() {
		return this.diagnostics;
	}

	public <T> T inAcquisitionSession(Supplier<T> operation) {
		return this.inAcquisitionSession(() -> false, operation);
	}

	public <T> T inAcquisitionSession(BooleanSupplier cancelled, Supplier<T> operation) {
		Objects.requireNonNull(cancelled, "cancelled");
		Objects.requireNonNull(operation, "operation");
		boolean acquired = false;
		try {
			while (!acquired) {
				checkCancelled(cancelled);
				acquired = this.acquisitionGate.tryLock(100L, TimeUnit.MILLISECONDS);
			}
			checkCancelled(cancelled);
			return operation.get();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			CancellationException cancellation = new CancellationException(
				"Interrupted waiting for the worldgen acquisition boundary"
			);
			cancellation.initCause(interrupted);
			throw cancellation;
		} finally {
			if (acquired) {
				this.acquisitionGate.unlock();
			}
		}
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Worldgen acquisition was superseded");
		}
	}

	public Resolution resolveCompile(WorldgenOwnerType ownerType, Set<WorldgenFacet> facets) {
		return this.resolve(Optional.of(ownerType), facets, Capability.COMPILE);
	}

	public Resolution resolvePreview() {
		return this.resolve(
			Optional.of(WorldgenOwnerType.PREVIEW_REQUEST),
			java.util.EnumSet.allOf(WorldgenFacet.class),
			Capability.PREVIEW
		);
	}

	public Resolution resolvePreServer() {
		return this.resolve(
			Optional.empty(),
			java.util.EnumSet.allOf(WorldgenFacet.class),
			Capability.PRE_SERVER
		);
	}

	public Resolution resolveContributionRevisions() {
		return this.resolve(
			Optional.empty(),
			java.util.EnumSet.allOf(WorldgenFacet.class),
			Capability.REVISION
		);
	}

	private Resolution resolve(
		Optional<WorldgenOwnerType> ownerType,
		Set<WorldgenFacet> facets,
		Capability capability
	) {
		List<ProviderBinding> providers = new ArrayList<>();
		List<FailedProvider> failures = new ArrayList<>();
		for (FailedProvider failed : this.rejected) {
			if (ownerType.map(failed.metadata().ownerTypes()::contains).orElse(true)
				&& capability.matches(failed.metadata(), facets)) {
				failures.add(failed);
			}
		}
		for (Registration registration : this.registrations) {
			WorldgenProviderMetadata metadata = registration.metadata();
			if (ownerType.map(type -> !metadata.ownerTypes().contains(type)).orElse(false)
				|| !capability.matches(metadata, facets)) {
				continue;
			}
			Optional<CapabilityFailure> negotiation = this.negotiate(metadata);
			if (negotiation.isPresent()) {
				failures.add(new FailedProvider(metadata, negotiation.orElseThrow()));
				continue;
			}
			if (metadata.mechanisms().stream().anyMatch(requirement ->
				requirement.optional() && !this.environment.isLoaded(requirement.modId()))) {
				continue;
			}
			try {
				WorldgenCapabilityProvider provider = registration.load(this.classLoader);
				if (registration.validatesImplementation()) {
					validateImplementation(metadata, provider);
				}
				providers.add(new ProviderBinding(metadata, provider));
			} catch (WorldgenProviderContractException | ClassCastException failure) {
				failures.add(new FailedProvider(
					metadata,
					CapabilityFailure.of("provider_contract_mismatch", failure)
				));
			} catch (ClassNotFoundException | LinkageError failure) {
				failures.add(new FailedProvider(
					metadata,
					CapabilityFailure.of("provider_implementation_linkage_failed", failure)
				));
			} catch (NoSuchMethodException | InstantiationException | IllegalAccessException failure) {
				failures.add(new FailedProvider(
					metadata,
					CapabilityFailure.of("provider_constructor_unavailable", failure)
				));
			} catch (InvocationTargetException failure) {
				Throwable cause = failure.getCause() == null ? failure : failure.getCause();
				failures.add(new FailedProvider(
					metadata,
					CapabilityFailure.of("provider_constructor_failed", cause)
				));
			} catch (RuntimeException failure) {
				failures.add(new FailedProvider(
					metadata,
					CapabilityFailure.of("provider_implementation_load_failed", failure)
				));
			}
		}
		OrderResult ordered = order(providers);
		failures.addAll(ordered.failures());
		failures.sort(Comparator.comparing(value -> value.metadata().id().toString()));
		return new Resolution(ordered.providers(), failures, this.diagnostics);
	}

	private static OrderResult order(List<ProviderBinding> input) {
		Map<net.minecraft.resources.ResourceLocation, ProviderBinding> providers = new HashMap<>();
		input.forEach(binding -> providers.put(binding.metadata().id(), binding));
		List<FailedProvider> failures = new ArrayList<>();
		Set<net.minecraft.resources.ResourceLocation> rejected = new HashSet<>();
		boolean changed;
		do {
			changed = false;
			for (ProviderBinding binding : input) {
				var id = binding.metadata().id();
				if (rejected.contains(id)) {
					continue;
				}
				for (ProviderOrder edge : binding.metadata().ordering()) {
					var peer = edge.before().equals(id) ? edge.after() : edge.before();
					if (edge.required() && (!providers.containsKey(peer) || rejected.contains(peer))) {
						rejected.add(id);
						failures.add(new FailedProvider(binding.metadata(), CapabilityFailure.unavailable(
							"provider_order_peer_missing",
							"Provider " + id + " requires ordering peer " + peer + " for edge " + edge
						)));
						changed = true;
						break;
					}
				}
			}
		} while (changed);

		Map<net.minecraft.resources.ResourceLocation, Set<net.minecraft.resources.ResourceLocation>> outgoing =
			new HashMap<>();
		Map<net.minecraft.resources.ResourceLocation, Integer> incoming = new HashMap<>();
		providers.keySet().stream().filter(id -> !rejected.contains(id)).forEach(id -> {
			outgoing.put(id, new HashSet<>());
			incoming.put(id, 0);
		});
		for (ProviderBinding binding : input) {
			if (rejected.contains(binding.metadata().id())) {
				continue;
			}
			for (ProviderOrder edge : binding.metadata().ordering()) {
				if (!incoming.containsKey(edge.before()) || !incoming.containsKey(edge.after())) {
					continue;
				}
				if (outgoing.get(edge.before()).add(edge.after())) {
					incoming.compute(edge.after(), (ignored, count) -> count + 1);
				}
			}
		}

		PriorityQueue<net.minecraft.resources.ResourceLocation> ready = new PriorityQueue<>(
			Comparator.comparing(net.minecraft.resources.ResourceLocation::toString)
		);
		incoming.forEach((id, count) -> {
			if (count == 0) {
				ready.add(id);
			}
		});
		List<ProviderBinding> ordered = new ArrayList<>();
		while (!ready.isEmpty()) {
			var id = ready.remove();
			ordered.add(providers.get(id));
			outgoing.get(id).stream().sorted(Comparator.comparing(Object::toString)).forEach(next -> {
				int count = incoming.compute(next, (ignored, value) -> value - 1);
				if (count == 0) {
					ready.add(next);
				}
			});
		}
		if (ordered.size() != incoming.size()) {
			Set<net.minecraft.resources.ResourceLocation> residue = new HashSet<>(incoming.keySet());
			ordered.forEach(binding -> residue.remove(binding.metadata().id()));
			for (var id : residue.stream().sorted(Comparator.comparing(Object::toString)).toList()) {
				ProviderBinding binding = providers.get(id);
				failures.add(new FailedProvider(binding.metadata(), CapabilityFailure.unavailable(
					"provider_order_cycle",
					"Provider " + id + " participates in or depends on cyclic provider ordering " + residue
				)));
			}
		}
		return new OrderResult(ordered, failures);
	}

	private Optional<CapabilityFailure> negotiate(WorldgenProviderMetadata metadata) {
		if (metadata.protocolVersion() != WorldgenProviderMetadata.CURRENT_PROTOCOL) {
			return Optional.of(CapabilityFailure.unavailable(
				"provider_protocol_incompatible",
				"Provider " + metadata.id() + " declares protocol " + metadata.protocolVersion()
					+ " but this runtime supports " + WorldgenProviderMetadata.CURRENT_PROTOCOL
			));
		}
		for (WorldgenMechanismRequirement requirement : metadata.mechanisms()) {
			if (!this.environment.isLoaded(requirement.modId())) {
				if (requirement.optional()) {
					continue;
				}
				return Optional.of(CapabilityFailure.unavailable(
					"provider_required_mechanism_missing",
					"Provider " + metadata.id() + " requires " + requirement.modId()
				));
			}
			Optional<String> loadedVersion = this.environment.version(requirement.modId());
			if (!requirement.supportedVersions().isEmpty()
				&& (loadedVersion.isEmpty() || !requirement.supportedVersions().contains(loadedVersion.orElseThrow()))) {
				return Optional.of(CapabilityFailure.unavailable(
					"provider_mechanism_version_incompatible",
					"Provider " + metadata.id() + " supports " + requirement.modId() + " versions "
						+ requirement.supportedVersions() + " but found " + loadedVersion.orElse("unknown")
				));
			}
		}
		return Optional.empty();
	}

	private static void validateImplementation(
		WorldgenProviderMetadata metadata,
		WorldgenCapabilityProvider provider
	) {
		if (!metadata.id().equals(provider.id())) {
			throw new WorldgenProviderContractException("Provider implementation ID does not match metadata");
		}
		if (metadata.adapterVersion() != provider.version()) {
			throw new WorldgenProviderContractException("Provider implementation version does not match metadata");
		}
		if (!metadata.facets().equals(Set.copyOf(provider.facets()))) {
			throw new WorldgenProviderContractException("Provider implementation facets do not match metadata");
		}
		if (!metadata.ownerTypes().equals(Set.copyOf(provider.ownerTypes()))) {
			throw new WorldgenProviderContractException("Provider implementation owners do not match metadata");
		}
		if (!metadata.ordering().equals(List.copyOf(provider.ordering()))) {
			throw new WorldgenProviderContractException("Provider implementation ordering does not match metadata");
		}
		for (WorldgenFacet facet : metadata.facets()) {
			if (metadata.contributionKind(facet) != provider.contributionKind(facet)) {
				throw new WorldgenProviderContractException(
					"Provider implementation contribution kind does not match metadata for " + facet
				);
			}
			if (metadata.queryMode(facet) != provider.declaredQueryMode(facet)) {
				throw new WorldgenProviderContractException(
					"Provider implementation query-mode declaration does not match metadata for " + facet
				);
			}
		}
		if (metadata.previewFactory() != provider.providesPreviewFactory()
			|| metadata.preServerFinalizer() != provider.requiresPreServerFinalization()
			|| metadata.contributionRevision() != provider.providesContributionRevision()) {
			throw new WorldgenProviderContractException("Provider implementation lifecycle declaration does not match metadata");
		}
	}

	private static WorldgenProviderMetadata metadata(WorldgenCapabilityProvider provider) {
		java.util.EnumMap<WorldgenFacet, WorldgenContributionKind> contributions =
			new java.util.EnumMap<>(WorldgenFacet.class);
		java.util.EnumMap<WorldgenFacet, WorldgenQueryMode> queryModes =
			new java.util.EnumMap<>(WorldgenFacet.class);
		provider.facets().forEach(facet -> contributions.put(facet, provider.contributionKind(facet)));
		provider.facets().forEach(facet -> queryModes.put(facet, provider.declaredQueryMode(facet)));
		return new WorldgenProviderMetadata(
			provider.id(), WorldgenProviderMetadata.CURRENT_PROTOCOL, provider.version(),
			provider.getClass().getName(), contributions, queryModes, provider.ownerTypes(), provider.ordering(),
			List.of(), provider.providesPreviewFactory(), provider.requiresPreServerFinalization(),
			provider.providesContributionRevision()
		);
	}

	public record ProviderBinding(
		WorldgenProviderMetadata metadata,
		WorldgenCapabilityProvider provider
	) {
		public ProviderBinding {
			metadata = Objects.requireNonNull(metadata, "metadata");
			provider = Objects.requireNonNull(provider, "provider");
		}
	}

	public record FailedProvider(
		WorldgenProviderMetadata metadata,
		CapabilityFailure failure
	) {
		public FailedProvider {
			metadata = Objects.requireNonNull(metadata, "metadata");
			failure = Objects.requireNonNull(failure, "failure");
		}
	}

	public record Resolution(
		List<ProviderBinding> providers,
		List<FailedProvider> failures,
		List<WorldgenProviderDiagnostic> diagnostics
	) {
		public Resolution {
			providers = List.copyOf(providers);
			failures = List.copyOf(failures);
			diagnostics = List.copyOf(diagnostics);
		}
	}

	private record OrderResult(List<ProviderBinding> providers, List<FailedProvider> failures) {
		private OrderResult {
			providers = List.copyOf(providers);
			failures = List.copyOf(failures);
		}
	}

	static final class Registration {
		private final WorldgenProviderMetadata metadata;
		private final WorldgenCapabilityProvider direct;
		private final boolean validateImplementation;
		private WorldgenCapabilityProvider loaded;

		Registration(WorldgenProviderMetadata metadata) {
			this(metadata, null, true);
		}

		private Registration(
			WorldgenProviderMetadata metadata,
			WorldgenCapabilityProvider direct,
			boolean validateImplementation
		) {
			this.metadata = Objects.requireNonNull(metadata, "metadata");
			this.direct = direct;
			this.validateImplementation = validateImplementation;
		}

		static Registration direct(
			WorldgenProviderMetadata metadata,
			WorldgenCapabilityProvider provider
		) {
			return new Registration(metadata, Objects.requireNonNull(provider, "provider"), false);
		}

		WorldgenProviderMetadata metadata() {
			return this.metadata;
		}

		boolean validatesImplementation() {
			return this.validateImplementation;
		}

		synchronized WorldgenCapabilityProvider load(ClassLoader classLoader) throws
			ClassNotFoundException,
			NoSuchMethodException,
			InstantiationException,
			IllegalAccessException,
			InvocationTargetException {
			if (this.direct != null) {
				return this.direct;
			}
			if (this.loaded == null) {
				Class<?> implementation = Class.forName(
					this.metadata.implementationClass(), true, classLoader
				);
				Class<? extends WorldgenCapabilityProvider> providerClass = implementation.asSubclass(
					WorldgenCapabilityProvider.class
				);
				Constructor<? extends WorldgenCapabilityProvider> constructor = providerClass.getConstructor();
				this.loaded = constructor.newInstance();
			}
			return this.loaded;
		}
	}

	private enum Capability {
		COMPILE,
		PREVIEW,
		PRE_SERVER,
		REVISION;

		boolean matches(WorldgenProviderMetadata metadata, Set<WorldgenFacet> facets) {
			return switch (this) {
				case COMPILE -> metadata.facets().stream().anyMatch(facets::contains);
				case PREVIEW -> metadata.previewFactory();
				case PRE_SERVER -> metadata.preServerFinalizer();
				case REVISION -> metadata.contributionRevision();
			};
		}
	}
}
