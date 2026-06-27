package raccoonman.reterraforged.mixin;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BlockPos.class)
public abstract class BlockPosMixin {

    // The hardcoded bit allocation masks from vanilla
    private static final int PACKED_X_LENGTH = 26;
    private static final int PACKED_Z_LENGTH = 26;
    private static final int PACKED_Y_LENGTH = 12;

    private static final int PACKED_Z_SHIFT = PACKED_Y_LENGTH; // 12
    private static final int PACKED_X_SHIFT = PACKED_Y_LENGTH + PACKED_Z_LENGTH; // 38

    private static final long PACKED_X_MASK = (1L << PACKED_X_LENGTH) - 1L; // 0x3FFFFFF
    private static final long PACKED_Z_MASK = (1L << PACKED_Z_LENGTH) - 1L; // 0x3FFFFFF
    private static final long PACKED_Y_MASK = (1L << PACKED_Y_LENGTH) - 1L; // 0xFFF

    // Your production offset configuration
    // Maps world Y = -256 to internal bits 000000000000 (0)
    // Maps world Y = 3839 to internal bits 111111111111 (4095)
    private static final int Y_OFFSET = 256;

    /**
     * Overwrites vanilla symmetric 12-bit signed Y packing with an asymmetric biased encoding.
     * This preserves full X/Z world limits while unlocking a massive +3839 skybox.
     */
    @Overwrite
    public static long asLong(int x, int y, int z) {
        long packed = 0L;

        // Pack X into the highest 26 bits
        packed |= ((long) x & PACKED_X_MASK) << PACKED_X_SHIFT;

        // Pack Z into the middle 26 bits
        packed |= ((long) z & PACKED_Z_MASK) << PACKED_Z_SHIFT;

        // Apply a bias to Y and force it into an unsigned 12-bit space.
        // The bitwise AND is a critical safety net: it guarantees that even if an out-of-bounds
        // Y coordinate is passed, it will never bleed into the Z-axis bits.
        long biasedY = (long) (y + Y_OFFSET) & PACKED_Y_MASK;
        packed |= biasedY;

        return packed;
    }

    /**
     * Unpacks the biased 12-bit unsigned Y coordinate and restores the true world positioning.
     */
    @Overwrite
    public static int getY(long packedLong) {
        // Isolate the lowest 12 bits as a pure positive integer (0 to 4095)
        int unsignedY = (int) (packedLong & PACKED_Y_MASK);

        // Subtract the bias to return the accurate coordinate back to the engine
        return unsignedY - Y_OFFSET;
    }
}