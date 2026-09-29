package etcodehome.freeterraforged.world.worldgen.biome.modifier.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import etcodehome.freeterraforged.forge.mixin.MixinBiomeGenerationSettingsPlainsBuilder;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.Filter;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.Order;
import net.minecraftforge.common.world.BiomeModifier;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.common.world.ModifiableBiomeInfo.BiomeInfo;

public record AddModifier(Order order, GenerationStep.Decoration step, Optional<Filter> biomes, HolderSet<PlacedFeature> features) implements ForgeBiomeModifier {
	public static final Codec<AddModifier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Order.CODEC.fieldOf("order").forGetter(AddModifier::order),
		GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(AddModifier::step),
		Filter.CODEC.optionalFieldOf("biomes").forGetter(AddModifier::biomes),
		PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(AddModifier::features)
	).apply(instance, AddModifier::new));
	
	@Override
	public void modify(Holder<Biome> biome, BiomeModifier.Phase phase, BiomeInfo.Builder builder) {
		if(phase == BiomeModifier.Phase.AFTER_EVERYTHING) {
			if(builder.getGenerationSettings() instanceof MixinBiomeGenerationSettingsPlainsBuilder builderAccessor) {
				if(this.biomes.isPresent() && !this.biomes.get().test(biome)) {
					return;
				}
				
				List<List<Holder<PlacedFeature>>> featureSteps = builderAccessor.getFeatures();
				int index = this.step.ordinal();
	
				while (index >= featureSteps.size()) {
					featureSteps.add(new ArrayList<>());
				}

				List<Holder<PlacedFeature>> updatedList = this.add(featureSteps.get(index));
				featureSteps.set(index, new ArrayList<>(updatedList));
			} else {
				throw new IllegalStateException();
			}
		}
	}

	@Override
	public Codec<AddModifier> codec() {
		return CODEC;
	}

	private List<Holder<PlacedFeature>> add(@Nullable List<Holder<PlacedFeature>> values) {
		List<Holder<PlacedFeature>> newFeatures = this.features.stream().toList();
		if (values == null) {
			return new ArrayList<>(newFeatures);
		}
		return new ArrayList<>(this.order.add(values, newFeatures));
	}
}
