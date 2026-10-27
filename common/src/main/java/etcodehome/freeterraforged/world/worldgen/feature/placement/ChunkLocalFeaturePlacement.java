package etcodehome.freeterraforged.world.worldgen.feature.placement;

import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import etcodehome.freeterraforged.world.worldgen.feature.placement.ChunkLocalPlacementClassifier.ChunkConfinement;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlan;

public final class ChunkLocalFeaturePlacement {
	private static final ThreadLocal<State> ACTIVE = ThreadLocal.withInitial(State::new);

	private ChunkLocalFeaturePlacement() {
	}

	public static boolean begin(PlacedFeature feature, PlacementContext context, BlockPos origin) {
		if (!(context.generator() instanceof TerraForgedChunkGenerator generator)) {
			return false;
		}
		State state = ACTIVE.get();
		if (state.depth > 0) {
			state.depth++;
			return true;
		}
		state.depth = 1;
		WorldgenPlan plan = generator.plan().orElse(null);
		ChunkLocalPlacementClassifier.Classification classification = plan != null
			? plan.placedFeatures().chunkLocalClassification(feature)
			: ChunkLocalPlacementClassifier.Classification.rejected();
		if (classification.eligible()) {
			state.confinement = classification.confinement();
			state.rootMinX = (origin.getX() >> 4) << 4;
			state.rootMinZ = (origin.getZ() >> 4) << 4;
		} else {
			state.confinement = null;
		}
		return true;
	}

	public static void finish(boolean entered) {
		if (!entered) {
			return;
		}
		State state = ACTIVE.get();
		if (state.depth <= 0) {
			throw new IllegalStateException("Chunk-local placement scope underflow");
		}
		state.depth--;
	}

	public static Stream<BlockPos> constrain(
		RandomOffsetPlacement placement,
		Stream<BlockPos> positions
	) {
		State state = ACTIVE.get();
		if (!state.active() || state.depth == 0
			|| !state.confinement.contains(placement)) {
			return positions;
		}
		int minX = state.rootMinX;
		int minZ = state.rootMinZ;
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
		private ChunkConfinement confinement;
		private int rootMinX;
		private int rootMinZ;
		private int depth;

		private boolean active() {
			return this.confinement != null;
		}
	}
}
