package raccoonman.reterraforged.world.worldgen.cell.rivermap;

import java.util.Random;

public class ContinentalHydrology {

    private static record Step(double x, double y) {}

    private static final Step[] BOUNDARIES;
    private static final double TRANSITION_WIDTH = 0.02;
    private static final int NUM_STEPS = 15;

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
            // Normalizing to 0.0 - 1.0 range
            BOUNDARIES[i] = new Step(currX / totalX, currY / totalY);
        }
    }

    /**
     * Pure function: The only input is x.
     * Boundaries and transitionWidth are self-contained.
     */
    public static float fixedCurvedStep(double x) {
        // Clamp input
        double val = Math.max(0.0, Math.min(1.0, x));

        if (val == 0.0) return 0.0F;

        // Safety check for first ramp
        if (val < (BOUNDARIES[0].x - TRANSITION_WIDTH)) return 0.0F;

        for (int i = 0; i < BOUNDARIES.length; i++) {
            double targetX = BOUNDARIES[i].x;
            double targetY = BOUNDARIES[i].y;
            double prevY = (i > 0) ? BOUNDARIES[i - 1].y : 0.0;

            double tStart = targetX - TRANSITION_WIDTH;
            double tEnd = targetX;

            // Ramp (Monotonic Smoothstep)
            if (val >= tStart && val <= tEnd) {
                double t = (val - tStart) / TRANSITION_WIDTH;
                double smoothT = t * t * (3 - 2 * t);
                return (float) (prevY + smoothT * (targetY - prevY));
            }

            // Plateau
            double nextRampStart = (i + 1 < BOUNDARIES.length) ?
                    BOUNDARIES[i + 1].x - TRANSITION_WIDTH : 1.1;
            if (val > tEnd && val < nextRampStart) {
                return (float) targetY;
            }
        }

        return (float) BOUNDARIES[BOUNDARIES.length - 1].y;
    }

    public static float getTargetWaterHeight(float continentEdge) {
        return fixedCurvedStep(continentEdge);
    }

    public static float getWeightedWaterHeight(float inlandPercentage) {
        return getTargetWaterHeight(inlandPercentage) * 0.50F;
    }

    /**
     * Determines if a sampling point is on a flat plateau (step).
     * * @param x The input sampling coordinate (0.0 to 1.0)
     * @return A float from 0.0 to 1.0. Returns 1.0 at the exact center of
     * any plateau, fading down to 0.0 at or outside the plateau edges.
     */
    public static float getFlatnessFactor(double x) {
        double val = Math.max(0.0, Math.min(1.0, x));

        // Handle the initial plateau before the very first ramp
        double firstRampStart = BOUNDARIES[0].x - TRANSITION_WIDTH;
        if (val < firstRampStart) {
            // The plateau goes from 0.0 to firstRampStart
            double mid = firstRampStart / 2.0;
            double radius = firstRampStart / 2.0;
            double linearDist = 1.0 - (Math.abs(val - mid) / radius);
            return (float) (linearDist * linearDist * (3.0 - 2.0 * linearDist)); // Smoothstep
        }

        for (int i = 0; i < BOUNDARIES.length; i++) {
            double pStart = BOUNDARIES[i].x; // Top of current ramp
            double pEnd = (i + 1 < BOUNDARIES.length) ?
                    BOUNDARIES[i + 1].x - TRANSITION_WIDTH : 1.0; // Start of next ramp

            // Check if x falls within this specific flat plateau
            if (val >= pStart && val <= pEnd) {
                double mid = (pStart + pEnd) / 2.0;
                double radius = (pEnd - pStart) / 2.0;

                // Safety check for extremely narrow plateaus to prevent division by zero
                if (radius < 1e-6) return 0.0F;

                // Linear weight: 1.0 at center, 0.0 at edges
                double linearDist = 1.0 - (Math.abs(val - mid) / radius);

                // Apply smoothstep shaping for an elegant fall-off curve
                return (float) (linearDist * linearDist * (3.0 - 2.0 * linearDist));
            }
        }

        // If x is currently inside a transition ramp, it's not on a step at all
        return 0.0F;
    }
}


