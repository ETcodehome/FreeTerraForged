package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public enum RenderMode {
	BIOME {
    	
        @Override
        public boolean handlesWater() {
            return true;
        }

		@Override
		public int getColor(Cell cell, Levels levels, int biomeColor) {
			float shade;
			if (cell.height < levels.water) {
				float depthRange = Math.max(0.0001F, levels.water - levels.min);
				float depth = NoiseUtil.clamp((levels.water - cell.height) / depthRange, 0.0F, 1.0F);
				shade = 1.0F - depth * 0.4F;
			} else {
				float elevation = levels.getNormalizedInlandElevation(cell.height);
                elevation = (float) cubicHermite(0.0, 1.0, 3.0, 0.0, elevation);
				shade = 0.88F + elevation * 0.12F;
			}
			return this.getColor(cell, levels, shade, 0.0F, biomeColor);
		}

        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {
			return 0xFFFF00FF;
		}

		@Override
		public int getColor(Cell cell, Levels levels, float scale, float bias, int biomeColor) {
			int red = biomeColor & 0xFF;
			int green = biomeColor >>> 8 & 0xFF;
			int blue = biomeColor >>> 16 & 0xFF;
			float[] hsb = Color.RGBtoHSB(red, green, blue, new float[3]);
			return rgba(hsb[0], hsb[1], NoiseUtil.clamp((hsb[2] * scale) + bias, 0.0F, 1.0F));
		}
	},
    TRANSITION_POINTS {
    	
        @Override
        public boolean handlesWater() {
            return true;
        }

        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {
            switch (cell.terrain.getCategory()) {
                case DEEP_OCEAN:
                    return rgba(0.63F, 0.65F, 0.8F);
                case SHALLOW_OCEAN:
                    return rgba(0.6F, 0.6F, 0.8F);
                case BEACH:
                    return rgba(0.2F, 0.4F, 0.75F);
                case COAST:
                    return rgba(0.35F, 0.75F, 0.65F);
                default:
                    if (cell.terrain.isRiver() || cell.terrain.isWetland()) {
                        return rgba(0.6F, 0.6F, 0.8F);
                    }
                    return rgba(0.3F, 0.7F, 0.5F);
            }
        }
    },
    TEMPERATURE {
    	
        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {
            float saturation = 0.7F;
            float brightness = 0.8F;
            return rgba(step(1 - cell.regionTemperature, 8) * 0.65F, saturation, brightness);
        }
    },
    MOISTURE {
    	
        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {
            float saturation = 0.7F;
            float brightness = 0.8F;
            return rgba(step(cell.regionMoisture, 8) * 0.65F, saturation, brightness);
        }
    },
    BIOME_CELLS {

		@Override
		public boolean handlesWater() {
			return true;
		}
    	
        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {
            float saturation = 0.7F;
            float brightness = 0.8F;
            return rgba(cell.biomeRegionId, saturation, brightness);
        }
    },
    MACRO_NOISE {
    	
        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {
            float saturation = 0.7F;
            float brightness = 0.8F;
            return rgba(cell.macroBiomeId, saturation, brightness);
        }
    },
    TERRAIN_REGION {
    	
        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {
            float saturation = 0.7F;
            float brightness = 0.8F;
            return rgba(cell.terrain.getRenderHue(), saturation, brightness);
        }
    },
    HYPSOMETRIC {

        @Override
        public boolean handlesWater() {
            return true;
        }

        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {

            // highlight watery regions
            if (cell.terrain.isWateryButNotOcean()) {
                return RenderMode.getWaterColor();
            }

            // Grey the ocean to keep focus on landmasses
            if (cell.height <= levels.water) {
                return rgba(17, 17, 17);
            }

            // Normalize height relative to sea level
            // 'elevation' will now be 0.0 at the shoreline and 1.0 at the highest peak
            float elevation = levels.getNormalizedInlandElevation(cell.height);
            elevation = (float) cubicHermite(0.0, 1.0, 3.0, 0.0, elevation);

            // Map Normalized Height to Hue
            // We start the hue at 0.35F (Green/Spring) for lowlands
            // and transition to 0.0F (Red) for mountain peaks.
            float hue = 0.35F * (1.0F - elevation);

            // Adjust Saturation and Brightness for depth
            // Lowlands (near coast) are softer; peaks are more intense.
            float saturation = 0.4F + (elevation * 0.4F);
            float brightness = 0.6F + (elevation * 0.3F);

            return rgba(hue, saturation, brightness);
        }
    },
    TOPOGRAPHY {

        @Override
        public boolean handlesWater() {
            return true;
        }

        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {

            // Define color bands
            int contourSteps = 10;

            // handles ocean water but not river water
            if (cell.height < levels.water) {

                // Normalize depth relative to water level (0.0 at surface, 1.0 at floor)
                float depth = 1.0F - (cell.height / levels.water);
                float depthStep = step(depth, contourSteps);

                // Deep blue (0.65) to shallow cyan (0.55)
                float hue = 0.65F - (depthStep * 0.1F);
                float saturation = 0.4F + (depthStep * 0.4F); // Saturation peaks in shallows
                float brightness = 0.6F - (depthStep * 0.4F); // Darker as it gets deeper

                return rgba(hue, saturation, brightness);
            }

            // handles remaining water
            if (cell.terrain.isWateryButNotOcean()) {
                return RenderMode.getWaterColor();
            }

            // Normalize land height (0.0 at water level, 1.0 at peak)
            float elevation = levels.getNormalizedInlandElevation(cell.height);
            elevation = (float) cubicHermite(0.0, 1.0, 3.0, 0.0, elevation);
            float landStep = step(elevation, contourSteps);

            float hue = 0.05F;
            float saturation = 0.5F;
            // High contrast: dark shores to bright peaks
            float brightness = 0.2F + (landStep * 0.8F);

            return rgba(hue, saturation, brightness);

        }
    },
    CONTINENT_UPLIFT {

        @Override
        public boolean handlesWater() {
            return true;
        }

        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {

            // highlight watery regions
            if (cell.terrain.isWateryButNotOcean()) {
                return RenderMode.getWaterColor();
            }

            // Grey the ocean to keep focus on landmasses
            if (cell.height <= levels.water) {
                return rgba(17, 17, 17);
            }

            float edgeValue = NoiseUtil.clamp(cell.waterTable, 0.0F, 1.0F);
            float hue = 0.0F;
            float saturation = 0.0F;
            float brightness = edgeValue;
            return rgba(hue, saturation, brightness);
        }
    },
    CONTINENT_EDGE {

        @Override
        public boolean handlesWater() {
            return true;
        }

        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {
            float hue = 0.0F;
            float saturation = 0.0F;
            float brightness = cell.continentEdge;
            return rgba(hue, saturation, brightness);
        }
    },
    RIVER_ZONE {

        @Override
        public boolean handlesWater() {
            return true;
        }

        @Override
        public int getColor(Cell cell, Levels levels, float scale, float bias) {

            switch (cell.riverZone){

                case None:
                    return rgba(17, 17,17);

                case Riverbed:
                    return rgba(0, 0,200);

                case Banks:
                    return rgba(0, 75,0);

                case ValleyFloor:
                    return rgba(0, 150,0);

                case ValleyFadeout:
                    return rgba(0, 255,0);

            }
            return rgba(17, 17,17);
        }
    };

	public int getColor(Cell cell, Levels levels) {
		return this.getColor(cell, levels, 0xFFFF00FF);
	}

	public int getColor(Cell cell, Levels levels, int biomeColor) {
		if (!this.handlesWater() && cell.height < levels.water) {
			return getWaterColor();
        }
        float bands = 10.0F;
        float alpha = 0.2F;
        float elevation = (cell.height - levels.water) / (1.0F - levels.water);
        int band = NoiseUtil.round(elevation * bands);
        float scale = 1.0F - alpha;
        float bias = alpha * (band / bands);
		return getColor(cell, levels, scale, bias, biomeColor);
	}

	public int getColor(Cell cell, Levels levels, float scale, float bias, int biomeColor) {
		return getColor(cell, levels, scale, bias);
	}

    public abstract int getColor(Cell cell, Levels levels, float scale, float bias);

	public boolean handlesWater() {
		return false;
	}

	public String displayName() {
		return this == BIOME_CELLS ? "BIOME_CELLS (RTF diagnostic)" : this.name();
	}

	private static int getWaterColor() {
        return rgba(40, 140, 200);
    }

    private static float step(float value, int steps) {
        return ((float) NoiseUtil.round(value * steps)) / steps;
    }

    private static int rgba(float h, float s, float b) {
		int argb = Color.HSBtoRGB(h, NoiseUtil.clamp(s, 0.0F, 1.0F), NoiseUtil.clamp(b, 0.0F, 1.0F));
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue =  argb & 0xFF;
        return rgba(red, green, blue);
    }

    private static int rgba(int r, int g, int b) {
        return r + (g << 8) + (b << 16) + (255 << 24);
    }

    /**
     * General Cubic Hermite Interpolation.
     *
     * @param p0 Start point (value at t = 0)
     * @param p1 End point (value at t = 1)
     * @param m0 Start tangent (slope at t = 0)
     * @param m1 End tangent (slope at t = 1)
     * @param t  Normalized linear input in [0, 1]
     */
    public static double cubicHermite(double p0, double p1, double m0, double m1, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        double t2 = t * t;
        double t3 = t2 * t;

        // Hermite Basis Functions
        double h00 =  2 * t3 - 3 * t2 + 1; // Basis for start value p0
        double h10 =      t3 - 2 * t2 + t; // Basis for start tangent m0
        double h01 = -2 * t3 + 3 * t2;     // Basis for end value p1
        double h11 =      t3 -     t2;     // Basis for end tangent m1

        return h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1;
    }
}
