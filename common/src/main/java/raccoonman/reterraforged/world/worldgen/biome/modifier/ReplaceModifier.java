package raccoonman.reterraforged.world.worldgen.biome.modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Declarative, loader-neutral feature replacement compiled into FTF's placed-feature plan. */
public record ReplaceModifier(
	GenerationStep.Decoration step,
	Optional<HolderSet<Biome>> biomes,
	Map<ResourceKey<PlacedFeature>, ResourceKey<PlacedFeature>> replacements
) implements BiomeModifier {
	public static final MapCodec<ReplaceModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(ReplaceModifier::step),
		Biome.LIST_CODEC.optionalFieldOf("biomes").forGetter(ReplaceModifier::biomes),
		Codec.unboundedMap(
			ResourceKey.codec(Registries.PLACED_FEATURE),
			ResourceKey.codec(Registries.PLACED_FEATURE)
		).fieldOf("replacements").forGetter(ReplaceModifier::replacements)
	).apply(instance, ReplaceModifier::new));

	public ReplaceModifier {
		step = Objects.requireNonNull(step, "step");
		biomes = biomes == null ? Optional.empty() : biomes;
		replacements = Map.copyOf(Objects.requireNonNull(replacements, "replacements"));
	}

	@Override
	public MapCodec<ReplaceModifier> codec() {
		return CODEC;
	}

	@Override
	public List<Holder<PlacedFeature>> apply(
		Holder<Biome> biome,
		List<Holder<PlacedFeature>> current,
		HolderLookup.Provider lookups
	) {
		if (this.biomes.isPresent() && !this.biomes.orElseThrow().contains(biome)) {
			return List.copyOf(current);
		}
		var placedFeatures = lookups.lookupOrThrow(Registries.PLACED_FEATURE);
		List<Holder<PlacedFeature>> replaced = new ArrayList<>(current.size());
		for (Holder<PlacedFeature> feature : current) {
			ResourceKey<PlacedFeature> replacement = feature.unwrapKey()
				.map(this.replacements::get)
				.orElse(null);
			replaced.add(replacement == null ? feature : placedFeatures.getOrThrow(replacement));
		}
		return List.copyOf(replaced);
	}
}
