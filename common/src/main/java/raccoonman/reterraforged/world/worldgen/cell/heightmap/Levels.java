package raccoonman.reterraforged.world.worldgen.cell.heightmap;

import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public class Levels {
    public int terrainScaleFactor;
    public int worldHeight;
    public int worldDepth;
    public float unit;
    public float min;
    public float max;
    public int waterY;
    private int groundY;
    public int groundLevel;
    public int waterLevel;
    public float ground;
    public float water;
    private float elevationRange;

    public Levels(int terrainScaleFactor, int height, int depth, int seaLevel) {
        this.terrainScaleFactor = Math.max(1, terrainScaleFactor);
        this.worldHeight = Math.max(1, height);
        this.worldDepth = Math.max(0, depth);
        this.unit = NoiseUtil.div(1, this.terrainScaleFactor);
        this.min = this.scale(-this.worldDepth);
        this.waterLevel = seaLevel;
        this.groundLevel = this.waterLevel + 1;
        this.waterY = Math.min(this.waterLevel - 1, this.terrainScaleFactor);
        this.groundY = Math.min(this.groundLevel - 1, this.terrainScaleFactor);
        this.ground = NoiseUtil.div(this.groundY, this.terrainScaleFactor);
        this.water = NoiseUtil.div(this.waterY, this.terrainScaleFactor);
        this.elevationRange = 1.0F - this.water;
    }
    
    public int scale(float value) {
        return (int) (value * this.terrainScaleFactor);
    }
    
    public float elevation(float value) {
        if (value <= this.water) {
            return 0.0F;
        }
        return (value - this.water) / this.elevationRange;
    }
    
    public float elevation(int y) {
        if (y <= this.waterY) {
            return 0.0F;
        }
        return this.scale(y - this.waterY) / this.elevationRange;
    }
    
    public float scale(int level) {
        return NoiseUtil.div(level, this.terrainScaleFactor);
    }
    
    public float water(int amount) {
        return NoiseUtil.div(this.waterY + amount, this.terrainScaleFactor);
    }
    
    public float ground(int amount) {
        return NoiseUtil.div(this.groundY + amount, this.terrainScaleFactor);
    }

    public float getNormalizedInlandElevation(float rawCellHeight) {
        return (scale(rawCellHeight) - waterLevel) / (float) (worldHeight - waterLevel);
    }
}
