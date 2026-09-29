package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import etcodehome.freeterraforged.world.worldgen.structure.rule.StructureRule;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryDataLoader;

import etcodehome.freeterraforged.data.worldgen.compat.terrablender.TBNoiseRouterData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetBiomeModifierData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetConfiguredFeatures;
import etcodehome.freeterraforged.data.worldgen.preset.PresetDimensionTypes;
import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseGeneratorSettings;
import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseRouterData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetPlacedFeatures;
import etcodehome.freeterraforged.data.worldgen.preset.PresetStructureRuleData;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

import java.util.*;
import java.util.stream.Stream;

public record Preset(WorldSettings world, SurfaceSettings surface, CaveSettings caves, ClimateSettings climate, TerrainSettings terrain, RiverSettings rivers, FlowSettings flow, IslandSettings island, FilterSettings filters, StructureSettings structures, MiscellaneousSettings miscellaneous, PresentationSettings presentation) {
	private static final Set<ResourceKey<? extends Registry<?>>> PREVIEW_REGISTRIES = Set.of(
		FTFRegistries.PRESET,
		FTFRegistries.NOISE,
		Registries.DENSITY_FUNCTION,
		Registries.NOISE_SETTINGS
	);

	public static final Codec<Preset> DIRECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			WorldSettings.CODEC.fieldOf("world").forGetter(Preset::world),
			SurfaceSettings.CODEC.optionalFieldOf("surface", new SurfaceSettings(new SurfaceSettings.Erosion(30, 140, 40, 95, 0.65F, 0.475F, 0.4F))).forGetter(Preset::surface),
			CaveSettings.CODEC.optionalFieldOf("caves", new CaveSettings(0.0F, 1.5625F, 1.0F, 1.0F, 1.0F, 0.14285715F, 0.07F, 0.02F, true, false)).forGetter(Preset::caves),
			ClimateSettings.CODEC.fieldOf("climate").forGetter(Preset::climate),
			TerrainSettings.CODEC.fieldOf("terrain").forGetter(Preset::terrain),
			RiverSettings.CODEC.fieldOf("rivers").forGetter(Preset::rivers),
			FlowSettings.CODEC.optionalFieldOf("flow").xmap(optional -> optional.orElseGet(FlowSettings::makeDefault),Optional::of).forGetter(Preset::flow),
			IslandSettings.CODEC.optionalFieldOf("island").xmap(optional -> optional.orElseGet(IslandSettings::makeDefault),Optional::of).forGetter(Preset::island),
			FilterSettings.CODEC.fieldOf("filters").forGetter(Preset::filters),
			StructureSettings.CODEC.fieldOf("structures").forGetter(Preset::structures),
			MiscellaneousSettings.CODEC.fieldOf("miscellaneous").forGetter(Preset::miscellaneous),
			PresentationSettings.CODEC.optionalFieldOf("presentation").xmap(optional -> optional.orElseGet(PresentationSettings::makeDefault),Optional::of).forGetter(Preset::presentation)
	).apply(instance, Preset::new));

	@Deprecated
	public static final ResourceKey<Preset> KEY = FTFRegistries.createKey(FTFRegistries.PRESET, "preset");

	public Preset copy() {
		return new Preset(this.world.copy(), this.surface.copy(), this.caves.copy(), this.climate.copy(), this.terrain.copy(), this.rivers.copy(), this.flow.copy(), this.island.copy(), this.filters.copy(), this.structures.copy(), this.miscellaneous.copy(), this.presentation.copy());
	}

	public HolderLookup.Provider buildPatch(HolderLookup.Provider registries) {
		return this.buildPatchedRegistries(registries);
	}

	public HolderLookup.Provider buildFullPatch(RegistryAccess registries) {
		return materialize(this.buildPatchedRegistries(registries));
	}

	private static final Set<String> PREVIEW_NAMESPACES = Set.of("minecraft", "freeterraforged");

	private static HolderLookup.Provider materialize(HolderLookup.Provider provider) {
		PREVIEW_REGISTRIES.forEach(key -> provider.lookup(key).ifPresent(lookup ->
			lookup.listElements()
				.filter(holder -> PREVIEW_NAMESPACES.contains(holder.key().location().getNamespace()))
				.forEach(holder -> holder.value())
		));
		return provider;
	}

	private HolderLookup.Provider buildPatchedRegistries(HolderLookup.Provider registries) {
		RegistrySetBuilder builder = new RegistrySetBuilder();

		// 1. Setup Patches
		this.addPatch(builder, FTFRegistries.PRESET, (preset, ctx) -> ctx.register(KEY, preset));
		this.addPatch(builder, FTFRegistries.NOISE, PresetNoiseData::bootstrap);
		this.addPatch(builder, FTFRegistries.BIOME_MODIFIER, PresetBiomeModifierData::bootstrap);
		this.addPatch(builder, FTFRegistries.STRUCTURE_RULE, PresetStructureRuleData::bootstrap);
		this.addPatch(builder, Registries.CONFIGURED_FEATURE, PresetConfiguredFeatures::bootstrap);
		this.addPatch(builder, Registries.PLACED_FEATURE, PresetPlacedFeatures::bootstrap);
		this.addPatch(builder, Registries.DIMENSION_TYPE, PresetDimensionTypes::bootstrap);
		this.addPatch(builder, Registries.DENSITY_FUNCTION, (preset, ctx) -> {
			PresetNoiseRouterData.bootstrap(preset, ctx);
			TBNoiseRouterData.bootstrap(ctx);
		});
		this.addPatch(builder, Registries.NOISE_SETTINGS, PresetNoiseGeneratorSettings::bootstrap);

		return builder.buildPatch(
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
				registries
		);
	}

	private <T> void addPatch(RegistrySetBuilder builder, ResourceKey<? extends Registry<T>> key, Patch<T> patch) {
		builder.add(key, (ctx) -> patch.apply(this, ctx));
	}

	private interface Patch<T> {
		void apply(Preset preset, net.minecraft.data.worldgen.BootstapContext<T> ctx);
	}
}
