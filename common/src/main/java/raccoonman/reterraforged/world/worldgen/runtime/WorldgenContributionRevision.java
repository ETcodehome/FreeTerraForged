package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.concurrent.atomic.AtomicLong;

public final class WorldgenContributionRevision {
	private static final AtomicLong CURRENT = new AtomicLong();

	private WorldgenContributionRevision() {
	}

	public static long current() {
		return CURRENT.get();
	}

	public static long advance() {
		return CURRENT.incrementAndGet();
	}
}
