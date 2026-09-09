package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.List;
import java.util.Objects;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

public record BiomeCandidateRoot(
	List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries,
	Climate.ParameterList<Holder<Biome>> candidates
) {
	public BiomeCandidateRoot {
		entries = List.copyOf(entries);
		candidates = Objects.requireNonNull(candidates, "candidates");
		if (entries.isEmpty()) {
			throw new IllegalArgumentException("Biome candidate root must not be empty");
		}
		if (!entries.equals(candidates.values())) {
			throw new IllegalArgumentException("Biome candidate entries and search index disagree");
		}
	}

	public static BiomeCandidateRoot fromEntries(
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries
	) {
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> immutable = List.copyOf(entries);
		return new BiomeCandidateRoot(immutable, new Climate.ParameterList<>(immutable));
	}

	public static BiomeCandidateRoot fromCandidates(
		Climate.ParameterList<Holder<Biome>> candidates
	) {
		return new BiomeCandidateRoot(candidates.values(), candidates);
	}
}
