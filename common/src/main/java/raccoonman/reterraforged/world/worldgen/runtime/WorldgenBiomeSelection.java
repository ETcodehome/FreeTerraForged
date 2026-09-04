package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.concurrent.Resource;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.ClimateQuerySemantics;
import raccoonman.reterraforged.world.worldgen.cell.Cell;

/** Shared generation/preview consumer for provider, spatial, and selection-decoration plans. */
public final class WorldgenBiomeSelection {
	private static final Set<WorldgenFacet> QUERY_FACETS = Set.of(
		WorldgenFacet.PROVIDER_SELECTION,
		WorldgenFacet.SELECTION_DECORATION,
		WorldgenFacet.SPATIAL_OWNERSHIP,
		WorldgenFacet.SAMPLER_DECORATION
	);

	private WorldgenBiomeSelection() {
	}

	public static Set<Holder<Biome>> possibleBiomes(WorldgenPlan plan) {
		LinkedHashSet<Holder<Biome>> possible = new LinkedHashSet<>();
		plan.providerSelection().providers().forEach(domain -> domain.candidates().values()
			.forEach(entry -> possible.add(entry.getSecond())));
		plan.providerSelection().fallback().ifPresent(table -> table.values()
			.forEach(entry -> possible.add(entry.getSecond())));
		plan.biomeComposition().entries().forEach(entry -> possible.add(entry.getSecond()));
		plan.providerSelection().directInput().ifPresent(input -> possible.addAll(input.possibleOutputs()));
		possible.addAll(plan.selectionDecoration().possibleOutputs());
		if (possible.isEmpty()) {
			throw new IllegalStateException(
				"FTF candidate-provider plan exposes no possible biomes; biome_composition="
					+ describe(plan.biomeComposition().descriptor()) + ", provider_selection="
					+ describe(plan.providerSelection().descriptor()) + ", selection_decoration="
					+ describe(plan.selectionDecoration().descriptor())
			);
		}
		return Collections.unmodifiableSet(possible);
	}

	private static String describe(PlanDescriptor descriptor) {
		return descriptor.state() + "/" + descriptor.mechanism()
			+ descriptor.firstCause().map(cause -> "[" + cause.code() + ": " + cause.message() + "]")
				.orElse("");
	}

	public static void requireExecutablePlan(WorldgenPlan plan) {
		requireAvailable(plan.providerSelection().descriptor());
		requireAvailable(plan.selectionDecoration().descriptor());
		requireAvailable(plan.spatialOwnership().descriptor());
		requireAvailable(plan.samplerDecoration().descriptor());
		if (plan.providerSelection().directInput().isPresent()) {
			if (!plan.providerSelection().providers().isEmpty()
				|| !plan.selectionDecoration().stages().isEmpty()
				|| plan.spatialOwnership().resolver().isPresent()) {
				throw new IllegalStateException(
					"A direct custom source root cannot share provider, spatial, or decorator execution"
				);
			}
			return;
		}
		if (plan.providerSelection().providers().isEmpty()) {
			throw new IllegalStateException("FTF biome selection has no candidate-provider domains");
		}
	}

	private static void requireAvailable(PlanDescriptor descriptor) {
		if (descriptor.state() != CapabilityState.UNAVAILABLE) {
			return;
		}
		CapabilityFailure cause = descriptor.firstCause().orElseThrow();
		throw new IllegalStateException(
			"FTF biome selection facet " + descriptor.facet() + " is unavailable ["
				+ cause.code() + "]: " + cause.message()
		);
	}

	public static Holder<Biome> resolve(
		WorldgenPlan plan,
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler
	) {
		return prepare(plan).resolve(quartX, quartY, quartZ, sampler);
	}

	/** Uses spatial coordinates already computed by the request's prepared FTF tile. */
	public static Holder<Biome> resolveInCell(
		WorldgenPlan plan,
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler,
		long biomeCellX,
		long biomeCellZ
	) {
		return prepare(plan).resolveInCell(
			quartX, quartY, quartZ, sampler, biomeCellX, biomeCellZ
		);
	}

	public static Executable prepare(WorldgenPlan plan) {
		requireExecutablePlan(plan);
		return new Executable(plan, possibleBiomes(plan));
	}

	static Executable prepare(WorldgenPlan plan, Set<Holder<Biome>> possibleBiomes) {
		requireExecutablePlan(plan);
		return new Executable(plan, possibleBiomes);
	}

	public static final class Executable {
		private final WorldgenPlan plan;
		private final BiomeSourcePlanInput directInput;
		private final WorldgenPlans.ProviderSelection providers;
		private final WorldgenPlans.SelectionDecoration decoration;
		private final WorldgenPlans.SpatialResolver spatialResolver;
		private final WorldgenPlans.SamplerDecoration samplerDecoration;
		private final WorldgenExecution execution;
		private final boolean isolatedParallelRead;
		private final Set<Holder<Biome>> possibleBiomes;

		private Executable(WorldgenPlan plan, Set<Holder<Biome>> possibleBiomes) {
			this.plan = plan;
			this.directInput = plan.providerSelection().directInput().orElse(null);
			this.providers = plan.providerSelection();
			this.decoration = plan.selectionDecoration();
			this.spatialResolver = plan.spatialOwnership().resolver().orElse(null);
			this.samplerDecoration = plan.samplerDecoration();
			this.execution = plan.execution();
			this.isolatedParallelRead = this.execution.supportsIsolatedParallelRead(QUERY_FACETS);
			this.possibleBiomes = Set.copyOf(possibleBiomes);
		}

		public WorldgenPlan plan() {
			return this.plan;
		}

		public boolean supportsIsolatedParallelRead() {
			return this.isolatedParallelRead;
		}

		public Set<Holder<Biome>> possibleBiomes() {
			return this.possibleBiomes;
		}

		public Holder<Biome> resolve(
			int quartX,
			int quartY,
			int quartZ,
			Climate.Sampler sampler
		) {
			if (this.isolatedParallelRead) {
				return this.resolveOwned(quartX, quartY, quartZ, sampler);
			}
			return this.execution.execute(
				QUERY_FACETS, () -> this.resolveOwned(quartX, quartY, quartZ, sampler)
			);
		}

		private Holder<Biome> resolveOwned(
			int quartX,
			int quartY,
			int quartZ,
			Climate.Sampler sampler
		) {
			if (this.directInput != null) {
				return this.directInput.resolve(quartX, quartY, quartZ, sampler);
			}
			RTFClimateSampler rtfSampler = requireOwnedSampler(sampler);
			ClimateQuerySemantics querySemantics = rtfSampler.climateQuerySemantics();
			GeneratorContext generatorContext = querySemantics.surfaceContext();
			if (generatorContext == null) {
				throw new IllegalStateException("FTF biome selection sampler has no generator context");
			}

			WorldgenPlans.SpatialResult spatial = rtfSampler.getBiomeCellCache()
				.find(this.plan, quartX, quartZ);
			if (spatial == null) {
				try (Resource<Cell> resource = Cell.getResource()) {
					Cell cell = resource.get().reset();
					generatorContext.lookup.applyBiomeRegion(
						cell, QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ)
					);
					spatial = this.requireSpatialResolver().resolve(
						cell.biomeRegionX, cell.biomeRegionZ
					);
				}
				rtfSampler.getBiomeCellCache().store(this.plan, quartX, quartZ, spatial);
			}
			return this.select(
				spatial, this.samplerDecoration.sample(sampler, quartX, quartY, quartZ),
				quartX, quartY, quartZ, sampler, generatorContext
			);
		}

		public Holder<Biome> resolveInCell(
			int quartX,
			int quartY,
			int quartZ,
			Climate.Sampler sampler,
			long biomeCellX,
			long biomeCellZ
		) {
			if (this.isolatedParallelRead) {
				return this.resolveOwnedInCell(
					quartX, quartY, quartZ, sampler, biomeCellX, biomeCellZ
				);
			}
			return this.execution.execute(
				QUERY_FACETS,
				() -> this.resolveOwnedInCell(
					quartX, quartY, quartZ, sampler, biomeCellX, biomeCellZ
				)
			);
		}

		private Holder<Biome> resolveOwnedInCell(
			int quartX,
			int quartY,
			int quartZ,
			Climate.Sampler sampler,
			long biomeCellX,
			long biomeCellZ
		) {
			if (this.directInput != null) {
				return this.directInput.resolve(quartX, quartY, quartZ, sampler);
			}
			RTFClimateSampler rtfSampler = requireOwnedSampler(sampler);
			ClimateQuerySemantics querySemantics = rtfSampler.climateQuerySemantics();
			GeneratorContext generatorContext = querySemantics.surfaceContext();
			if (generatorContext == null) {
				throw new IllegalStateException("FTF biome selection requires its owned sampler context");
			}
			WorldgenPlans.SpatialResult spatial = this.requireSpatialResolver()
				.resolve(biomeCellX, biomeCellZ);
			return this.select(
				spatial, this.samplerDecoration.sample(sampler, quartX, quartY, quartZ),
				quartX, quartY, quartZ, sampler, generatorContext
			);
		}

		private Holder<Biome> select(
			WorldgenPlans.SpatialResult spatial,
			Climate.TargetPoint target,
			int quartX,
			int quartY,
			int quartZ,
			Climate.Sampler sampler,
			GeneratorContext generatorContext
		) {
			ResourceLocation spatialDomain = spatial.domain();
			WorldgenPlans.ProviderResult selection;
			try {
				selection = this.providers.resolveRequired(spatialDomain, target);
			} catch (IllegalArgumentException failure) {
				throw new IllegalStateException(
					"Spatial provider plan selected unknown domain " + spatialDomain, failure
				);
			}
			if (!spatialDomain.equals(selection.domain())) {
				throw new IllegalStateException(
					"Spatial and provider plans disagree for FTF cell "
						+ spatial.cellX() + "," + spatial.cellZ()
				);
			}
			return this.decoration.apply(
				selection, spatial, target, quartX, quartY, quartZ, sampler, generatorContext
			);
		}

		private WorldgenPlans.SpatialResolver requireSpatialResolver() {
			if (this.spatialResolver == null) {
				throw new IllegalStateException("FTF biome selection has no spatial resolver");
			}
			return this.spatialResolver;
		}

		private static RTFClimateSampler requireOwnedSampler(Climate.Sampler sampler) {
			if (!((Object) sampler instanceof RTFClimateSampler rtfSampler)) {
				throw new IllegalStateException("FTF biome selection requires its owned climate sampler");
			}
			return rtfSampler;
		}
	}

}
