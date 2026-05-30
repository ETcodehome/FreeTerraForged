package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public enum RenderMode {
    BIOME_TYPE {
    	
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
                default:
                    if (cell.height < levels.water) {
                        return RenderMode.getWaterColor();
                    } else {
                        Color color = cell.biome.getColor();
                        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), new float[3]);
                        return rgba(hsb[0], hsb[1], (hsb[2] * scale) + bias);
                    }
            }
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
    BIOME {
    	
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
            // 'h' will now be 0.0 at the shoreline and 1.0 at the highest peak
            float h = (cell.height - levels.water) / (1.0F - levels.water);
            h = NoiseUtil.clamp(h, 0.0F, 1.0F);

            // Map Normalized Height to Hue
            // We start the hue at 0.35F (Green/Spring) for lowlands
            // and transition to 0.0F (Red) for mountain peaks.
            float hue = 0.35F * (1.0F - h);

            // Adjust Saturation and Brightness for depth
            // Lowlands (near coast) are softer; peaks are more intense.
            float saturation = 0.4F + (h * 0.4F);
            float brightness = 0.6F + (h * 0.3F);

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
            float landRange = 1.0F - levels.water;
            float landHeight = (cell.height - levels.water) / landRange;
            float landStep = step(landHeight, contourSteps);

            float hue = 0.05F;
            float saturation = 0.5F;
            // High contrast: dark shores to bright peaks
            float brightness = 0.2F + (landStep * 0.8F);

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

            if (cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean()) {
                return rgba(17, 17, 17);
            }

            // Ensure the value is clamped between 0.0 and 1.0
            float edgeValue = NoiseUtil.clamp(cell.continentEdge, 0.0F, 1.0F);

            // At 0.0: White (Saturation 0, Brightness 1)
            // At 1.0: Pure Red (Hue 0, Saturation 1, Brightness 1)

            float hue = 0.0F;              // Solid Red hue
            float saturation = edgeValue;  // Increases from 0 (White) to 1 (Red)
            float brightness = 1.0F;       // Maintain full brightness for "clean" look

            return rgba(hue, saturation, brightness);

        }
    };

    public int getColor(Cell cell, Levels levels) {
        if (!this.handlesWater() && cell.height < levels.water) {
            return getWaterColor();
        }
        float bands = 10.0F;
        float alpha = 0.2F;
        float elevation = (cell.height - levels.water) / (1.0F - levels.water);
        int band = NoiseUtil.round(elevation * bands);
        float scale = 1.0F - alpha;
        float bias = alpha * (band / bands);
        return getColor(cell, levels, scale, bias);
    }

    public abstract int getColor(Cell cell, Levels levels, float scale, float bias);

    public boolean handlesWater() {
        return false;
    }

    private static int getWaterColor() {
        return rgba(40, 140, 200);
    }

    private static float step(float value, int steps) {
        return ((float) NoiseUtil.round(value * steps)) / steps;
    }

    private static int rgba(float h, float s, float b) {
        int argb = Color.HSBtoRGB(h, s, b);
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue =  argb & 0xFF;
        return rgba(red, green, blue);
    }

    private static int rgba(int r, int g, int b) {
        return r + (g << 8) + (b << 16) + (255 << 24);
    }
}
