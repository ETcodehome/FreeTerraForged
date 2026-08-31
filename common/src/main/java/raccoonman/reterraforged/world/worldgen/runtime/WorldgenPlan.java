package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;

/** One immutable, owner-bound plan spanning every independent worldgen facet. */
public record WorldgenPlan(
	WorldgenOwner owner,
	WorldgenPlans.BiomeComposition biomeComposition,
	WorldgenPlans.ProviderSelection providerSelection,
	WorldgenPlans.SelectionDecoration selectionDecoration,
	WorldgenPlans.SpatialOwnership spatialOwnership,
	WorldgenPlans.SamplerDecoration samplerDecoration,
	WorldgenPlans.DensitySettings densitySettings,
	WorldgenPlans.Surface surface,
	WorldgenPlans.Carvers carvers,
	WorldgenPlans.PlacedFeatures placedFeatures,
	WorldgenPlans.Structures structures,
	WorldgenExecution execution,
	WorldgenCapabilityReport report
) {
	public WorldgenPlan {
		owner = Objects.requireNonNull(owner, "owner");
		biomeComposition = Objects.requireNonNull(biomeComposition, "biomeComposition");
		providerSelection = Objects.requireNonNull(providerSelection, "providerSelection");
		selectionDecoration = Objects.requireNonNull(selectionDecoration, "selectionDecoration");
		spatialOwnership = Objects.requireNonNull(spatialOwnership, "spatialOwnership");
		samplerDecoration = Objects.requireNonNull(samplerDecoration, "samplerDecoration");
		densitySettings = Objects.requireNonNull(densitySettings, "densitySettings");
		surface = Objects.requireNonNull(surface, "surface");
		carvers = Objects.requireNonNull(carvers, "carvers");
		placedFeatures = Objects.requireNonNull(placedFeatures, "placedFeatures");
		structures = Objects.requireNonNull(structures, "structures");
		execution = Objects.requireNonNull(execution, "execution");
		report = Objects.requireNonNull(report, "report");
		if (!execution.equals(report.execution())) {
			throw new IllegalArgumentException("Worldgen plan and capability report execution modes differ");
		}
	}

	public WorldgenPlans.DomainPlan facet(WorldgenFacet facet) {
		return switch (facet) {
			case BIOME_COMPOSITION -> this.biomeComposition;
			case PROVIDER_SELECTION -> this.providerSelection;
			case SELECTION_DECORATION -> this.selectionDecoration;
			case SPATIAL_OWNERSHIP -> this.spatialOwnership;
			case SAMPLER_DECORATION -> this.samplerDecoration;
			case DENSITY_SETTINGS -> this.densitySettings;
			case SURFACE -> this.surface;
			case CARVERS -> this.carvers;
			case PLACED_FEATURES -> this.placedFeatures;
			case STRUCTURES -> this.structures;
		};
	}
}
