package raccoonman.reterraforged.world.worldgen.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/** Exact bounded request-worker memoization; hash collisions miss rather than aliasing a quart. */
final class PreviewQuartCache {
	private static final int CAPACITY = 256;
	private static final int MASK = CAPACITY - 1;

	private final int[] quartX = new int[CAPACITY];
	private final int[] quartY = new int[CAPACITY];
	private final int[] quartZ = new int[CAPACITY];
	@SuppressWarnings("unchecked")
	private final Holder<Biome>[] values = new Holder[CAPACITY];

	Holder<Biome> get(int x, int y, int z) {
		int slot = slot(x, y, z);
		Holder<Biome> value = this.values[slot];
		return value != null
			&& this.quartX[slot] == x
			&& this.quartY[slot] == y
			&& this.quartZ[slot] == z
				? value
				: null;
	}

	void put(int x, int y, int z, Holder<Biome> value) {
		int slot = slot(x, y, z);
		this.quartX[slot] = x;
		this.quartY[slot] = y;
		this.quartZ[slot] = z;
		this.values[slot] = value;
	}

	private static int slot(int x, int y, int z) {
		int hash = x * 0x9E3779B9;
		hash = Integer.rotateLeft(hash, 11) ^ y * 0x85EBCA6B;
		hash = Integer.rotateLeft(hash, 13) ^ z * 0xC2B2AE35;
		hash ^= hash >>> 16;
		return hash & MASK;
	}
}
