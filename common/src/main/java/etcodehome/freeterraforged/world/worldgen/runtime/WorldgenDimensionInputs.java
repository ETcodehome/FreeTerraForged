package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldDimensions;

public final class WorldgenDimensionInputs {
	private WorldgenDimensionInputs() {
	}

	public static WorldDimensions acquire(WorldDimensions selected, Registry<LevelStem> declarations) {
		Map<ResourceKey<LevelStem>, LevelStem> replacements = replacements(selected, declarations);
		if (replacements.isEmpty()) {
			return selected;
		}
		return new WorldDimensions(replaceEntries(selected.dimensions(), replacements));
	}

	// Imported biome holders may not exist after pack removal; strip them only for UI reload.
	public static WorldDimensions forReload(WorldDimensions selected) {
		Map<ResourceKey<LevelStem>, LevelStem> replacements = new LinkedHashMap<>();
		for (var entry : selected.dimensions().entrySet()) {
			LevelStem stem = withoutDeclaration(entry.getValue());
			if (stem != entry.getValue()) {
				replacements.put(entry.getKey(), stem);
			}
		}
		return replacements.isEmpty() ? selected : new WorldDimensions(replaceEntries(selected.dimensions(), replacements));
	}

	private static LevelStem withoutDeclaration(LevelStem stem) {
		if (stem.generator() instanceof TerraForgedChunkGenerator generator) {
			var selected = generator.withoutDimensionBiomeSource();
			if (selected != generator) {
				return new LevelStem(stem.type(), selected);
			}
		}
		return stem;
	}

	public static Registry<LevelStem> forBake(WorldDimensions selected, Registry<LevelStem> declarations) {
		Map<ResourceKey<LevelStem>, LevelStem> replacements = new LinkedHashMap<>();
		for (var entry : selected.dimensions().entrySet()) {
			var key = entry.getKey();
			var stem = entry.getValue();
			if (stem.generator() instanceof TerraForgedChunkGenerator) {
				declarations.getOptional(key).ifPresent(declared -> {
					LevelStem acquired = acquire(stem, declared);
					if (acquired != declared) {
						replacements.put(key, acquired);
					}
				});
			}
		}
		if (replacements.isEmpty()) {
			return declarations;
		}
		return replaceEntries(declarations, replacements);
	}

	private static Registry<LevelStem> replaceEntries(
		Registry<LevelStem> source, Map<ResourceKey<LevelStem>, LevelStem> replacements
	) {
		MappedRegistry<LevelStem> resolved = new MappedRegistry<>(
			Registries.LEVEL_STEM, source.registryLifecycle()
		);
		source.holders().forEach(holder -> resolved.register(
			holder.key(), replacements.getOrDefault(holder.key(), holder.value()),
			source.lifecycle(holder.value())
		));
		Map<TagKey<LevelStem>, List<Holder<LevelStem>>> tags = new LinkedHashMap<>();
		source.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream()
			.<Holder<LevelStem>>map(holder -> resolved.getHolderOrThrow(holder.unwrapKey().orElseThrow()))
			.toList()));
		resolved.bindTags(tags);
		return resolved.freeze();
	}

	private static Map<ResourceKey<LevelStem>, LevelStem> replacements(
		WorldDimensions selected, Registry<LevelStem> declarations
	) {
		Map<ResourceKey<LevelStem>, LevelStem> replacements = new LinkedHashMap<>();
		for (var entry : selected.dimensions().entrySet()) {
			var key = entry.getKey();
			var stem = entry.getValue();
			if (stem.generator() instanceof TerraForgedChunkGenerator) {
				LevelStem acquired = declarations.getOptional(key)
					.map(declared -> acquire(stem, declared)).orElseGet(() -> withoutDeclaration(stem));
				if (acquired != stem) {
					replacements.put(key, acquired);
				}
			}
		}
		return replacements;
	}

	private static LevelStem acquire(LevelStem selected, LevelStem declared) {
		TerraForgedChunkGenerator owner = (TerraForgedChunkGenerator) selected.generator();
		if (declared.generator() instanceof TerraForgedChunkGenerator) {
			return declared;
		}
		if (!(declared.generator() instanceof NoiseBasedChunkGenerator)) {
			return selected;
		}
		var source = declared.generator().getBiomeSource();
		if (owner.acquisitionBiomeSource() == source) {
			return selected;
		}
		return new LevelStem(selected.type(), owner.withDimensionBiomeSource(source));
	}
}
