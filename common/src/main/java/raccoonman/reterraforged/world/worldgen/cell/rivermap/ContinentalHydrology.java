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
     * * @param x The input sampling coordinate (0.0 to 1.0)
     * @return -1 for the initial base plateau,
     * 0 to NUM_STEPS-1 for the defined steps,
     * -2 if the value falls on a transition ramp.
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

    public static float fixedCurvedStep(double x) {
        double val = Math.max(0.0, Math.min(1.0, x));
        if (val == 0.0) return 0.0F;

        // Base plateau safety check
        if (val < SPLITS[0]) return 0.0F;

        for (int i = 0; i < NUM_STEPS; i++) {
            double targetY = BOUNDARIES[i].y;
            double prevY = (i > 0) ? BOUNDARIES[i - 1].y : 0.0;

            double tStart = SPLITS[i * 2];
            double tEnd = SPLITS[i * 2 + 1];

            // Inside transition ramp
            if (val >= tStart && val <= tEnd) {
                double t = (val - tStart) / TRANSITION_WIDTH;
                double smoothT = t * t * (3.0 - 2.0 * t);
                return (float) (prevY + smoothT * (targetY - prevY));
            }

            // Inside flat plateau
            double nextRampStart = (i + 1 < NUM_STEPS) ? SPLITS[(i + 1) * 2] : 1.1;
            if (val > tEnd && val < nextRampStart) {
                return (float) targetY;
            }
        }

        return (float) BOUNDARIES[NUM_STEPS - 1].y;
    }

    public static float getTargetWaterHeight(float continentEdge) {
        return fixedCurvedStep(continentEdge);
    }

    public static float getComplexWaterHeight(float waterTableBaseGradient, float globalContinentScale, float thisContinentScale) {
        return getTargetWaterHeight(waterTableBaseGradient) // base gradient strength input
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
}