package etcodehome.freeterraforged.world.worldgen.search;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.mojang.datafixers.util.Pair;
import org.jetbrains.annotations.Nullable;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.ReferenceWeirdness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenBiomeSelection;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Proves when a raw cell is already authoritative or can be safely rejected for one standard FTF biome-locate query. */
final class BiomeLocateCellOptimization {
	private static final ResourceLocation FTF_SELECTION_POLICY = FTFCommon.location("ftf_selection_policy");
	private static final long MAX_GRID_ROUNDING_DIST_SQ = 25L;
	private static final int MIN_FULL_OVERWORLD_PARAMETER_COUNT = 7000;

	private final WorldgenBiomeSelection.Executable selection;
	private final GeneratorContext context;
	private final Set<CellSampler.Field> sampledFields;
	private final boolean enabled;

	private BiomeLocateCellOptimization(
		WorldgenBiomeSelection.Executable selection,
		GeneratorContext context,
		Set<CellSampler.Field> sampledFields,
		boolean enabled
	) {
		this.selection = selection;
		this.context = context;
		this.sampledFields = Set.copyOf(sampledFields);
		this.enabled = enabled;
	}

	static BiomeLocateCellOptimization compile(
		WorldgenBiomeSelection.Executable selection,
		GeneratorContext context,
		Climate.Sampler sampler
	) {
		var plan = selection.plan();
		boolean standardSelection = plan.providerSelection().directInput().isEmpty()
			&& plan.samplerDecoration().stages().isEmpty()
			&& plan.selectionDecoration().stages().size() == 1
			&& plan.selectionDecoration().stages().getFirst().id().equals(FTF_SELECTION_POLICY);
		EnumSet<CellSampler.Field> sampledFields = EnumSet.noneOf(CellSampler.Field.class);
		collect(sampledFields, sampler.temperature());
		collect(sampledFields, sampler.humidity());
		collect(sampledFields, sampler.continentalness());
		collect(sampledFields, sampler.erosion());
		collect(sampledFields, sampler.depth());
		collect(sampledFields, sampler.weirdness());
		return new BiomeLocateCellOptimization(selection, context, sampledFields, standardSelection);
	}

	boolean canUseRaw(Cell cell) {
		return this.enabled && (this.context.filters == null
			|| this.context.filters.preservesRawBiomeLocateInputs(cell, this.sampledFields));
	}

	boolean enabled() {
		return this.enabled;
	}

	@Nullable
	TargetFilter forTarget(Predicate<Holder<Biome>> target, BiomeLocateSearch.SearchPlan searchPlan) {
		if (!this.enabled
			|| !searchPlan.roles().searchesSurface()
			|| searchPlan.roles().searchesUnderground()
			|| this.sampledFields.contains(CellSampler.Field.GRADIENT)
			|| this.sampledFields.contains(CellSampler.Field.HEIGHT_EROSION)
			|| this.sampledFields.contains(CellSampler.Field.SEDIMENT)) {
			return null;
		}

		boolean anyMatch = false;
		boolean allMatchingAreRivers = true;
		boolean anyMatchingIsRiver = false;
		for (Holder<Biome> biome : this.selection.possibleBiomes()) {
			if (target.test(biome)) {
				anyMatch = true;
				boolean isRiver = biome.is(BiomeTags.IS_RIVER);
				allMatchingAreRivers &= isRiver;
				anyMatchingIsRiver |= isRiver;
			}
		}
		if (!anyMatch) {
			return null;
		}

		WorldgenPlans.ProviderSelection providers = this.selection.plan().providerSelection();
		BiomeReplacementPlan replacement = this.selection.plan().biomeReplacement().plan();
		Holder<Biome> deferredPlaceholder = providers.deferredPlaceholder().orElse(null);

		if (allMatchingAreRivers && hasNonRiverSurfaceCandidatesInAllDomains(providers)
			&& !anyNonRiverReplacedByTarget(providers, replacement, deferredPlaceholder, target)) {
			return TargetFilter.riverOnly(this.context);
		}

		if (!anyMatchingIsRiver && hasFullSurfaceGridCoverage(providers)) {
			List<Climate.ParameterPoint> targetPoints = collectTargetSurfacePoints(
				providers, replacement, deferredPlaceholder, target
			);
			if (!targetPoints.isEmpty()) {
				return TargetFilter.parameterBound(this.context, targetPoints);
			}
		}
		return null;
	}

	private static boolean hasNonRiverSurfaceCandidatesInAllDomains(WorldgenPlans.ProviderSelection providers) {
		for (WorldgenPlans.ProviderDomain domain : providers.providers()) {
			if (!hasNonRiverSurfaceCandidate(domain.candidates())) {
				return false;
			}
		}
		if (providers.fallback().isPresent() && !hasNonRiverSurfaceCandidate(providers.fallback().get())) {
			return false;
		}
		return !providers.providers().isEmpty();
	}

	private static boolean hasNonRiverSurfaceCandidate(Climate.ParameterList<Holder<Biome>> list) {
		for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : list.values()) {
			Climate.Parameter depth = entry.getFirst().depth();
			if (depth.min() <= 0L && depth.max() >= 0L && !entry.getSecond().is(BiomeTags.IS_RIVER)) {
				return true;
			}
		}
		return false;
	}

	private static boolean anyNonRiverReplacedByTarget(
		WorldgenPlans.ProviderSelection providers,
		BiomeReplacementPlan replacement,
		@Nullable Holder<Biome> deferredPlaceholder,
		Predicate<Holder<Biome>> target
	) {
		for (WorldgenPlans.ProviderDomain domain : providers.providers()) {
			for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : domain.candidates().values()) {
				Holder<Biome> base = entry.getSecond();
				if (deferredPlaceholder != null && deferredPlaceholder.equals(base)) {
					continue;
				}
				if (!base.is(BiomeTags.IS_RIVER) && target.test(replacement.apply(base, base))) {
					return true;
				}
			}
		}
		if (providers.fallback().isPresent()) {
			for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : providers.fallback().get().values()) {
				Holder<Biome> base = entry.getSecond();
				Holder<Biome> replaced = replacement.apply(base, deferredPlaceholder != null ? deferredPlaceholder : base);
				if (!base.is(BiomeTags.IS_RIVER) && target.test(replaced)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean hasFullSurfaceGridCoverage(WorldgenPlans.ProviderSelection providers) {
		if (providers.providers().isEmpty()) {
			return false;
		}
		for (WorldgenPlans.ProviderDomain domain : providers.providers()) {
			if (domain.candidates().values().size() < MIN_FULL_OVERWORLD_PARAMETER_COUNT) {
				return false;
			}
		}
		return providers.fallback().isEmpty()
			|| providers.fallback().get().values().size() >= MIN_FULL_OVERWORLD_PARAMETER_COUNT;
	}

	private static List<Climate.ParameterPoint> collectTargetSurfacePoints(
		WorldgenPlans.ProviderSelection providers,
		BiomeReplacementPlan replacement,
		@Nullable Holder<Biome> deferredPlaceholder,
		Predicate<Holder<Biome>> target
	) {
		List<Climate.ParameterPoint> points = new ArrayList<>();
		for (WorldgenPlans.ProviderDomain domain : providers.providers()) {
			for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : domain.candidates().values()) {
				Climate.ParameterPoint point = entry.getFirst();
				if (point.depth().min() > 0L) {
					continue;
				}
				Holder<Biome> base = entry.getSecond();
				if (deferredPlaceholder != null && deferredPlaceholder.equals(base)) {
					continue;
				}
				if (target.test(replacement.apply(base, base))) {
					points.add(point);
				}
			}
		}
		if (providers.fallback().isPresent()) {
			for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : providers.fallback().get().values()) {
				Climate.ParameterPoint point = entry.getFirst();
				if (point.depth().min() > 0L) {
					continue;
				}
				Holder<Biome> base = entry.getSecond();
				Holder<Biome> replaced = replacement.apply(base, deferredPlaceholder != null ? deferredPlaceholder : base);
				if (target.test(replaced)) {
					points.add(point);
				}
			}
		}
		return points;
	}

	void assertEquivalent(Cell raw, Cell filtered, int blockX, int blockZ) {
		assertEqual(blockX, blockZ, "height", raw.height, filtered.height);
		if (raw.biomeRegionX != filtered.biomeRegionX || raw.biomeRegionZ != filtered.biomeRegionZ) {
			throw mismatch(blockX, blockZ, "biome-region ownership", raw, filtered);
		}
		if (raw.terrain.isRiver() != filtered.terrain.isRiver()) {
			throw mismatch(blockX, blockZ, "river terrain role", raw.terrain, filtered.terrain);
		}
		for (CellSampler.Field field : this.sampledFields) {
			assertEqual(
				blockX,
				blockZ,
				field.getSerializedName(),
				field.readFinite(raw, this.context.heightmap),
				field.readFinite(filtered, this.context.heightmap)
			);
		}
	}

	private static void collect(EnumSet<CellSampler.Field> fields, DensityFunction root) {
		root.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if (function instanceof CellSampler sampler) {
					fields.add(sampler.field());
				} else if (function instanceof CellSampler.CacheChunk sampler) {
					fields.add(sampler.field());
				}
				return function;
			}
		});
	}

	private static void assertEqual(
		int blockX,
		int blockZ,
		String field,
		float expected,
		float actual
	) {
		if (Float.floatToIntBits(expected) != Float.floatToIntBits(actual)) {
			throw mismatch(blockX, blockZ, field, expected, actual);
		}
	}

	private static AssertionError mismatch(
		int blockX,
		int blockZ,
		String field,
		Object expected,
		Object actual
	) {
		return new AssertionError(
			"Raw biome-locate optimization disagrees with filtered terrain at " + blockX + "," + blockZ
				+ " for " + field + ": expected " + expected + ", got " + actual
		);
	}

	static final class TargetFilter {
		private static final ThreadLocal<Cell> PROBE_CELL = ThreadLocal.withInitial(Cell::new);
		private static final int[][] TILE_QUADRANT_OFFSETS = {
			{ 16, 16 },
			{ 48, 16 },
			{ 16, 48 },
			{ 48, 48 }
		};

		private final GeneratorContext context;
		private final boolean riverOnly;
		private final long envMinT;
		private final long envMaxT;
		private final long envMinH;
		private final long envMaxH;
		private final long envMinC;
		private final long envMaxC;
		private final long envMinE;
		private final long envMaxE;
		private final long envMinW;
		private final long envMaxW;
		private final long minOffsetSq;
		private final long[] pMinT;
		private final long[] pMaxT;
		private final long[] pMinH;
		private final long[] pMaxH;
		private final long[] pMinC;
		private final long[] pMaxC;
		private final long[] pMinE;
		private final long[] pMaxE;
		private final long[] pMinW;
		private final long[] pMaxW;
		private final long[] pOffsetSq;

		private TargetFilter(
			GeneratorContext context,
			boolean riverOnly,
			long envMinT,
			long envMaxT,
			long envMinH,
			long envMaxH,
			long envMinC,
			long envMaxC,
			long envMinE,
			long envMaxE,
			long envMinW,
			long envMaxW,
			long minOffsetSq,
			long[] pMinT,
			long[] pMaxT,
			long[] pMinH,
			long[] pMaxH,
			long[] pMinC,
			long[] pMaxC,
			long[] pMinE,
			long[] pMaxE,
			long[] pMinW,
			long[] pMaxW,
			long[] pOffsetSq
		) {
			this.context = context;
			this.riverOnly = riverOnly;
			this.envMinT = envMinT;
			this.envMaxT = envMaxT;
			this.envMinH = envMinH;
			this.envMaxH = envMaxH;
			this.envMinC = envMinC;
			this.envMaxC = envMaxC;
			this.envMinE = envMinE;
			this.envMaxE = envMaxE;
			this.envMinW = envMinW;
			this.envMaxW = envMaxW;
			this.minOffsetSq = minOffsetSq;
			this.pMinT = pMinT;
			this.pMaxT = pMaxT;
			this.pMinH = pMinH;
			this.pMaxH = pMaxH;
			this.pMinC = pMinC;
			this.pMaxC = pMaxC;
			this.pMinE = pMinE;
			this.pMaxE = pMaxE;
			this.pMinW = pMinW;
			this.pMaxW = pMaxW;
			this.pOffsetSq = pOffsetSq;
		}

		static TargetFilter riverOnly(GeneratorContext context) {
			long[] empty = new long[0];
			return new TargetFilter(
				context, true,
				0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty
			);
		}

		static TargetFilter parameterBound(GeneratorContext context, List<Climate.ParameterPoint> points) {
			int n = points.size();
			long[] pMinT = new long[n];
			long[] pMaxT = new long[n];
			long[] pMinH = new long[n];
			long[] pMaxH = new long[n];
			long[] pMinC = new long[n];
			long[] pMaxC = new long[n];
			long[] pMinE = new long[n];
			long[] pMaxE = new long[n];
			long[] pMinW = new long[n];
			long[] pMaxW = new long[n];
			long[] pOffsetSq = new long[n];

			long envMinT = Long.MAX_VALUE, envMaxT = Long.MIN_VALUE;
			long envMinH = Long.MAX_VALUE, envMaxH = Long.MIN_VALUE;
			long envMinC = Long.MAX_VALUE, envMaxC = Long.MIN_VALUE;
			long envMinE = Long.MAX_VALUE, envMaxE = Long.MIN_VALUE;
			long envMinW = Long.MAX_VALUE, envMaxW = Long.MIN_VALUE;
			long minOffsetSq = Long.MAX_VALUE;

			for (int i = 0; i < n; i++) {
				Climate.ParameterPoint p = points.get(i);
				pMinT[i] = p.temperature().min();
				pMaxT[i] = p.temperature().max();
				pMinH[i] = p.humidity().min();
				pMaxH[i] = p.humidity().max();
				pMinC[i] = p.continentalness().min();
				pMaxC[i] = p.continentalness().max();
				pMinE[i] = p.erosion().min();
				pMaxE[i] = p.erosion().max();
				pMinW[i] = p.weirdness().min();
				pMaxW[i] = p.weirdness().max();
				pOffsetSq[i] = Mth.square(p.offset());

				if (pMinT[i] < envMinT) envMinT = pMinT[i];
				if (pMaxT[i] > envMaxT) envMaxT = pMaxT[i];
				if (pMinH[i] < envMinH) envMinH = pMinH[i];
				if (pMaxH[i] > envMaxH) envMaxH = pMaxH[i];
				if (pMinC[i] < envMinC) envMinC = pMinC[i];
				if (pMaxC[i] > envMaxC) envMaxC = pMaxC[i];
				if (pMinE[i] < envMinE) envMinE = pMinE[i];
				if (pMaxE[i] > envMaxE) envMaxE = pMaxE[i];
				if (pMinW[i] < envMinW) envMinW = pMinW[i];
				if (pMaxW[i] > envMaxW) envMaxW = pMaxW[i];
				if (pOffsetSq[i] < minOffsetSq) minOffsetSq = pOffsetSq[i];
			}

			return new TargetFilter(
				context, false,
				envMinT, envMaxT, envMinH, envMaxH, envMinC, envMaxC, envMinE, envMaxE, envMinW, envMaxW, minOffsetSq,
				pMinT, pMaxT, pMinH, pMaxH, pMinC, pMaxC, pMinE, pMaxE, pMinW, pMaxW, pOffsetSq
			);
		}

		boolean requiresClimateForRawPrecheck() {
			return !this.riverOnly;
		}

		boolean canSkipTile(ParallelFirstSearch.Tile tile) {
			if (!this.riverOnly) {
				return false;
			}
			Cell probe = PROBE_CELL.get();
			int minX = tile.minBlockX();
			int minZ = tile.minBlockZ();
			for (int[] offset : TILE_QUADRANT_OFFSETS) {
				this.context.heightmap.apply(probe.reset(), minX + offset[0], minZ + offset[1], false);
				if (probe.riverMask < 1.0F
					|| probe.terrain.isRiver()
					|| probe.terrain.isWetland()
					|| probe.terrain.isLake()) {
					return false;
				}
			}
			return true;
		}

		boolean canMatchRaw(Cell raw) {
			boolean isHydrology = raw.terrain.isRiver() || raw.terrain.isWetland() || raw.terrain.isLake();
			if (this.riverOnly) {
				return isHydrology;
			}
			if (isHydrology) {
				return true;
			}

			long qT = Climate.quantizeCoord(raw.temperature);
			long qH = Climate.quantizeCoord(raw.moisture);
			long qE = Climate.quantizeCoord(raw.erosion);
			long qC = Climate.quantizeCoord(raw.continentalness);
			long cMin = qC;
			long cMax = qC;
			if (raw.controlPoint <= this.context.heightmap.controlPoints().beach
				|| raw.terrain == TerrainType.MUSHROOM_FIELDS) {
				cMin = Math.min(-12000L, qC);
				cMax = Math.max(-1850L, qC);
			}

			float w1 = ReferenceWeirdness.fromSediment(-1.0F, raw.macroBiomeId);
			float w2 = ReferenceWeirdness.fromSediment(1.0F, raw.macroBiomeId);
			long wMin = Climate.quantizeCoord(Math.min(w1, w2));
			long wMax = Climate.quantizeCoord(Math.max(w1, w2));
			boolean isMountain = raw.terrain.isMountain() || raw.terrain == TerrainType.ISLAND_MOUNTAINS;
			boolean isCoast = !isMountain && raw.terrain != TerrainType.MUSHROOM_FIELDS && (
				raw.terrain.isCoast()
				|| raw.terrain == TerrainType.BEACH
				|| raw.terrain == TerrainType.ISLAND_BEACH
				|| raw.controlPoint <= this.context.heightmap.controlPoints().coast
			);
			if (isCoast) {
				wMin = Math.min(wMin, -1500L);
				wMax = Math.max(wMax, 0L);
			} else if (raw.height <= this.context.levels.ground(16)) {
				wMin = Math.min(wMin, -550L);
				wMax = Math.max(wMax, 550L);
			}

			long envDistSq = Mth.square(intervalDist(this.envMinT, this.envMaxT, qT, qT))
				+ Mth.square(intervalDist(this.envMinH, this.envMaxH, qH, qH))
				+ Mth.square(intervalDist(this.envMinC, this.envMaxC, cMin, cMax))
				+ Mth.square(intervalDist(this.envMinE, this.envMaxE, qE, qE))
				+ Mth.square(intervalDist(this.envMinW, this.envMaxW, wMin, wMax))
				+ this.minOffsetSq;
			if (envDistSq > MAX_GRID_ROUNDING_DIST_SQ) {
				return false;
			}

			for (int i = 0; i < this.pMinT.length; i++) {
				long d = Mth.square(intervalDist(this.pMinT[i], this.pMaxT[i], qT, qT))
					+ Mth.square(intervalDist(this.pMinH[i], this.pMaxH[i], qH, qH))
					+ Mth.square(intervalDist(this.pMinC[i], this.pMaxC[i], cMin, cMax))
					+ Mth.square(intervalDist(this.pMinE[i], this.pMaxE[i], qE, qE))
					+ Mth.square(intervalDist(this.pMinW[i], this.pMaxW[i], wMin, wMax))
					+ this.pOffsetSq[i];
				if (d <= MAX_GRID_ROUNDING_DIST_SQ) {
					return true;
				}
			}
			return false;
		}

		private static long intervalDist(long pMin, long pMax, long min, long max) {
			if (max < pMin) {
				return pMin - max;
			}
			if (min > pMax) {
				return min - pMax;
			}
			return 0L;
		}
	}
}
