package raccoonman.reterraforged.world.worldgen.biome.modifier;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public record AddModifier(
	Order order,
	GenerationStep.Decoration step,
	Optional<Filter> biomes,
	HolderSet<PlacedFeature> features
) implements BiomeModifier {
	public static final MapCodec<AddModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Order.CODEC.fieldOf("order").forGetter(AddModifier::order),
		GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(AddModifier::step),
		Filter.CODEC.optionalFieldOf("biomes").forGetter(AddModifier::biomes),
		PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(AddModifier::features)
	).apply(instance, AddModifier::new));

	public AddModifier {
		order = Objects.requireNonNull(order, "order");
		step = Objects.requireNonNull(step, "step");
		biomes = biomes == null ? Optional.empty() : biomes;
		features = Objects.requireNonNull(features, "features");
	}

	@Override
	public MapCodec<AddModifier> codec() {
		return CODEC;
	}

	@Override
	public List<Holder<PlacedFeature>> apply(
		Holder<Biome> biome,
		List<Holder<PlacedFeature>> current,
		HolderLookup.Provider lookups
	) {
		if (this.biomes.isPresent() && !this.biomes.orElseThrow().test(biome)) {
			return List.copyOf(current);
		}
		return List.copyOf(this.order.add(current, this.features.stream().toList()));
	}
}
