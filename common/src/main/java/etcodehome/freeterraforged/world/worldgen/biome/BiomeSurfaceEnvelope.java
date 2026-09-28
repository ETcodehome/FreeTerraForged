package etcodehome.freeterraforged.world.worldgen.biome;

import java.lang.ref.WeakReference;
import java.util.function.IntBinaryOperator;

import net.minecraft.core.QuartPos;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.TileCache;
import etcodehome.freeterraforged.world.worldgen.runtime.OwnerThreadCache;

/** Conservative terrain envelope for one stored biome cell, independent of its Y coordinate. */
public final class BiomeSurfaceEnvelope {
	// BiomeManager subtracts two blocks, then chooses either corner of a quart cube.
	// A cell at 4*q can therefore affect blocks [4*q-2, 4*q+5]. Include the adjacent
	// column as well: a solid face above that column's skyline is exterior too.
	static final int MIN_OFFSET = -3;
	static final int MAX_OFFSET = 6;
	static final int MAX_VERTICAL_OFFSET = 5;
	private final OwnerThreadCache<Entry> values = new OwnerThreadCache<>(1024);

	public int minimumSurfaceY(GeneratorContext context, int quartX, int quartZ) {
		int baseX = QuartPos.toBlock(quartX), baseZ = QuartPos.toBlock(quartZ);
		TileCache.Lease local = context.cache == null ? null : context.cache.acquireIfPresent(
			context.cache.chunkToTile(baseX >> 4), context.cache.chunkToTile(baseZ >> 4));
		try (local) {
			// Generation already owns this tile. Its filter halo covers the sampling
			// footprint without consulting neighbors whose residency is scheduler-dependent.
			var tile = local == null ? null : local.tile();
			boolean accurate = tile != null && tile.getBlockSize().border() >= MAX_OFFSET;
			Cell cell = new Cell();
			return this.minimumSurfaceY(context.lookup, accurate, quartX, quartZ, (x, z) -> {
				if (accurate) {
					int border = tile.getBlockSize().border();
					return Math.min(context.levels.worldHeight - 1,
						context.levels.scale(tile.getCellRaw(x - tile.getBlockX() + border,
							z - tile.getBlockZ() + border).height));
				}
				// Distant prediction/locate must not create terrain tiles. Use one coherent
				// unfiltered estimate, never a mixture dependent on partial tile residency.
				context.lookup.getHeightmap().apply(cell.reset(), x, z, false);
				return Math.min(context.levels.worldHeight - 1, context.levels.scale(cell.height));
			});
		}
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
