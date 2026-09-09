package raccoonman.reterraforged.world.worldgen.runtime;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;
import raccoonman.reterraforged.platform.ModLoaderUtil;

public final class WorldgenCapabilityDiscovery {
	public static final String RESOURCE = "META-INF/reterraforged/worldgen-capabilities.json";

	private WorldgenCapabilityDiscovery() {
	}

	public static WorldgenProviderCatalog discover(ClassLoader classLoader) {
		return discover(classLoader, new WorldgenProviderEnvironment() {
			@Override
			public boolean isLoaded(String modId) {
				return ModLoaderUtil.isLoaded(modId);
			}

			@Override
			public Optional<String> version(String modId) {
				return ModLoaderUtil.version(modId);
			}
		});
	}

	static WorldgenProviderCatalog discover(
		ClassLoader classLoader,
		WorldgenProviderEnvironment environment
	) {
		List<Parsed> parsed = new ArrayList<>();
		List<WorldgenProviderDiagnostic> diagnostics = new ArrayList<>();
		Set<String> visitedResources = new HashSet<>();
		Set<String> visitedResourceContents = new HashSet<>();
		try {
			var resources = classLoader.getResources(RESOURCE);
			while (resources.hasMoreElements()) {
				URL resource = resources.nextElement();
				if (!visitedResources.add(resource.toExternalForm())) {
					continue;
				}
				try (var stream = resource.openStream()) {
					String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
					if (!visitedResourceContents.add(content)) {
						continue;
					}
					JsonElement root = JsonParser.parseString(content);
					JsonObject rootObject = object(root, "root");
					requireKeys(rootObject, Set.of("providers"), "root");
					JsonArray providers = rootObject.getAsJsonArray("providers");
					if (providers == null) {
						throw new IllegalArgumentException("Missing providers array");
					}
					for (int index = 0; index < providers.size(); index++) {
						String source = resource + "#providers[" + index + "]";
						try {
							parsed.add(new Parsed(source, parse(object(providers.get(index), source))));
						} catch (RuntimeException failure) {
							diagnostics.add(new WorldgenProviderDiagnostic(
								source, Optional.empty(), Optional.empty(),
								CapabilityFailure.of("provider_metadata_invalid", failure)
							));
						}
					}
				} catch (IOException | RuntimeException failure) {
					diagnostics.add(new WorldgenProviderDiagnostic(
						resource.toString(), Optional.empty(), Optional.empty(),
						CapabilityFailure.of("provider_metadata_resource_invalid", failure)
					));
				}
			}
		} catch (IOException | RuntimeException failure) {
			diagnostics.add(new WorldgenProviderDiagnostic(
				RESOURCE, Optional.empty(), Optional.empty(),
				CapabilityFailure.of("provider_metadata_discovery_failed", failure)
			));
		}

		Map<ResourceLocation, List<Parsed>> byId = new HashMap<>();
		parsed.forEach(value -> byId.computeIfAbsent(value.metadata().id(), ignored -> new ArrayList<>()).add(value));
		List<WorldgenProviderCatalog.Registration> registrations = new ArrayList<>();
		List<WorldgenProviderCatalog.FailedProvider> rejected = new ArrayList<>();
		for (Map.Entry<ResourceLocation, List<Parsed>> entry : byId.entrySet()) {
			if (entry.getValue().size() == 1) {
				registrations.add(new WorldgenProviderCatalog.Registration(
					entry.getValue().getFirst().metadata()
				));
				continue;
			}
			String sources = entry.getValue().stream().map(Parsed::source).sorted().toList().toString();
			diagnostics.add(new WorldgenProviderDiagnostic(
				sources, Optional.of(entry.getKey()), Optional.empty(),
				CapabilityFailure.unavailable(
					"provider_metadata_duplicate_id",
					"Provider ID " + entry.getKey() + " is declared by multiple metadata resources"
				)
			));
			for (Parsed duplicate : entry.getValue()) {
				rejected.add(new WorldgenProviderCatalog.FailedProvider(
					duplicate.metadata(),
					CapabilityFailure.unavailable(
						"provider_metadata_duplicate_id",
						"Provider ID " + entry.getKey() + " is declared by multiple metadata resources"
					)
				));
			}
		}
		return new WorldgenProviderCatalog(classLoader, environment, registrations, diagnostics, rejected);
	}

	private static WorldgenProviderMetadata parse(JsonObject value) {
		Set<String> known = Set.of(
			"id", "protocol_version", "adapter_version", "implementation", "owners",
			"contributions", "query_modes", "ordering", "mechanisms", "preview_factory", "pre_server_finalizer",
			"contribution_revision"
		);
		for (String key : value.keySet()) {
			if (!known.contains(key)) {
				throw new IllegalArgumentException("Unknown provider metadata key: " + key);
			}
		}
		ResourceLocation id = ResourceLocation.parse(string(value, "id"));
		int protocol = integer(value, "protocol_version");
		int adapter = integer(value, "adapter_version");
		String implementation = string(value, "implementation");

		Set<WorldgenOwnerType> owners = new HashSet<>();
		for (JsonElement owner : array(value, "owners")) {
			owners.add(WorldgenOwnerType.valueOf(string(owner, "owners entry").toUpperCase(Locale.ROOT)));
		}
		EnumMap<WorldgenFacet, WorldgenContributionKind> contributions =
			new EnumMap<>(WorldgenFacet.class);
		JsonObject contributionObject = object(value.get("contributions"), "contributions");
		for (Map.Entry<String, JsonElement> contribution : contributionObject.entrySet()) {
			WorldgenFacet facet = WorldgenFacet.valueOf(contribution.getKey().toUpperCase(Locale.ROOT));
			contributions.put(facet, WorldgenContributionKind.parse(
				string(contribution.getValue(), "contribution " + contribution.getKey())
			));
		}
		EnumMap<WorldgenFacet, WorldgenQueryMode> queryModes = new EnumMap<>(WorldgenFacet.class);
		JsonObject queryModeObject = object(value.get("query_modes"), "query_modes");
		for (Map.Entry<String, JsonElement> queryMode : queryModeObject.entrySet()) {
			WorldgenFacet facet = WorldgenFacet.valueOf(queryMode.getKey().toUpperCase(Locale.ROOT));
			queryModes.put(facet, WorldgenQueryMode.valueOf(
				string(queryMode.getValue(), "query mode " + queryMode.getKey()).toUpperCase(Locale.ROOT)
			));
		}

		List<ProviderOrder> ordering = new ArrayList<>();
		for (JsonElement element : optionalArray(value, "ordering")) {
			JsonObject order = object(element, "ordering entry");
			requireKeys(order, Set.of("before", "after", "required"), "ordering entry");
			ordering.add(new ProviderOrder(
				ResourceLocation.parse(string(order, "before")),
				ResourceLocation.parse(string(order, "after")),
				optionalBoolean(order, "required", true)
			));
		}
		List<WorldgenMechanismRequirement> mechanisms = new ArrayList<>();
		for (JsonElement element : optionalArray(value, "mechanisms")) {
			JsonObject mechanism = object(element, "mechanism entry");
			requireKeys(
				mechanism, Set.of("mod_id", "supported_versions", "optional"), "mechanism entry"
			);
			Set<String> versions = new HashSet<>();
			for (JsonElement version : optionalArray(mechanism, "supported_versions")) {
				versions.add(string(version, "supported version"));
			}
			mechanisms.add(new WorldgenMechanismRequirement(
				string(mechanism, "mod_id"), versions,
				optionalBoolean(mechanism, "optional", false)
			));
		}
		return new WorldgenProviderMetadata(
			id, protocol, adapter, implementation, contributions, queryModes, owners, ordering, mechanisms,
			optionalBoolean(value, "preview_factory", false),
			optionalBoolean(value, "pre_server_finalizer", false),
			optionalBoolean(value, "contribution_revision", false)
		);
	}

	private static JsonObject object(JsonElement value, String context) {
		if (value == null || !value.isJsonObject()) {
			throw new IllegalArgumentException(context + " must be an object");
		}
		return value.getAsJsonObject();
	}

	private static JsonArray array(JsonObject value, String key) {
		JsonElement element = value.get(key);
		if (element == null || !element.isJsonArray()) {
			throw new IllegalArgumentException(key + " must be an array");
		}
		return element.getAsJsonArray();
	}

	private static JsonArray optionalArray(JsonObject value, String key) {
		JsonElement element = value.get(key);
		if (element == null) {
			return new JsonArray();
		}
		if (!element.isJsonArray()) {
			throw new IllegalArgumentException(key + " must be an array");
		}
		return element.getAsJsonArray();
	}

	private static String string(JsonObject value, String key) {
		JsonElement element = value.get(key);
		if (element == null) {
			throw new IllegalArgumentException(key + " must be a string");
		}
		return string(element, key);
	}

	private static String string(JsonElement element, String context) {
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(context + " must be a string");
		}
		return element.getAsString();
	}

	private static int integer(JsonObject value, String key) {
		JsonElement element = value.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(key + " must be an integer");
		}
		try {
			return element.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException failure) {
			throw new IllegalArgumentException(key + " must be an exact 32-bit integer", failure);
		}
	}

	private static void requireKeys(JsonObject value, Set<String> known, String context) {
		for (String key : value.keySet()) {
			if (!known.contains(key)) {
				throw new IllegalArgumentException("Unknown " + context + " key: " + key);
			}
		}
	}

	private static boolean optionalBoolean(JsonObject value, String key, boolean fallback) {
		JsonElement element = value.get(key);
		if (element == null) {
			return fallback;
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(key + " must be a boolean");
		}
		return element.getAsBoolean();
	}

	private record Parsed(String source, WorldgenProviderMetadata metadata) {
	}
}
