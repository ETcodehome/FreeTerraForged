package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter.StepFeatureData;
import net.minecraft.world.level.levelgen.structure.Structure;

public record BiomeDecorationPlan(
	List<Holder.Reference<Structure>> structures,
	List<StepFeatureData> featureSteps,
	Map<net.minecraft.resources.ResourceKey<Biome>, BiomeGenerationSettings> generationSettings,
	Set<Holder<Biome>> possibleBiomes
) {
	public BiomeDecorationPlan {
		structures = List.copyOf(structures);
		featureSteps = List.copyOf(featureSteps);
		generationSettings = Map.copyOf(generationSettings);
		possibleBiomes = Set.copyOf(possibleBiomes);
	}

	public Stream<Structure> structureValues() {
		return this.structures.stream().map(Holder.Reference::value);
	}

	public BiomeGenerationSettings generationSettings(Holder<Biome> biome) {
		var key = biome.unwrapKey().orElseThrow(() -> new IllegalStateException(
			"Biome decoration selected an unregistered biome: " + biome
		));
		BiomeGenerationSettings settings = this.generationSettings.get(key);
		if (settings == null) {
			throw new IllegalStateException(
				"Biome decoration selected a biome outside the compiled feature plan: " + biome
			);
		}
		return settings;
	}
}
