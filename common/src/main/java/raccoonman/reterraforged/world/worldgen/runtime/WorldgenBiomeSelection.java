package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.concurrent.Resource;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.cell.Cell;

/** Shared generation/preview consumer for provider, spatial, and selection-decoration plans. */
public final class WorldgenBiomeSelection {
	private WorldgenBiomeSelection() {
	}

	public static Set<Holder<Biome>> possibleBiomes(WorldgenPlan plan) {
		LinkedHashSet<Holder<Biome>> possible = new LinkedHashSet<>();
		plan.providerSelection().providers().forEach(domain -> domain.candidates().values()
			.forEach(entry -> possible.add(entry.getSecond())));
		plan.providerSelection().fallback().ifPresent(table -> table.values()
			.forEach(entry -> possible.add(entry.getSecond())));
		plan.biomeComposition().entries().forEach(entry -> possible.add(entry.getSecond()));
		possible.addAll(plan.selectionDecoration().possibleOutputs());
		if (possible.isEmpty()) {
			throw new IllegalStateException("FTF candidate-provider plan exposes no possible biomes");
		}
		return Collections.unmodifiableSet(possible);
	}

	public static void requireExecutablePlan(WorldgenPlan plan) {
		requireAvailable(plan.providerSelection().descriptor());
		requireAvailable(plan.selectionDecoration().descriptor());
		requireAvailable(plan.spatialOwnership().descriptor());
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
		if (!((Object) sampler instanceof RTFClimateSampler rtfSampler)) {
			throw new IllegalStateException("FTF biome selection requires its owned climate sampler");
		}
		GeneratorContext generatorContext = rtfSampler.getUndergroundBiomeSurfaceContext();
		if (generatorContext == null) {
			throw new IllegalStateException("FTF biome selection sampler has no generator context");
		}
		requireExecutablePlan(plan);

		WorldgenPlans.SpatialResult spatial = rtfSampler.getBiomeCellCache().find(plan, quartX, quartZ);
		if (spatial == null) {
			try (Resource<Cell> resource = Cell.getResource()) {
				Cell cell = resource.get().reset();
				generatorContext.lookup.applyCell(
					cell, QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ), false, true
				);
				spatial = plan.spatialOwnership().resolver().orElseThrow()
					.resolve(cell.biomeRegionX, cell.biomeRegionZ);
			}
			rtfSampler.getBiomeCellCache().store(plan, quartX, quartZ, spatial);
		}
		return select(plan, spatial, sampler.sample(quartX, quartY, quartZ), quartX, quartY, quartZ, sampler);
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
		if (!((Object) sampler instanceof RTFClimateSampler rtfSampler)
			|| rtfSampler.getUndergroundBiomeSurfaceContext() == null) {
			throw new IllegalStateException("FTF biome selection requires its owned sampler context");
		}
		requireExecutablePlan(plan);
		WorldgenPlans.SpatialResult spatial = plan.spatialOwnership().resolver().orElseThrow()
			.resolve(biomeCellX, biomeCellZ);
		return select(plan, spatial, sampler.sample(quartX, quartY, quartZ), quartX, quartY, quartZ, sampler);
	}

	private static Holder<Biome> select(
		WorldgenPlan plan,
		WorldgenPlans.SpatialResult spatial,
		Climate.TargetPoint target,
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler
	) {
		ResourceLocation spatialDomain = spatial.domain();
		WorldgenPlans.ProviderResult selection = plan.providerSelection()
			.resolve(spatialDomain, target)
			.orElseThrow(() -> new IllegalStateException(
				"Spatial provider plan selected unknown domain " + spatialDomain
			));
		if (!spatial.domain().equals(selection.domain())) {
			throw new IllegalStateException(
				"Spatial and provider plans disagree for FTF cell " + spatial.cellX() + "," + spatial.cellZ()
			);
		}
		return plan.selectionDecoration().apply(
			selection, spatial, target, quartX, quartY, quartZ, sampler
		);
	}

}
