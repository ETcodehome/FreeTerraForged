package etcodehome.freeterraforged.world.worldgen.terrablender;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.platform.ModLoaderUtil;
import etcodehome.freeterraforged.world.worldgen.runtime.CapabilityState;
import etcodehome.freeterraforged.world.worldgen.runtime.MinecraftBiomeSourceGraphs;
import etcodehome.freeterraforged.world.worldgen.runtime.PlanDescriptor;
import etcodehome.freeterraforged.world.worldgen.runtime.ProviderOrder;
import etcodehome.freeterraforged.world.worldgen.runtime.PreviewSourceContext;
import etcodehome.freeterraforged.world.worldgen.runtime.RequestOwnedBiomeSource;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenCapabilityProvider;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenCompilationContext;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenFacet;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenApplicability;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenOwnerType;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenQueryMode;
import etcodehome.freeterraforged.world.worldgen.runtime.WeightedRendezvous;
import terrablender.DimensionTypeTags;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;

public final class TerraBlenderCapabilityProvider implements WorldgenCapabilityProvider {
	private static final ResourceLocation ID = FTFCommon.location("terrablender_regions");
	private static final long SALT = 0x5d6f7a2c8b9134e1L;

	@Override
	public ResourceLocation id() {
		return ID;
	}

	@Override
	public int version() {
		return 3;
	}

	@Override
	public Set<WorldgenFacet> facets() {
		return EnumSet.of(
			WorldgenFacet.PROVIDER_SELECTION,
			WorldgenFacet.SPATIAL_OWNERSHIP
		);
	}

	@Override
	public Set<WorldgenOwnerType> ownerTypes() {
		return EnumSet.allOf(WorldgenOwnerType.class);
	}

	@Override
	public List<ProviderOrder> ordering() {
		return List.of();
	}

	@Override
	public Optional<RequestOwnedBiomeSource> previewSource(PreviewSourceContext context) {
		return Optional.empty();
	}

	@Override
	public WorldgenQueryMode declaredQueryMode(WorldgenFacet facet) {
		return WorldgenQueryMode.ISOLATED_PARALLEL_READ;
	}

	@Override
	public WorldgenQueryMode queryMode(
		WorldgenFacet facet,
		WorldgenCompilationContext context
	) {
		return WorldgenQueryMode.ISOLATED_PARALLEL_READ;
	}

	@Override
	public WorldgenApplicability applicability(
		WorldgenFacet facet,
		WorldgenCompilationContext context
	) {
		if (!ModLoaderUtil.isLoaded("terrablender")
			|| !isOverworldRegionGraph(context)
			|| !(MinecraftBiomeSourceGraphs.acquisitionSource(
				context.owner().selectedStem().generator()
			) instanceof MultiNoiseBiomeSource)) {
			return WorldgenApplicability.NOT_APPLICABLE;
		}
		List<ResourceLocation> ids = Regions.get(RegionType.OVERWORLD).stream()
			.map(Region::getName)
			.toList();
		return isContributingRegionIds(ids)
			? WorldgenApplicability.APPLICABLE
			: WorldgenApplicability.NOT_APPLICABLE;
	}

	@Override
	public Optional<? extends WorldgenPlans.DomainPlan> compile(
		WorldgenFacet facet,
		WorldgenCompilationContext context
	) throws Exception {
		if (!ModLoaderUtil.isLoaded("terrablender")) {
			return Optional.empty();
		}
		if (!isOverworldRegionGraph(context)
			|| !(MinecraftBiomeSourceGraphs.acquisitionSource(
			context.owner().selectedStem().generator()
		) instanceof MultiNoiseBiomeSource)) {
			return Optional.empty();
		}
		WorldgenPlans.ProviderSelection selection = context.snapshot(
			ID,
			WorldgenPlans.ProviderSelection.class,
			() -> snapshot(context)
		);
		return switch (facet) {
			case PROVIDER_SELECTION -> Optional.of(selection);
			case SPATIAL_OWNERSHIP -> Optional.of(new WorldgenPlans.SpatialOwnership(
				descriptor(WorldgenFacet.SPATIAL_OWNERSHIP,
					"FTF cell coordinates select exactly one public TerraBlender provider domain"),
				Optional.of((cellX, cellZ) -> new WorldgenPlans.SpatialResult(
					selection.selectDomain(cellX, cellZ),
					cellX,
					cellZ
				))
			));
			default -> Optional.empty();
		};
	}

	private static WorldgenPlans.ProviderSelection snapshot(WorldgenCompilationContext context) {
		context.checkCancelled();
		Registry<Biome> biomes = context.owner().registries().registryOrThrow(Registries.BIOME);
		List<Region> regions = List.copyOf(Regions.get(RegionType.OVERWORLD));
		if (regions.isEmpty()) {
			throw new IllegalStateException("TerraBlender exposes no Overworld default provider");
		}
		Set<ResourceLocation> ids = new HashSet<>();
		List<WorldgenPlans.ProviderDomain> providers = new ArrayList<>(regions.size());
		Holder<Biome> deferred = biomes.getHolderOrThrow(Region.DEFERRED_PLACEHOLDER);
		for (int order = 0; order < regions.size(); order++) {
			context.checkCancelled();
			Region region = regions.get(order);
			if (!ids.add(region.getName())) {
				throw new IllegalStateException("Duplicate TerraBlender provider ID: " + region.getName());
			}
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> registeredEntries = new ArrayList<>();
			region.addBiomes(biomes, entry -> registeredEntries.add(Pair.of(
				entry.getFirst(), biomes.getHolderOrThrow(entry.getSecond())
			)));
			List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = deduplicateEntries(registeredEntries);
			if (entries.isEmpty()) {
				throw new IllegalStateException("TerraBlender provider has no public candidates: " + region.getName());
			}
			providers.add(new WorldgenPlans.ProviderDomain(
				region.getName(), region.getWeight(), new Climate.ParameterList<>(entries), order
			));
		}
		context.checkCancelled();
		return new WorldgenPlans.ProviderSelection(
			descriptor(WorldgenFacet.PROVIDER_SELECTION,
				"Public provider IDs, positive weights, climate tables, order, and index-zero fallback were snapshotted"),
			context.owner().seed() ^ SALT,
			providers,
			Optional.of(providers.getFirst().id()),
			Optional.of(providers.getFirst().candidates()),
			Optional.of(deferred)
		);
	}

	static <T> List<Pair<Climate.ParameterPoint, T>> deduplicateEntries(
		List<Pair<Climate.ParameterPoint, T>> entries
	) {
		return List.copyOf(new LinkedHashSet<>(entries));
	}

	static boolean isContributingRegionIds(List<ResourceLocation> ids) {
		return ids.stream().anyMatch(id -> !id.equals(ResourceLocation.withDefaultNamespace("overworld")));
	}

	private static boolean isOverworldRegionGraph(WorldgenCompilationContext context) {
		return context.owner().selectedStem().type().is(DimensionTypeTags.OVERWORLD_REGIONS);
	}

	private static PlanDescriptor descriptor(WorldgenFacet facet, String detail) {
		return new PlanDescriptor(
			ID, facet, CapabilityState.PROVIDER_CONTRACT, "terrablender_public_regions", detail, Optional.empty()
		);
	}
}
