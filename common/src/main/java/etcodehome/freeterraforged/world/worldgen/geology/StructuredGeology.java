package etcodehome.freeterraforged.world.worldgen.geology;

import etcodehome.freeterraforged.data.worldgen.preset.settings.GeologySettings;

/** Depositional cover truncates older folded beds; faults offset both, then dikes cross-cut them. */
public final class StructuredGeology implements GeologySampler {
    private final GeologySampler basement, cover;
    private final long seed;
    private final double scale;
    private final int seaLevel;
    private final GeologySettings settings;
    private final ThreadLocal<Column[]> columns = ThreadLocal.withInitial(() -> new Column[512]);

    public StructuredGeology(GeologySampler basement, GeologySampler cover, long seed, int regionSize,
                             int seaLevel, GeologySettings settings) {
        this.basement = basement;
        this.cover = cover;
        this.seed = seed;
        this.scale = Math.max(128, regionSize);
        this.seaLevel = seaLevel;
        this.settings = settings;
    }

    /** Shared construction for generation and preview/regression sampling. */
    public static StructuredGeology create(long seed, int regionSize, int seaLevel, float tilt,
                                           float folding, GeologySettings settings) {
        return new StructuredGeology(new LayeredGeology(seed, regionSize, seaLevel, tilt, folding, settings.layers()),
            new LayeredGeology(seed ^ 0x6a09e667f3bcc909L, regionSize, seaLevel, tilt * 0.75F, folding * 0.6F, settings.layers()),
            seed, regionSize, seaLevel, settings);
    }

    private Column column(int x, int z) {
        Column[] cache = columns.get();
        int slot = (x * 73428767 ^ z * 912931) & 511;
        Column c = cache[slot];
        if (c == null || c.x != x || c.z != z) {
            double px = x / scale, pz = z / scale;
            double fault = LayeredGeology.noise(seed + 2309, px, pz);
            double displacement = settings.faultThrow() == 0 ? 0 : settings.faultThrow() * (fault >= 0 ? 0.5 : -0.5);
            // Younger deposits occupy broad basins. The contact rises smoothly out of them,
            // exposing basement on the shoulders instead of blanketing every high cliff.
            double basin = Math.clamp(0.5 + 1.5 * LayeredGeology.noise(seed + 2341, px * 0.5, pz * 0.5), 0, 1);
            double contact = basin == 0 ? Double.POSITIVE_INFINITY : seaLevel + 40
                + 20 * LayeredGeology.noise(seed + 2311, px * 0.7, pz * 0.7)
                + 192 * (1 - basin) * (1 - basin) / basin;
            // A narrow coherent ribbon forms a dike.
            double distance = Math.abs(LayeredGeology.noise(seed + 2317, px * 1.7, pz * 1.7)) * scale;
            double top = seaLevel + 128 + 40 * LayeredGeology.noise(seed + 2321, px, pz);
            double bottom = seaLevel - 128 + 24 * LayeredGeology.noise(seed + 2333, px, pz);
            RockType intrusion = LayeredGeology.noise(seed + 2339, px, pz) > 0 ? RockType.GRANITE : RockType.ANDESITE;
            c = new Column(x, z, displacement, contact + displacement, distance, bottom, top, intrusion);
            cache[slot] = c;
        }
        return c;
    }

    @Override
    public RockSample sample(int x, double y, int z) {
        if (!Double.isFinite(y)) throw new IllegalArgumentException("Geology requires a finite elevation");
        Column c = column(x, z);
        boolean dike = settings.intrusions() && c.distance < 4;
        if (dike && y >= c.bottom && y < c.top) {
            return new RockSample(c.intrusion, c.bottom);
        }
        boolean young = settings.unconformities() && y >= c.contact;
        GeologySampler source = young ? cover : basement;
        // Probe from above in WORLD precision: near cancellation, a world ULP can span many local ULPs.
        // Then descend any contact that actually translates above the requested world elevation.
        double localY = c.displacement == 0 ? y : Math.nextUp(Math.nextUp(y) - c.displacement);
        RockSample rock = source.sample(x, localY, z);
        double lower = rock.lowerBoundaryY() + c.displacement;
        while (lower > y) {
            rock = source.sample(x, Math.nextDown(rock.lowerBoundaryY()), z);
            lower = rock.lowerBoundaryY() + c.displacement;
        }
        if (young) lower = Math.max(lower, c.contact);
        if (dike && y >= c.top) lower = Math.max(lower, c.top);
        return new RockSample(rock.type(), lower);
    }

    private record Column(int x, int z, double displacement, double contact, double distance,
                          double bottom, double top, RockType intrusion) {}
}
