package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;

public record WorldgenProviderDiagnostic(
	String source,
	Optional<ResourceLocation> provider,
	Optional<WorldgenFacet> facet,
	CapabilityFailure failure
) {
	public WorldgenProviderDiagnostic {
		source = Objects.requireNonNull(source, "source");
		provider = Objects.requireNonNull(provider, "provider");
		facet = Objects.requireNonNull(facet, "facet");
		failure = Objects.requireNonNull(failure, "failure");
	}
}
