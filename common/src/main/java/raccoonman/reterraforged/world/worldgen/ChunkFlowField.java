package raccoonman.reterraforged.world.worldgen;

import net.minecraft.nbt.CompoundTag;
import raccoonman.reterraforged.data.worldgen.preset.settings.FlowSettings;

public class ChunkFlowField {
    private static final String FLOW_FIELD_KEY = "RTFFlowField";
    private static final String FLOW_SETTINGS_KEY = "RTFFlowSettings";
    private static final byte FLOW_PARTICLES = 1;
    private static final byte BOAT_FLOW_DYNAMICS = 1 << 1;
    private static final byte NAVIGABLE_WATERFALLS = 1 << 2;
    private static final byte DEFAULT_SETTINGS = FLOW_PARTICLES | BOAT_FLOW_DYNAMICS | NAVIGABLE_WATERFALLS;

    private final byte[] flowGrid = new byte[256];
    private boolean hasRivers = false;
    private byte settings = DEFAULT_SETTINGS;

    // Bitmasks and Shift constants
    // Format: [ MAGNITUDE (bits 5-7) | DIRECTION (bits 0-4) ]
    public static final int DIR_BITS = 5;
    public static final int DIR_MASK = 0x1F;        // 0001 1111 (0..31)
    public static final int MAG_MASK = 0x07;        // 0000 0111 (0..7)
    public static final int MAG_SHIFT = 5;

    public static final double TWO_PI = 2.0 * Math.PI;

    // --- Mutators & Setters ---

    public void setFlow(int localX, int localZ, byte packedFlow) {
        this.flowGrid[(localZ << 4) | localX] = packedFlow;
        if (packedFlow != 0) {
            this.hasRivers = true;
        }
    }

    public void setFlow(int localX, int localZ, int magnitudeIndex, int directionIndex) {
        setFlow(localX, localZ, pack(magnitudeIndex, directionIndex));
    }

    public void setFlow(int localX, int localZ, float normalizedMagnitude, double radians) {
        setFlow(localX, localZ, pack(normalizedMagnitude, radians));
    }

    // --- Direct Getters ---

    public byte getPackedFlow(int localX, int localZ) {
        return this.flowGrid[(localZ << 4) | localX];
    }

    /**
     * Returns true if there is active flow at this cell (magnitude > 0).
     */
    public boolean hasFlow(int localX, int localZ) {
        return getMagnitude(localX, localZ) > 0;
    }

    /**
     * Returns the raw magnitude level from 0 (NO_FLOW) to 7 (MAX_FLOW).
     */
    public int getMagnitude(int localX, int localZ) {
        int raw = this.flowGrid[(localZ << 4) | localX] & 0xFF;
        return (raw >> MAG_SHIFT) & MAG_MASK;
    }

    /**
     * Returns the flow magnitude normalized to a 0.0f - 1.0f range.
     */
    public float getNormalizedMagnitude(int localX, int localZ) {
        return getMagnitude(localX, localZ) / 7.0F;
    }

    /**
     * Returns the discrete direction index from 0 to 31.
     */
    public int getDirectionIndex(int localX, int localZ) {
        return (this.flowGrid[(localZ << 4) | localX] & 0xFF) & DIR_MASK;
    }

    /**
     * Reconstructs the flow heading in radians [0, 2*PI).
     */
    public double getAngleRadians(int localX, int localZ) {
        int dirIndex = getDirectionIndex(localX, localZ);
        return (dirIndex / 32.0) * TWO_PI;
    }

    // --- General State & Utilities ---

    public boolean hasRivers() { return this.hasRivers; }
    public byte[] getRawGrid() { return this.flowGrid; }

    public void configure(FlowSettings settings) {
        this.settings = encodeSettings(settings);
    }

    public void loadSettings(byte settings) {
        this.settings = (byte) (settings & DEFAULT_SETTINGS);
    }

    public byte getSettings() {
        return this.settings;
    }

    public boolean enableFlowParticles() {
        return (this.settings & FLOW_PARTICLES) != 0;
    }

    public boolean enableBoatFlowDynamics() {
        return (this.settings & BOAT_FLOW_DYNAMICS) != 0;
    }

    public boolean enableNavigableWaterfalls() {
        return (this.settings & NAVIGABLE_WATERFALLS) != 0;
    }

    public void writeToNbt(CompoundTag tag) {
        if (hasRivers) {
            tag.putByteArray(FLOW_FIELD_KEY, flowGrid);
            tag.putByte(FLOW_SETTINGS_KEY, this.settings);
        }
    }

    public void readFromNbt(CompoundTag tag) {
        if (tag.contains(FLOW_FIELD_KEY)) {
            byte[] read = tag.getByteArray(FLOW_FIELD_KEY);
            System.arraycopy(read, 0, this.flowGrid, 0, Math.min(read.length, 256));
            this.settings = tag.contains(FLOW_SETTINGS_KEY) ? tag.getByte(FLOW_SETTINGS_KEY) : DEFAULT_SETTINGS;
            this.hasRivers = false;
            for (byte b : this.flowGrid) {
                if (b != 0) {
                    this.hasRivers = true;
                    break;
                }
            }
        }
    }

    public void copyFrom(ChunkFlowField other) {
        System.arraycopy(other.getRawGrid(), 0, this.flowGrid, 0, 256);
        this.hasRivers = other.hasRivers();
        this.settings = other.settings;
    }

    public void loadRawGrid(byte[] sourceGrid) {
        System.arraycopy(sourceGrid, 0, this.flowGrid, 0, Math.min(sourceGrid.length, 256));
        this.hasRivers = false;
        for (byte b : this.flowGrid) {
            if (b != 0) {
                this.hasRivers = true;
                break;
            }
        }
    }

    private static byte encodeSettings(FlowSettings settings) {
        byte encoded = 0;
        if (settings.enableFlowParticles()) {
            encoded |= FLOW_PARTICLES;
        }
        if (settings.enableBoatFlowDynamics()) {
            encoded |= BOAT_FLOW_DYNAMICS;
        }
        if (settings.enableNavigableWaterfalls()) {
            encoded |= NAVIGABLE_WATERFALLS;
        }
        return encoded;
    }

    // --- Static Packing Helpers ---

    /**
     * Packs discrete indices into a single byte.
     * @param magnitude Index 0..7 (0 = NO_FLOW)
     * @param direction Index 0..31
     */
    public static byte pack(int magnitude, int direction) {
        int mag = Math.min(7, Math.max(0, magnitude));
        int dir = direction & DIR_MASK;
        return (byte) ((mag << MAG_SHIFT) | dir);
    }

    /**
     * Packs continuous physical values into a single byte.
     * @param normalizedMagnitude Strength from 0.0f to 1.0f
     * @param radians Flow angle heading in radians
     */
    public static byte pack(float normalizedMagnitude, double radians) {
        if (normalizedMagnitude <= 0.0F) {
            return 0; // Magnitude 0 is strictly NO_FLOW
        }

        // Map [0.0, 1.0] to [1, 7]
        int magIndex = 1 + (int) (Math.min(1.0F, Math.max(0.0F, normalizedMagnitude)) * 6.0F);

        // Normalize angle into [0, 2*PI)
        double normalizedAngle = radians % TWO_PI;
        if (normalizedAngle < 0.0) {
            normalizedAngle += TWO_PI;
        }

        // Map angle to 5 bits (0..31)
        int dirIndex = (int) Math.round((normalizedAngle / TWO_PI) * 32.0) & DIR_MASK;

        return pack(magIndex, dirIndex);
    }
}
