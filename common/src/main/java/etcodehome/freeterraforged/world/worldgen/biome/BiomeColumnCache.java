package etcodehome.freeterraforged.world.worldgen.biome;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;

public final class BiomeColumnCache {
    public int quartX = Integer.MIN_VALUE;
    public int quartZ = Integer.MIN_VALUE;
    public long regionX;
    public long regionZ;
    public final Cell scratch = new Cell();

    private static final ThreadLocal<BiomeColumnCache> THREAD_LOCAL = ThreadLocal.withInitial(BiomeColumnCache::new);

    public static BiomeColumnCache get() {
        return THREAD_LOCAL.get();
    }
}
