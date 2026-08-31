package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class WorldgenCapabilityReportTest {
	@Test
	void jsonIsDeterministicValueOnlyAndIncludesFirstFailure() {
		CapabilityFailure failure = CapabilityFailure.unavailable("missing_contract", "No public factory");
		CapabilityNodeReport unavailable = new CapabilityNodeReport(
			id("z"), WorldgenFacet.SURFACE, CapabilityState.UNAVAILABLE, "provider_contract",
			WorldgenOwnerType.PREVIEW_REQUEST, "surface unavailable", Optional.of(failure)
		);
		CapabilityNodeReport normalized = new CapabilityNodeReport(
			id("a"), WorldgenFacet.DENSITY_SETTINGS, CapabilityState.NORMALIZED, "registry_graph",
			WorldgenOwnerType.PREVIEW_REQUEST, "density retained", Optional.empty()
		);
		WorldgenCapabilityReport report = new WorldgenCapabilityReport(List.of(unavailable, normalized));

		String first = report.toJson().toString();
		String second = report.toJson().toString();
		assertEquals(first, second);
		assertEquals(2, report.toJson().get("schema_version").getAsInt());
		assertEquals(
			"owner_serial",
			report.toJson().getAsJsonObject("query_modes").get("provider_selection").getAsString()
		);
		assertTrue(first.indexOf("density_settings") < first.indexOf("surface"));
		assertTrue(first.contains("missing_contract"));
		assertFalse(first.contains("@"));
	}

	@Test
	void unseenMechanismsRetainEveryCapabilityClassification() {
		List<CapabilityNodeReport> nodes = List.of(
			node("declarative_graph", CapabilityState.NORMALIZED, Optional.empty()),
			node("registered_custom_leaf", CapabilityState.OPAQUE_LEAF, Optional.empty()),
			node("lifecycle_custom_root", CapabilityState.OPAQUE_ROOT, Optional.empty()),
			node("explicit_provider_factory", CapabilityState.PROVIDER_CONTRACT, Optional.empty()),
			node("hidden_imperative_mutation", CapabilityState.UNAVAILABLE, Optional.of(
				CapabilityFailure.unavailable("missing_public_contract", "No observable immutable snapshot or factory")
			)),
			node("non_fixed_point_codec", CapabilityState.OPAQUE_ROOT, Optional.empty())
		);
		WorldgenCapabilityReport report = new WorldgenCapabilityReport(nodes);

		assertEquals(
			java.util.Map.of(
				"declarative_graph", CapabilityState.NORMALIZED,
				"registered_custom_leaf", CapabilityState.OPAQUE_LEAF,
				"lifecycle_custom_root", CapabilityState.OPAQUE_ROOT,
				"explicit_provider_factory", CapabilityState.PROVIDER_CONTRACT,
				"hidden_imperative_mutation", CapabilityState.UNAVAILABLE,
				"non_fixed_point_codec", CapabilityState.OPAQUE_ROOT
			),
			report.nodes().stream().collect(java.util.stream.Collectors.toMap(
				node -> node.id().getPath(), CapabilityNodeReport::state
			))
		);
		assertEquals(
			"missing_public_contract",
			report.firstCause(WorldgenFacet.SURFACE).orElseThrow().code()
		);
	}

	private static CapabilityNodeReport node(
		String path,
		CapabilityState state,
		Optional<CapabilityFailure> failure
	) {
		WorldgenFacet facet = path.equals("hidden_imperative_mutation")
			? WorldgenFacet.SURFACE
			: WorldgenFacet.PLACED_FEATURES;
		return new CapabilityNodeReport(
			ResourceLocation.fromNamespaceAndPath("unseen", path),
			facet,
			state,
			"synthetic_public_mechanism",
			WorldgenOwnerType.PREVIEW_REQUEST,
			path,
			failure
		);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}
}
