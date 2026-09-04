package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Public codec/registry extraction for the selected multi-noise source graph. */
public final class MinecraftBiomeSourceGraphs {
	private MinecraftBiomeSourceGraphs() {
	}

	public static BiomeSource acquisitionSource(ChunkGenerator generator) {
		return generator instanceof TerraForgedChunkGenerator terraForged
			? terraForged.acquisitionBiomeSource()
			: generator.getBiomeSource();
	}

	public static BiomeCandidateRoot multiNoiseRoot(
		BiomeSource source,
		HolderLookup.Provider lookups
	) {
		if (!(source instanceof MultiNoiseBiomeSource multiNoise)) {
			throw new IllegalArgumentException("Selected biome source is not a multi-noise source");
		}
		var parameterLists = lookups.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
		var retainedPreset = parameterLists.listElements()
			.filter(holder -> multiNoise.stable(holder.key()))
			.findFirst();
		if (retainedPreset.isPresent()) {
			return BiomeCandidateRoot.fromCandidates(
				retainedPreset.orElseThrow().value().parameters()
			);
		}
		var ops = RegistryOps.create(JsonOps.INSTANCE, lookups);
		JsonElement encoded = MultiNoiseBiomeSource.CODEC.codec()
			.encodeStart(ops, multiNoise)
			.getOrThrow(message -> new IllegalStateException(
				"Failed to encode selected multi-noise source: " + message
			));
		var direct = MultiNoiseBiomeSource.DIRECT_CODEC.codec().parse(ops, encoded).result();
		if (direct.isPresent()) {
			return BiomeCandidateRoot.fromCandidates(direct.orElseThrow());
		}
		if (!encoded.isJsonObject()) {
			throw new IllegalStateException("Selected multi-noise codec graph is not an object: " + encoded);
		}
		JsonObject object = encoded.getAsJsonObject();
		JsonElement presetElement = object.get("preset");
		if (presetElement == null || !presetElement.isJsonPrimitive()) {
			throw new IllegalStateException("Selected multi-noise codec graph has neither biomes nor preset: " + encoded);
		}
		ResourceLocation presetId = ResourceLocation.tryParse(presetElement.getAsString());
		if (presetId == null) {
			throw new IllegalStateException("Invalid multi-noise preset identity: " + presetElement);
		}
		ResourceKey<MultiNoiseBiomeSourceParameterList> presetKey = ResourceKey.create(
			Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
			presetId
		);
		return BiomeCandidateRoot.fromCandidates(
			parameterLists
			.get(presetKey)
			.orElseThrow(() -> new IllegalStateException(
				"Selected multi-noise preset is absent from the final lookup graph: " + presetId
			))
			.value()
			.parameters()
		);
	}

	public static List<Pair<Climate.ParameterPoint, Holder<Biome>>> multiNoiseEntries(
		BiomeSource source,
		HolderLookup.Provider lookups
	) {
		return multiNoiseRoot(source, lookups).entries();
	}
}
