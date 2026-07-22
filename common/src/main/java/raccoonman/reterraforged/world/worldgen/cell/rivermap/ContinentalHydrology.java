package raccoonman.reterraforged.world.worldgen.cell.rivermap;

import java.util.Arrays;
import java.util.Random;

public class ContinentalHydrology {

    private static record Step(double x, double y) {}

    private static final Step[] BOUNDARIES;
    private static final double TRANSITION_WIDTH = 0.02;
    private static final int NUM_STEPS = 15;

    // --- FLAT BOUNDARY LOOKUP FIELDS ---
    // Storing 30 exact coordinate split-points (2 per step: Ramp Start & Plateau Start)
    private static final double[] SPLITS = new double[NUM_STEPS * 2];

    // Thread-safe single-element lookup cache container
    private static class StepCache {
        double lastX = -1.0;
        int lastId = -3;
    }
    private static final ThreadLocal<StepCache> THREAD_CACHE = ThreadLocal.withInitial(StepCache::new);

    // Static initializer: Generates the fixed, normalized model once
    static {
        Random rand = new Random(42); // Seeded for consistent results
        BOUNDARIES = new Step[NUM_STEPS];

        double[] xDeltas = new double[NUM_STEPS];
        double[] yDeltas = new double[NUM_STEPS];
        double totalX = 0;
        double totalY = 0;

        for (int i = 0; i < NUM_STEPS; i++) {
            xDeltas[i] = 0.5 + rand.nextDouble();
            yDeltas[i] = 0.5 + rand.nextDouble();
            totalX += xDeltas[i];
            totalY += yDeltas[i];
        }

        double currX = 0;
        double currY = 0;
        for (int i = 0; i < NUM_STEPS; i++) {
            currX += xDeltas[i];
            currY += yDeltas[i];
            BOUNDARIES[i] = new Step(currX / totalX, currY / totalY);
        }

        // --- PRE-CALCULATE AND FLAT-STORE EXACT BOUNDARIES ---
        for (int i = 0; i < NUM_STEPS; i++) {
            SPLITS[i * 2]     = BOUNDARIES[i].x - TRANSITION_WIDTH; // Exact point a ramp begins
            SPLITS[i * 2 + 1] = BOUNDARIES[i].x;                    // Exact point a plateau begins
        }
    }

    /**
     * Determines the integer ID of the plateau (step) using an O(log N) binary search
     * against pre-calculated boundary cutoffs.
     */
    public static int getStepId(double x) {
        double val = Math.max(0.0, Math.min(1.0, x));

        // 1. O(1) Fast Path: Check if thread-local cached value matches
        StepCache cache = THREAD_CACHE.get();
        if (val == cache.lastX) {
            return cache.lastId;
        }

        // 2. O(log N) Binary Search Lookup Path
        int idx = Arrays.binarySearch(SPLITS, val);

        // Derive insertion point index if it didn't land exactly on an edge line
        int insertionPoint = (idx >= 0) ? idx + 1 : -(idx + 1);

        int result;
        if (insertionPoint == 0) {
            result = -1; // Landed before the very first split boundary
        } else if ((insertionPoint & 1) == 1) {
            result = -2; // Odd insertion points are transition ramps
        } else {
            result = (insertionPoint >> 1) - 1; // Even insertion points map directly to steps
        }

        // 3. Cache the calculated result
        cache.lastX = val;
        cache.lastId = result;

        return result;
    }

    /**
     * Calculates dynamic micro-terrace step count based on combined scale (2.25x scaling).
     */
    public static int calculateSubStepCount(float globalContinentScale, float thisContinentScale) {
        float effectiveScale = globalContinentScale * thisContinentScale;
        float minScale = 500.0F;
        float maxScale = 8000.0F;

        float t = (effectiveScale - minScale) / (maxScale - minScale);
        t = Math.max(0.0F, Math.min(1.0F, t));

        float minSteps = 1.0F * 2.25F;  // ~2.25 (2 sub-steps)
        float maxSteps = 7.0F * 2.25F;  // ~15.75 (16 sub-steps)

        return Math.round(minSteps + t * (maxSteps - minSteps));
    }

    /**
     * Maps progress t within a primary fall [0.0, 1.0] into clustered micro-falls
     * and pools. Uses hash-based cluster gap detection to group sub-steps into
     * tight cascades separated by wider resting stretches.
     */
    private static double evaluateSubStep(double t, int stepIdx, int numSubSteps) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        if (numSubSteps <= 1) {
            return t * t * (3.0 - 2.0 * t); // Smooth single transition curve
        }

        double[] wT = new double[numSubSteps];
        double[] wY = new double[numSubSteps];
        double[] fallRatios = new double[numSubSteps];

        double sumWT = 0.0;
        double sumWY = 0.0;

        for (int k = 0; k < numSubSteps; k++) {
            // ~35% chance to trigger an inter-cluster gap (starts a new group)
            double clusterHash = hash(stepIdx, k, 10);
            boolean isClusterGap = (k > 0) && (clusterHash > 0.65);

            if (isClusterGap) {
                // INTER-CLUSTER GAP: Long flat pool / wide spacing between terrace groups
                wT[k] = 2.5 + 2.0 * hash(stepIdx, k, 1);           // Expanded horizontal width [2.5, 4.5]
                wY[k] = 0.5 + 1.0 * hash(stepIdx, k, 2);           // Moderate height step
                fallRatios[k] = 0.15 + 0.20 * hash(stepIdx, k, 3); // Shallow fall ratio (80%+ pool stretch)
            } else {
                // INTRA-CLUSTER CASCADE: Tightly grouped, rapid micro-steps
                wT[k] = 0.3 + 0.5 * hash(stepIdx, k, 1);           // Compressed horizontal width [0.3, 0.8]
                wY[k] = 0.6 + 1.0 * hash(stepIdx, k, 2);           // Prominent vertical drop
                fallRatios[k] = 0.50 + 0.35 * hash(stepIdx, k, 3); // Steep fall ratio (50%-85% drop zone)
            }

            sumWT += wT[k];
            sumWY += wY[k];
        }

        double[] tBounds = new double[numSubSteps + 1];
        double[] yBounds = new double[numSubSteps + 1];

        double currT = 0.0;
        double currY = 0.0;
        for (int k = 0; k < numSubSteps; k++) {
            currT += wT[k] / sumWT;
            currY += wY[k] / sumWY;
            tBounds[k + 1] = currT;
            yBounds[k + 1] = currY;
        }
        tBounds[numSubSteps] = 1.0;
        yBounds[numSubSteps] = 1.0;

        // Locate active sub-step segment
        int k = 0;
        while (k < numSubSteps && t >= tBounds[k + 1]) {
            k++;
        }

        if (k >= numSubSteps) {
            return 1.0;
        }

        double tStart = tBounds[k];
        double segWidth = tBounds[k + 1] - tStart;
        double relT = t - tStart;
        double rampWidth = segWidth * fallRatios[k];

        if (relT < rampWidth) {
            double u = relT / rampWidth;
            double smoothU = u * u * (3.0 - 2.0 * u);
            return yBounds[k] + smoothU * (yBounds[k + 1] - yBounds[k]);
        } else {
            return yBounds[k + 1]; // Flat plateau within micro-step
        }
    }

    /**
     * Fast deterministic hash returning pseudo-random float in [0.0, 1.0).
     */
    private static double hash(int stepIdx, int subIdx, int salt) {
        int h = stepIdx * 31221 + subIdx * 19997 + salt * 1337;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0x7FFFFFFF) / (double) Integer.MAX_VALUE;
    }

    public static float fixedCurvedStep(double x, float globalContinentScale, float thisContinentScale) {
        double val = Math.max(0.0, Math.min(1.0, x));
        if (val == 0.0) return 0.0F;

        // Base plateau safety check
        if (val < SPLITS[0]) return 0.0F;

        int numSubSteps = calculateSubStepCount(globalContinentScale, thisContinentScale);

        for (int i = 0; i < NUM_STEPS; i++) {
            double targetY = BOUNDARIES[i].y;
            double prevY = (i > 0) ? BOUNDARIES[i - 1].y : 0.0;

            double tStart = SPLITS[i * 2];
            double tEnd = SPLITS[i * 2 + 1];

            // Inside transition ramp -> apply clustered sub-stepping
            if (val >= tStart && val <= tEnd) {
                double t = (val - tStart) / TRANSITION_WIDTH;
                double subT = evaluateSubStep(t, i, numSubSteps);
                return (float) (prevY + subT * (targetY - prevY));
            }

            // Inside flat plateau
            double nextRampStart = (i + 1 < NUM_STEPS) ? SPLITS[(i + 1) * 2] : 1.1;
            if (val > tEnd && val < nextRampStart) {
                return (float) targetY;
            }
        }

        return (float) BOUNDARIES[NUM_STEPS - 1].y;
    }

    public static float fixedCurvedStep(double x) {
        return fixedCurvedStep(x, 4000.0F, 1.0F);
    }

    public static float getTargetWaterHeight(float continentEdge, float globalContinentScale, float thisContinentScale) {
        return fixedCurvedStep(continentEdge, globalContinentScale, thisContinentScale);
    }

    public static float getTargetWaterHeight(float continentEdge) {
        return getTargetWaterHeight(continentEdge, 4000.0F, 1.0F);
    }

    public static float getComplexWaterHeight(float waterTableBaseGradient, float globalContinentScale, float thisContinentScale) {
        return getTargetWaterHeight(waterTableBaseGradient, globalContinentScale, thisContinentScale) // base gradient strength input
                * (globalContinentScale / 4000.0F) // scale uplift based on continent width
                * (thisContinentScale / 1.0F) // obey per continent jitter
                * 0.50F; // base uplift
    }

    public static float getFlatnessFactor(double x) {
        double val = Math.max(0.0, Math.min(1.0, x));

        // Initial base plateau
        if (val < SPLITS[0]) {
            double mid = SPLITS[0] / 2.0;
            double radius = SPLITS[0] / 2.0;
            double linearDist = 1.0 - (Math.abs(val - mid) / radius);
            return (float) (linearDist * linearDist * (3.0 - 2.0 * linearDist));
        }

        for (int i = 0; i < NUM_STEPS; i++) {
            double pStart = SPLITS[i * 2 + 1];
            double pEnd = (i + 1 < NUM_STEPS) ? SPLITS[(i + 1) * 2] : 1.0;

            if (val >= pStart && val <= pEnd) {
                double mid = (pStart + pEnd) / 2.0;
                double radius = (pEnd - pStart) / 2.0;

                if (radius < 1e-6) return 0.0F;

                double linearDist = 1.0 - (Math.abs(val - mid) / radius);
                return (float) (linearDist * linearDist * (3.0 - 2.0 * linearDist));
            }
        }

        return 0.0F;
    }

    /**
     * Calculates complex water height with domain warping targeted specifically at Zone 3 (Valley Floor).
     * Breaks up linear micro-terrace bands inside the valley floor while ensuring terrain outside
     * Zone 3 remains un-distorted.
     *
     * @param waterTableBaseGradient Base sampling gradient x [0.0, 1.0]
     * @param noiseOffset Raw 2D/3D noise offset [-maxOffset, +maxOffset]
     * @param zone3Weight Zone 3 influence factor [0.0 = outside valley floor, 1.0 = deep valley floor]
     * @param globalContinentScale Global continent scale
     * @param thisContinentScale Per-continent scale factor
     * @return Final valley floor elevation, strictly >= base uplift height
     */
    public static float getZone3WarpedWaterHeight(
            float waterTableBaseGradient,
            float noiseOffset,
            float zone3Weight,
            float globalContinentScale,
            float thisContinentScale) {

        // 1. Calculate unwarped baseline floor height
        float baseHeight = getComplexWaterHeight(waterTableBaseGradient, globalContinentScale, thisContinentScale);

        // Fast path: Outside Zone 3, return unwarped base height directly
        if (zone3Weight <= 0.001F) {
            return baseHeight;
        }

        // 2. Modulate noise offset by Zone 3 intensity (fades smoothly towards valley walls)
        float effectiveOffset = noiseOffset * zone3Weight;

        // 3. Evaluate warped height
        float warpedX = Math.max(0.0F, Math.min(1.0F, waterTableBaseGradient + effectiveOffset));
        float warpedHeight = getComplexWaterHeight(warpedX, globalContinentScale, thisContinentScale);

        // 4. Strict safety clamp: Never carve lower than unwarped base uplift
        return Math.max(baseHeight, warpedHeight);
    }
}