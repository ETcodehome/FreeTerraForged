package raccoonman.reterraforged.world.worldgen.biome;

/**
 * Thread-local hand-off for one preview biome query.
 *
 * The ordinary MultiNoise call can pass through the TerraBlender parameter
 * list and then through the biome-source return hook.  Keeping the result of
 * that composition for the duration of the query lets the preview resolver
 * and the return hook reuse it without running the region/banding search a
 * second time.  The context is inactive during normal world generation.
 */
public final class PreviewBiomeQueryContext {
    private static final ThreadLocal<State> CURRENT = ThreadLocal.withInitial(State::new);

    private PreviewBiomeQueryContext() {
    }

    public static void begin(int x, int y, int z) {
        State state = CURRENT.get();
        state.active = true;
        state.x = x;
        state.y = y;
        state.z = z;
        state.recorded = false;
        state.original = null;
        state.banded = null;
    }

    public static void record(int x, int y, int z, Object original, Object banded) {
        State state = CURRENT.get();
        if (state.active && state.x == x && state.y == y && state.z == z) {
            state.recorded = true;
            state.original = original;
            state.banded = banded;
        }
    }

    public static boolean matches(int x, int y, int z, Object selected) {
        State state = CURRENT.get();
        return state.active && state.recorded
            && state.x == x && state.y == y && state.z == z
            && java.util.Objects.equals(state.banded, selected);
    }

    public static Object original() {
        return CURRENT.get().original;
    }

    public static void end() {
        State state = CURRENT.get();
        state.active = false;
        state.recorded = false;
        state.original = null;
        state.banded = null;
    }

    private static final class State {
        private boolean active;
        private boolean recorded;
        private int x;
        private int y;
        private int z;
        private Object original;
        private Object banded;
    }
}
