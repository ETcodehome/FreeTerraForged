package raccoonman.reterraforged.world.worldgen.biolith;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.terraformersmc.biolith.api.biome.sub.Criterion;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenPlans;

final class BiolithCriterionBridge {
	private static final java.util.Set<String> NORMALIZED_TYPES = java.util.Set.of(
		"not", "all_of", "any_of", "value", "deviation", "ratio", "alternate", "original", "neighbor"
	);

	private BiolithCriterionBridge() {
	}

	static Snapshot capture(Criterion criterion) {
		Objects.requireNonNull(criterion, "criterion");
		ResourceLocation type = criterion.getType().getId();
		if (!type.getNamespace().equals("biolith") || !NORMALIZED_TYPES.contains(type.getPath())) {
			return new Snapshot(
				type, Optional.empty(), Optional.of(
					type + ": custom criterion codecs have no immutable FTF evaluation contract"
				)
			);
		}
		try {
			JsonElement encoded = Criterion.CODEC.encodeStart(JsonOps.INSTANCE, criterion)
				.getOrThrow(message -> new IllegalArgumentException("criterion codec encode: " + message));
			return new Snapshot(type, Optional.of(captureNode(object(encoded))), Optional.empty());
		} catch (UnsupportedCriterion exception) {
			return new Snapshot(type, Optional.empty(), Optional.of(exception.getMessage()));
		} catch (RuntimeException exception) {
			return new Snapshot(
				type,
				Optional.empty(),
				Optional.of("Could not snapshot Biolith criterion " + type + ": " + exception.getMessage())
			);
		}
	}

	private static Node captureNode(JsonObject value) {
		ResourceLocation type = ResourceLocation.parse(string(value, "type"));
		return switch (type.getNamespace().equals("biolith") ? type.getPath() : "") {
			case "not" -> new Not(captureNode(object(required(value, "criterion"))));
			case "all_of" -> new AllOf(required(value, "criteria").getAsJsonArray().asList().stream()
				.map(BiolithCriterionBridge::object).map(BiolithCriterionBridge::captureNode).toList());
			case "any_of" -> new AnyOf(required(value, "criteria").getAsJsonArray().asList().stream()
				.map(BiolithCriterionBridge::object).map(BiolithCriterionBridge::captureNode).toList());
			case "value" -> new Numeric(
				NumericKind.VALUE, ParameterTarget.parse(string(value, "parameter")),
				floating(value, "min", Float.NEGATIVE_INFINITY),
				floating(value, "max", Float.POSITIVE_INFINITY)
			);
			case "deviation" -> deviation(value);
			case "ratio" -> new Ratio(
				RatioTarget.parse(string(value, "target")),
				floating(value, "min", Float.NEGATIVE_INFINITY),
				floating(value, "max", Float.POSITIVE_INFINITY)
			);
			case "alternate" -> new Alternate(
				target(string(value, "biome")), biomeKey(string(value, "alternate"))
			);
			case "original" -> new Original(target(string(value, "biome")));
			case "neighbor" -> new Neighbor(target(string(value, "biome")));
			default -> throw new UnsupportedCriterion(
				type + ": custom criterion codecs have no immutable FTF evaluation algebra"
			);
		};
	}

	private static Numeric deviation(JsonObject value) {
		ParameterTarget target = ParameterTarget.parse(string(value, "parameter"));
			if (target == ParameterTarget.DEPTH_OCEAN) {
				throw new UnsupportedCriterion(
					"biolith:deviation: Biolith itself does not define deviation semantics for depth_ocean"
				);
			}
		return new Numeric(
			NumericKind.DEVIATION, target,
			floating(value, "min", Float.NEGATIVE_INFINITY),
			floating(value, "max", Float.POSITIVE_INFINITY)
		);
	}

	private static JsonObject object(JsonElement value) {
		if (value == null || !value.isJsonObject()) {
			throw new IllegalArgumentException("Criterion codec node must be an object");
		}
		return value.getAsJsonObject();
	}

	private static JsonElement required(JsonObject value, String key) {
		JsonElement found = value.get(key);
		if (found == null) {
			throw new IllegalArgumentException("Criterion codec node is missing " + key);
		}
		return found;
	}

	private static String string(JsonObject value, String key) {
		JsonElement found = required(value, key);
		if (!found.isJsonPrimitive() || !found.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("Criterion codec field " + key + " must be a string");
		}
		return found.getAsString();
	}

	private static float floating(JsonObject value, String key, float fallback) {
		JsonElement found = value.get(key);
		return found == null ? fallback : found.getAsFloat();
	}

	private static BiomeTarget target(String encoded) {
		return encoded.startsWith("#")
			? new BiomeTarget(Optional.empty(), Optional.of(TagKey.create(
				Registries.BIOME, ResourceLocation.parse(encoded.substring(1))
			)))
			: new BiomeTarget(Optional.of(biomeKey(encoded)), Optional.empty());
	}

	private static ResourceKey<Biome> biomeKey(String encoded) {
		return ResourceKey.create(Registries.BIOME, ResourceLocation.parse(encoded));
	}

	static boolean matches(Node node, Evaluation evaluation) {
		return switch (node) {
			case Not value -> !matches(value.criterion(), evaluation);
			case AllOf value -> matchesAll(value.criteria(), evaluation);
			case AnyOf value -> matchesAny(value.criteria(), evaluation);
			case Numeric value -> inRange(
				numericValue(value.kind(), value.target(), evaluation), value.min(), value.max()
			);
			case Ratio value -> inRange(ratioValue(value.target(), evaluation), value.min(), value.max());
			case Original value -> value.target().matches(evaluation.candidates().ultimate().biome());
			case Neighbor value -> evaluation.candidates().penultimate()
				.map(WorldgenPlans.CandidateMatch::biome)
				.map(value.target()::matches)
				.orElse(false);
			case Alternate value -> value.target().matches(evaluation.alternate().apply(value.alternate()));
		};
	}

	private static boolean matchesAll(List<Node> criteria, Evaluation evaluation) {
		for (Node criterion : criteria) {
			if (!matches(criterion, evaluation)) {
				return false;
			}
		}
		return true;
	}

	private static boolean matchesAny(List<Node> criteria, Evaluation evaluation) {
		for (Node criterion : criteria) {
			if (matches(criterion, evaluation)) {
				return true;
			}
		}
		return false;
	}

	static Node bindTags(Node node, Registry<Biome> biomes) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(biomes, "biomes");
		return switch (node) {
			case Not value -> new Not(bindTags(value.criterion(), biomes));
			case AllOf value -> new AllOf(value.criteria().stream()
				.map(entry -> bindTags(entry, biomes)).toList());
			case AnyOf value -> new AnyOf(value.criteria().stream()
				.map(entry -> bindTags(entry, biomes)).toList());
			case Original value -> new Original(value.target().bind(biomes));
			case Neighbor value -> new Neighbor(value.target().bind(biomes));
			case Alternate value -> new Alternate(value.target().bind(biomes), value.alternate());
			case Numeric value -> value;
			case Ratio value -> value;
		};
	}

	private static boolean inRange(float value, float min, float max) {
		return value >= min && value <= max;
	}

	private static float numericValue(
		NumericKind kind,
		ParameterTarget target,
		Evaluation evaluation
	) {
		Climate.TargetPoint point = evaluation.target();
		long value = switch (target) {
			case TEMPERATURE -> point.temperature();
			case HUMIDITY -> point.humidity();
			case CONTINENTALNESS -> point.continentalness();
			case EROSION -> point.erosion();
			case DEPTH -> point.depth();
			case WEIRDNESS -> point.weirdness();
			case PEAKS_VALLEYS -> peaksAndValleys(point.weirdness());
			case DEPTH_OCEAN -> depthWithOceanSurface(point.depth(), evaluation);
		};
		if (kind == NumericKind.DEVIATION) {
			value -= parameterCenter(parameter(target, evaluation.candidates().ultimate().point()));
		}
		return Climate.unquantizeCoord(value);
	}

	private static long depthWithOceanSurface(long depth, Evaluation evaluation) {
		double bottom = evaluation.minY();
		double top = evaluation.topY();
		long atY = (long) Mth.clampedMap(
			QuartPos.toBlock(evaluation.quartY()), bottom, top, 15_000.0D, -15_000.0D
		);
		long atSea = (long) Mth.clampedMap(
			evaluation.seaLevel(), bottom, top, 15_000.0D, -15_000.0D
		);
		return Math.max(depth, atY - atSea);
	}

	private static float ratioValue(RatioTarget target, Evaluation evaluation) {
		WorldgenPlans.CandidateMatch ultimate = evaluation.candidates().ultimate();
		ReplacementContext replacement = evaluation.replacement();
		if (target == RatioTarget.CENTER) {
			Climate.ParameterPoint point = ultimate.point();
			long squared = Mth.square(evaluation.target().temperature() - parameterCenter(point.temperature()))
				+ Mth.square(evaluation.target().humidity() - parameterCenter(point.humidity()))
				+ Mth.square(evaluation.target().continentalness() - parameterCenter(point.continentalness()))
				+ Mth.square(evaluation.target().erosion() - parameterCenter(point.erosion()))
				+ Mth.square(evaluation.target().depth() - parameterCenter(point.depth()))
				+ Mth.square(evaluation.target().weirdness() - parameterCenter(point.weirdness()))
				+ Mth.square(point.offset());
			float comparable = Mth.sqrt((float) squared) / 10_000.0F;
			if (replacement != null) {
				if (replacement.minInclusive() <= 0.0F) {
					if (replacement.maxInclusive() < 1.0F) {
						comparable = Math.max(replacement.sample(), comparable);
					}
				} else if (replacement.maxInclusive() >= 1.0F) {
					comparable = Math.max(1.0F - replacement.sample(), comparable);
				} else {
					comparable = Math.max(Math.abs(
						replacement.sample()
							- (replacement.minInclusive() + replacement.maxInclusive()) / 2.0F
					), comparable);
				}
			}
			return comparable;
		}
		Optional<WorldgenPlans.CandidateMatch> penultimate = evaluation.candidates().penultimate();
		float comparable;
		if (penultimate.isEmpty()) {
			comparable = 1.0F;
		} else {
			long second = penultimate.orElseThrow().distance();
			comparable = second == 0L
				? 0.0F
				: (float) (second - ultimate.distance()) / (float) second;
		}
		if (replacement != null) {
			if (replacement.minInclusive() <= 0.0F) {
				if (replacement.maxInclusive() < 1.0F) {
					comparable = Math.min(
						replacement.maxInclusive() - replacement.sample(), comparable
					);
				}
			} else if (replacement.maxInclusive() >= 1.0F) {
				comparable = Math.min(
					replacement.sample() - replacement.minInclusive(), comparable
				);
			} else {
				comparable = Math.min(Math.min(
					replacement.sample() - replacement.minInclusive(),
					replacement.maxInclusive() - replacement.sample()
				), comparable);
			}
		}
		return comparable;
	}

	private static Climate.Parameter parameter(
		ParameterTarget target,
		Climate.ParameterPoint point
	) {
		return switch (target) {
			case TEMPERATURE -> point.temperature();
			case HUMIDITY -> point.humidity();
			case CONTINENTALNESS -> point.continentalness();
			case EROSION -> point.erosion();
			case DEPTH -> point.depth();
			case WEIRDNESS -> point.weirdness();
			case PEAKS_VALLEYS -> peaksAndValleys(point.weirdness());
			case DEPTH_OCEAN -> throw new IllegalStateException("depth_ocean has no deviation parameter");
		};
	}

	private static Climate.Parameter peaksAndValleys(Climate.Parameter weirdness) {
		long first = peaksAndValleys(weirdness.min());
		long second = peaksAndValleys(weirdness.max());
		long min = weirdness.min() < 0L && weirdness.max() > 0L
			? -10_000L
			: Math.min(first, second);
		long lowerInflection = -20_000L / 3L;
		long upperInflection = 20_000L / 3L;
		long max = (weirdness.min() < lowerInflection && weirdness.max() > lowerInflection)
			|| (weirdness.min() < upperInflection && weirdness.max() > upperInflection)
			? 10_000L
			: Math.max(first, second);
		return new Climate.Parameter(min, max);
	}

	private static long peaksAndValleys(long weirdness) {
		return 10_000L - Math.abs(Math.abs(weirdness * 3L) - 20_000L);
	}

	private static long parameterCenter(Climate.Parameter parameter) {
		return (parameter.min() + parameter.max()) / 2L;
	}

	record Snapshot(
		ResourceLocation type,
		Optional<Node> node,
		Optional<String> failure
	) {
		Snapshot {
			type = Objects.requireNonNull(type, "type");
			node = Objects.requireNonNull(node, "node");
			failure = Objects.requireNonNull(failure, "failure");
			if (node.isPresent() == failure.isPresent()) {
				throw new IllegalArgumentException("A criterion snapshot must contain either a node or a failure");
			}
		}

		Snapshot bindTags(Registry<Biome> biomes) {
			return this.node.isEmpty()
				? this
				: new Snapshot(this.type, Optional.of(BiolithCriterionBridge.bindTags(
					this.node.orElseThrow(), biomes
				)), Optional.empty());
		}
	}

	static final class Evaluation {
		private final WorldgenPlans.ProviderResult selection;
		private WorldgenPlans.CandidateFit candidates;
		private final Climate.TargetPoint target;
		private final int quartY;
		private final int minY;
		private final int topY;
		private final int seaLevel;
		private final Function<ResourceKey<Biome>, Holder<Biome>> alternate;
		private final ReplacementContext replacement;

		Evaluation(
			WorldgenPlans.ProviderResult selection,
			Climate.TargetPoint target,
			int quartY,
			int minY,
			int topY,
			int seaLevel,
			Function<ResourceKey<Biome>, Holder<Biome>> alternate,
			ReplacementContext replacement
		) {
			this.selection = Objects.requireNonNull(selection, "selection");
			this.target = Objects.requireNonNull(target, "target");
			this.quartY = quartY;
			this.minY = minY;
			this.topY = topY;
			this.seaLevel = seaLevel;
			this.alternate = Objects.requireNonNull(alternate, "alternate");
			this.replacement = replacement;
			this.validateHeight();
		}

		Evaluation(
			WorldgenPlans.CandidateFit candidates,
			Climate.TargetPoint target,
			int quartY,
			int minY,
			int topY,
			int seaLevel,
			Function<ResourceKey<Biome>, Holder<Biome>> alternate,
			ReplacementContext replacement
		) {
			this.selection = null;
			this.candidates = Objects.requireNonNull(candidates, "candidates");
			this.target = Objects.requireNonNull(target, "target");
			this.quartY = quartY;
			this.minY = minY;
			this.topY = topY;
			this.seaLevel = seaLevel;
			this.alternate = Objects.requireNonNull(alternate, "alternate");
			this.replacement = replacement;
			this.validateHeight();
		}

		WorldgenPlans.CandidateFit candidates() {
			WorldgenPlans.CandidateFit current = this.candidates;
			if (current == null) {
				current = this.selection.candidateFit();
				this.candidates = current;
			}
			return current;
		}

		Climate.TargetPoint target() { return this.target; }
		int quartY() { return this.quartY; }
		int minY() { return this.minY; }
		int topY() { return this.topY; }
		int seaLevel() { return this.seaLevel; }
		Function<ResourceKey<Biome>, Holder<Biome>> alternate() { return this.alternate; }
		ReplacementContext replacement() { return this.replacement; }

		private void validateHeight() {
			if (this.topY <= this.minY) {
				throw new IllegalArgumentException("World height bounds are inverted");
			}
		}
	}

	record ReplacementContext(float minInclusive, float maxInclusive, float sample) {
		ReplacementContext {
			if (!Float.isFinite(minInclusive) || !Float.isFinite(maxInclusive)
				|| !Float.isFinite(sample) || minInclusive < 0.0F
				|| minInclusive > sample || sample > maxInclusive || maxInclusive > 1.0F) {
				throw new IllegalArgumentException("Invalid Biolith replacement context");
			}
		}
	}

	sealed interface Node permits Not, AllOf, AnyOf, Numeric, Ratio, Original, Neighbor, Alternate {
	}

	record Not(Node criterion) implements Node {
		Not {
			criterion = Objects.requireNonNull(criterion, "criterion");
		}
	}

	record AllOf(List<Node> criteria) implements Node {
		AllOf {
			criteria = List.copyOf(criteria);
		}
	}

	record AnyOf(List<Node> criteria) implements Node {
		AnyOf {
			criteria = List.copyOf(criteria);
		}
	}

	record Numeric(NumericKind kind, ParameterTarget target, float min, float max) implements Node {
		Numeric {
			kind = Objects.requireNonNull(kind, "kind");
			target = Objects.requireNonNull(target, "target");
			if (Float.isNaN(min) || Float.isNaN(max) || min > max) {
				throw new IllegalArgumentException("Invalid numeric criterion range");
			}
		}
	}

	record Ratio(RatioTarget target, float min, float max) implements Node {
		Ratio {
			target = Objects.requireNonNull(target, "target");
			if (Float.isNaN(min) || Float.isNaN(max) || min > max) {
				throw new IllegalArgumentException("Invalid ratio criterion range");
			}
		}
	}

	record Original(BiomeTarget target) implements Node {
	}

	record Neighbor(BiomeTarget target) implements Node {
	}

	record Alternate(BiomeTarget target, ResourceKey<Biome> alternate) implements Node {
	}

	record BiomeTarget(
		Optional<ResourceKey<Biome>> biome,
		Optional<TagKey<Biome>> tag,
		Optional<java.util.Set<ResourceKey<Biome>>> resolvedTag
	) {
		BiomeTarget(Optional<ResourceKey<Biome>> biome, Optional<TagKey<Biome>> tag) {
			this(biome, tag, Optional.empty());
		}

		BiomeTarget {
			biome = Objects.requireNonNull(biome, "biome");
			tag = Objects.requireNonNull(tag, "tag");
			resolvedTag = Objects.requireNonNull(resolvedTag, "resolvedTag")
				.map(java.util.Set::copyOf);
			int alternatives = (biome.isPresent() ? 1 : 0)
				+ (tag.isPresent() ? 1 : 0)
				+ (resolvedTag.isPresent() ? 1 : 0);
			if (alternatives != 1) {
				throw new IllegalArgumentException("A biome target must contain exactly one key or tag");
			}
		}

		BiomeTarget bind(Registry<Biome> biomes) {
			if (this.tag.isEmpty()) {
				return this;
			}
			java.util.Set<ResourceKey<Biome>> keys = biomes.getTag(this.tag.orElseThrow())
				.stream()
					.flatMap(set -> set.stream())
					.map(holder -> holder.unwrapKey().orElseThrow(
						() -> new IllegalStateException("Biome tag contains a direct holder")
					))
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
			return new BiomeTarget(Optional.empty(), Optional.empty(), Optional.of(keys));
		}

		boolean matches(Holder<Biome> value) {
			if (this.biome.isPresent()) {
				return value.is(this.biome.orElseThrow());
			}
			if (this.resolvedTag.isPresent()) {
				return value.unwrapKey().map(this.resolvedTag.orElseThrow()::contains).orElse(false);
			}
			throw new IllegalStateException("Biome tag target was not bound into owner-scoped plan data");
		}
	}

	enum NumericKind {
		VALUE,
		DEVIATION
	}

	enum ParameterTarget {
		TEMPERATURE,
		HUMIDITY,
		CONTINENTALNESS,
		EROSION,
		DEPTH,
		WEIRDNESS,
		PEAKS_VALLEYS,
		DEPTH_OCEAN;

		static ParameterTarget parse(String value) {
			return valueOf(value.toUpperCase(java.util.Locale.ROOT));
		}
	}

	enum RatioTarget {
		CENTER,
		EDGE;

		static RatioTarget parse(String value) {
			return valueOf(value.toUpperCase(java.util.Locale.ROOT));
		}
	}

	private static final class UnsupportedCriterion extends RuntimeException {
		private UnsupportedCriterion(String message) {
			super(message);
		}
	}
}
