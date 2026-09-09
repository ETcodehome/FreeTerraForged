package raccoonman.reterraforged.world.worldgen.biolith;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.terraformersmc.biolith.api.biome.sub.Criterion;
import com.terraformersmc.biolith.impl.biome.BiomeCoordinator;
import com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import raccoonman.reterraforged.platform.ModLoaderUtil;

public final class BiolithPlacementBridge {
	public static final Set<String> SUPPORTED_VERSIONS = Set.of("3.0.11", "3.0.14");
	private static final Map<Dimension, Collector> COLLECTORS = new EnumMap<>(Dimension.class);

	static {
		for (Dimension dimension : Dimension.values()) {
			COLLECTORS.put(dimension, new Collector());
		}
	}

	private BiolithPlacementBridge() {
	}

	public static synchronized void addPlacement(
		DimensionBiomePlacement owner,
		ResourceKey<Biome> biome,
		Climate.ParameterPoint point,
		boolean fromData
	) {
		collector(owner).filter(value -> value.addPlacement(new Placement(biome, point, fromData)))
			.ifPresent(Collector::advanceRevision);
	}

	public static synchronized void addRemoval(
		DimensionBiomePlacement owner,
		ResourceKey<Biome> biome,
		boolean fromData
	) {
		collector(owner).filter(value -> value.addRemoval(new Removal(biome, fromData)))
			.ifPresent(Collector::advanceRevision);
	}

	public static synchronized void addReplacement(
		DimensionBiomePlacement owner,
		ResourceKey<Biome> target,
		ResourceKey<Biome> biome,
		double proportion,
		boolean fromData
	) {
		collector(owner).filter(value -> value.addReplacement(
			new Replacement(target, biome, Math.clamp(proportion, 0.0D, 1.0D), fromData)
		)).ifPresent(Collector::advanceRevision);
	}

	public static synchronized void addSubBiome(
		DimensionBiomePlacement owner,
		ResourceKey<Biome> target,
		ResourceKey<Biome> biome,
		Criterion criterion,
		boolean fromData
	) {
		collector(owner).filter(value -> value.addSubBiome(
			new SubBiome(target, biome, BiolithCriterionBridge.capture(criterion), fromData)
		)).ifPresent(Collector::advanceRevision);
	}

	public static synchronized void clearFromData(DimensionBiomePlacement owner) {
		collector(owner).filter(Collector::clearFromData)
			.ifPresent(Collector::advanceRevision);
	}

	public static synchronized Optional<Snapshot> snapshot(ResourceKey<LevelStem> dimension) {
		return snapshot(Dimension.fromStem(dimension).orElse(null));
	}

	private static Optional<Snapshot> snapshot(Dimension kind) {
		if (kind == null) {
			return Optional.empty();
		}
		Collector collector = COLLECTORS.get(kind);
		if (collector.empty()) {
			return Optional.empty();
		}
		String version = ModLoaderUtil.version("biolith").orElse("unknown");
		return Optional.of(collector.snapshot(kind, version));
	}

	private static Optional<Collector> collector(DimensionBiomePlacement owner) {
		return dimension(owner).map(COLLECTORS::get);
	}

	private static Optional<Dimension> dimension(DimensionBiomePlacement owner) {
		if (owner == BiomeCoordinator.OVERWORLD) {
			return Optional.of(Dimension.OVERWORLD);
		}
		if (owner == BiomeCoordinator.NETHER) {
			return Optional.of(Dimension.NETHER);
		}
		if (owner == BiomeCoordinator.END) {
			return Optional.of(Dimension.END);
		}
		return Optional.empty();
	}

	public static synchronized long revision(ResourceKey<LevelStem> dimension) {
		return Dimension.fromStem(dimension).map(COLLECTORS::get).map(Collector::revision).orElse(0L);
	}

	public enum Dimension {
		OVERWORLD(ResourceLocation.withDefaultNamespace("overworld")),
		NETHER(ResourceLocation.withDefaultNamespace("the_nether")),
		END(ResourceLocation.withDefaultNamespace("the_end"));

		private final ResourceLocation location;

		Dimension(ResourceLocation location) {
			this.location = location;
		}

		static Optional<Dimension> fromStem(ResourceKey<LevelStem> stem) {
			return java.util.Arrays.stream(values()).filter(value -> value.location.equals(stem.location())).findFirst();
		}

		public ResourceLocation location() {
			return this.location;
		}
	}

	public record Placement(ResourceKey<Biome> biome, Climate.ParameterPoint point, boolean fromData) {
	}

	public record Removal(ResourceKey<Biome> biome, boolean fromData) {
	}

	public record Replacement(
		ResourceKey<Biome> target,
		ResourceKey<Biome> biome,
		double proportion,
		boolean fromData
	) {
	}

	public record SubBiome(
		ResourceKey<Biome> target,
		ResourceKey<Biome> biome,
		BiolithCriterionBridge.Snapshot criterion,
		boolean fromData
	) {
		public ResourceLocation criterionType() {
			return this.criterion.type();
		}

		public boolean criterionNormalized() {
			return this.criterion.node().isPresent();
		}

		public Optional<String> criterionFailure() {
			return this.criterion.failure();
		}
	}

	public record Snapshot(
		Dimension dimension,
		String mechanismVersion,
		List<Placement> placements,
		List<Removal> removals,
		Map<ResourceKey<Biome>, List<Replacement>> replacements,
		Map<ResourceKey<Biome>, List<SubBiome>> subBiomes
	) {
		public Snapshot {
			placements = List.copyOf(placements);
			removals = List.copyOf(removals);
			replacements = immutableLists(replacements);
			subBiomes = immutableLists(subBiomes);
		}

		private static <K, V> Map<K, List<V>> immutableLists(Map<K, List<V>> input) {
			Map<K, List<V>> copied = new LinkedHashMap<>();
			input.forEach((key, values) -> copied.put(key, List.copyOf(values)));
			return Map.copyOf(copied);
		}
	}

	static final class Collector {
		private long revision;
		private final LinkedHashSet<Placement> placements = new LinkedHashSet<>();
		private final LinkedHashSet<Removal> removals = new LinkedHashSet<>();
		private final Map<ResourceKey<Biome>, List<Replacement>> replacements = new LinkedHashMap<>();
		private final Map<ResourceKey<Biome>, List<SubBiome>> subBiomes = new LinkedHashMap<>();
		private boolean addPlacement(Placement value) {
			return this.placements.add(value);
		}

		private boolean addRemoval(Removal value) {
			return this.removals.add(value);
		}

		boolean addReplacement(Replacement value) {
			List<Replacement> values = this.replacements.computeIfAbsent(value.target(), ignored -> new java.util.ArrayList<>());
			boolean duplicate = values.stream().anyMatch(candidate ->
				candidate.biome().equals(value.biome())
					&& Double.compare(candidate.proportion(), value.proportion()) == 0
			);
			if (duplicate) {
				return false;
			}
			values.add(value);
			return true;
		}

		private boolean addSubBiome(SubBiome value) {
			List<SubBiome> values = this.subBiomes.computeIfAbsent(value.target(), ignored -> new java.util.ArrayList<>());
			if (values.contains(value)) {
				return false;
			}
			values.add(value);
			return true;
		}

		boolean clearFromData() {
			boolean changed = this.placements.removeIf(Placement::fromData);
			changed |= this.removals.removeIf(Removal::fromData);
			changed |= clearEntries(this.replacements, Replacement::fromData);
			changed |= clearEntries(this.subBiomes, SubBiome::fromData);
			return changed;
		}

		private static <T> boolean clearEntries(
			Map<ResourceKey<Biome>, List<T>> entries,
			java.util.function.Predicate<T> fromData
		) {
			boolean changed = false;
			var iterator = entries.entrySet().iterator();
			while (iterator.hasNext()) {
				List<T> values = iterator.next().getValue();
				changed |= values.removeIf(fromData);
				if (values.isEmpty()) {
					iterator.remove();
					changed = true;
				}
			}
			return changed;
		}

		private boolean empty() {
			return this.placements.isEmpty() && this.removals.isEmpty()
				&& this.replacements.values().stream().allMatch(List::isEmpty)
				&& this.subBiomes.values().stream().allMatch(List::isEmpty);
		}

		private void advanceRevision() {
			this.revision = Math.addExact(this.revision, 1L);
		}

		private long revision() {
			return this.revision;
		}

		Snapshot snapshot(Dimension dimension, String version) {
			return new Snapshot(
				dimension, version, List.copyOf(this.placements), List.copyOf(this.removals),
				this.replacements, this.subBiomes
			);
		}
	}
}
