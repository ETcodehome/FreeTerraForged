package raccoonman.reterraforged.world.worldgen.feature.ore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalFrame;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalTransform;
import raccoonman.reterraforged.world.worldgen.feature.ore.OreContractClassifier.Status;

public final class DynamicOrePlanner {
	private final OreContractClassifier classifier;

	public DynamicOrePlanner() {
		this(new OreContractClassifier());
	}

	private DynamicOrePlanner(OreContractClassifier classifier) {
		this.classifier = classifier;
	}

	public DynamicOrePlan build(
		RegistryAccess registries,
		ChunkGenerator generator,
		Collection<Holder<Biome>> possibleBiomes,
		VerticalFrame verticalFrame
	) {
		Registry<PlacedFeature> placedFeatures = registries.registryOrThrow(Registries.PLACED_FEATURE);
		List<BiomeInput> biomes = possibleBiomes.stream().map(biome -> new BiomeInput(
			generator.getBiomeGenerationSettings(biome).features().stream()
				.map(step -> step.stream()
					.map(holder -> new FeatureInput(featureId(holder, holder.value(), placedFeatures), holder.value()))
					.toList())
				.toList()
		)).toList();
		return new DynamicOrePlanner(new OreContractClassifier(registries)).build(biomes, verticalFrame);
	}

	DynamicOrePlan build(List<BiomeInput> biomes, VerticalFrame frame) {
		Map<String, PlacedFeature> active = new TreeMap<>();
		Set<String> conflicts = new TreeSet<>();
		for (BiomeInput biome : biomes) {
			for (List<FeatureInput> step : biome.steps()) {
				for (FeatureInput input : step) {
					PlacedFeature previous = active.putIfAbsent(input.id(), input.feature());
					if (previous != null && previous != input.feature()) {
						conflicts.add(input.id());
					}
				}
			}
		}

		Map<String, VerticalTransform> transforms = new TreeMap<>();
		Map<String, Integer> skipped = new TreeMap<>();
		List<String> failures = new ArrayList<>();
		int standardOres = 0;
		int delegated = 0;
		for (Map.Entry<String, PlacedFeature> entry : active.entrySet()) {
			String featureId = entry.getKey();
			if (conflicts.contains(featureId)) {
				increment(skipped, "CONFLICTING_FEATURES_FOR_ID");
				continue;
			}

			OreContractClassifier.Result result = this.classifier.classify(entry.getValue());
			if (result.status() == Status.NOT_ORE) {
				continue;
			}
			standardOres++;
			if (result.status() != Status.SUPPORTED) {
				increment(skipped, result.reasonCode());
				result.failure().ifPresent(failure -> failures.add(featureId + " | " + failure));
				continue;
			}
			if ("<direct>".equals(featureId)) {
				increment(skipped, "DIRECT_FEATURE_HAS_NO_STABLE_IDENTITY");
				continue;
			}
			if (DynamicOreVerticalTransform.isReferenceFrame(frame)) {
				delegated++;
				continue;
			}

			OreContractClassifier.Contract contract = result.contract().orElseThrow();
			DynamicOreVerticalTransform.Derivation derivation = DynamicOreVerticalTransform.derive(
				contract.height(),
				frame,
				contract.fanoutStage(),
				contract.fanoutModifierIndex(),
				contract.heightModifierIndex()
			);
			if (derivation.transform().isPresent()) {
				transforms.put(featureId, derivation.transform().orElseThrow());
			} else if ("FEATURE_VERTICAL_MAPPING_IS_IDENTITY".equals(derivation.reasonCode())) {
				delegated++;
			} else {
				increment(skipped, derivation.reasonCode());
			}
		}

		return new DynamicOrePlan(
			Optional.of(frame),
			transforms,
			active.size(),
			standardOres,
			delegated,
			skipped,
			failures
		);
	}

	private static void increment(Map<String, Integer> counts, String reason) {
		counts.merge(reason, 1, Integer::sum);
	}

	private static String featureId(Holder<PlacedFeature> holder, PlacedFeature feature, Registry<PlacedFeature> registry) {
		return holder.unwrapKey()
			.map(key -> key.location().toString())
			.orElseGet(() -> {
				ResourceLocation id = registry.getKey(feature);
				return id == null ? "<direct>" : id.toString();
			});
	}

	record FeatureInput(String id, PlacedFeature feature) {
	}

	record BiomeInput(List<List<FeatureInput>> steps) {
		BiomeInput {
			steps = steps.stream().map(List::copyOf).toList();
		}
	}
}
