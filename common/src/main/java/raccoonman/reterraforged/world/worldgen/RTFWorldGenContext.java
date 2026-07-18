package raccoonman.reterraforged.world.worldgen;

public class RTFWorldGenContext {
    // Safely tracks if the ChunkMap/RandomState currently being built belongs to the true overworld
    public static final ThreadLocal<Boolean> IS_VANILLA_OVERWORLD = ThreadLocal.withInitial(() -> false);
}