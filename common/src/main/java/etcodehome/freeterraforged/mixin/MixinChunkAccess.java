package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.chunk.ChunkAccess;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;
import etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess implements IFlowFieldHolder, IChunkCellsHolder {
	@Unique
	@Nullable
	private volatile ChunkFlowField freeterraforged$flowField;

	@Unique
	@Nullable
	private volatile Cell[] freeterraforged$chunkCells;

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
	@Nullable
	public Cell[] freeterraforged$getChunkCells() {
		return this.freeterraforged$chunkCells;
	}

	@Override
	public void freeterraforged$setChunkCells(Cell[] cells) {
		this.freeterraforged$chunkCells = cells;
	}

	@Override
	public Cell[] freeterraforged$getOrCreateChunkCells(GeneratorContext context) {
		Cell[] current = this.freeterraforged$chunkCells;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			current = this.freeterraforged$chunkCells;
			if (current == null) {
				ChunkAccess chunk = (ChunkAccess) (Object) this;
				int chunkX = chunk.getPos().x;
				int chunkZ = chunk.getPos().z;
				current = ChunkFilterGrid.generateChunkCells(chunkX, chunkZ, context);
				this.freeterraforged$chunkCells = current;
			}
		}
		return current;
	}

	@Override
	public Cell freeterraforged$getCell(int localX, int localZ, GeneratorContext context) {
		Cell[] cells = this.freeterraforged$getOrCreateChunkCells(context);
		return cells[(localZ & 15) * 16 + (localX & 15)];
	}
}
