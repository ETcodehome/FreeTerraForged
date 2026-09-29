package etcodehome.freeterraforged.world.worldgen.biome.modifier.forge;

import java.util.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import etcodehome.freeterraforged.forge.mixin.MixinBiomeGenerationSettingsPlainsBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo;
import net.minecraftforge.server.ServerLifecycleHooks;

public record ReplaceModifier(GenerationStep.Decoration step, Optional<HolderSet<Biome>> biomes, Map<ResourceKey<PlacedFeature>, ResourceKey<PlacedFeature>> replacements) implements ForgeBiomeModifier {
	public static final Codec<ReplaceModifier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(ReplaceModifier::step),
			Biome.LIST_CODEC.optionalFieldOf("biomes").forGetter(ReplaceModifier::biomes),
			Codec.unboundedMap(
					ResourceKey.codec(Registries.PLACED_FEATURE),
					ResourceKey.codec(Registries.PLACED_FEATURE)
			).fieldOf("replacements").forGetter(ReplaceModifier::replacements)
	).apply(instance, ReplaceModifier::new));

	@Override
	public void modify(Holder<Biome> biome, BiomeModifier.Phase phase, BiomeInfo.Builder builder) {
		if (phase == BiomeModifier.Phase.AFTER_EVERYTHING) {
			if (builder.getGenerationSettings() instanceof MixinBiomeGenerationSettingsPlainsBuilder builderAccessor) {
				if (this.biomes.isPresent() && !this.biomes.get().contains(biome)) {
					return;
				}

				var server = ServerLifecycleHooks.getCurrentServer();
				var registryAccess = server.registryAccess();
				var featureRegistry = registryAccess.lookupOrThrow(Registries.PLACED_FEATURE);

				List<List<Holder<PlacedFeature>>> featureSteps = builderAccessor.getFeatures();
				int index = this.step.ordinal();

				while (index >= featureSteps.size()) {
					featureSteps.add(Collections.emptyList());
				}

				List<Holder<PlacedFeature>> replaced = new ArrayList<>(featureSteps.get(index));

				replaced.replaceAll((f) -> {
					// Check if this feature is one of our keys
					return f.unwrapKey()
							.map(key -> {
								// If we have a replacement, look it up in the registry
								if (this.replacements.containsKey(key)) {
									ResourceKey<PlacedFeature> replacementKey = this.replacements.get(key);
									return featureRegistry.get(replacementKey)
											.orElseThrow(() -> new IllegalStateException("Missing feature: " + replacementKey.location()));
								}
								return f;
							})
							.orElse(f);
				});
				featureSteps.set(index, replaced);
			}
		}
	}

	@Override
	public Codec<ReplaceModifier> codec() {
		return CODEC;
	}
}
