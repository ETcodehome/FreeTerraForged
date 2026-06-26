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
    protected static float CENTER_CORRECTION = 0.35F;
    protected float frequency;
    protected float variance;
    protected int varianceSeed;
    protected Domain warp;
    protected Domain cleanWarp;
    protected Noise cliffNoise;
    protected Noise bayNoise;
    protected Levels levels;

    public UpliftContinentGenerator(Seed seed, GeneratorContext context) {
        super(seed, context);
        WorldSettings settings = context.preset.world();
        int tectonicScale = settings.continent.continentScale * 4;
        this.frequency = 1.0F / tectonicScale;
        this.varianceSeed = seed.next();
        this.variance = settings.continent.continentSizeVariance;

        // CRITICAL FIX: Extract identical seeds so both domains share the exact same macro-shape.
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
        float sumX = 0.0F;
        float sumY = 0.0F;

        for (int cy2 = cellY - 1; cy2 <= cellY + 1; ++cy2) {
            for (int cx2 = cellX - 1; cx2 <= cellX + 1; ++cx2) {
                if (cx2 != cellX || cy2 != cellY) {
                    Vec2f vec2 = NoiseUtil.cell(this.seed, cx2, cy2);
                    float px2 = cx2 + vec2.x() * this.jitter;
                    float py2 = cy2 + vec2.y() * this.jitter;
                    float dist3 = getDistance(x, y, cellPointX, cellPointY, px2, py2);
                    sumX += px2;
                    sumY += py2;
                    if (dist3 < nearest) {
                        nearest = dist3;
                    }
                }
            }
        }

        // We always resolve a stable continent centre even for continents that get skipped.
        // getRivermap()/getNearestCenter() rely on cell.continentX/continentZ so if we return early
        // without setting them they keep whatever value this pooled Cell last held (often a leftover
        // (0,0) from a previous tile), causing an unrelated continents rivers to be carved here instead.
        cell.continentX = this.getCorrectedContinentCenter(cellPointX, sumX / 8.0F);
        cell.continentZ = this.getCorrectedContinentCenter(cellPointY, sumY / 8.0F);

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
        float upliftGradient = getCustomPeakVoronoiGradient(rawX, rawY);
        upliftGradient += getSmoothVoronoiGradient(cell, rawX, rawY);
        upliftGradient /= 2.0;

        upliftGradient = shiftAndRemap(upliftGradient, levels.water);
        upliftGradient = shiftAndRemap(upliftGradient, 0.15F);

        // rescale the uplift to handle continental variance
        cell.continentSizeModifier = getContinentSizeModifier(cellX, cellY);
        if (cell.continentSizeModifier != 1.0F){
            upliftGradient = shiftAndRemap(upliftGradient, (1.0F - cell.continentSizeModifier));
        }

        // use the continent edge values to guarantee we fall to the ocean near the ocean.
        // sometimes this can look a little rough but havent found a more reliable way yet.
        float customPeak = shiftAndRemap(cell.continentEdge, levels.water);
        if (customPeak < 0.05F && customPeak < upliftGradient){
            upliftGradient = customPeak;
        }
        cell.waterTable = upliftGradient;
    }

    protected int getCorrectedContinentCenter(float point, float average) {
        float corrected = NoiseUtil.lerp(point, average, UpliftContinentGenerator.CENTER_CORRECTION) / this.frequency;
        return Math.round(corrected);
    }

    public float getWaterTable(float x, float z) {
        try (Resource<Cell> resource = Cell.getResource()) {
            Cell cell = resource.get();
            this.apply(cell, x, z);
            return cell.waterTable;
        }
    }

    public float shiftAndRemap(float value, float threshold) {
        if (value <= threshold) {
            return 0.0F;
        }
        float remapped = (value - threshold) / (1.0F - threshold);
        return NoiseUtil.clamp(remapped, 0.0F, 1.0F);
    }

    /**
     * Generates a linear polygonal pyramid where the peak is centered exactly at
     * the true average vertex (corner) location of the cell polygon. Does not use warped coords for the gradient calculation.
     * Warp is a one directional operation so we can't run an inverse to get back the real world peak coordinate in blocks.
     *
     * @param cell Output - updates the continent centroid values (avoids repeat calcs)
     * @param rawX The raw input X coordinate (e.g., world block X)
     * @param rawZ The raw input Z coordinate (e.g., world block Z)
     * @return A float from 0.0 (exact cell edge) to 1.0 (exact true centroid peak)
     */
    public float getSmoothVoronoiGradient(Cell cell, float rawX, float rawZ) {

        // if we're at the ocean we're always at continent edge.
        if (cell.terrain.isShallowOcean() || cell.terrain.isDeepOcean()){
            return 0.0F;
        }

        float x = rawX * this.frequency;
        float y = rawZ * this.frequency;

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

        // Find the true polygon vertices
        float vertexSumX = 0.0F;
        float vertexSumY = 0.0F;
        int vertexCount = 0;
        float s0Sq = cellPointX * cellPointX + cellPointY * cellPointY;

        for (int i = 0; i < 8; i++) {
            float x1 = neighborX[i];
            float y1 = neighborY[i];
            float dx1 = x1 - cellPointX;
            float dy1 = y1 - cellPointY;
            float b1 = 0.5F * ((x1 * x1 + y1 * y1) - s0Sq);

            for (int j = i + 1; j < 8; j++) {
                float x2 = neighborX[j];
                float y2 = neighborY[j];
                float dx2 = x2 - cellPointX;
                float dy2 = y2 - cellPointY;
                float b2 = 0.5F * ((x2 * x2 + y2 * y2) - s0Sq);

                float det = dx1 * dy2 - dy1 * dx2;
                if (Math.abs(det) < 0.00001F) continue;

                float vx = (b1 * dy2 - b2 * dy1) / det;
                float vy = (dx1 * b2 - dx2 * b1) / det;

                float d0Sq = (vx - cellPointX) * (vx - cellPointX) + (vy - cellPointY) * (vy - cellPointY);
                boolean isValidVertex = true;

                for (int k = 0; k < 8; k++) {
                    if (k == i || k == j) continue;
                    float xk = neighborX[k];
                    float yk = neighborY[k];
                    float dkSq = (vx - xk) * (vx - xk) + (vy - yk) * (vy - yk);
                    if (dkSq < d0Sq - 0.0001F) { isValidVertex = false; break; }
                }

                if (isValidVertex) {
                    vertexSumX += vx;
                    vertexSumY += vy;
                    vertexCount++;
                }
            }
        }

        float centerX = (vertexCount > 0) ? (vertexSumX / vertexCount) : cellPointX;
        float centerY = (vertexCount > 0) ? (vertexSumY / vertexCount) : cellPointY;

        // Assign the centroid coordinates to the Cell object in world space
        // We reverse the frequency scaling to get back to raw coordinate space
        cell.continentX = Math.round(centerX / this.frequency);
        cell.continentZ = Math.round(centerY / this.frequency);

        // Render clean linear planes using unwarped logic
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
                float h_c = baseHalfDiff - (centerX * dx + centerY * dy);
                if (h_c > 0.00001F) {
                    float planeValue = h_x / h_c;
                    if (planeValue < minGradient) minGradient = planeValue;
                }
            }
        }
        return NoiseUtil.clamp(minGradient, 0.0F, 1.0F);
    }

    /**
     * Reverted to the structurally perfect geometric bisector math.
     * The alignment is now handled securely in the generator constructor.
     */
    public float getCustomPeakVoronoiGradient(float rawX, float rawZ) {
        float warpedX = this.cleanWarp.getX(rawX, rawZ, 0);
        float warpedZ = this.cleanWarp.getZ(rawX, rawZ, 0);

        float x = warpedX * this.frequency;
        float y = warpedZ * this.frequency;

        int xi = NoiseUtil.floor(x);
        int yi = NoiseUtil.floor(y);

        int cellX = xi;
        int cellY = yi;
        float cellPointX = x;
        float cellPointY = y;
        float nearestSq = Float.MAX_VALUE;

        // Pass 1: Find closest Voronoi cell seed (S0)
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

        // Collect neighbors
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

        // Pass 3: Find true polygon vertices by intersecting perpendicular bisectors
        float vertexSumX = 0.0F;
        float vertexSumY = 0.0F;
        int vertexCount = 0;
        float s0Sq = cellPointX * cellPointX + cellPointY * cellPointY;

        for (int i = 0; i < 8; i++) {
            float x1 = neighborX[i];
            float y1 = neighborY[i];
            float dx1 = x1 - cellPointX;
            float dy1 = y1 - cellPointY;
            float b1 = 0.5F * ((x1 * x1 + y1 * y1) - s0Sq);

            for (int j = i + 1; j < 8; j++) {
                float x2 = neighborX[j];
                float y2 = neighborY[j];
                float dx2 = x2 - cellPointX;
                float dy2 = y2 - cellPointY;
                float b2 = 0.5F * ((x2 * x2 + y2 * y2) - s0Sq);

                float det = dx1 * dy2 - dy1 * dx2;
                if (Math.abs(det) < 0.00001F) continue;

                float vx = (b1 * dy2 - b2 * dy1) / det;
                float vy = (dx1 * b2 - dx2 * b1) / det;

                float d0Sq = (vx - cellPointX) * (vx - cellPointX) + (vy - cellPointY) * (vy - cellPointY);
                boolean isValidVertex = true;

                for (int k = 0; k < 8; k++) {
                    if (k == i || k == j) continue;
                    float xk = neighborX[k];
                    float yk = neighborY[k];
                    float dkSq = (vx - xk) * (vx - xk) + (vy - yk) * (vy - yk);

                    if (dkSq < d0Sq - 0.0001F) {
                        isValidVertex = false;
                        break;
                    }
                }

                if (isValidVertex) {
                    vertexSumX += vx;
                    vertexSumY += vy;
                    vertexCount++;
                }
            }
        }

        // Compute pure visual centroid
        float centerX = (vertexCount > 0) ? (vertexSumX / vertexCount) : cellPointX;
        float centerY = (vertexCount > 0) ? (vertexSumY / vertexCount) : cellPointY;

        // Pass 4: Render clean linear planes
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
                float h_c = baseHalfDiff - (centerX * dx + centerY * dy);

                if (h_c > 0.00001F) {
                    float planeValue = h_x / h_c;
                    if (planeValue < minGradient) {
                        minGradient = planeValue;
                    }
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

    protected float getContinentSizeModifier(int cellX, int cellY){
        if (this.variance > 0.0f && !this.isDefaultContinent(cellX, cellY)) {
            float sizeValue = AbstractContinent.getCellValue(this.varianceSeed, cellX, cellY);
            float sizeModifier = NoiseUtil.map(sizeValue, 0.0f, this.variance, this.variance);
            return sizeModifier;
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