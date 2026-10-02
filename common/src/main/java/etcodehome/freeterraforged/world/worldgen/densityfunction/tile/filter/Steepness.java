package etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Filter.Visitor;
import java.util.Objects;

public final class Steepness implements Filter, Visitor {

	private static final float SQRT_2 = 1.4142135623730951F;

	private final int radius;
	private final float scaler;
	private final float waterLevel;

	// Pre-calculated multipliers to eliminate Math.sqrt and float divisions in hot loop
	private final float invDistOrth;
	private final float invDistDiag;
	private final float[] scalerTable;

	public Steepness(int radius, float scaler, float waterLevel) {
		this.radius = radius;
		this.scaler = scaler;
		this.waterLevel = waterLevel;
		this.invDistOrth = 1.0F / radius;
		this.invDistDiag = 1.0F / (SQRT_2 * radius);

		this.scalerTable = new float[9];
		for (int i = 1; i <= 8; i++) {
			this.scalerTable[i] = scaler / i;
		}
	}

	public int radius() {
		return this.radius;
	}

	public float scaler() {
		return this.scaler;
	}

	public float waterLevel() {
		return this.waterLevel;
	}

	@Override
	public void apply(Filterable cellMap, int seedX, int seedZ, int iterations) {
		this.iterate(cellMap, this);
	}

	@Override
	public void visit(Filterable cellMap, Cell cell, int cx, int cz) {
		float totalSlope = 0.0F;
		int validSamples = 0;
		float cellHeight = Math.max(cell.height, this.waterLevel);
		int r = this.radius;

		// 1. Orthogonal neighbours (dist = radius)
		Cell neighbour = cellMap.getCellRaw(cx - r, cz);
		if (neighbour != null && !neighbour.isAbsent()) {
			totalSlope += Math.abs(cellHeight - Math.max(neighbour.height, this.waterLevel)) * this.invDistOrth;
			validSamples++;
		}
		neighbour = cellMap.getCellRaw(cx + r, cz);
		if (neighbour != null && !neighbour.isAbsent()) {
			totalSlope += Math.abs(cellHeight - Math.max(neighbour.height, this.waterLevel)) * this.invDistOrth;
			validSamples++;
		}
		neighbour = cellMap.getCellRaw(cx, cz - r);
		if (neighbour != null && !neighbour.isAbsent()) {
			totalSlope += Math.abs(cellHeight - Math.max(neighbour.height, this.waterLevel)) * this.invDistOrth;
			validSamples++;
		}
		neighbour = cellMap.getCellRaw(cx, cz + r);
		if (neighbour != null && !neighbour.isAbsent()) {
			totalSlope += Math.abs(cellHeight - Math.max(neighbour.height, this.waterLevel)) * this.invDistOrth;
			validSamples++;
		}

		// 2. Diagonal neighbours (dist = sqrt(2) * radius)
		neighbour = cellMap.getCellRaw(cx - r, cz - r);
		if (neighbour != null && !neighbour.isAbsent()) {
			totalSlope += Math.abs(cellHeight - Math.max(neighbour.height, this.waterLevel)) * this.invDistDiag;
			validSamples++;
		}
		neighbour = cellMap.getCellRaw(cx + r, cz - r);
		if (neighbour != null && !neighbour.isAbsent()) {
			totalSlope += Math.abs(cellHeight - Math.max(neighbour.height, this.waterLevel)) * this.invDistDiag;
			validSamples++;
		}
		neighbour = cellMap.getCellRaw(cx - r, cz + r);
		if (neighbour != null && !neighbour.isAbsent()) {
			totalSlope += Math.abs(cellHeight - Math.max(neighbour.height, this.waterLevel)) * this.invDistDiag;
			validSamples++;
		}
		neighbour = cellMap.getCellRaw(cx + r, cz + r);
		if (neighbour != null && !neighbour.isAbsent()) {
			totalSlope += Math.abs(cellHeight - Math.max(neighbour.height, this.waterLevel)) * this.invDistDiag;
			validSamples++;
		}

		if (validSamples > 0) {
			cell.gradient = totalSlope * this.scalerTable[validSamples];
		} else {
			cell.gradient = 0.0F;
		}
	}

	public static Steepness make(int radius, float scaler, Levels levels) {
		return new Steepness(radius, scaler, levels.water);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Steepness steepness = (Steepness) o;
		return radius == steepness.radius &&
				Float.compare(steepness.scaler, scaler) == 0 &&
				Float.compare(steepness.waterLevel, waterLevel) == 0;
	}

	@Override
	public int hashCode() {
		return Objects.hash(radius, scaler, waterLevel);
	}

	@Override
	public String toString() {
		return "Steepness[radius=" + radius + ", scaler=" + scaler + ", waterLevel=" + waterLevel + "]";
	}
}