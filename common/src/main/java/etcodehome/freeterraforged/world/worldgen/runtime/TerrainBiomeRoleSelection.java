package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.concurrent.Resource;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

final class TerrainBiomeRoleSelection {
	private final Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers;

	TerrainBiomeRoleSelection(WorldgenPlans.ProviderSelection providers) {
		IdentityHashMap<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers =
			new IdentityHashMap<>();
		for (WorldgenPlans.ProviderDomain provider : providers.providers()) {
			addRiverCandidates(rivers, provider.candidates());
		}
		providers.fallback().ifPresent(table -> addRiverCandidates(rivers, table));
		this.rivers = rivers;
	}

	Holder<Biome> resolve(
		WorldgenPlans.ProviderResult result,
		Climate.TargetPoint target,
		Holder<Biome> selected,
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler,
		GeneratorContext context
	) {
		Climate.ParameterList<Holder<Biome>> riverCandidates = this.rivers.get(result.candidates());
		if (riverCandidates == null || !result.biome().equals(result.baseBiome())
			|| selected.is(BiomeTags.IS_RIVER)
			|| UndergroundBiomeSurfaceProtection.coverageFactor(
				sampler, target, quartX, quartY, quartZ
			) > 0.0F) {
			return selected;
		}
		try (Resource<Cell> resource = Cell.getResource()) {
			Cell cell = resource.get().reset();
			context.lookup.applyCell(
				cell, QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ), false
			);
			return cell.terrain.isRiver() ? riverCandidates.findValue(result.target()) : selected;
		}
	}

	private static void addRiverCandidates(
		Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers,
		Climate.ParameterList<Holder<Biome>> source
	) {
		Climate.ParameterList<Holder<Biome>> candidates = taggedSurfaceCandidates(
			source, holder -> holder.is(BiomeTags.IS_RIVER)
		);
		if (candidates != null) {
			rivers.put(source, candidates);
		}
	}

	static <T> Climate.ParameterList<T> taggedSurfaceCandidates(
		Climate.ParameterList<T> source,
		Predicate<T> tag
	) {
		List<Pair<Climate.ParameterPoint, T>> candidates = source.values().stream()
			.filter(entry -> tag.test(entry.getSecond())
				&& entry.getFirst().depth().min() <= 0
				&& entry.getFirst().depth().max() >= 0)
			.toList();
		return candidates.isEmpty() ? null : new Climate.ParameterList<>(candidates);
	}
}
