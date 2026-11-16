package etcodehome.freeterraforged.world.worldgen.feature;

import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;

public class ScreeNoises {
    public final Noise rand;
    public final Noise clusterNoise;
    public final Noise warpX;
    public final Noise warpZ;
    public final Noise desertErosionVariance;

    public ScreeNoises(long worldSeed, Levels levels) {
        this.rand = Noises.white((int) worldSeed, 1);
        this.clusterNoise = Noises.perlin((int) (worldSeed + 7777), 2, 1);
        this.warpX = Noises.perlin((int) (worldSeed + 1001), 2, 1);
        this.warpZ = Noises.perlin((int) (worldSeed + 2002), 2, 1);
        this.desertErosionVariance = Noises.mul(Noises.perlin(435, 8, 1), levels.scale(16));
    }
}
