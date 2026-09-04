package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.net.URL;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.resources.ResourceLocation;

class WorldgenCapabilityDiscoveryTest {
	private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("synthetic", "provider");
	private static final ResourceLocation REQUIRED_ID = ResourceLocation.fromNamespaceAndPath("synthetic", "required_peer");
	private static final ResourceLocation MISSING_ID = ResourceLocation.fromNamespaceAndPath("synthetic", "missing_peer");
	private static final ResourceLocation CYCLE_A_ID = ResourceLocation.fromNamespaceAndPath("synthetic", "cycle_a");
	private static final ResourceLocation CYCLE_B_ID = ResourceLocation.fromNamespaceAndPath("synthetic", "cycle_b");

	@TempDir
	Path temporary;

	@BeforeEach
	void resetConstructionCount() {
		SyntheticProvider.CONSTRUCTIONS.set(0);
	}

	@Test
	void absentOptionalMechanismNeverLinksImplementation() throws Exception {
		WorldgenProviderCatalog catalog = discover(metadata(1, SyntheticProvider.class.getName(), true), Map.of());

		WorldgenProviderCatalog.Resolution resolution = catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);

		assertTrue(resolution.providers().isEmpty());
		assertTrue(resolution.failures().isEmpty());
		assertEquals(0, SyntheticProvider.CONSTRUCTIONS.get());
	}

	@Test
	void incompatibleProtocolFailsBeforeImplementationLoading() throws Exception {
		WorldgenProviderCatalog catalog = discover(
			metadata(99, SyntheticProvider.class.getName(), false), Map.of("syntheticlib", "1.0")
		);

		WorldgenProviderCatalog.Resolution resolution = catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);

		assertEquals("provider_protocol_incompatible", resolution.failures().getFirst().failure().code());
		assertEquals(0, SyntheticProvider.CONSTRUCTIONS.get());
	}

	@Test
	void implementationLinkageFailureIsBoundedToDeclaredMetadata() throws Exception {
		WorldgenProviderCatalog catalog = discover(
			metadata(1, "missing.optional.LibraryProvider", false), Map.of("syntheticlib", "1.0")
		);

		WorldgenProviderCatalog.Resolution resolution = catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);

		assertTrue(resolution.providers().isEmpty());
		assertEquals("provider_implementation_linkage_failed", resolution.failures().getFirst().failure().code());
		assertEquals(Set.of(WorldgenFacet.SURFACE), resolution.failures().getFirst().metadata().facets());
	}

	@Test
	void constructorFailureIsDistinctFromLinkageAndRetainsItsFirstCause() throws Exception {
		WorldgenProviderCatalog catalog = discover(
			metadata(1, ThrowingConstructorProvider.class.getName(), false), Map.of("syntheticlib", "1.0")
		);

		WorldgenProviderCatalog.Resolution resolution = catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);

		assertEquals("provider_constructor_failed", resolution.failures().getFirst().failure().code());
		assertEquals("synthetic constructor failure", resolution.failures().getFirst().failure().message());
	}

	@Test
	void duplicateDirectProviderIdsAreRejectedWithoutMapOverwrite() {
		WorldgenProviderCatalog catalog = WorldgenProviderCatalog.of(List.of(
			new SyntheticProvider(), new SyntheticProvider()
		));

		WorldgenProviderCatalog.Resolution resolution = catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);

		assertTrue(resolution.providers().isEmpty());
		assertEquals(2, resolution.failures().size());
		assertTrue(resolution.failures().stream().allMatch(
			failure -> failure.failure().code().equals("provider_metadata_duplicate_id")
		));
	}

	@Test
	void unrelatedPreviewResolutionDoesNotLoadCompileOnlyProvider() throws Exception {
		WorldgenProviderCatalog catalog = discover(
			metadata(1, SyntheticProvider.class.getName(), false), Map.of("syntheticlib", "1.0")
		);

		assertTrue(catalog.resolvePreview().providers().isEmpty());
		assertEquals(0, SyntheticProvider.CONSTRUCTIONS.get());
		assertEquals(1, catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		).providers().size());
		assertEquals(1, SyntheticProvider.CONSTRUCTIONS.get());
	}

	@Test
	void directCompileOnlyProviderIsNotMisdeclaredAsALifecycleProvider() {
		WorldgenProviderCatalog catalog = WorldgenProviderCatalog.of(List.of(new SyntheticProvider()));

		assertTrue(catalog.resolvePreview().providers().isEmpty());
		assertTrue(catalog.resolvePreServer().providers().isEmpty());
		assertEquals(1, catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		).providers().size());
	}

	@Test
	void preServerFinalizationIsNotAccidentallyFilteredByPreviewOwnership() {
		WorldgenProviderCatalog catalog = WorldgenProviderCatalog.of(List.of(new EpochFinalizerProvider()));

		assertEquals(1, catalog.resolvePreServer().providers().size());
		assertTrue(catalog.resolvePreview().providers().isEmpty());
	}

	@Test
	void malformedMetadataIsAValueOnlyDiagnostic() throws Exception {
		WorldgenProviderCatalog catalog = discover("{\"providers\":[{\"id\":4}]}", Map.of());

		assertEquals(1, catalog.diagnostics().size());
		assertEquals("provider_metadata_invalid", catalog.diagnostics().getFirst().failure().code());
		assertTrue(catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		).providers().isEmpty());
	}

	@Test
	void fractionalProtocolAndUnknownNestedKeysAreRejectedBeforeLinkage() throws Exception {
		String fractional = metadata(1, SyntheticProvider.class.getName(), false)
			.replace("\"protocol_version\": 1", "\"protocol_version\": 1.5");
		WorldgenProviderCatalog fractionalCatalog = discover(fractional, Map.of("syntheticlib", "1.0"));
		assertEquals("provider_metadata_invalid", fractionalCatalog.diagnostics().getFirst().failure().code());
		assertEquals(0, SyntheticProvider.CONSTRUCTIONS.get());

		String nested = metadata(1, SyntheticProvider.class.getName(), false)
			.replace("\"optional\": false", "\"optional\": false, \"guess\": true");
		WorldgenProviderCatalog nestedCatalog = discover(nested, Map.of("syntheticlib", "1.0"));
		assertEquals("provider_metadata_invalid", nestedCatalog.diagnostics().getFirst().failure().code());
		assertEquals(0, SyntheticProvider.CONSTRUCTIONS.get());

		String unknownRoot = metadata(1, SyntheticProvider.class.getName(), false)
			.replaceFirst("\\{", "{\\\"guess\\\":true,");
		WorldgenProviderCatalog rootCatalog = discover(unknownRoot, Map.of("syntheticlib", "1.0"));
		assertEquals("provider_metadata_resource_invalid", rootCatalog.diagnostics().getFirst().failure().code());
		assertEquals(0, SyntheticProvider.CONSTRUCTIONS.get());
	}

	@Test
	void fatalJvmErrorsAreNeverDowngradedToProviderDiagnostics() {
		AssertionError fatal = new AssertionError("fatal discovery control");
		ClassLoader classLoader = new ClassLoader(null) {
			@Override
			public Enumeration<URL> getResources(String name) throws IOException {
				throw fatal;
			}
		};

		assertEquals(fatal, assertThrows(
			AssertionError.class,
			() -> WorldgenCapabilityDiscovery.discover(classLoader, new WorldgenProviderEnvironment() {
				@Override public boolean isLoaded(String modId) { return false; }
				@Override public Optional<String> version(String modId) { return Optional.empty(); }
			})
		));
	}

	@Test
	void requiredMissingOrderingPeerFailsOnlyTheDeclaringProvider() throws Exception {
		String json = providers(metadata(
			REQUIRED_ID, RequiredPeerProvider.class, "owner_serial",
			order(REQUIRED_ID, MISSING_ID, true)
		));

		WorldgenProviderCatalog.Resolution resolution = discover(json, Map.of()).resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);

		assertTrue(resolution.providers().isEmpty());
		assertEquals(1, resolution.failures().size());
		assertEquals("provider_order_peer_missing", resolution.failures().getFirst().failure().code());
	}

	@Test
	void providerOrderingCycleIsAContainedDeterministicFailure() throws Exception {
		String json = providers(
			metadata(CYCLE_A_ID, CycleAProvider.class, "owner_serial", order(CYCLE_A_ID, CYCLE_B_ID, true)),
			metadata(CYCLE_B_ID, CycleBProvider.class, "owner_serial", order(CYCLE_B_ID, CYCLE_A_ID, true))
		);

		WorldgenProviderCatalog.Resolution resolution = discover(json, Map.of()).resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);

		assertTrue(resolution.providers().isEmpty());
		assertEquals(List.of(CYCLE_A_ID, CYCLE_B_ID), resolution.failures().stream()
			.map(failure -> failure.metadata().id()).toList());
		assertTrue(resolution.failures().stream()
			.allMatch(failure -> failure.failure().code().equals("provider_order_cycle")));
	}

	@Test
	void duplicateIdsRetainFacetScopedFailuresWithoutLoadingEitherClass() throws Exception {
		String declaration = metadata(ID, SyntheticProvider.class, "owner_serial", "");
		WorldgenProviderCatalog catalog = discover(providers(declaration, declaration), Map.of());

		WorldgenProviderCatalog.Resolution resolution = catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);

		assertTrue(resolution.providers().isEmpty());
		assertEquals(2, resolution.failures().size());
		assertTrue(resolution.failures().stream()
			.allMatch(failure -> failure.failure().code().equals("provider_metadata_duplicate_id")));
		assertEquals(0, SyntheticProvider.CONSTRUCTIONS.get());
	}

	@Test
	void repeatedEnumerationOfTheSameResourceUrlIsOneDeclaration() throws Exception {
		Path resource = this.temporary.resolve(WorldgenCapabilityDiscovery.RESOURCE);
		Files.createDirectories(resource.getParent());
		Files.writeString(resource, metadata(1, SyntheticProvider.class.getName(), false));
		URL resourceUrl = resource.toUri().toURL();
		ClassLoader classLoader = new ClassLoader(getClass().getClassLoader()) {
			@Override
			public Enumeration<URL> getResources(String name) {
				return Collections.enumeration(List.of(resourceUrl, resourceUrl));
			}
		};

		WorldgenProviderCatalog catalog = WorldgenCapabilityDiscovery.discover(
			classLoader,
			new WorldgenProviderEnvironment() {
				@Override public boolean isLoaded(String modId) { return modId.equals("syntheticlib"); }
				@Override public Optional<String> version(String modId) { return Optional.of("1.0"); }
			}
		);

		assertTrue(catalog.diagnostics().isEmpty());
		assertEquals(1, catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		).providers().size());
	}

	@Test
	void byteIdenticalResourcesAtDistinctUrlsAreOneDeclaration() throws Exception {
		String declaration = metadata(1, SyntheticProvider.class.getName(), false);
		Path first = this.temporary.resolve("first").resolve(WorldgenCapabilityDiscovery.RESOURCE);
		Path second = this.temporary.resolve("second").resolve(WorldgenCapabilityDiscovery.RESOURCE);
		Files.createDirectories(first.getParent());
		Files.createDirectories(second.getParent());
		Files.writeString(first, declaration);
		Files.writeString(second, declaration);
		ClassLoader classLoader = new ClassLoader(getClass().getClassLoader()) {
			@Override
			public Enumeration<URL> getResources(String name) throws IOException {
				return Collections.enumeration(List.of(first.toUri().toURL(), second.toUri().toURL()));
			}
		};

		WorldgenProviderCatalog catalog = WorldgenCapabilityDiscovery.discover(
			classLoader,
			new WorldgenProviderEnvironment() {
				@Override public boolean isLoaded(String modId) { return modId.equals("syntheticlib"); }
				@Override public Optional<String> version(String modId) { return Optional.of("1.0"); }
			}
		);

		assertTrue(catalog.diagnostics().isEmpty());
		assertEquals(1, catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		).providers().size());
	}

	@Test
	void nonidenticalResourcesWithTheSameIdRemainAmbiguous() throws Exception {
		String declaration = metadata(1, SyntheticProvider.class.getName(), false);
		Path first = this.temporary.resolve("first").resolve(WorldgenCapabilityDiscovery.RESOURCE);
		Path second = this.temporary.resolve("second").resolve(WorldgenCapabilityDiscovery.RESOURCE);
		Files.createDirectories(first.getParent());
		Files.createDirectories(second.getParent());
		Files.writeString(first, declaration);
		Files.writeString(second, declaration.replace("\"adapter_version\": 1", "\"adapter_version\": 2"));
		ClassLoader classLoader = new ClassLoader(getClass().getClassLoader()) {
			@Override
			public Enumeration<URL> getResources(String name) throws IOException {
				return Collections.enumeration(List.of(first.toUri().toURL(), second.toUri().toURL()));
			}
		};

		WorldgenProviderCatalog catalog = WorldgenCapabilityDiscovery.discover(
			classLoader,
			new WorldgenProviderEnvironment() {
				@Override public boolean isLoaded(String modId) { return modId.equals("syntheticlib"); }
				@Override public Optional<String> version(String modId) { return Optional.of("1.0"); }
			}
		);

		WorldgenProviderCatalog.Resolution resolution = catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);
		assertTrue(resolution.providers().isEmpty());
		assertEquals(2, resolution.failures().size());
		assertTrue(resolution.failures().stream().allMatch(
			failure -> failure.failure().code().equals("provider_metadata_duplicate_id")
		));
	}

	@Test
	void metadataImplementationContractMismatchIsDistinctFromLinkageFailure() throws Exception {
		WorldgenProviderCatalog catalog = discover(
			providers(metadata(ID, SyntheticProvider.class, "isolated_parallel_read", "")), Map.of()
		);

		WorldgenProviderCatalog.Resolution resolution = catalog.resolveCompile(
			WorldgenOwnerType.WORLDGEN_EPOCH, Set.of(WorldgenFacet.SURFACE)
		);

		assertEquals("provider_contract_mismatch", resolution.failures().getFirst().failure().code());
		assertEquals(1, SyntheticProvider.CONSTRUCTIONS.get());
	}

	private WorldgenProviderCatalog discover(String json, Map<String, String> mods) throws Exception {
		Path resource = this.temporary.resolve(WorldgenCapabilityDiscovery.RESOURCE);
		Files.createDirectories(resource.getParent());
		Files.writeString(resource, json);
		URLClassLoader classLoader = new URLClassLoader(
			new java.net.URL[] { this.temporary.toUri().toURL() }, getClass().getClassLoader()
		);
		return WorldgenCapabilityDiscovery.discover(classLoader, new WorldgenProviderEnvironment() {
			@Override public boolean isLoaded(String modId) { return mods.containsKey(modId); }
			@Override public Optional<String> version(String modId) { return Optional.ofNullable(mods.get(modId)); }
		});
	}

	private static String metadata(int protocol, String implementation, boolean optional) {
		return """
			{
			  "providers": [{
			    "id": "synthetic:provider",
			    "protocol_version": %d,
			    "adapter_version": 1,
			    "implementation": "%s",
			    "owners": ["worldgen_epoch"],
			    "contributions": {"surface": "unique_root"},
			    "query_modes": {"surface": "owner_serial"},
			    "mechanisms": [{
			      "mod_id": "syntheticlib",
			      "supported_versions": ["1.0"],
			      "optional": %s
			    }]
			  }]
			}
			""".formatted(protocol, implementation, optional);
	}

	private static String providers(String... declarations) {
		return "{\"providers\":[" + String.join(",", declarations) + "]}";
	}

	private static String metadata(
		ResourceLocation id,
		Class<? extends WorldgenCapabilityProvider> implementation,
		String queryMode,
		String ordering
	) {
		return """
			{
			  "id": "%s",
			  "protocol_version": 1,
			  "adapter_version": 1,
			  "implementation": "%s",
			  "owners": ["worldgen_epoch"],
			  "contributions": {"surface": "unique_root"},
			  "query_modes": {"surface": "%s"}%s
			}
			""".formatted(id, implementation.getName(), queryMode, ordering);
	}

	private static String order(ResourceLocation before, ResourceLocation after, boolean required) {
		return ",\"ordering\":[{\"before\":\"%s\",\"after\":\"%s\",\"required\":%s}]"
			.formatted(before, after, required);
	}

	public static final class SyntheticProvider implements WorldgenCapabilityProvider {
		static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

		public SyntheticProvider() {
			CONSTRUCTIONS.incrementAndGet();
		}

		@Override public ResourceLocation id() { return ID; }
		@Override public int version() { return 1; }
		@Override public Set<WorldgenFacet> facets() { return EnumSet.of(WorldgenFacet.SURFACE); }
		@Override public Set<WorldgenOwnerType> ownerTypes() { return EnumSet.of(WorldgenOwnerType.WORLDGEN_EPOCH); }
		@Override public List<ProviderOrder> ordering() { return List.of(); }
		@Override public WorldgenApplicability applicability(WorldgenFacet facet, WorldgenCompilationContext context) {
			return WorldgenApplicability.APPLICABLE;
		}
		@Override public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
			return Optional.empty();
		}
		@Override public Optional<? extends WorldgenPlans.DomainPlan> compile(
			WorldgenFacet facet, WorldgenCompilationContext context
		) {
			return Optional.empty();
		}
		@Override public WorldgenQueryMode queryMode(WorldgenFacet facet, WorldgenCompilationContext context) {
			return WorldgenQueryMode.OWNER_SERIAL;
		}
	}

	public static final class ThrowingConstructorProvider extends SyntheticProviderBase {
		public ThrowingConstructorProvider() {
			throw new IllegalStateException("synthetic constructor failure");
		}

		@Override public ResourceLocation id() { return ID; }
		@Override public Set<WorldgenFacet> facets() { return Set.of(WorldgenFacet.SURFACE); }
	}

	public static final class EpochFinalizerProvider extends SyntheticProviderBase {
		@Override public ResourceLocation id() {
			return ResourceLocation.fromNamespaceAndPath("synthetic", "epoch_finalizer");
		}
		@Override public boolean requiresPreServerFinalization() { return true; }
	}

	public abstract static class SyntheticProviderBase implements WorldgenCapabilityProvider {
		@Override public int version() { return 1; }
		@Override public Set<WorldgenFacet> facets() { return Set.of(); }
		@Override public Set<WorldgenOwnerType> ownerTypes() { return Set.of(WorldgenOwnerType.WORLDGEN_EPOCH); }
		@Override public List<ProviderOrder> ordering() { return List.of(); }
		@Override public WorldgenApplicability applicability(WorldgenFacet facet, WorldgenCompilationContext context) {
			return WorldgenApplicability.NOT_APPLICABLE;
		}
		@Override public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
			return Optional.empty();
		}
		@Override public Optional<? extends WorldgenPlans.DomainPlan> compile(
			WorldgenFacet facet, WorldgenCompilationContext context
		) {
			return Optional.empty();
		}
		@Override public WorldgenQueryMode queryMode(WorldgenFacet facet, WorldgenCompilationContext context) {
			return WorldgenQueryMode.OWNER_SERIAL;
		}
	}

	public abstract static class OrderedProvider implements WorldgenCapabilityProvider {
		private final ResourceLocation id;
		private final List<ProviderOrder> ordering;

		protected OrderedProvider(ResourceLocation id, List<ProviderOrder> ordering) {
			this.id = id;
			this.ordering = ordering;
		}

		@Override public ResourceLocation id() { return this.id; }
		@Override public int version() { return 1; }
		@Override public Set<WorldgenFacet> facets() { return Set.of(WorldgenFacet.SURFACE); }
		@Override public Set<WorldgenOwnerType> ownerTypes() { return Set.of(WorldgenOwnerType.WORLDGEN_EPOCH); }
		@Override public List<ProviderOrder> ordering() { return this.ordering; }
		@Override public WorldgenApplicability applicability(WorldgenFacet facet, WorldgenCompilationContext context) {
			return WorldgenApplicability.APPLICABLE;
		}
		@Override public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
			return Optional.empty();
		}
		@Override public Optional<? extends WorldgenPlans.DomainPlan> compile(
			WorldgenFacet facet, WorldgenCompilationContext context
		) {
			return Optional.empty();
		}
		@Override public WorldgenQueryMode queryMode(WorldgenFacet facet, WorldgenCompilationContext context) {
			return WorldgenQueryMode.OWNER_SERIAL;
		}
	}

	public static final class RequiredPeerProvider extends OrderedProvider {
		public RequiredPeerProvider() {
			super(REQUIRED_ID, List.of(new ProviderOrder(REQUIRED_ID, MISSING_ID, true)));
		}
	}

	public static final class CycleAProvider extends OrderedProvider {
		public CycleAProvider() {
			super(CYCLE_A_ID, List.of(new ProviderOrder(CYCLE_A_ID, CYCLE_B_ID, true)));
		}
	}

	public static final class CycleBProvider extends OrderedProvider {
		public CycleBProvider() {
			super(CYCLE_B_ID, List.of(new ProviderOrder(CYCLE_B_ID, CYCLE_A_ID, true)));
		}
	}
}
