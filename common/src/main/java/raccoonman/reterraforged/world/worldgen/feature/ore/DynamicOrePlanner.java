package raccoonman.reterraforged.world.worldgen.feature.ore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Membership;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Occurrence;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Action;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.Contract;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.InspectionStatus;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.FanoutStage;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalFrame;
import raccoonman.reterraforged.world.worldgen.feature.ore.DynamicOrePlan.VerticalTransform;

/** Builds a normalized value-only report from the final Overworld graph. */
public final class DynamicOrePlanner {
	private static final String SCHEMA_FINGERPRINT = fingerprint(List.of(
		"ore-contract-classifier-v2",
		"feature:ore|scattered_ore",
		"height:one-range+uniform|trapezoid",
		"modifiers:count|rarity|in_square|placement_filter",
		"vertical-map:inclusive-cell-bands[-64..-1|0..8|9..62|63..319]",
		"density:probability-weighted-mapped-cell-width-v1",
		"action:pre-spatial-fanout+height-sampling+stochastic-rounding-v2"
	));

	private final OreContractClassifier classifier;

	public DynamicOrePlanner() {
		this(new OreContractClassifier());
	}

	DynamicOrePlanner(OreContractClassifier classifier) {
		this.classifier = classifier;
	}

	public static String schemaFingerprint() {
		return SCHEMA_FINGERPRINT;
	}

	public DynamicOrePlan build(
		RegistryAccess registries,
		ChunkGenerator generator,
		Collection<Holder<Biome>> possibleBiomes
	) {
		return this.build(registries, generator, possibleBiomes, Optional.empty());
	}

	public DynamicOrePlan build(
		RegistryAccess registries,
		ChunkGenerator generator,
		Collection<Holder<Biome>> possibleBiomes,
		VerticalFrame verticalFrame
	) {
		return this.build(registries, generator, possibleBiomes, Optional.of(verticalFrame));
	}

	private DynamicOrePlan build(
		RegistryAccess registries,
		ChunkGenerator generator,
		Collection<Holder<Biome>> possibleBiomes,
		Optional<VerticalFrame> verticalFrame
	) {
		Registry<Biome> biomes = registries.registryOrThrow(Registries.BIOME);
		Registry<PlacedFeature> placedFeatures = registries.registryOrThrow(Registries.PLACED_FEATURE);
		List<BiomeInput> biomeInputs = possibleBiomes.stream().map(biome -> {
			BiomeGenerationSettings settings = generator.getBiomeGenerationSettings(biome);
			List<List<FeatureInput>> steps = settings.features().stream()
				.map(step -> step.stream()
					.map(holder -> new FeatureInput(featureId(holder, holder.value(), placedFeatures), holder.value()))
					.toList())
				.toList();
			return new BiomeInput(biomeId(biome, biomes), steps);
		}).toList();
		List<FeatureInput> registeredInputs = placedFeatures.entrySet().stream()
			.map(entry -> new FeatureInput(entry.getKey().location().toString(), entry.getValue()))
			.toList();
		return new DynamicOrePlanner(new OreContractClassifier(registries)).build(biomeInputs, registeredInputs, verticalFrame);
	}

	DynamicOrePlan build(List<BiomeInput> biomeInputs, List<FeatureInput> registeredInputs) {
		return this.build(biomeInputs, registeredInputs, Optional.empty());
	}

	DynamicOrePlan build(
		List<BiomeInput> biomeInputs,
		List<FeatureInput> registeredInputs,
		Optional<VerticalFrame> verticalFrame
	) {
		List<BiomeInput> orderedBiomes = biomeInputs.stream()
			.sorted(Comparator.comparing(BiomeInput::biomeId))
			.toList();
		Set<PlacedFeature> activeFeatures = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		List<Occurrence> occurrences = new ArrayList<>();

		for (BiomeInput biome : orderedBiomes) {
			String biomeId = biome.biomeId();
			List<List<FeatureInput>> steps = biome.steps();
			for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
				int order = 0;
				for (FeatureInput input : steps.get(stepIndex)) {
					PlacedFeature feature = input.feature();
					activeFeatures.add(feature);
					Membership membership = new Membership(biomeId, stepName(stepIndex), stepIndex, order++);
					occurrences.add(this.classifier.classify(input.id(), membership, feature));
				}
			}
		}

		registeredInputs.stream()
			.sorted(Comparator.comparing(FeatureInput::id))
			.filter(input -> !activeFeatures.contains(input.feature()))
			.forEach(input -> occurrences.add(this.classifier.inactive(input.id(), input.feature())));

		List<Occurrence> classified = List.copyOf(occurrences);
		AppliedTransforms applied = verticalFrame
			.map(frame -> applyTransforms(classified, frame))
			.orElseGet(() -> new AppliedTransforms(classified, Map.of()));
		return new DynamicOrePlan(
			SCHEMA_FINGERPRINT,
			fingerprint(classified),
			applied.occurrences(),
			verticalFrame,
			applied.transforms()
		);
	}

	private static AppliedTransforms applyTransforms(List<Occurrence> occurrences, VerticalFrame frame) {
		Map<String, List<Occurrence>> byFeature = occurrences.stream()
			.filter(DynamicOrePlanner::isTransformCandidate)
			.collect(Collectors.groupingBy(
				Occurrence::placedFeatureId,
				LinkedHashMap::new,
				Collectors.toList()
			));
		Map<String, VerticalTransform> transforms = new LinkedHashMap<>();
		Map<String, ActionDecision> decisions = new LinkedHashMap<>();
		for (Map.Entry<String, List<Occurrence>> entry : byFeature.entrySet()) {
			String featureId = entry.getKey();
			List<Occurrence> featureOccurrences = entry.getValue();
			if ("<direct>".equals(featureId)) {
				decisions.put(featureId, new ActionDecision(Action.PRESERVE_UNCHANGED, "DIRECT_FEATURE_HAS_NO_STABLE_IDENTITY"));
				continue;
			}
			Map<String, Occurrence> contracts = featureOccurrences.stream().collect(Collectors.toMap(
				Occurrence::contractFingerprint,
				Function.identity(),
				(first, ignored) -> first,
				LinkedHashMap::new
			));
			if (contracts.size() != 1) {
				decisions.put(featureId, new ActionDecision(Action.PRESERVE_UNCHANGED, "CONFLICTING_CONTRACTS_FOR_FEATURE_ID"));
				continue;
			}
			Occurrence representative = contracts.values().iterator().next();
			Optional<FanoutSelection> fanout = selectFanout(representative);
			if (fanout.isEmpty()) {
				decisions.put(featureId, new ActionDecision(Action.PRESERVE_UNCHANGED, "NO_SAFE_SPATIAL_FANOUT_BOUNDARY"));
				continue;
			}
			if (DynamicOreVerticalTransform.isReferenceFrame(frame)) {
				decisions.put(featureId, new ActionDecision(Action.DELEGATE_REFERENCE_IDENTITY, "REFERENCE_FRAME_DELEGATES_TO_VANILLA"));
				continue;
			}
			DynamicOreVerticalTransform.Derivation derivation = DynamicOreVerticalTransform.derive(
				featureId,
				representative.contractFingerprint(),
				representative.height().orElseThrow(),
				frame,
				fanout.orElseThrow().stage(),
				fanout.orElseThrow().modifierIndex(),
				fanout.orElseThrow().heightModifierIndex()
			);
			if (derivation.transform().isPresent()) {
				VerticalTransform transform = derivation.transform().orElseThrow();
				transforms.put(featureId, transform);
				decisions.put(featureId, new ActionDecision(Action.DYNAMIC_VERTICAL_DENSITY, derivation.reasonCode()));
			} else {
				decisions.put(featureId, new ActionDecision(Action.PRESERVE_UNCHANGED, derivation.reasonCode()));
			}
		}

		List<Occurrence> planned = occurrences.stream().map(occurrence -> {
			ActionDecision decision = decisions.get(occurrence.placedFeatureId());
			return decision == null ? occurrence : occurrence.withAction(decision.action(), decision.reasonCode());
		}).toList();
		return new AppliedTransforms(planned, Collections.unmodifiableMap(new LinkedHashMap<>(transforms)));
	}

	private static boolean isTransformCandidate(Occurrence occurrence) {
		return occurrence.membership().isPresent()
			&& occurrence.inspection().status() == InspectionStatus.CLASSIFIED
			&& (occurrence.contract() == Contract.SUPPORTED_STANDARD
				|| occurrence.contract() == Contract.STANDARD_WITH_CUSTOM_FILTER)
			&& occurrence.height().isPresent();
	}

	private static Optional<FanoutSelection> selectFanout(Occurrence occurrence) {
		List<String> types = occurrence.placementModifierTypes();
		int heightIndex = types.indexOf("minecraft:height_range");
		if (heightIndex < 0) {
			return Optional.empty();
		}
		int inSquareIndex = types.indexOf("minecraft:in_square");
		int firstSpatialIndex = inSquareIndex < 0 ? heightIndex : Math.min(heightIndex, inSquareIndex);
		for (int index = firstSpatialIndex - 1; index >= 0; index--) {
			String type = types.get(index);
			if ("minecraft:count".equals(type)) {
				return Optional.of(new FanoutSelection(FanoutStage.COUNT, index, heightIndex));
			}
			if ("minecraft:rarity_filter".equals(type)) {
				return Optional.of(new FanoutSelection(FanoutStage.RARITY, index, heightIndex));
			}
		}
		if (inSquareIndex >= 0 && inSquareIndex < heightIndex) {
			return Optional.of(new FanoutSelection(FanoutStage.IN_SQUARE, inSquareIndex, heightIndex));
		}
		return Optional.of(new FanoutSelection(FanoutStage.HEIGHT, heightIndex, heightIndex));
	}

	private static String biomeId(Holder<Biome> holder, Registry<Biome> registry) {
		return holder.unwrapKey()
			.map(ResourceKey::location)
			.orElseGet(() -> {
				ResourceLocation id = registry.getKey(holder.value());
				return id == null ? ResourceLocation.parse("reterraforged:direct_biome") : id;
			})
			.toString();
	}

	private static String featureId(Holder<PlacedFeature> holder, PlacedFeature feature, Registry<PlacedFeature> registry) {
		return holder.unwrapKey()
			.map(ResourceKey::location)
			.map(ResourceLocation::toString)
			.orElseGet(() -> {
				ResourceLocation id = registry.getKey(feature);
				return id == null ? "<direct>" : id.toString();
			});
	}

	private static String stepName(int index) {
		GenerationStep.Decoration[] steps = GenerationStep.Decoration.values();
		return index < steps.length ? steps[index].getName() : "unknown_step_" + index;
	}

	static String fingerprint(List<?> values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Object value : values) {
				digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
				digest.update((byte)0);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	record FeatureInput(String id, PlacedFeature feature) {
	}

	record BiomeInput(String biomeId, List<List<FeatureInput>> steps) {
		BiomeInput {
			steps = steps.stream().map(List::copyOf).toList();
		}
	}

	private record ActionDecision(Action action, String reasonCode) {
	}

	private record AppliedTransforms(List<Occurrence> occurrences, Map<String, VerticalTransform> transforms) {
	}

	private record FanoutSelection(FanoutStage stage, int modifierIndex, int heightModifierIndex) {
	}
}
