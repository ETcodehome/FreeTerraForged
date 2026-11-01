package etcodehome.freeterraforged.world.worldgen.densityfunction;

import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;

public record ClimateDepthFunction(
	Supplier<WorldLookup> deferredLookup,
	int worldDepth,
	int worldHeight
) implements MarkerFunction.Mapped {

	@Override
	public double compute(FunctionContext ctx) {
		WorldLookup lookup = this.deferredLookup.get();
		if (lookup == null || lookup.getContext() == null || lookup.getContext().levels == null) {
			return 0.0D;
		}

		Cell cell = CellSampler.getCachedOrLookup(lookup, ctx.blockX(), ctx.blockZ(), false);
		int surfaceY = lookup.getContext().levels.scale(cell.height);
		int by = ctx.blockY();

		if (by <= surfaceY) {
			int depthSpan = surfaceY + this.worldDepth;
			if (depthSpan <= 0) {
				return 1.5D;
			}
			double factor = (double) (surfaceY - by) / (double) depthSpan;
			return Math.clamp(factor, 0.0D, 1.0D) * 1.5D;
		} else {
			int heightSpan = this.worldHeight - surfaceY;
			if (heightSpan <= 0) {
				return -1.5D;
			}
			double factor = (double) (by - surfaceY) / (double) heightSpan;
			return -Math.clamp(factor, 0.0D, 1.0D) * 1.5D;
		}
	}

	@Override
	public void fillArray(double[] arr, ContextProvider ctx) {
		ctx.fillAllDirectly(arr, this);
	}

	@Override
	public DensityFunction mapAll(Visitor visitor) {
		return visitor.apply(this);
	}

	@Override
	public double minValue() {
		return -1.5D;
	}

	@Override
	public double maxValue() {
		return 1.5D;
	}


	public record Marker(int worldDepth, int worldHeight) implements MarkerFunction {
		public static final MapCodec<Marker> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.INT.fieldOf("world_depth").forGetter(Marker::worldDepth),
			Codec.INT.fieldOf("world_height").forGetter(Marker::worldHeight)
		).apply(instance, Marker::new));

		@Override
		public KeyDispatchDataCodec<Marker> codec() {
			return new KeyDispatchDataCodec<>(CODEC);
		}
	}
}
