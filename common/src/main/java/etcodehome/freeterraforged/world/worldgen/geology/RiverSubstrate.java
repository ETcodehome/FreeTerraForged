package etcodehome.freeterraforged.world.worldgen.geology;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings.RiverZone;

/** Shallow alluvial cover follows the actual river zones, above unchanged regional bedrock. */
public record RiverSubstrate(GeologySampler bedrock, long seed) implements GeologySampler {
    @Override
    public RockSample sample(int x, double y, int z) {
        return this.bedrock.sample(x, y, z);
    }

    @Override
    public RockSample sample(int x, double y, int z, Cell cell, int terrainScale) {
        RockSample rock = this.bedrock.sample(x, y, z);
        if (terrainScale <= 0 || !Float.isFinite(cell.height)) return rock;
        boolean channel = isChannel(cell);
        RiverZone zone = cell.riverZone;
        if (!channel && zone != RiverZone.Banks && zone != RiverZone.ValleyFloor) return rock;
        double top = (double) cell.height * terrainScale;
        double texture = LayeredGeology.noise(this.seed + 1879, x / 32.0, z / 32.0);
        double depth = initialDepth(cell, channel, texture);
        RockType material = channel || texture > 0.35 ? RockType.GRAVEL : RockType.PACKED_MUD;
        if (depth < 0.05) return rock;
        double bottom = top - depth;
        if (y >= top) {
            return new RockSample(rock.type(), Math.max(top, rock.lowerBoundaryY()));
        }
        if (y >= bottom) {
            return new RockSample(material, bottom);
        }
        return rock;
    }

    private static boolean isChannel(Cell cell) {
        return cell.riverZone == RiverZone.Riverbed || (cell.terrain != null && cell.terrain.isRiver());
    }

    private static double initialDepth(Cell cell, boolean channel, double texture) {
        if (channel) return 2.5 + texture * 1.5;
        double influence = Float.isFinite(cell.riverMask) ? Math.clamp(1.0 - cell.riverMask, 0, 1) : 0;
        double coverage = cell.riverZone == RiverZone.Banks ? 1.0 : influence;
        return Math.max(0, (1.5 + texture * 2.0) * coverage);
    }
}
