package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryDataLoader;

import raccoonman.reterraforged.data.worldgen.compat.terrablender.TBNoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.PresetBiomeModifierData;
import raccoonman.reterraforged.data.worldgen.preset.PresetConfiguredFeatures;
import raccoonman.reterraforged.data.worldgen.preset.PresetDimensionTypes;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseData;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseGeneratorSettings;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.PresetPlacedFeatures;
import raccoonman.reterraforged.data.worldgen.preset.PresetStructureRuleData;
import raccoonman.reterraforged.data.worldgen.preset.PresetWorldPresets;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifier;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.structure.rule.StructureRule;

import java.util.*;
import java.util.stream.Stream;

public record Preset(WorldSettings world, SurfaceSettings surface, CaveSettings caves, ClimateSettings climate, TerrainSettings terrain, RiverSettings rivers, FlowSettings flow, IslandSettings island, FilterSettings filters, StructureSettings structures, MiscellaneousSettings miscellaneous, PresentationSettings presentation) {
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
	public static final ResourceKey<Preset> KEY = RTFRegistries.createKey(RTFRegistries.PRESET, "preset");

	public Preset copy() {
		return new Preset(this.world.copy(), this.surface.copy(), this.caves.copy(), this.climate.copy(), this.terrain.copy(), this.rivers.copy(), this.flow.copy(), this.island.copy(), this.filters.copy(), this.structures.copy(), this.miscellaneous.copy(), this.presentation.copy());
	}

	public HolderLookup.Provider buildPatch(HolderLookup.Provider registries) {
		return this.buildPatchedRegistries(registries).patches();
	}

	public HolderLookup.Provider buildPreviewLookups(RegistryAccess registries) {
		RegistryAccess.Frozen selected = registries.freeze();
		Set<ResourceKey<? extends Registry<?>>> overrides = Set.of(
			RTFRegistries.PRESET,
			RTFRegistries.NOISE
		);
		RegistrySetBuilder builder = new RegistrySetBuilder();
		this.addPatch(builder, RTFRegistries.PRESET, (preset, ctx) -> ctx.register(KEY, preset));
		this.addPatch(builder, RTFRegistries.NOISE, PresetNoiseData::bootstrap);
		Cloner.Factory factory = new Cloner.Factory();
		factory.addCodec(RTFRegistries.PRESET, Preset.DIRECT_CODEC);
		factory.addCodec(RTFRegistries.NOISE, Noise.DIRECT_CODEC);
		HolderLookup.Provider patches = builder.buildPatch(
			RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
			this.filterToArmedOnly(selected, overrides),
			factory
		).patches();
		return new HolderLookup.Provider() {
			@Override
			public Stream<ResourceKey<? extends Registry<?>>> listRegistries() {
				return Stream.concat(selected.listRegistries(), overrides.stream()).distinct();
			}

			@Override
			public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(
				ResourceKey<? extends Registry<? extends T>> key
			) {
				return overrides.contains(key) ? patches.lookup(key) : selected.lookup(key);
			}
		};
	}

	private RegistrySetBuilder.PatchedRegistries buildPatchedRegistries(HolderLookup.Provider registries) {
		RegistrySetBuilder builder = new RegistrySetBuilder();

		// 1. Setup Patches
		this.addPatch(builder, RTFRegistries.PRESET, (preset, ctx) -> ctx.register(KEY, preset));
		this.addPatch(builder, RTFRegistries.NOISE, PresetNoiseData::bootstrap);
		this.addPatch(builder, RTFRegistries.BIOME_MODIFIER, PresetBiomeModifierData::bootstrap);
		this.addPatch(builder, RTFRegistries.STRUCTURE_RULE, PresetStructureRuleData::bootstrap);
		this.addPatch(builder, Registries.CONFIGURED_FEATURE, PresetConfiguredFeatures::bootstrap);
		this.addPatch(builder, Registries.PLACED_FEATURE, PresetPlacedFeatures::bootstrap);
		this.addPatch(builder, Registries.DIMENSION_TYPE, PresetDimensionTypes::bootstrap);
		this.addPatch(builder, Registries.DENSITY_FUNCTION, (preset, ctx) -> {
			PresetNoiseRouterData.bootstrap(preset, ctx);
			TBNoiseRouterData.bootstrap(ctx);
		});
		this.addPatch(builder, Registries.NOISE_SETTINGS, PresetNoiseGeneratorSettings::bootstrap);
		this.addPatch(builder, Registries.WORLD_PRESET, PresetWorldPresets::bootstrap);

		// 2. Initialize Cloner and Gatekeeper tracking
		Cloner.Factory factory = new Cloner.Factory();
		Set<ResourceKey<? extends Registry<?>>> armedRegistries = new HashSet<>();

		RegistryDataLoader.WORLDGEN_REGISTRIES.forEach(registryData -> {
			ResourceKey<? extends Registry<?>> key = registryData.key();
			registryData.runWithArguments(factory::addCodec);
			armedRegistries.add(key);
		});

		armedRegistries.add(Registries.STRUCTURE_SET);

		// 4. Arm Custom RTF Registries
		this.addAndTrack(factory, armedRegistries, RTFRegistries.NOISE, Noise.DIRECT_CODEC);
		this.addAndTrack(factory, armedRegistries, RTFRegistries.BIOME_MODIFIER, BiomeModifier.DIRECT_CODEC);
		this.addAndTrack(factory, armedRegistries, RTFRegistries.STRUCTURE_RULE, StructureRule.DIRECT_CODEC);
		this.addAndTrack(factory, armedRegistries, RTFRegistries.PRESET, Preset.DIRECT_CODEC);

		// 5. Wrap registries in a safety shield
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

	/**
	 * Creates a virtual view of the RegistryAccess that hides any folders we don't have a cloner for.
	 */
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
