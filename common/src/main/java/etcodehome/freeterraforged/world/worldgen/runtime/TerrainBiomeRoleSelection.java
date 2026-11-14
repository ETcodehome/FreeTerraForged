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
import etcodehome.freeterraforged.world.worldgen.biome.WetlandBiomeTags;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;

final class TerrainBiomeRoleSelection {
	private final Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers;
	private final Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> wetlands;
	private final Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> nonRivers;

	TerrainBiomeRoleSelection(WorldgenPlans.ProviderSelection providers) {
		IdentityHashMap<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers =
			new IdentityHashMap<>();
		IdentityHashMap<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> wetlands =
			new IdentityHashMap<>();
		IdentityHashMap<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> nonRivers =
			new IdentityHashMap<>();
		for (WorldgenPlans.ProviderDomain provider : providers.providers()) {
			addCandidates(rivers, wetlands, nonRivers, provider.candidates());
		}
		providers.fallback().ifPresent(table -> addCandidates(rivers, wetlands, nonRivers, table));
		this.rivers = rivers;
		this.wetlands = wetlands;
		this.nonRivers = nonRivers;
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
			|| UndergroundBiomeSurfaceProtection.coverageFactor(
				sampler, target, quartX, quartY, quartZ
			) > 0.0F) {
			return selected;
		}
		boolean perfActive = WorldgenPerfTracker.isActive();
		WorldgenPerfTracker.ThreadStats ts = perfActive ? WorldgenPerfTracker.threadStats() : null;
		WorldgenPerfTracker.ApplyCellCaller prevCaller = null;
		if (ts != null) {
			prevCaller = ts.currentCaller;
			ts.currentCaller = WorldgenPerfTracker.ApplyCellCaller.TERRAIN_ROLE_RIVER;
		}
		try {
			Cell cell = CellSampler.getCachedOrLookup(
				context.lookup, QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ), false
			);
			boolean submerged = isSubmerged(cell, context.levels.water);
			if (submerged) {
				if (cell.terrain.isWetland()) {
					// Submerged wetland water pool:
					// 1. If already classified as a swamp or wetland biome, preserve it intact
					if (WetlandBiomeTags.isWetland(selected)) {
						return selected;
					}
					// 2. If classified as a river biome, allow it (e.g. river corridor through wetland)
					if (selected.is(BiomeTags.IS_RIVER)) {
						return selected;
					}
					// 3. If classified as a dry land biome (e.g. plains), prefer regional wetland candidates, else river
					Climate.ParameterList<Holder<Biome>> wetlandCandidates = this.wetlands.get(result.candidates());
					if (wetlandCandidates != null) {
						Holder<Biome> wetland = wetlandCandidates.findValue(result.target());
						if (wetland != null) {
							return wetland;
						}
					}
					return riverCandidates.findValue(result.target());
				} else {
					// Submerged river channel or lake:
					return selected.is(BiomeTags.IS_RIVER) ? selected : riverCandidates.findValue(result.target());
				}
			} else if (selected.is(BiomeTags.IS_RIVER)) {
				// Dry land (river banks, lake shores, wetland hummocks):
				// Strict channel restriction: dry land must not receive river biomes
				Climate.ParameterList<Holder<Biome>> nonRiverCandidates = this.nonRivers.get(result.candidates());
				return nonRiverCandidates != null ? nonRiverCandidates.findValue(result.target()) : selected;
			}
			return selected;
		} finally {
			if (ts != null) {
				ts.currentCaller = prevCaller;
			}
		}
	}

	static boolean isSubmerged(Cell cell, float defaultWaterLevel, float tolerance) {
		if (!cell.terrain.isRiver() && !cell.terrain.isWetland() && !cell.terrain.isLake()) {
			return false;
		}
		float waterLevel = cell.riverWaterLevel > 0.0F ? cell.riverWaterLevel : defaultWaterLevel;
		return cell.height <= waterLevel + tolerance;
	}

	static boolean isSubmerged(Cell cell, float defaultWaterLevel) {
		return isSubmerged(cell, defaultWaterLevel, 0.0F);
	}

	static boolean isSubmerged(Cell cell) {
		return isSubmerged(cell, 0.0F, 0.0F);
	}

	private static void addCandidates(
		Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> rivers,
		Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> wetlands,
		Map<Climate.ParameterList<Holder<Biome>>, Climate.ParameterList<Holder<Biome>>> nonRivers,
		Climate.ParameterList<Holder<Biome>> source
	) {
		Climate.ParameterList<Holder<Biome>> riverList = taggedSurfaceCandidates(
			source, holder -> holder.is(BiomeTags.IS_RIVER)
		);
		if (riverList != null) {
			rivers.put(source, riverList);
		}
		Climate.ParameterList<Holder<Biome>> wetlandList = taggedSurfaceCandidates(
			source, WetlandBiomeTags::isWetland
		);
		if (wetlandList != null) {
			wetlands.put(source, wetlandList);
		}
		Climate.ParameterList<Holder<Biome>> nonRiverList = taggedSurfaceCandidates(
			source, holder -> !holder.is(BiomeTags.IS_RIVER)
		);
		if (nonRiverList != null) {
			nonRivers.put(source, nonRiverList);
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
