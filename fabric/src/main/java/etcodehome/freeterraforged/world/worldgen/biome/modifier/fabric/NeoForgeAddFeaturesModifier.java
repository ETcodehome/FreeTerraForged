package etcodehome.freeterraforged.world.worldgen.biome.modifier.fabric;

import java.util.Optional;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import etcodehome.freeterraforged.world.worldgen.biome.modifier.Filter;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.Order;
import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * NeoForge compatibility shim for the {@code neoforge:add_features} biome modifier serializer.
 * 
 * Mods ported from NeoForge may ship datapack files under {@code data/<ns>/neoforge/biome_modifier/}
 * that reference {@code "type": "neoforge:add_features"}. On NeoForge this serializer is provided by
 * the platform itself, but on Fabric nothing registered it, causing registry loading (and therefore
 * world creation) to fail with "Unknown registry key ... neoforge:add_features".
 * 
 * This modifier mirrors the NeoForge field layout (biomes, features, step) and delegates to
 * {@link AddModifier} with append ordering and whitelist semantics, matching NeoForge behaviour.
 */
record NeoForgeAddFeaturesModifier(GenerationStep.Decoration step, HolderSet<Biome> biomes, HolderSet<PlacedFeature> features) implements FabricBiomeModifier {
	public static final MapCodec<NeoForgeAddFeaturesModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(NeoForgeAddFeaturesModifier::step),
		Biome.LIST_CODEC.fieldOf("biomes").forGetter(NeoForgeAddFeaturesModifier::biomes),
		PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(NeoForgeAddFeaturesModifier::features)
	).apply(instance, NeoForgeAddFeaturesModifier::new));

	@Override
	public MapCodec<NeoForgeAddFeaturesModifier> codec() {
		return CODEC;
	}

	@Override
	public void apply(BiomeSelectionContext selectionContext, BiomeModificationContext modificationContext) {
		AddModifier delegate = new AddModifier(Order.APPEND, this.step, Optional.of(new Filter(this.biomes, Filter.Behavior.WHITELIST)), this.features);
		delegate.apply(selectionContext, modificationContext);
	}
}
