package raccoonman.reterraforged.world.worldgen;

import net.minecraft.world.level.chunk.ChunkAccess;

public class ActiveChunk {
	private static final ThreadLocal<ChunkAccess> VALUE_HOLDER = new ThreadLocal<>();
	
	public static void set(ChunkAccess chunk) {
		VALUE_HOLDER.set(chunk);
	}
	
	public static ChunkAccess get() {
		return VALUE_HOLDER.get();
	}
}
