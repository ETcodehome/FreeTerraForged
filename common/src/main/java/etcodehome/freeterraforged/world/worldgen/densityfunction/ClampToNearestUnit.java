package etcodehome.freeterraforged.world.worldgen.densityfunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public record ClampToNearestUnit(DensityFunction function, int resolution) implements DensityFunction {
	public static final MapCodec<ClampToNearestUnit> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		DensityFunction.HOLDER_HELPER_CODEC.fieldOf("function").forGetter(ClampToNearestUnit::function),
		Codec.intRange(1, Integer.MAX_VALUE).fieldOf("resolution").forGetter(ClampToNearestUnit::resolution)
	).apply(instance, ClampToNearestUnit::new));

	public ClampToNearestUnit {
		if (resolution <= 0) {
			throw new IllegalArgumentException("Density quantization resolution must be positive");
		}
	}
	
	@Override
	public double compute(FunctionContext ctx) {
		return this.computeClamped(this.function.compute(ctx));
	}

	@Override
	public void fillArray(double[] arr, ContextProvider ctx) {
		this.function.fillArray(arr, ctx);
		for(int i = 0; i < arr.length; i++) {
			arr[i] = this.computeClamped(arr[i]);
		}
	}

	@Override
	public DensityFunction mapAll(Visitor visitor) {
		return visitor.apply(new ClampToNearestUnit(this.function.mapAll(visitor), this.resolution));
	}

	@Override
	public double minValue() {
		return this.computeClamped(this.function.minValue());
	}

	@Override
	public double maxValue() {
		return this.computeClamped(this.function.maxValue());
	}

	@Override
	public KeyDispatchDataCodec<ClampToNearestUnit> codec() {
		return new KeyDispatchDataCodec<>(CODEC);
	}
	
	private double computeClamped(double value) {
		double scaled = value * this.resolution;
		if (!Double.isFinite(scaled)) {
			// Preserve infinite bounds. For finite values whose product overflows, one
			// quantization unit is already far smaller than their representable spacing.
			return value;
		}
		// Retain truncation toward zero plus one unit, without integer overflow.
		double truncated = scaled < 0.0D ? Math.ceil(scaled) : Math.floor(scaled);
		return (truncated + 1.0D) / this.resolution;
	}
}
