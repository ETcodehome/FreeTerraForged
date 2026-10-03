package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;
import etcodehome.freeterraforged.world.worldgen.NoiseChunkHolder;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.NoiseChunkTileOwner;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess implements IFlowFieldHolder, NoiseChunkHolder {
	@Shadow @Nullable protected NoiseChunk noiseChunk;

	@Unique
	@Nullable
	private volatile ChunkFlowField freeterraforged$flowField;
	@Unique
	private int freeterraforged$tileStageDepth;
	@Unique
	private int freeterraforged$attachedTileStageDepth;

	@Override
	public ChunkFlowField freeterraforged$getFlowField() {
		return this.freeterraforged$flowField;
	}

	@Override
	public ChunkFlowField freeterraforged$getOrCreateFlowField() {
		ChunkFlowField current = this.freeterraforged$flowField;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			current = this.freeterraforged$flowField;
			if (current == null) {
				current = new ChunkFlowField();
				this.freeterraforged$flowField = current;
			}
		}
		return current;
	}

	@Override
	public NoiseChunk freeterraforged$getNoiseChunk() {
		return this.noiseChunk;
	}

	@Override
	public synchronized void freeterraforged$beginNoiseChunkTileStage() {
		this.freeterraforged$tileStageDepth++;
		try {
			this.freeterraforged$attachPendingTileStages();
		} catch (RuntimeException | Error failure) {
			this.freeterraforged$tileStageDepth--;
			throw failure;
		}
	}

	@Override
	public synchronized void freeterraforged$endNoiseChunkTileStage() {
		if (this.freeterraforged$tileStageDepth <= 0) {
			throw new IllegalStateException("NoiseChunk tile-stage ownership underflow");
		}
		this.freeterraforged$tileStageDepth--;
		if (this.freeterraforged$attachedTileStageDepth > this.freeterraforged$tileStageDepth) {
			this.freeterraforged$attachedTileStageDepth--;
			if (this.noiseChunk instanceof NoiseChunkTileOwner owner) {
				owner.freeterraforged$endTileStage();
			} else {
				throw new IllegalStateException("Attached NoiseChunk does not expose tile ownership");
			}
		}
	}

	@Inject(method = "getOrCreateNoiseChunk", at = @At("RETURN"))
	private synchronized void freeterraforged$attachCreatedNoiseChunk(
		java.util.function.Function<ChunkAccess, NoiseChunk> factory,
		CallbackInfoReturnable<NoiseChunk> callback
	) {
		this.freeterraforged$attachPendingTileStages();
	}

	@Unique
	private void freeterraforged$attachPendingTileStages() {
		if (!(this.noiseChunk instanceof NoiseChunkTileOwner owner)) {
			return;
		}
		int initialDepth = this.freeterraforged$attachedTileStageDepth;
		try {
			while (this.freeterraforged$attachedTileStageDepth < this.freeterraforged$tileStageDepth) {
				owner.freeterraforged$beginTileStage();
				this.freeterraforged$attachedTileStageDepth++;
			}
		} catch (RuntimeException | Error failure) {
			while (this.freeterraforged$attachedTileStageDepth > initialDepth) {
				this.freeterraforged$attachedTileStageDepth--;
				try {
					owner.freeterraforged$endTileStage();
				} catch (RuntimeException | Error cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
	}

}
