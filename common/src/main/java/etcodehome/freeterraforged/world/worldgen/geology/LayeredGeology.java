package etcodehome.freeterraforged.world.worldgen.geology;

import etcodehome.freeterraforged.data.worldgen.preset.settings.GeologySettings.Layers;

/** Tilted and folded, non-repeating beds within warped cellular geological provinces. */
public final class LayeredGeology implements GeologySampler {
    private static final int BAND_HEIGHT = 32;
    private static final int BED_COUNT = 8;
    private static final double TRANSITION_WIDTH = 96.0;
    private static final int DARK_PALETTE = 3;
    private static final RockType[][] PALETTES = {
        {RockType.STONE, RockType.ANDESITE, RockType.TUFF},
        {RockType.DIORITE, RockType.CALCITE},
        {RockType.GRANITE, RockType.DRIPSTONE, RockType.PACKED_MUD},
        {RockType.BLACKSTONE, RockType.DEEPSLATE, RockType.BASALT}
    };
    public enum FormationStyle { BEDDED, MASSIVE, FOLDED }
    private static final FormationStyle[] FORMATIONS = FormationStyle.values();

    static FormationStyle formationFor(long province) {
        return FORMATIONS[(int) Math.floorMod(mix(province + 941L), 3L)];
    }

    static int paletteIndex(long province) {
        // Allocate about one quarter of provinces to the dark palette while preserving the other host palettes.
        return (mix(province + 1889L) & 3L) == 0 ? DARK_PALETTE : Math.floorMod(province, DARK_PALETTE);
    }

    private final long seed;
    private final int regionSize;
    private final int seaLevel;
    private final double tiltX, tiltZ, foldStrength;
    private final Layers layers;
    private final double bandHeight;
    // Bounded per-worker cache reuses columns revisited by surface rules.
    private final ThreadLocal<ColumnCache[]> cache = ThreadLocal.withInitial(() -> new ColumnCache[512]);

    public LayeredGeology(long seed, int regionSize, int seaLevel) {
        this(seed, regionSize, seaLevel, 0.06F, 1.0F);
    }

    public LayeredGeology(long seed, int regionSize, int seaLevel, float tilt, float folding) {
        this(seed, regionSize, seaLevel, tilt, folding, Layers.DEFAULT);
    }

    public LayeredGeology(long seed, int regionSize, int seaLevel, float tilt, float folding, Layers layers) {
        this.seed = seed;
        this.regionSize = Math.max(64, regionSize);
        this.seaLevel = seaLevel;
        double angle = unit(mix(seed + 12768L)) * Math.PI * 2;
        double slope = Math.clamp(tilt, 0.0F, 1.0F);
        this.tiltX = Math.cos(angle) * slope;
        this.tiltZ = Math.sin(angle) * slope;
        this.foldStrength = Math.clamp(folding, 0.0F, 2.0F);
        this.layers = layers;
        this.bandHeight = BAND_HEIGHT * (double) layers.bedThicknessScale();
    }

    @Override
    public RockSample sample(int x, double y, int z) {
        if (!Double.isFinite(y)) {
            throw new IllegalArgumentException("Geology requires a finite elevation");
        }
        ColumnCache[] columns = this.cache.get();
        int slot = (int) mix(((long) x << 32) ^ (z & 0xffffffffL)) & (columns.length - 1);
        ColumnCache cache = columns[slot];
        if (cache == null) {
            cache = columns[slot] = new ColumnCache();
        }
        if (!cache.initialized || cache.x != x || cache.z != z) {
            cache.initialized = true;
            cache.x = x;
            cache.z = z;
            this.prepareProvinces(cache, x, z);
            cache.reference = this.referenceY(cache, x, z);
            cache.band = Long.MIN_VALUE;
            cache.rock = null;
        }
        if (cache.rock != null && y >= cache.rock.lowerBoundaryY() && y < cache.upper) {
            return cache.rock;
        }
        long band = (long) Math.floor((y - cache.reference) / this.bandHeight);
        double base = cache.reference + band * this.bandHeight;
        double bandEnd = cache.reference + (band + 1.0) * this.bandHeight;
        // Check the actual boundaries after floating-point subtraction and division.
        if (base > y) {
            band--;
            bandEnd = base;
            base = cache.reference + band * this.bandHeight;
        } else if (y >= bandEnd) {
            band++;
            base = bandEnd;
            bandEnd = cache.reference + (band + 1.0) * this.bandHeight;
        }
        if (cache.band != band) {
            this.prepareBand(cache, band);
        }
        for (int bed = BED_COUNT - 1; bed >= 0; bed--) {
            double lower = base + cache.offsets[bed];
            double upper = bed == BED_COUNT - 1 ? bandEnd : base + cache.offsets[bed + 1];
            if (y >= lower) {
                RockType lens = bed == cache.gravelBed ? RockType.GRAVEL : cache.contrasts[bed];
                double thickness = bed == cache.gravelBed ? cache.gravelThickness : cache.contrastThickness[bed];
                if (lens != null && thickness > 1e-5) {
                    double lensTop = lower + thickness;
                    if (y < lensTop) {
                        cache.upper = lensTop;
                        return cache.rock = new RockSample(lens, lower);
                    }
                    lower = lensTop;
                }
                if (cache.lenses[bed] != null && cache.lensThickness[bed] > 1e-5) {
                    double lensTop = lower + cache.lensThickness[bed];
                    if (y < lensTop) {
                        cache.upper = lensTop;
                        return cache.rock = new RockSample(cache.lenses[bed], lower);
                    }
                    lower = lensTop;
                }
                cache.upper = upper;
                return cache.rock = new RockSample(cache.types[bed], lower);
            }
        }
        throw new IllegalStateException("No geological bed at elevation " + y);
    }

    private void prepareBand(ColumnCache cache, long band) {
        cache.band = band;
        long seamHash = mix(this.seed ^ mix(band));
        cache.offsets[0] = 0;
        for (int pair = 0; pair < BED_COUNT / 2; pair++) {
            double offset = 0;
            for (int site = 0; site < cache.provinceCount; site++) {
                long hash = mix(this.seed ^ cache.provinces[site] ^ mix(band));
                offset += cache.weights[site] * (2 + Math.floorMod(mix(hash + pair), 5L));
            }
            cache.offsets[pair * 2 + 1] = (pair * 8 + offset) * this.layers.bedThicknessScale();
            cache.offsets[pair * 2 + 2] = (pair + 1) * 8 * (double) this.layers.bedThicknessScale();
        }
        // Seam placement and thickness are shared across provinces so seams continue through contacts.
        cache.gravelBed = this.layers.gravelThickness() > 0 && (seamHash & 1) == 0
            ? (int) ((seamHash >>> 1) & (BED_COUNT - 1)) : -1;
        double envelope = Math.clamp(0.4 + 1.6 * noise(seamHash + 331,
            cache.x / 96.0, cache.z / 96.0), 0.0, 1.0);
        if (cache.gravelBed >= 0) {
            double bedHeight = cache.offsets[cache.gravelBed + 1] - cache.offsets[cache.gravelBed];
            cache.gravelThickness = Math.min(envelope * this.layers.gravelThickness(), Math.max(0, bedHeight - 1));
        }
        for (int bed = 0; bed < BED_COUNT; bed++) {
            long detailHash = mix(seamHash ^ mix(bed + 137L));
            // Smooth world-space patches select a province, not independent random blocks.
            double selector = Math.clamp(0.5 + 0.85 * noise(detailHash,
                cache.x / 24.0, cache.z / 24.0), 0.001, 0.999);
            long province = cache.provinces[cache.provinceCount - 1];
            double cumulative = 0;
            for (int site = 0; site < cache.provinceCount; site++) {
                cumulative += cache.weights[site];
                if (selector < cumulative) {
                    province = cache.provinces[site];
                    break;
                }
            }
            long hash = mix(this.seed ^ province ^ mix(band));
            FormationStyle style = formationFor(province);
            int materialBed = style == FormationStyle.MASSIVE ? bed / 4 : bed;
            long bedHash = mix(hash ^ mix(materialBed + 137L));
            int paletteIndex = paletteIndex(province);
            RockType[] palette = PALETTES[paletteIndex];
            cache.types[bed] = palette[(int) Math.floorMod(bedHash, palette.length)];
            // Related stones form small coherent patches inside a bed; no per-block randomness.
            double texture = noise(bedHash + 503, cache.x / 18.0, cache.z / 18.0);
            if (texture > 0.45) {
                cache.types[bed] = palette[(int) Math.floorMod(bedHash + 1, palette.length)];
            }
            cache.lenses[bed] = null;
            cache.lensThickness[bed] = 0;
            double lensShape = Math.clamp(noise(detailHash + 811,
                cache.x / 120.0, cache.z / 120.0) * 2.5 - 0.15, 0.0, 1.0);
            if ((detailHash & 3) == 1 && cache.lensStrength > 1e-5 && lensShape > 1e-5) {
                int host = (int) Math.floorMod(bedHash, palette.length);
                cache.lenses[bed] = palette[(host + 1) % palette.length];
                // Reserve host material even when the underlying bed is only two blocks thick.
                cache.lensThickness[bed] = lensShape * cache.lensStrength * Math.min(this.layers.lensThickness(),
                    (cache.offsets[bed + 1] - cache.offsets[bed] - 1.0) * 0.5);
            }
            cache.contrasts[bed] = null;
            // The low two bits retain the original placement when frequency is 0.25.
            if (unit(Long.rotateRight(detailHash, 2)) < this.layers.contrastFrequency() && bed != cache.gravelBed) {
                // Always select a different color family from the local host. Keep the
                // full accent palette rather than occasionally selecting the host itself.
                int seamPalette = (paletteIndex + 1 + Math.floorMod(mix(detailHash), PALETTES.length - 1)) % PALETTES.length;
                RockType[] contrast = PALETTES[seamPalette];
                cache.contrasts[bed] = contrast[(int) Math.floorMod(mix(detailHash + 1), contrast.length)];
                double seamEnvelope = Math.clamp(0.75 + 1.6 * noise(seamHash + 331,
                    cache.x / 96.0, cache.z / 96.0), 0, 1);
                cache.contrastThickness[bed] = seamEnvelope * Math.min(this.layers.contrastThickness(),
                    cache.offsets[bed + 1] - cache.offsets[bed] - 1);
            }
            // Insertions share the parent bed's finite thickness and always leave host rock.
            double accent = bed == cache.gravelBed ? cache.gravelThickness :
                cache.contrasts[bed] == null ? 0 : cache.contrastThickness[bed];
            cache.lensThickness[bed] = Math.min(cache.lensThickness[bed],
                Math.max(0, cache.offsets[bed + 1] - cache.offsets[bed] - accent - 1));
        }
    }

    /** Dominant province, retained for diagnostics; generation uses all nearby province weights. */
    long regionAt(int x, int z) {
        ColumnCache column = new ColumnCache();
        this.prepareProvinces(column, x, z);
        return column.province;
    }

    /** Compact smooth weights also blend three-way junctions without changing neighbor pairs. */
    private void prepareProvinces(ColumnCache cache, int x, int z) {
        double px = x / (double) this.regionSize;
        double pz = z / (double) this.regionSize;
        double wx = px + 0.28 * noise(this.seed + 31, px * 0.5, pz * 0.5);
        double wz = pz + 0.28 * noise(this.seed + 47, px * 0.5, pz * 0.5);
        int gx = (int) Math.floor(wx), gz = (int) Math.floor(wz);
        double nearest = Double.POSITIVE_INFINITY;
        int count = 0;
        // Stable spatial order: when the window moves, entering/leaving sites have zero weight.
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                long hash = mix(this.seed ^ mix(gx + dx) ^ Long.rotateLeft(mix(gz + dz), 32));
                double sx = gx + dx + 0.2 + 0.6 * unit(hash);
                double sz = gz + dz + 0.2 + 0.6 * unit(mix(hash));
                double distance = (wx - sx) * (wx - sx) + (wz - sz) * (wz - sz);
                cache.provinces[count] = hash;
                cache.weights[count++] = distance;
                if (distance < nearest) {
                    nearest = distance;
                    cache.province = hash;
                }
            }
        }
        double width = Math.min(TRANSITION_WIDTH, this.regionSize * 0.5);
        double total = 0;
        int active = 0;
        for (int site = 0; site < count; site++) {
            // For normally spaced sites this fades across about width/2 blocks on each side.
            double t = (cache.weights[site] - nearest) * this.regionSize / width;
            if (t < 1) {
                double weight = (1 - t) * (1 - t);
                cache.provinces[active] = cache.provinces[site];
                cache.weights[active++] = weight;
                total += weight;
            }
        }
        cache.provinceCount = active;
        cache.lensStrength = 0;
        for (int site = 0; site < active; site++) {
            cache.weights[site] /= total;
            if (formationFor(cache.provinces[site]) != FormationStyle.MASSIVE) {
                cache.lensStrength += cache.weights[site];
            }
        }
    }

    /** Regional waves blend with the same continuous weights as bed thicknesses. */
    double referenceY(int x, int z) {
        ColumnCache column = new ColumnCache();
        this.prepareProvinces(column, x, z);
        return this.referenceY(column, x, z);
    }

    private double referenceY(ColumnCache column, int x, int z) {
        double incline = x * this.tiltX + z * this.tiltZ;
        if (this.foldStrength == 0) return this.seaLevel + incline;
        double folded = 0;
        for (int site = 0; site < column.provinceCount; site++) {
            long province = column.provinces[site];
            long hash = mix(province + 179L);
            double angle = unit(hash) * Math.PI * 2;
            double dx = Math.cos(angle), dz = Math.sin(angle);
            double scale = this.regionSize * (0.7 + 0.6 * unit(mix(hash)));
            double along = x * dx + z * dz;
            double across = -x * dz + z * dx;
            double warp = scale * 0.18 * noise(this.seed + 71, x / (scale * 0.8), z / (scale * 0.8));
            double phase = unit(mix(hash + 1)) * Math.PI * 2;
            double wave = Math.sin((along + warp) * Math.PI * 2 / (scale * 0.9) + phase);
            double detail = Math.sin((along * 0.8 + across * 0.6 + warp * 0.5) * Math.PI * 2 / (scale * 0.35) + phase);
            double strength = switch (formationFor(province)) {
                case BEDDED -> 0.35;
                case MASSIVE -> 0.12;
                case FOLDED -> 1.0;
            };
            folded += column.weights[site] * strength * (0.75 * wave + 0.25 * detail);
        }
        double amplitude = Math.min(96.0, this.regionSize * 0.12) * this.foldStrength;
        return this.seaLevel + incline + amplitude * folded;
    }

    static double noise(long seed, double x, double z) {
        long gx = (long) Math.floor(x), gz = (long) Math.floor(z);
        double tx = x - gx, tz = z - gz;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = value(seed, gx, gz) * (1 - tx) + value(seed, gx + 1, gz) * tx;
        double b = value(seed, gx, gz + 1) * (1 - tx) + value(seed, gx + 1, gz + 1) * tx;
        return a * (1 - tz) + b * tz;
    }

    private static double value(long seed, long x, long z) {
        return unit(mix(seed ^ mix(x) ^ Long.rotateLeft(mix(z), 32))) * 2 - 1;
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static final class ColumnCache {
        boolean initialized;
        int x, z, gravelBed, provinceCount;
        double gravelThickness, lensStrength;
        final long[] provinces = new long[25];
        final double[] weights = new double[25];
        long province, band;
        double reference, upper;
        RockSample rock;
        final double[] offsets = new double[BED_COUNT + 1];
        final RockType[] types = new RockType[BED_COUNT];
        final RockType[] contrasts = new RockType[BED_COUNT];
        final double[] contrastThickness = new double[BED_COUNT];
        final RockType[] lenses = new RockType[BED_COUNT];
        final double[] lensThickness = new double[BED_COUNT];
    }
}
