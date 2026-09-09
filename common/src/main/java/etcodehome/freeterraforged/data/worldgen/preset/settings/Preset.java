package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import etcodehome.freeterraforged.world.worldgen.structure.rule.StructureRule;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
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
		return this.buildPatchedRegistries(registries).patches();
	}

	public HolderLookup.Provider buildFullPatch(RegistryAccess registries) {
		return materialize(this.buildPatchedRegistries(registries).full());
	}

	private static final Set<String> PREVIEW_NAMESPACES = Set.of("minecraft", "reterraforged");

	private static HolderLookup.Provider materialize(HolderLookup.Provider provider) {
		provider.listRegistries()
			.filter(PREVIEW_REGISTRIES::contains)
			.forEach(key -> provider.lookupOrThrow(key).listElements()
				.filter(holder -> PREVIEW_NAMESPACES.contains(holder.key().location().getNamespace()))
				.forEach(holder -> holder.value()));
		return provider;
	}

	private RegistrySetBuilder.PatchedRegistries buildPatchedRegistries(HolderLookup.Provider registries) {
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

		// 2. Initialize Cloner and Gatekeeper tracking
		Cloner.Factory factory = new Cloner.Factory();
		Set<ResourceKey<? extends Registry<?>>> armedRegistries = new HashSet<>();

		// 3. Process Vanilla Worldgen Registries
		RegistryDataLoader.WORLDGEN_REGISTRIES.forEach(registryData -> {
			ResourceKey<? extends Registry<?>> key = registryData.key();
			// Only arm registries from known safe namespaces to be extra cautious
			String namespace = key.location().getNamespace();

			if (namespace.equals("minecraft") || namespace.equals("reterraforged")) {
				registryData.runWithArguments(factory::addCodec);
				armedRegistries.add(key);
			}
		});

		armedRegistries.add(Registries.STRUCTURE_SET);

		// 4. Arm Custom RTF Registries
		this.addAndTrack(factory, armedRegistries, FTFRegistries.NOISE, Noise.DIRECT_CODEC);
		this.addAndTrack(factory, armedRegistries, FTFRegistries.BIOME_MODIFIER, BiomeModifier.DIRECT_CODEC);
		this.addAndTrack(factory, armedRegistries, FTFRegistries.STRUCTURE_RULE, StructureRule.DIRECT_CODEC);
		this.addAndTrack(factory, armedRegistries, FTFRegistries.PRESET, Preset.DIRECT_CODEC);

		// 5. Wrap registries in a safety shield
		// This ensures the cloner only sees registries we explicitly gave it a codec for.
		// Unarmed registries (like mixed_litter) will be ignored safely.
		HolderLookup.Provider safeSource = this.filterToArmedOnly(registries, armedRegistries);

		return builder.buildPatch(
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
				safeSource,
				factory
		);
	}

	/**
	 * Adds a codec to the factory and records the registry key so the filter knows it's safe to process.
	 */
	private <T> void addAndTrack(Cloner.Factory factory, Set<ResourceKey<? extends Registry<?>>> set, ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
		factory.addCodec(key, codec);
		set.add(key);
	}

	private HolderLookup.Provider filterToArmedOnly(HolderLookup.Provider original, Set<ResourceKey<? extends Registry<?>>> armed) {
		return new HolderLookup.Provider() {
			@Override
			public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
				return armed.contains(key) ? original.lookup(key) : Optional.empty();
			}

			@Override
			public Stream<ResourceKey<? extends Registry<?>>> listRegistries() {
				return original.listRegistries().filter(armed::contains);
			}
		};
	}

	private <T> void addPatch(RegistrySetBuilder builder, ResourceKey<? extends Registry<T>> key, Patch<T> patch) {
		builder.add(key, (ctx) -> patch.apply(this, ctx));
	}

	private interface Patch<T> {
		void apply(Preset preset, BootstrapContext<T> ctx);
	}
}
