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
}


