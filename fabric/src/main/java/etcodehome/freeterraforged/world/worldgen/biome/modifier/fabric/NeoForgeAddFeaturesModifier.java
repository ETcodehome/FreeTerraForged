package etcodehome.freeterraforged.world.worldgen.biome.modifier.fabric;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * NeoForge compatibility shim for the {@code neoforge:add_features} biome modifier serializer.
 *
 * <p>Mods ported from NeoForge may ship datapack files under {@code data/<ns>/neoforge/biome_modifier/}
 * that reference {@code "type": "neoforge:add_features"}. On NeoForge this serializer is provided by
 * the platform itself, but on Fabric nothing registered it, so FTF (which loads the
 * {@code neoforge:biome_modifier} registry for cross-loader compatibility) failed to parse those
 * files and world creation crashed with "Unknown registry key ... neoforge:add_features" (issue #287).
 *
 * <p>This shim parses the NeoForge field layout (biomes, features, step) so the files load
 * successfully, but deliberately applies <b>nothing</b>: on Fabric, mods shipping such files
 * (e.g. Cobblemon: Mega Showdown) already register the same features through the Fabric
 * BiomeModifications API. Applying them a second time here caused a "Feature order cycle found"
 * error in lush_caves and stalled chunk generation at 0%.
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
		// Intentionally no-op: parsing succeeds so registries load, but the feature is not
		// re-applied — the owning mod already registers it via Fabric BiomeModifications.
	}
}
