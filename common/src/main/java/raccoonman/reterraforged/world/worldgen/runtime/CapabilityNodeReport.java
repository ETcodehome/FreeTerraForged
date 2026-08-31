package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;

/** Immutable explanation for one compiled plan node. */
public record CapabilityNodeReport(
	ResourceLocation id,
	WorldgenFacet facet,
	CapabilityState state,
	String mechanism,
	WorldgenOwnerType ownerType,
	String detail,
	Optional<CapabilityFailure> firstCause
) {
	public CapabilityNodeReport {
		id = Objects.requireNonNull(id, "id");
		facet = Objects.requireNonNull(facet, "facet");
		state = Objects.requireNonNull(state, "state");
		mechanism = Objects.requireNonNull(mechanism, "mechanism");
		ownerType = Objects.requireNonNull(ownerType, "ownerType");
		detail = Objects.requireNonNull(detail, "detail");
		firstCause = Objects.requireNonNull(firstCause, "firstCause");
	}
}
