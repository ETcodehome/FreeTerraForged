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

/** Resolves dimension declarations before preview or server ownership is established. */
public final class WorldgenDimensionInputs {
	private WorldgenDimensionInputs() {
	}

	public static WorldDimensions acquire(WorldDimensions selected, Registry<LevelStem> declarations) {
		Map<ResourceKey<LevelStem>, LevelStem> replacements = replacements(selected, declarations);
		if (replacements.isEmpty()) {
			return selected;
		}
		Map<ResourceKey<LevelStem>, LevelStem> dimensions = new LinkedHashMap<>(selected.dimensions());
		dimensions.putAll(replacements);
		return new WorldDimensions(Map.copyOf(dimensions));
	}

	/** Encode only selected inputs into the new registry context when the creation UI reloads packs.
	 * Imported biome holders may no longer exist there. Server/world-save codecs retain both inputs.
	 */
	public static WorldDimensions forReload(WorldDimensions selected) {
		Map<ResourceKey<LevelStem>, LevelStem> dimensions = new LinkedHashMap<>(selected.dimensions());
		boolean changed = false;
		for (var entry : selected.dimensions().entrySet()) {
			LevelStem stem = withoutDeclaration(entry.getValue());
			if (stem != entry.getValue()) {
				dimensions.put(entry.getKey(), stem);
				changed = true;
			}
		}
		return changed ? new WorldDimensions(Map.copyOf(dimensions)) : selected;
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

	/** Leaves vanilla's bake implementation and non-FTF dimension precedence intact. */
	public static Registry<LevelStem> forBake(WorldDimensions selected, Registry<LevelStem> declarations) {
		Map<ResourceKey<LevelStem>, LevelStem> replacements = new LinkedHashMap<>();
		selected.dimensions().forEach((key, stem) -> {
			if (stem.generator() instanceof TerraForgedChunkGenerator) {
				declarations.getOptional(key).ifPresent(declared -> {
					LevelStem acquired = acquire(stem, declared);
					if (acquired != declared) {
						replacements.put(key, acquired);
					}
				});
			}
		});
		if (replacements.isEmpty()) {
			return declarations;
		}
		MappedRegistry<LevelStem> resolved = new MappedRegistry<>(
			Registries.LEVEL_STEM, declarations.registryLifecycle()
		);
		declarations.holders().forEach(holder -> resolved.register(
			holder.key(), replacements.getOrDefault(holder.key(), holder.value()),
			declarations.registrationInfo(holder.key()).orElseThrow()
		));
		Map<TagKey<LevelStem>, List<Holder<LevelStem>>> tags = new LinkedHashMap<>();
		declarations.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream()
			.<Holder<LevelStem>>map(holder -> resolved.getHolderOrThrow(holder.unwrapKey().orElseThrow()))
			.toList()));
		resolved.bindTags(tags);
		return resolved.freeze();
	}

	private static Map<ResourceKey<LevelStem>, LevelStem> replacements(
		WorldDimensions selected, Registry<LevelStem> declarations
	) {
		Map<ResourceKey<LevelStem>, LevelStem> replacements = new LinkedHashMap<>();
		selected.dimensions().forEach((key, stem) -> {
			if (stem.generator() instanceof TerraForgedChunkGenerator) {
				LevelStem acquired = declarations.getOptional(key)
					.map(declared -> acquire(stem, declared)).orElseGet(() -> withoutDeclaration(stem));
				if (acquired != stem) {
					replacements.put(key, acquired);
				}
			}
		});
		return replacements;
	}

	private static LevelStem acquire(LevelStem selected, LevelStem declared) {
		TerraForgedChunkGenerator owner = (TerraForgedChunkGenerator) selected.generator();
		if (declared.generator() instanceof TerraForgedChunkGenerator) {
			return declared;
		}
		if (declared.generator().getClass() != NoiseBasedChunkGenerator.class) {
			throw new IllegalStateException(
				"Unsupported dimension generator composition: selected FTF generator and declared "
					+ declared.generator().getClass().getName()
			);
		}
		var source = declared.generator().getBiomeSource();
		if (owner.acquisitionBiomeSource() == source) {
			return selected;
		}
		// The selected preset owns density, height and settings. The declaration contributes its
		// public biome graph, which is normalized by the ordinary owner-scoped plan compiler.
		return new LevelStem(selected.type(), owner.withDimensionBiomeSource(source));
	}
}
