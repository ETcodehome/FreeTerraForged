package raccoonman.reterraforged.world.worldgen.runtime;

/** How much behavior a compiled worldgen facet can soundly own. */
public enum CapabilityState {
	NORMALIZED,
	OPAQUE_LEAF,
	OPAQUE_ROOT,
	PROVIDER_CONTRACT,
	UNAVAILABLE
}
