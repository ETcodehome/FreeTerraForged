package etcodehome.freeterraforged.world.worldgen.biome;

final class PreviewQuartCache<T> {
	private static final int CAPACITY = 256;
	private static final int MASK = CAPACITY - 1;

	private final int[] quartX = new int[CAPACITY];
	private final int[] quartY = new int[CAPACITY];
	private final int[] quartZ = new int[CAPACITY];
	private final Object[] values = new Object[CAPACITY];

	@SuppressWarnings("unchecked")
	T get(int x, int y, int z) {
		int slot = slot(x, y, z);
		Object value = this.values[slot];
		return value != null
			&& this.quartX[slot] == x
			&& this.quartY[slot] == y
			&& this.quartZ[slot] == z
				? (T) value
				: null;
	}

	void put(int x, int y, int z, T value) {
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
