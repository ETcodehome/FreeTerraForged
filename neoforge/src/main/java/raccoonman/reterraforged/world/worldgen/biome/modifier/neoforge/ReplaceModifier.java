package raccoonman.reterraforged.world.worldgen.biome.modifier.neoforge;

import java.util.*;

import com.mojang.serialization.MapCodec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo;
import raccoonman.reterraforged.neoforge.mixin.MixinBiomeGenerationSettingsPlainsBuilder;

public record ReplaceModifier(GenerationStep.Decoration step, Optional<HolderSet<Biome>> biomes, Map<ResourceKey<PlacedFeature>, Holder<PlacedFeature>> replacements) implements ForgeBiomeModifier {
	public static final MapCodec<ReplaceModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(ReplaceModifier::step),
		Biome.LIST_CODEC.optionalFieldOf("biomes").forGetter(ReplaceModifier::biomes),
		Codec.unboundedMap(ResourceKey.codec(Registries.PLACED_FEATURE), PlacedFeature.CODEC).fieldOf("replacements").forGetter(ReplaceModifier::replacements)
	).apply(instance, ReplaceModifier::new));

	@Override
	public void modify(Holder<Biome> biome, BiomeModifier.Phase phase, BiomeInfo.Builder builder) {
		if (phase == BiomeModifier.Phase.AFTER_EVERYTHING) {
			if (builder.getGenerationSettings() instanceof MixinBiomeGenerationSettingsPlainsBuilder builderAccessor) {
				if (this.biomes.isPresent() && !this.biomes.get().contains(biome)) {
					return;
				}

				List<List<Holder<PlacedFeature>>> featureSteps = builderAccessor.getFeatures();
				int index = this.step.ordinal();

				while (index >= featureSteps.size()) {
					featureSteps.add(Collections.emptyList());
				}

				// Copy to a new mutable list, then set it back
				List<Holder<PlacedFeature>> replaced = new ArrayList<>(featureSteps.get(index));
				replaced.replaceAll((f) -> f.unwrapKey().map(this.replacements::get).orElse(f));
				featureSteps.set(index, replaced);

			} else {
				throw new IllegalStateException();
			}
		}
	}

	@Override
	public MapCodec<ReplaceModifier> codec() {
		return CODEC;
	}
}
