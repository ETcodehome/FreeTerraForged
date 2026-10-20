package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.chunk.ChunkAccess;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess implements IFlowFieldHolder {
	@Unique
	@Nullable
	private volatile ChunkFlowField freeterraforged$flowField;

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
}
