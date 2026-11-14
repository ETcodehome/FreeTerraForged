package etcodehome.freeterraforged.world.worldgen.cell.rivermap.river;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.wetland.Wetland;
import etcodehome.freeterraforged.world.worldgen.noise.module.Line;
import etcodehome.freeterraforged.world.worldgen.util.Boundsf;
import etcodehome.freeterraforged.world.worldgen.util.PosUtil;

import static org.junit.jupiter.api.Assertions.*;

public class RiverForkAttachmentTest {

    private static class RecordingRiverCarver implements FTFRiverCarver {
        final River river;
        final RiverWarp warp;
        float lastPrevX, lastPrevZ, lastPrevT;
        float lastCurrX, lastCurrZ, lastCurrT;
        int carveCallCount = 0;

        RecordingRiverCarver(River river, RiverWarp warp) {
            this.river = river;
            this.warp = warp;
        }

        @Override
        public boolean isMain() {
            return true;
        }

        @Override
        public River getRiver() {
            return this.river;
        }

        @Override
        public RiverWarp getWarp() {
            return this.warp;
        }

        @Override
        public RiverConfig getConfig() {
            return null;
        }

        @Override
        public void carve(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT) {
            this.lastPrevX = prevX;
            this.lastPrevZ = prevZ;
            this.lastPrevT = prevT;
            this.lastCurrX = currX;
            this.lastCurrZ = currZ;
            this.lastCurrT = currT;
            this.carveCallCount++;
        }

        @Override
        public RiverConfig createForkConfig(float t, Levels levels) {
            return null;
        }
    }

    @Test
    @DisplayName("Verify Network.carve passes parent-warped coordinates to children and wetlands")
    void testNetworkCarvePassesWarpedCoordinatesToChildren() {
        // Parent river from (0, 0) to (1000, 0)
        River parentRiver = new River(0.0F, 0.0F, 1000.0F, 0.0F);

        // Fixed warp that shifts by (+50, +80)
        RiverWarp parentWarp = new RiverWarp(42, 0.1F, 0.9F, 0.001F, 100.0F);

        RecordingRiverCarver parentCarver = new RecordingRiverCarver(parentRiver, parentWarp);

        // Child fork river
        River childRiver = new River(500.0F, 300.0F, 500.0F, 0.0F);
        RecordingRiverCarver childCarver = new RecordingRiverCarver(childRiver, RiverWarp.NONE);

        Network childNetwork = new Network(childCarver, new Wetland[0], new Network[0], Boundsf.NONE);
        Network parentNetwork = new Network(parentCarver, new Wetland[0], new Network[]{childNetwork}, Boundsf.NONE);

        Cell cell = new Cell();
        float inputX = 500.0F;
        float inputZ = 10.0F;

        parentNetwork.carve(cell, inputX, inputZ, 0.0F, 0.0F);

        assertEquals(1, parentCarver.carveCallCount, "Parent carver should have been called once");
        assertEquals(1, childCarver.carveCallCount, "Child carver should have been called once");

        // Parent river carver received inputX, inputZ as px, pz and warped coordinates as currX, currZ
        float parentWarpedX = parentCarver.lastCurrX;
        float parentWarpedZ = parentCarver.lastCurrZ;

        // Child carver MUST receive the parent's warped coordinates as its input x, z!
        // Because child has RiverWarp.NONE, its currX and currZ will equal its input x and z.
        assertEquals(parentWarpedX, childCarver.lastCurrX, 1e-4F,
            "Child network must receive parent-warped X coordinate so fork connects to meandering parent bed");
        assertEquals(parentWarpedZ, childCarver.lastCurrZ, 1e-4F,
            "Child network must receive parent-warped Z coordinate so fork connects to meandering parent bed");

        // Confirm that the parent warp actually differed from the raw input coordinates in Z (perpendicular to river)
        assertNotEquals(inputZ, parentWarpedZ, "Parent warp offset in Z must be non-zero for this test setup");
    }

    @Test
    @DisplayName("Verify child fork confluence mouth connects seamlessly to parent river bed without gap")
    void testForkConfluenceAttachesToMeanderingParentRiver() {
        Preset preset = Presets.makeFTFDefault();
        Levels levels = new Levels(preset.world().properties);
        LakeConfig lakeConfig = LakeConfig.of(preset.rivers().lakes, levels);

        River parentRiver = new River(0.0F, 0.0F, 1000.0F, 0.0F);
        RiverWarp parentWarp = new RiverWarp(1234, 0.1F, 0.9F, 0.001F, 100.0F);
        RiverConfig riverConfig = RiverConfig.builder(levels)
            .main(true)
            .bedWidth(8)
            .bankWidth(20)
            .bedDepth(6)
            .bankHeight(1, 4)
            .length(1000)
            .fade(0.2F)
            .build();

        RiverCarverSettings parentSettings = new RiverCarverSettings(new Random(42));
        parentSettings.connecting = false;
        parentSettings.fadeIn = riverConfig.fade;
        parentSettings.valleySize = 275.0F;

        FTFRiverCarver parentCarver = new UpliftRiverCarver(parentRiver, parentWarp, riverConfig, parentSettings, levels, lakeConfig, false);

        // Branch fork joins at offset = 0.5 along parent river: (500, 0)
        long junctionPos = parentRiver.pos(0.5F);
        float jx = PosUtil.unpackLeftf(junctionPos);
        float jz = PosUtil.unpackRightf(junctionPos);
        assertEquals(500.0F, jx, 1e-3F);
        assertEquals(0.0F, jz, 1e-3F);

        // Fork extends 400 blocks perpendicular from (500, 400) to (500, 0)
        River forkRiver = new River(500.0F, 400.0F, jx, jz);
        RiverWarp forkWarp = parentWarp.createChild(0.15F, 0.75F, 0.65F, new Random(42));
        RiverCarverSettings forkSettings = new RiverCarverSettings(new Random(42));
        forkSettings.connecting = true;
        forkSettings.fadeIn = riverConfig.fade;
        forkSettings.valleySize = 200.0F;

        FTFRiverCarver forkCarver = new UpliftRiverCarver(forkRiver, forkWarp, riverConfig, forkSettings, levels, lakeConfig, false);

        Network forkNetwork = new Network(forkCarver, new Wetland[0], new Network[0], Boundsf.NONE);
        Network parentNetwork = new Network(parentCarver, new Wetland[0], new Network[]{forkNetwork}, Boundsf.NONE);

        // Now find the world coordinates of the parent river bed at the junction offset.
        // Along the line t = 0.5, we compute what warp offset the parent applies.
        long warpOffset = parentWarp.getOffset(jx, jz, 0.5F, parentRiver);
        float dx = PosUtil.unpackLeftf(warpOffset);
        float dz = PosUtil.unpackRightf(warpOffset);

        // The world coordinate that maps to (jx, jz) under parent warp is (jx - dx, jz - dz):
        float worldJunctionX = jx - dx;
        float worldJunctionZ = jz - dz;

        // Verify that evaluating the parent network at this world position carves a river
        Cell cell = new Cell();
        cell.height = levels.water + 0.1F; // dry land initially
        parentNetwork.carve(cell, worldJunctionX, worldJunctionZ, 0.0F, 0.0F);

        assertTrue(cell.erosionMask, "Junction point must be carved with erosionMask = true");
        assertTrue(cell.terrain.isRiver(), "Junction point terrain must be RIVER");

        // Verify that tracing 20 blocks upstream along the child fork towards its source
        // also maintains continuous river carving without any gap at the confluence.
        for (float distUpstream = 2.0F; distUpstream <= 30.0F; distUpstream += 2.0F) {
            Cell upstreamCell = new Cell();
            upstreamCell.height = levels.water + 0.1F;

            // In parent-warped space, upstream along the fork is at (jx, jz + distUpstream)
            // World coordinate mapping:
            float targetWarpedX = jx;
            float targetWarpedZ = jz + distUpstream;

            // Compute warp at this location to invert to world coordinates
            float estT = Line.distanceOnLine(targetWarpedX, targetWarpedZ, parentRiver.x1, parentRiver.z1, parentRiver.x2, parentRiver.z2);
            long localWarp = parentWarp.getOffset(targetWarpedX, targetWarpedZ, estT, parentRiver);
            float ldx = PosUtil.unpackLeftf(localWarp);
            float ldz = PosUtil.unpackRightf(localWarp);

            float worldX = targetWarpedX - ldx;
            float worldZ = targetWarpedZ - ldz;

            parentNetwork.carve(upstreamCell, worldX, worldZ, 0.0F, 0.0F);
            assertTrue(upstreamCell.erosionMask,
                String.format("Fork channel %.1f blocks upstream from junction must be carved (no gap between fork and parent river)", distUpstream));
            assertTrue(upstreamCell.terrain.isRiver(),
                String.format("Fork channel %.1f blocks upstream must be RIVER terrain", distUpstream));
        }
    }
}
