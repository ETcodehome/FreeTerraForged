package raccoonman.reterraforged.world.worldgen;

import net.minecraft.nbt.CompoundTag;

public class ChunkFlowField {
    private final byte[] flowGrid = new byte[256];
    private boolean hasRivers = false;

    public void setFlow(int localX, int localZ, byte packedAngle) {
        this.flowGrid[(localZ << 4) | localX] = packedAngle;
        this.hasRivers = true;
    }

    public byte getAngle(int localX, int localZ) {
        return this.flowGrid[(localZ << 4) | localX];
    }

    public boolean hasRivers() { return this.hasRivers; }
    public byte[] getRawGrid() { return this.flowGrid; }

    public void writeToNbt(CompoundTag tag) {
        if (hasRivers) {
            tag.putByteArray("RTFFlowField", flowGrid);
        }
    }

    public void readFromNbt(CompoundTag tag) {
        if (tag.contains("RTFFlowField")) {
            byte[] read = tag.getByteArray("RTFFlowField");
            System.arraycopy(read, 0, this.flowGrid, 0, Math.min(read.length, 256));
            this.hasRivers = true;
        }
    }

    public void copyFrom(ChunkFlowField other) {
        System.arraycopy(other.getRawGrid(), 0, this.flowGrid, 0, 256);
        this.hasRivers = other.hasRivers();
    }

    public void loadRawGrid(byte[] sourceGrid) {
        System.arraycopy(sourceGrid, 0, this.flowGrid, 0, Math.min(sourceGrid.length, 256));
        // Automatically flag as active if any river data exists in this array
        this.hasRivers = false;
        for (byte b : this.flowGrid) {
            if (b != 0) {
                this.hasRivers = true;
                break;
            }
        }
    }
}
