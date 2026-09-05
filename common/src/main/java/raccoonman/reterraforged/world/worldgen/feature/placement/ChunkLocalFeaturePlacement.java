package raccoonman.reterraforged.world.worldgen.feature.placement;

import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import raccoonman.reterraforged.world.worldgen.feature.placement.ChunkLocalPlacementClassifier.ChunkConfinement;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

public final class ChunkLocalFeaturePlacement {
	private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

	private ChunkLocalFeaturePlacement() {
	}

	public static boolean begin(PlacedFeature feature, PlacementContext context, BlockPos origin) {
		if (!(context.generator() instanceof TerraForgedChunkGenerator generator)) {
			return false;
		}
		State state = ACTIVE.get();
		if (state != null) {
			state.depth++;
			return true;
		}
		ChunkLocalPlacementClassifier.Classification classification = generator.plan()
			.map(plan -> plan.placedFeatures().chunkLocalClassification(feature))
			.orElseGet(ChunkLocalPlacementClassifier.Classification::rejected);
		ACTIVE.set(classification.eligible()
			? new State(classification.confinement(), new ChunkPos(origin))
			: new State(null, null));
		return true;
	}

	public static void finish(boolean entered) {
		if (!entered) {
			return;
		}
		State state = ACTIVE.get();
		if (state == null) {
			throw new IllegalStateException("Chunk-local placement scope underflow");
		}
		if (state.depth-- == 0) {
			ACTIVE.remove();
		}
	}

	public static Stream<BlockPos> constrain(
		RandomOffsetPlacement placement,
		Stream<BlockPos> positions
	) {
		State state = ACTIVE.get();
		if (state == null || !state.active() || state.depth == 0
			|| !state.confinement.contains(placement)) {
			return positions;
		}
		int minX = state.root.getMinBlockX();
		int minZ = state.root.getMinBlockZ();
		return positions.map(position -> new BlockPos(
			wrap(position.getX(), minX),
			position.getY(),
			wrap(position.getZ(), minZ)
		));
	}

	static int wrap(int coordinate, int minimum) {
		return minimum + Math.floorMod(coordinate - minimum, 16);
	}

	private static final class State {
		private final ChunkConfinement confinement;
		private final ChunkPos root;
		private int depth;

		private State(ChunkConfinement confinement, ChunkPos root) {
			this.confinement = confinement;
			this.root = root;
		}

		private boolean active() {
			return this.confinement != null;
		}
	}
}
