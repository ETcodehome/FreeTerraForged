package etcodehome.freeterraforged.world.worldgen.geology;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.RiverCarverSettings.RiverZone;

class RiverSubstrateTest {
    private final GeologySampler bedrock = (x, y, z) ->
        new RockSample(RockType.ANDESITE, Double.NEGATIVE_INFINITY);
    private final RiverSubstrate rivers = new RiverSubstrate(this.bedrock, 42);

    private Cell cell(RiverZone zone) {
        Cell cell = new Cell();
        cell.height = 0.5F;
        cell.riverMask = 0.25F;
        cell.riverZone = zone;
        return cell;
    }

    @Test
    void channelsHaveShallowGravelAboveLocalBedrockWithExactBoundaries() {
        Cell cell = cell(RiverZone.Riverbed);
        RockSample deposit = rivers.sample(10, 99.9, 20, cell, 200);
        assertEquals(RockType.GRAVEL, deposit.type());
        assertTrue(deposit.lowerBoundaryY() >= 96 && deposit.lowerBoundaryY() <= 99);
        assertEquals(deposit, rivers.sample(10, deposit.lowerBoundaryY(), 20, cell, 200));
        assertEquals(bedrock.sample(10, 90, 20),
            rivers.sample(10, Math.nextDown(deposit.lowerBoundaryY()), 20, cell, 200));
        assertEquals(100, rivers.sample(10, 100, 20, cell, 200).lowerBoundaryY());
    }

    @Test
    void banksHaveCoherentPackedMudAndFloodplainCoverFadesWithRiverMask() {
        Cell banks = cell(RiverZone.Banks);
        int muddy = 0;
        for (int x = -256; x <= 256; x += 8) {
            if (rivers.sample(x, 99.99, 20, banks, 200).type() == RockType.PACKED_MUD) muddy++;
        }
        assertTrue(muddy > 20);
        Cell floodplain = cell(RiverZone.ValleyFloor);
        floodplain.riverMask = 1;
        assertEquals(bedrock.sample(0, 99.9, 0), rivers.sample(0, 99.9, 0, floodplain, 200));
        assertEquals(bedrock.sample(0, 99.9, 0), rivers.sample(0, 99.9, 0, cell(RiverZone.None), 200));
    }

    @Test
    void columnQueriesAndHeightScaleCannotLeakRiverCoverIntoOtherCells() {
        Cell channel = cell(RiverZone.Riverbed), dry = cell(RiverZone.None);
        RockSample expected = rivers.sample(-20, 99.9, -30, channel, 200);
        assertEquals(RockType.ANDESITE, rivers.sample(-20, 99.9, -30, dry, 200).type());
        assertEquals(expected, rivers.sample(-20, 99.9, -30, channel, 200));
        assertEquals(RockType.GRAVEL, rivers.sample(-20, 199.9, -30, channel, 400).type());
        assertEquals(RockType.ANDESITE, rivers.sample(-20, 150, -30, channel, 400).type());
    }

}
