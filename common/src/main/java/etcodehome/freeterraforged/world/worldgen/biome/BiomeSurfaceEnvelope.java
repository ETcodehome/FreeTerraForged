package etcodehome.freeterraforged.world.worldgen.biome;

import java.lang.ref.WeakReference;
import java.util.function.IntBinaryOperator;

import net.minecraft.core.QuartPos;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.runtime.OwnerThreadCache;

public final class BiomeSurfaceEnvelope {
	// BiomeManager subtracts two blocks, then chooses either corner of a quart cube.
	// A cell at 4*q can therefore affect blocks [4*q-2, 4*q+5]. Include the adjacent
	// column as well: a solid face above that column's skyline is exterior too.
	static final int MIN_OFFSET = -3;
	static final int MAX_OFFSET = 6;
	static final int MAX_VERTICAL_OFFSET = 5;
	private final OwnerThreadCache<Entry> values = new OwnerThreadCache<>(1024);

	public int minimumSurfaceY(GeneratorContext context, int quartX, int quartZ) {
		Cell cell = new Cell();
		return this.minimumSurfaceY(context.lookup, true, quartX, quartZ, (x, z) -> {
			context.lookup.getHeightmap().apply(cell.reset(), x, z, false);
			return Math.min(context.levels.worldHeight - 1, context.levels.scale(cell.height));
		});
	}

	int minimumSurfaceY(WorldLookup lookup, boolean accurate, int quartX, int quartZ, IntBinaryOperator surface) {
		long key = ((long) quartX << 32) ^ (quartZ & 0xFFFFFFFFL);
		Entry cached = this.values.find(key);
		if (cached != null && cached.get() == lookup && cached.accurate == accurate) return cached.height;
		int height = minimumSurfaceY(quartX, quartZ, surface);
		this.values.store(key, new Entry(lookup, height, accurate));
		return height;
	}

	static int minimumSurfaceY(int quartX, int quartZ, IntBinaryOperator surface) {
		int baseX = QuartPos.toBlock(quartX);
		int baseZ = QuartPos.toBlock(quartZ);
		int minimum = Integer.MAX_VALUE;
		for (int dz = MIN_OFFSET; dz <= MAX_OFFSET; dz++) {
			for (int dx = MIN_OFFSET; dx <= MAX_OFFSET; dx++) {
				minimum = Math.min(minimum, surface.applyAsInt(baseX + dx, baseZ + dz));
			}
		}
		return minimum;
	}

	public void clear() {
		this.values.clear();
	}

	private static final class Entry extends WeakReference<WorldLookup> {
		private final int height;
		private final boolean accurate;
		private Entry(WorldLookup owner, int height, boolean accurate) {
			super(owner);
			this.height = height;
			this.accurate = accurate;
		}
	}
}
