package etcodehome.freeterraforged.world.worldgen.biome;

import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;

public enum ReferenceWeirdness implements BiomeParameter {
    MID_SLICE_NORMAL_ASCENDING(-1.0F, -0.93333334F),
    HIGH_SLICE_NORMAL_ASCENDING(-0.93333334F, -0.7666667F),
    PEAK_NORMAL(-0.7666667F, -0.56666666F),
    HIGH_SLICE_NORMAL_DESCENDING(-0.56666666F, -0.4F),
    MID_SLICE_NORMAL_DESCENDING(-0.4F, -0.26666668F),
    LOW_SLICE_NORMAL_DESCENDING(-0.26666668F, -0.05F),
    VALLEY(-0.05F, 0.05F),
    LOW_SLICE_VARIANT_ASCENDING(0.05F, 0.26666668F),
    MID_SLICE_VARIANT_ASCENDING(0.26666668F, 0.4F),
    HIGH_SLICE_VARIANT_ASCENDING(0.4F, 0.56666666F),
    PEAK_VARIANT(0.56666666F, 0.7666667F),
    HIGH_SLICE_VARIANT_DESCENDING(0.7666667F, 0.93333334F),
    MID_SLICE_VARIANT_DESCENDING(0.93333334F, 1.0F);
	
    private float min;
    private float max;

    private ReferenceWeirdness(float min, float max) {
    	this.min = min;
    	this.max = max;
    }
    
    @Override
    public float min() {
    	return this.min;
    }
    
    @Override
    public float max() {
    	return this.max;
    }

	public static float fromSediment(float sediment) {
		float magnitude = NoiseUtil.clamp(sediment + 0.5F, 0.0F, 1.0F);
		return -magnitude;
	}

	public static float fromSediment(float sediment, float macroBiomeId) {
		float magnitude = NoiseUtil.clamp(sediment + 0.5F, 0.0F, 1.0F);
		return macroBiomeId > 0.5F ? magnitude : -magnitude;
	}
}