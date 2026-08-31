package raccoonman.reterraforged.world.worldgen.runtime;

/**
 * Declares how an immutable facet plan may be queried by one owner.
 *
 * <p>This is a capability contract, not an optimization hint. A facet may opt into parallel reads
 * only when its executable closure contains immutable or concurrency-safe state and every mutable
 * query object is confined to one worker. Undeclared mechanisms remain serial.</p>
 */
public enum WorldgenQueryMode {
	OWNER_SERIAL,
	ISOLATED_PARALLEL_READ;

	public boolean supportsIsolatedParallelRead() {
		return this == ISOLATED_PARALLEL_READ;
	}
}
