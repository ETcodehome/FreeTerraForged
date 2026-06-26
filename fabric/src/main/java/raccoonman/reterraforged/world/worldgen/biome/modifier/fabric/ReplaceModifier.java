package raccoonman.reterraforged.world.worldgen.biome.modifier.fabric;

import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public record ReplaceModifier(
		GenerationStep.Decoration step,
		Optional<HolderSet<Biome>> biomes,
		Map<ResourceKey<PlacedFeature>, ResourceKey<PlacedFeature>> replacements
) implements FabricBiomeModifier {

	public static final MapCodec<ReplaceModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(ReplaceModifier::step),
			Biome.LIST_CODEC.optionalFieldOf("biomes").forGetter(ReplaceModifier::biomes),
			Codec.unboundedMap(
					ResourceKey.codec(Registries.PLACED_FEATURE),
					ResourceKey.codec(Registries.PLACED_FEATURE)
			).fieldOf("replacements").forGetter(ReplaceModifier::replacements)
	).apply(instance, ReplaceModifier::new));

	@Override
	public void apply(BiomeSelectionContext selectionContext, BiomeModificationContext modificationContext) {
		if (this.biomes.isPresent() && !this.biomes.get().contains(selectionContext.getBiomeRegistryEntry())) {
			return;
		}

		this.replacements.forEach((originalKey, replacementKey) -> {
			// Attempt to safely remove the vanilla feature using Fabric's API
			if (modificationContext.getGenerationSettings().removeFeature(this.step, originalKey)) {
				// If it was successfully found and removed, inject the custom replacement
				modificationContext.getGenerationSettings().addFeature(this.step, replacementKey);
			}
		});
	}

	@Override
	public MapCodec<ReplaceModifier> codec() {
		return CODEC;
	}
}