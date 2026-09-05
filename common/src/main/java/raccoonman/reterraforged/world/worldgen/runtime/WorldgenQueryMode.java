package raccoonman.reterraforged.world.worldgen.runtime;

public enum WorldgenQueryMode {
	OWNER_SERIAL,
	ISOLATED_PARALLEL_READ;

	public boolean supportsIsolatedParallelRead() {
		return this == ISOLATED_PARALLEL_READ;
	}
}
