package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunctions;

import java.util.Random;

public class RiverCarverSettings {
    public float valleySize;
    public float fadeIn;
    public boolean connecting;
    public CurveFunction valleyCurve;

    public RiverCarverSettings(Random random) {
        this.valleySize = 275.0F;
        this.fadeIn = 0.7F;
        this.connecting = false;
        this.valleyCurve = getValleyType(random);
    }

    public static CurveFunction getValleyType(Random random) {
        int value = random.nextInt(100);
        if (value < 5) {
            return CurveFunctions.scurve(0.4F, 1.0F);
        }
        if (value < 30) {
            return CurveFunctions.scurve(4.0F, 5.0F);
        }
        if (value < 50) {
            return CurveFunctions.scurve(3.0F, 0.25F);
        }
        return CurveFunctions.scurve(2.0F, -0.5F);
    }

    public enum RiverZone{
        Riverbed,
        Banks,
        ValleyFloor,
        ValleyFadeout,
        None
    }
}
