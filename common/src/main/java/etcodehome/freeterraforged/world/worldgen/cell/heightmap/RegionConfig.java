package etcodehome.freeterraforged.world.worldgen.cell.heightmap;

import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public record RegionConfig(int seed, int scale, Noise warpX, Noise warpZ, float warpStrength) {
}
