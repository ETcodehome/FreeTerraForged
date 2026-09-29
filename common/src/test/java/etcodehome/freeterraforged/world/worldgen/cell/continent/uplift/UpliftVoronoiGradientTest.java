package etcodehome.freeterraforged.world.worldgen.cell.continent.uplift;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;

class UpliftVoronoiGradientTest {
	@Test
	void gradientMatchesTheArrayBasedCalculationBitForBit() {
		int[] seeds = { 0, 137, -28903 };
		float[] jitters = { 0.25F, 0.7F, 1.0F };
		for (int seed : seeds) {
			for (float jitter : jitters) {
				for (int zi = -100; zi <= 100; zi += 3) {
					for (int xi = -100; xi <= 100; xi += 3) {
						float x = xi * 0.037F;
						float z = zi * 0.041F;
						float expected = originalGradient(seed, jitter, x, z);
						float actual = UpliftContinentGenerator.smoothVoronoiGradient(seed, jitter, x, z);
						assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual),
							() -> "seed=" + seed + ", jitter=" + jitter + ", x=" + x + ", z=" + z);
					}
				}
			}
		}
	}

	private static float originalGradient(int seed, float jitter, float x, float y) {
		int xi = NoiseUtil.floor(x);
		int yi = NoiseUtil.floor(y);
		int cellX = xi;
		int cellY = yi;
		float cellPointX = x;
		float cellPointY = y;
		float nearestSq = Float.MAX_VALUE;
		for (int cy = yi - 1; cy <= yi + 1; ++cy) {
			for (int cx = xi - 1; cx <= xi + 1; ++cx) {
				NoiseUtil.Vec2f vec = NoiseUtil.cell(seed, cx, cy);
				float px = cx + vec.x() * jitter;
				float py = cy + vec.y() * jitter;
				float dist2 = distanceSquared(x, y, px, py);
				if (dist2 < nearestSq) {
					nearestSq = dist2;
					cellPointX = px;
					cellPointY = py;
					cellX = cx;
					cellY = cy;
				}
			}
		}
		float[] neighborX = new float[8];
		float[] neighborY = new float[8];
		int nIndex = 0;
		for (int cy2 = cellY - 1; cy2 <= cellY + 1; ++cy2) {
			for (int cx2 = cellX - 1; cx2 <= cellX + 1; ++cx2) {
				if (cx2 != cellX || cy2 != cellY) {
					NoiseUtil.Vec2f vec2 = NoiseUtil.cell(seed, cx2, cy2);
					neighborX[nIndex] = cx2 + vec2.x() * jitter;
					neighborY[nIndex] = cy2 + vec2.y() * jitter;
					nIndex++;
				}
			}
		}
		float s0Sq = cellPointX * cellPointX + cellPointY * cellPointY;
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
				float h_c = baseHalfDiff - (cellPointX * dx + cellPointY * dy);
				if (h_c > 0.00001F) {
					float planeValue = h_x / h_c;
					if (planeValue < minGradient) minGradient = planeValue;
				}
			}
		}
		return NoiseUtil.clamp(minGradient, 0.0F, 1.0F);
	}

	private static float distanceSquared(float x1, float y1, float x2, float y2) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		return dx * dx + dy * dy;
	}
}
