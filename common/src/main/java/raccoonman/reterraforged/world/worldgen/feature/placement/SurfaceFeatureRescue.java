package raccoonman.reterraforged.world.worldgen.feature.placement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import raccoonman.reterraforged.world.worldgen.feature.placement.DynamicHeightRangePlacement.HeightBand;
import raccoonman.reterraforged.world.worldgen.feature.placement.SurfacePlacementClassifier.Classification;
import raccoonman.reterraforged.world.worldgen.feature.placement.SurfacePlacementClassifier.SurfacePipeline;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

/**
 * Gives a conventional surface placement its original chance first, then
 * redirects a scaled subset of failed scans to eligible surfaces in the same
 * X/Z column and dynamic-height band. Counts up to eight retain every failed
 * opportunity; larger counts receive eight plus a logarithmic tail.
 */
public final class SurfaceFeatureRescue {
	private static final int UNSCALED_RESCUE_THRESHOLD = 8;
	private static final Frame INACTIVE = new Frame(null);
	private static final ThreadLocal<Deque<Frame>> ACTIVE_FEATURES = new ThreadLocal<>();

	private SurfaceFeatureRescue() {
	}

	public static boolean begin(PlacedFeature feature, PlacementContext context) {
		if (!(context.generator() instanceof TerraForgedChunkGenerator generator)) {
			return false;
		}
		Run run = null;
		Classification classification = generator.plan()
			.map(plan -> plan.placedFeatures().surfaceClassification(feature))
			.orElseGet(Classification::rejected);
		if (classification.eligible()) {
			Optional<UndergroundFeatureEnclosure.Guard> enclosure = UndergroundFeatureEnclosure.create(context);
			if (enclosure.isPresent()) {
				run = new Run(feature, context, classification.pipeline(), enclosure.get());
			}
		}
		Deque<Frame> stack = ACTIVE_FEATURES.get();
		if (stack == null) {
			stack = new ArrayDeque<>();
			ACTIVE_FEATURES.set(stack);
		}
		stack.push(run == null ? INACTIVE : new Frame(run));
		return true;
	}

	public static void finish(boolean entered) {
		if (!entered) {
			return;
		}
		Deque<Frame> stack = ACTIVE_FEATURES.get();
		if (stack == null || stack.isEmpty()) {
			throw new IllegalStateException("Surface-feature rescue scope underflow");
		}
		stack.pop();
		if (stack.isEmpty()) {
			ACTIVE_FEATURES.remove();
		}
	}

	public static void recordCount(CountPlacement placement, int count) {
		Run run = activeRun();
		if (run != null) {
			run.recordCount(placement, count);
		}
	}

	public static Stream<BlockPos> rescue(
		EnvironmentScanPlacement scan,
		BlockPos originalOrigin,
		Stream<BlockPos> original
	) {
		Run run = activeRun();
		if (run == null || run.pipeline.scan() != scan) {
			return original;
		}
		Optional<BlockPos> originalResult = original.findFirst();
		return originalResult.isPresent()
			? originalResult.stream()
			: run.rescue(originalOrigin).stream();
	}

	private static Run activeRun() {
		Deque<Frame> stack = ACTIVE_FEATURES.get();
		return stack == null || stack.isEmpty() ? null : stack.peek().run();
	}

	private static int scaledAttempts(int count) {
		if (count <= UNSCALED_RESCUE_THRESHOLD) {
			return count;
		}
		int thresholdMultiples = (count - 1) / UNSCALED_RESCUE_THRESHOLD + 1;
		int logarithmicTail = 32 - Integer.numberOfLeadingZeros(thresholdMultiples - 1);
		return UNSCALED_RESCUE_THRESHOLD + logarithmicTail;
	}

	private record Frame(Run run) {
	}

	private static final class Run {
		private final PlacedFeature feature;
		private final PlacementContext context;
		private final SurfacePipeline pipeline;
		private final UndergroundFeatureEnclosure.Guard enclosure;
		private final Map<ColumnBand, long[]> surfaceCache = new HashMap<>();
		private final Map<HeightBand, BandBudget> bandBudgets = new HashMap<>();
		private final LongSet reservedPlacementCells = new LongOpenHashSet();
		private int sampledCount;

		private Run(
			PlacedFeature feature,
			PlacementContext context,
			SurfacePipeline pipeline,
			UndergroundFeatureEnclosure.Guard enclosure
		) {
			this.feature = feature;
			this.context = context;
			this.pipeline = pipeline;
			this.enclosure = enclosure;
			this.sampledCount = pipeline.countPlacement() == null ? 1 : -1;
		}

		private void recordCount(CountPlacement placement, int count) {
			if (this.pipeline.countPlacement() == placement) {
				this.sampledCount = Math.max(0, count);
			}
		}

		private Optional<BlockPos> rescue(BlockPos originalOrigin) {
			if (this.sampledCount <= 0) {
				return Optional.empty();
			}

			int minY = this.context.getMinGenY();
			int maxY = minY + this.context.getGenDepth() - 1;
			int originY = originalOrigin.getY();
			if (originY < minY || originY > maxY) {
				return Optional.empty();
			}

			HeightBand band = DynamicHeightRangePlacement.bandContaining(minY, maxY, originY);
			if (!this.bandBudgets
				.computeIfAbsent(band, ignored -> new BandBudget(this.sampledCount))
				.shouldSearch()) {
				return Optional.empty();
			}

			ColumnBand key = new ColumnBand(
				originalOrigin.getX(),
				originalOrigin.getZ(),
				band.minInclusive(),
				band.maxInclusive()
			);
			long[] surfaces = this.surfaceCache.computeIfAbsent(key, this::scan);
			if (surfaces.length == 0) {
				return Optional.empty();
			}

			LongArrayList eligibleSurfaces = new LongArrayList(surfaces.length);
			BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
			BlockPos.MutableBlockPos placement = new BlockPos.MutableBlockPos();
			for (long packed : surfaces) {
				target.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
				placement.set(
					target.getX(),
					target.getY() + this.pipeline.placementOffsetY(),
					target.getZ()
				);
				if (!this.reservedPlacementCells.contains(placement.asLong())
					&& this.isStillEligible(target, placement)
					&& this.enclosure.isProtected(placement)) {
					eligibleSurfaces.add(packed);
				}
			}
			if (eligibleSurfaces.isEmpty()) {
				return Optional.empty();
			}

			int selectedIndex = Math.floorMod(
				originalOrigin.getY() - band.minInclusive(),
				eligibleSurfaces.size()
			);
			BlockPos selectedTarget = BlockPos.of(eligibleSurfaces.getLong(selectedIndex));
			int placementY = selectedTarget.getY() + this.pipeline.placementOffsetY();
			this.reservedPlacementCells.add(BlockPos.asLong(
				selectedTarget.getX(),
				placementY,
				selectedTarget.getZ()
			));
			return Optional.of(selectedTarget);
		}

		private long[] scan(ColumnBand key) {
			LongArrayList result = new LongArrayList();
			BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
			BlockPos.MutableBlockPos placement = new BlockPos.MutableBlockPos();
			for (int targetY = key.minInclusive; targetY <= key.maxInclusive; targetY++) {
				int placementY = targetY + this.pipeline.placementOffsetY();
				if (placementY < key.minInclusive || placementY > key.maxInclusive) {
					continue;
				}
				target.set(key.x, targetY, key.z);
				placement.set(key.x, placementY, key.z);
				if (this.isStillEligible(target, placement)) {
					result.add(target.asLong());
				}
			}
			return result.toLongArray();
		}

		private boolean isStillEligible(BlockPos target, BlockPos placement) {
			if (!this.pipeline.target().test(this.context.getLevel(), target)
				|| !this.pipeline.allowed().test(this.context.getLevel(), placement)) {
				return false;
			}
			for (PlacementModifier modifier : this.pipeline.downstreamFilters()) {
				if (modifier.getPositions(
					this.context,
					net.minecraft.util.RandomSource.create(0L),
					placement
				).findAny().isEmpty()) {
					return false;
				}
			}
			return true;
		}
	}

	private record ColumnBand(int x, int z, int minInclusive, int maxInclusive) {
	}

	private static final class BandBudget {
		private final long count;
		private final long searches;
		private long accumulator;

		private BandBudget(int count) {
			this.count = Math.max(1, count);
			this.searches = scaledAttempts(count);
			this.accumulator = this.count / 2;
		}

		private boolean shouldSearch() {
			this.accumulator += this.searches;
			if (this.accumulator < this.count) {
				return false;
			}
			this.accumulator -= this.count;
			return true;
		}
	}
}
