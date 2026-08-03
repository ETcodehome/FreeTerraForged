package raccoonman.reterraforged.world.worldgen.cell.continent.uplift;

import raccoonman.reterraforged.concurrent.Resource;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.continent.SimpleContinent;
import raccoonman.reterraforged.world.worldgen.cell.continent.advanced.AbstractContinent;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domains;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import raccoonman.reterraforged.world.worldgen.util.Seed;

public class UpliftContinentGenerator extends AbstractContinent implements SimpleContinent {
    protected float frequency;
    protected float variance;
    protected int varianceSeed;
    protected Domain warp;
    protected Domain cleanWarp;
    protected Noise cliffNoise;
    protected Noise bayNoise;
    protected Levels levels;

    protected WorldSettings.Continent continentSettings;

    public UpliftContinentGenerator(Seed seed, GeneratorContext context) {
        super(seed, context);
        WorldSettings settings = context.preset.world();
        int tectonicScale = settings.continent.continentScale * 4;
        this.continentSettings = settings.continent;
        this.frequency = 1.0F / tectonicScale;
        this.varianceSeed = seed.next();
        this.variance = settings.continent.continentSizeVariance;

        // We extract identical seeds so both domains share the exact same macro-shape.
        // If we pass 'seed' directly, seed.next() desyncs the two warps entirely.
        int warpSeedX = seed.next();
        int warpSeedZ = seed.next();

        this.warp = this.createWarp(warpSeedX, warpSeedZ, tectonicScale, settings.continent, true);
        this.cleanWarp = this.createWarp(warpSeedX, warpSeedZ, tectonicScale, settings.continent, false);

        float frequency = 1.0F / this.frequency;

        Noise cliffNoise = Noises.simplex2(seed.next(), this.continentScale / 2, 2);
        cliffNoise = Noises.clamp(cliffNoise, 0.1F, 0.25F);
        cliffNoise = Noises.map(cliffNoise, 0.0F, 1.0F);
        cliffNoise = Noises.frequency(cliffNoise, frequency);
        this.cliffNoise = cliffNoise;

        Noise bayNoise = Noises.simplex(seed.next(), 100, 1);
        bayNoise = Noises.mul(bayNoise, 0.1F);
        bayNoise = Noises.add(bayNoise, 0.9F);
        bayNoise = Noises.frequency(bayNoise, frequency);
        this.bayNoise = bayNoise;

        this.levels = context.levels;
    }

    @Override
    public void apply(Cell cell, float rawX, float rawY) {
        float wx = this.warp.getX(rawX, rawY, 0);
        float wy = this.warp.getZ(rawX, rawY, 0);
        float x = wx * this.frequency;
        float y = wy * this.frequency;
        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);
        int cellX = xi;
        int cellY = yi;
        float cellPointX = x;
        float cellPointY = y;
        float nearest = Float.MAX_VALUE;

        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx = xi - 1; cx <= xi + 1; ++cx) {
                Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                float px = cx + vec.x() * this.jitter;
                float py = cy + vec.y() * this.jitter;
                float dist2 = Line.distSq(x, y, px, py);
                if (dist2 < nearest) {
                    cellPointX = px;
                    cellPointY = py;
                    cellX = cx;
                    cellY = cy;
                    nearest = dist2;
                }
            }
        }

        nearest = Float.MAX_VALUE;

        for (int cy2 = cellY - 1; cy2 <= cellY + 1; ++cy2) {
            for (int cx2 = cellX - 1; cx2 <= cellX + 1; ++cx2) {
                if (cx2 != cellX || cy2 != cellY) {
                    Vec2f vec2 = NoiseUtil.cell(this.seed, cx2, cy2);
                    float px2 = cx2 + vec2.x() * this.jitter;
                    float py2 = cy2 + vec2.y() * this.jitter;
                    float dist3 = getDistance(x, y, cellPointX, cellPointY, px2, py2);
                    if (dist3 < nearest) {
                        nearest = dist3;
                    }
                }
            }
        }

        // We always resolve a stable continent centre even for continents that get skipped.
        // RiverCache / getRivermap() / getNearestCenter() all rely on cell.continentX/continentZ so if we return early
        // without setting them they keep whatever value this pooled Cell last held (often a leftover
        // (0,0) from a previous tile), causing an unrelated continents rivers to be carved here instead.
        cell.continentX = Math.round(cellPointX / this.frequency);
        cell.continentZ = Math.round(cellPointY / this.frequency);

        // early exit guard to avoid processing skipped continents
        if (this.shouldSkip(cellX, cellY)) {
            return;
        }

        // process regular continent masks
        cell.continentDistance = NoiseUtil.sqrt(nearest);
        cell.continentEdge = this.getDistanceValue(x, y, cellX, cellY, nearest);
        cell.continentId = AbstractContinent.getCellValue(this.seed, cellX, cellY);

        // this updates continent centers to the voronoi centroid.
        // we remap by the water level so the continent uplift is usefully placed above the water line
        // then an additional 15ish percent insets from coastal boundaries
        float upliftGradient = getSmoothVoronoiGradient(cell, rawX, rawY);
        upliftGradient = shiftAndRemap(upliftGradient, levels.water);
        upliftGradient = shiftAndRemap(upliftGradient, 0.15F);

        // rescale the uplift to handle continental variance
        cell.continentSizeModifier = getContinentSizeModifier(cellX, cellY);
        if (cell.continentSizeModifier != 1.0F) {
            upliftGradient = shiftAndRemap(upliftGradient, (1.0F - cell.continentSizeModifier));
        }

        // use the continent edge values to guarantee we fall to the ocean near the ocean.
        // sometimes this can look a little rough but havent found a more reliable way yet.
        float customPeak = shiftAndRemap(cell.continentEdge, levels.water);
        if (customPeak < 0.05F && customPeak < upliftGradient) {
            upliftGradient = customPeak;
        }
        cell.waterTable = upliftGradient;
    }

    public float shiftAndRemap(float value, float threshold) {
        if (value <= threshold) {
            return 0.0F;
        }
        float remapped = (value - threshold) / (1.0F - threshold);
        return NoiseUtil.clamp(remapped, 0.0F, 1.0F);
    }

    /**
     * Generates a linear polygonal pyramid strictly in unwarped block space.
     * Peak relies completely on true cellPoint to ensure zero boundary discontinuities.
     */
    public float getSmoothVoronoiGradient(Cell cell, float rawX, float rawY) {
        if (cell.terrain != null && (cell.terrain.isShallowOcean() || cell.terrain.isDeepOcean())) {
            return 0.0F;
        }

        float x = rawX * this.frequency;
        float y = rawY * this.frequency;

        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);

        int cellX = xi;
        int cellY = yi;
        float cellPointX = x;
        float cellPointY = y;
        float nearestSq = Float.MAX_VALUE;

        // Find the closest Voronoi cell seed
        for (int cy = yi - 1; cy <= yi + 1; ++cy) {
            for (int cx = xi - 1; cx <= xi + 1; ++cx) {
                Vec2f vec = NoiseUtil.cell(this.seed, cx, cy);
                float px = cx + vec.x() * this.jitter;
                float py = cy + vec.y() * this.jitter;
                float dist2 = Line.distSq(x, y, px, py);

                if (dist2 < nearestSq) {
                    nearestSq = dist2;
                    cellPointX = px;
                    cellPointY = py;
                    cellX = cx;
                    cellY = cy;
                }
            }
        }

        // Collect the 8 immediate neighboring seeds
        float[] neighborX = new float[8];
        float[] neighborY = new float[8];
        int nIndex = 0;
        for (int cy2 = cellY - 1; cy2 <= cellY + 1; ++cy2) {
            for (int cx2 = cellX - 1; cx2 <= cellX + 1; ++cx2) {
                if (cx2 != cellX || cy2 != cellY) {
                    Vec2f vec2 = NoiseUtil.cell(this.seed, cx2, cy2);
                    neighborX[nIndex] = cx2 + vec2.x() * this.jitter;
                    neighborY[nIndex] = cy2 + vec2.y() * this.jitter;
                    nIndex++;
                }
            }
        }

        float s0Sq = cellPointX * cellPointX + cellPointY * cellPointY;

        float minGradient = 1.0F;
        for (int i = 0; i < 8; i++) {
            float px2 = neighborX[i];
            float py2 = neighborY[i];
            float dx = px2 - cellPointX;
            float dy = py2 - cellPointY;
            float lenSq = dx * dx + dy * dy;

            if (lenSq > 0.00001F) {
                float siSq = px2 * px2 + py2 * py2;
                float baseHalfDiff = 0.5F * (siSq - s0Sq);
                float h_x = baseHalfDiff - (x * dx + y * dy);

                // Using the true centroid ensures the intersecting planes
                // always evaluate to exactly 0.0 at the Voronoi border edge.
                float h_c = baseHalfDiff - (cellPointX * dx + cellPointY * dy);

                if (h_c > 0.00001F) {
                    float planeValue = h_x / h_c;
                    if (planeValue < minGradient) minGradient = planeValue;
                }
            }
        }
        return NoiseUtil.clamp(minGradient, 0.0F, 1.0F);
    }

    @Override
    public float getEdgeValue(float x, float z) {
        try (Resource<Cell> resource = Cell.getResource()) {
            Cell cell = resource.get();
            this.apply(cell, x, z);
            return cell.continentEdge;
        }
    }

    @Override
    public long getNearestCenter(float x, float z) {
        try (Resource<Cell> resource = Cell.getResource()) {
            Cell cell = resource.get();
            this.apply(cell, x, z);
            return PosUtil.pack(cell.continentX, cell.continentZ);
        }
    }

    @Override
    public Rivermap getRivermap(int x, int z) {
        return this.riverCache.getRivers(x, z);
    }

    protected Domain createWarp(int seedX, int seedZ, int tectonicScale, WorldSettings.Continent continent, boolean applyLacunarity) {
        int warpScale = NoiseUtil.round(tectonicScale * 0.225F);
        float strength = NoiseUtil.round(tectonicScale * 0.33F);
        float lacunarity = applyLacunarity ? continent.continentNoiseLacunarity : 0.0F;
        return Domains.domain(
                Noises.perlin2(seedX, warpScale, continent.continentNoiseOctaves, lacunarity, continent.continentNoiseGain),
                Noises.perlin2(seedZ, warpScale, continent.continentNoiseOctaves, lacunarity, continent.continentNoiseGain),
                Noises.constant(strength)
        );
    }

    public float getContinentSizeModifier(int cellX, int cellY) {
        if (this.variance > 0.0f && !this.isDefaultContinent(cellX, cellY)) {
            float sizeValue = AbstractContinent.getCellValue(this.varianceSeed, cellX, cellY);
            return NoiseUtil.map(sizeValue, 0.0f, this.variance, this.variance);
        }
        return 1.0F;
    }

    protected float getDistanceValue(float x, float y, int cellX, int cellY, float distance) {
        distance = this.getVariedDistanceValue(cellX, cellY, distance);
        distance = NoiseUtil.sqrt(distance);
        distance = NoiseUtil.map(distance, 0.05F, 0.25F, 0.2F);
        distance = this.getCoastalDistanceValue(x, y, distance);
        if (distance < this.controlPoints.inland && distance >= this.controlPoints.shallowOcean) {
            distance = this.getCoastalDistanceValue(x, y, distance);
        }
        return distance;
    }

    protected float getVariedDistanceValue(int cellX, int cellY, float distance) {
        if (this.variance > 0.0f && !this.isDefaultContinent(cellX, cellY)) {
            float sizeValue = AbstractContinent.getCellValue(this.varianceSeed, cellX, cellY);
            float sizeModifier = NoiseUtil.map(sizeValue, 0.0f, this.variance, this.variance);
            distance *= sizeModifier;
        }
        return distance;
    }

    protected float getCoastalDistanceValue(float x, float y, float distance) {
        if (distance > this.controlPoints.shallowOcean && distance < this.controlPoints.inland) {
            float alpha = distance / this.controlPoints.inland;
            float cliff = this.cliffNoise.compute(x, y, 0);
            distance = NoiseUtil.lerp(distance * cliff, distance, alpha);
            if (distance < this.controlPoints.shallowOcean) {
                distance = this.controlPoints.shallowOcean * this.bayNoise.compute(x, y, 0);
            }
        }
        return distance;
    }

    protected static float midPoint(float a, float b) {
        return (a + b) * 0.5F;
    }

    protected static float getDistance(float x, float y, float ax, float ay, float bx, float by) {
        float mx = midPoint(ax, bx);
        float my = midPoint(ay, by);
        float dx = bx - ax;
        float dy = by - ay;
        float nx = -dy;
        float ny = dx;
        return getDistance2Line(x, y, mx, my, mx + nx, my + ny);
    }

    protected static float getDistance2Line(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float v = (x - ax) * dx + (y - ay) * dy;
        v /= dx * dx + dy * dy;
        float ox = ax + dx * v;
        float oy = ay + dy * v;
        return Line.distSq(x, y, ox, oy);
    }
}