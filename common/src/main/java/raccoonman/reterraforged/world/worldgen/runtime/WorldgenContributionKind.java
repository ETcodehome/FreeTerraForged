package raccoonman.reterraforged.world.worldgen.runtime;

public enum WorldgenContributionKind {
	UNIQUE_ROOT,
	ORDERED_TRANSFORM,
	IMMUTABLE_SET,
	KEYED_REPLACEMENT,
	EXECUTABLE_LEAF_COLLECTION,
	UNSUPPORTED;

	public static WorldgenContributionKind parse(String value) {
		return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
	}
}
