package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import etcodehome.freeterraforged.world.worldgen.feature.ErodeFeature;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public record SurfaceColumnPlan(
	List<Root> roots,
	Map<ResourceKey<Biome>, Set<PlacedFeature>> byBiome,
	Map<ResourceKey<Biome>, BiomeGenerationSettings> decorationSettings
) {
	private static final int RAW = GenerationStep.Decoration.RAW_GENERATION.ordinal();

	public SurfaceColumnPlan {
		roots = List.copyOf(roots);
		var copy = new java.util.LinkedHashMap<ResourceKey<Biome>, Set<PlacedFeature>>();
		byBiome.forEach((key, value) -> copy.put(key, immutableIdentitySet(value)));
		byBiome = Map.copyOf(copy);
		decorationSettings = Map.copyOf(decorationSettings);
	}

	public static SurfaceColumnPlan compile(
		List<FeatureSorter.StepFeatureData> steps,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> settings
	) {
		Set<PlacedFeature> eligible = identitySet();
		if (steps.size() > RAW) {
			for (PlacedFeature feature : steps.get(RAW).features()) {
				// Only this exact implementation is known to be chunk-local and placement-independent.
				if (feature.feature().value().feature().getClass() == ErodeFeature.class
					&& feature.placement().isEmpty()) eligible.add(feature);
			}
		}
		for (BiomeGenerationSettings biome : settings.values()) {
			for (int step = 0; step < biome.features().size(); step++) {
				if (step != RAW) biome.features().get(step).stream().map(Holder::value).forEach(eligible::remove);
			}
		}
		var roots = new java.util.ArrayList<Root>();
		if (!eligible.isEmpty()) {
			var schedule = steps.get(RAW);
			for (int i = 0; i < schedule.features().size(); i++) {
				PlacedFeature feature = schedule.features().get(i);
				if (eligible.contains(feature)) roots.add(new Root(feature, i));
			}
		}
		var byBiome = new java.util.LinkedHashMap<ResourceKey<Biome>, Set<PlacedFeature>>();
		var late = new java.util.LinkedHashMap<ResourceKey<Biome>, BiomeGenerationSettings>();
		settings.forEach((key, biome) -> {
			Set<PlacedFeature> moved = identitySet();
			if (biome.features().size() > RAW) biome.features().get(RAW).stream().map(Holder::value)
				.filter(eligible::contains).forEach(moved::add);
			if (moved.isEmpty()) {
				late.put(key, biome);
				return;
			}
			byBiome.put(key, moved);
			var builder = new BiomeGenerationSettings.PlainBuilder();
			for (GenerationStep.Carving step : GenerationStep.Carving.values())
				biome.getCarvers(step).forEach(carver -> builder.addCarver(step, carver));
			for (int step = 0; step < biome.features().size(); step++) {
				for (Holder<PlacedFeature> feature : biome.features().get(step)) {
					if (!moved.contains(feature.value())) builder.addFeature(step, feature);
				}
			}
			late.put(key, builder.build());
		});
		return new SurfaceColumnPlan(roots, byBiome, late);
	}

	public void apply(WorldGenRegion region, ChunkGenerator generator, ChunkAccess chunk, Set<Holder<Biome>> possibleBiomes) {
		if (this.roots.isEmpty() || SharedConstants.debugVoidTerrain(chunk.getPos())) return;
		Set<Holder<Biome>> biomes = new java.util.HashSet<>();
		// SURFACE already requires BIOMES at radius one, matching decoration's eligibility neighborhood.
		ChunkPos.rangeClosed(chunk.getPos(), 1).forEach(pos -> {
			for (var section : region.getChunk(pos.x, pos.z).getSections()) section.getBiomes().getAll(biomes::add);
		});
		biomes.retainAll(possibleBiomes);
		Set<PlacedFeature> selected = identitySet();
		for (Holder<Biome> biome : biomes) selected.addAll(this.byBiome.getOrDefault(biome.unwrapKey().orElseThrow(), Set.of()));
		var origin = SectionPos.of(chunk.getPos(), region.getMinSection()).origin();
		var random = new WorldgenRandom(new XoroshiroRandomSource(0));
		long decorationSeed = random.setDecorationSeed(region.getSeed(), origin.getX(), origin.getZ());
		for (Root root : this.roots) {
			if (selected.contains(root.feature)) {
				random.setFeatureSeed(decorationSeed, root.originalIndex, RAW);
				root.feature.placeWithBiomeCheck(region, generator, random, origin);
			}
		}
	}

	public record Root(PlacedFeature feature, int originalIndex) {
		public Root {
			java.util.Objects.requireNonNull(feature, "surface column feature");
			if (originalIndex < 0) throw new IllegalArgumentException("Feature index must be non-negative");
		}
	}

	private static Set<PlacedFeature> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}
	private static Set<PlacedFeature> immutableIdentitySet(Set<PlacedFeature> source) {
		Set<PlacedFeature> copy = identitySet();
		copy.addAll(source);
		return Collections.unmodifiableSet(copy);
	}
}
