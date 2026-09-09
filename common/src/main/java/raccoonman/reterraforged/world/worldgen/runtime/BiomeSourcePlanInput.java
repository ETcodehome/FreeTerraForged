package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

public record BiomeSourcePlanInput(
	ResourceLocation id,
	Set<Holder<Biome>> possibleOutputs,
	WorldgenQueryMode queryMode,
	Query query
) {
	public BiomeSourcePlanInput {
		id = Objects.requireNonNull(id, "id");
		possibleOutputs = Collections.unmodifiableSet(new LinkedHashSet<>(possibleOutputs));
		queryMode = Objects.requireNonNull(queryMode, "queryMode");
		query = Objects.requireNonNull(query, "query");
		if (possibleOutputs.isEmpty() || possibleOutputs.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("A custom biome-source plan must declare every possible output");
		}
	}

	public Holder<Biome> resolve(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
		Holder<Biome> result = Objects.requireNonNull(
			this.query.resolve(quartX, quartY, quartZ, sampler),
			() -> "Custom biome-source plan " + this.id + " returned null"
		);
		if (!this.possibleOutputs.contains(result)) {
			throw new IllegalStateException(
				"Custom biome-source plan " + this.id + " returned an undeclared biome " + result
			);
		}
		return result;
	}

	public BiomeSourcePlanInput canonicalize(Registry<Biome> biomes) {
		Objects.requireNonNull(biomes, "biomes");
		Map<Holder<Biome>, Holder<Biome>> canonicalByDeclared = new HashMap<>();
		LinkedHashSet<Holder<Biome>> canonicalOutputs = new LinkedHashSet<>();
		boolean unchanged = true;
		for (Holder<Biome> declared : this.possibleOutputs) {
			ResourceKey<Biome> key = declared.unwrapKey()
				.or(() -> biomes.getResourceKey(declared.value()))
				.orElseThrow(() -> new IllegalArgumentException(
					"Custom biome-source plan " + this.id
						+ " declares an output absent from the selected biome registry"
				));
			Holder.Reference<Biome> canonical = biomes.getHolder(key).orElseThrow(() ->
				new IllegalArgumentException(
					"Custom biome-source plan " + this.id + " declares missing biome "
						+ key.location()
				)
			);
			canonicalByDeclared.put(declared, canonical);
			canonicalOutputs.add(canonical);
			unchanged &= declared == canonical;
		}
		if (unchanged) {
			return this;
		}
		return new BiomeSourcePlanInput(
			this.id,
			canonicalOutputs,
			this.queryMode,
			(quartX, quartY, quartZ, sampler) -> {
				Holder<Biome> declared = this.resolve(quartX, quartY, quartZ, sampler);
				Holder<Biome> canonical = canonicalByDeclared.get(declared);
				if (canonical == null) {
					throw new IllegalStateException(
						"Custom biome-source plan " + this.id
							+ " returned an output that could not be rebound to its declared registry closure"
					);
				}
				return canonical;
			}
		);
	}

	@FunctionalInterface
	public interface Query {
		Holder<Biome> resolve(int quartX, int quartY, int quartZ, Climate.Sampler sampler);
	}
}
