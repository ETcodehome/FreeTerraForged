package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.biome.modifier.BiomeModifier;
import etcodehome.freeterraforged.world.worldgen.structure.rule.StructureRule;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

import etcodehome.freeterraforged.data.worldgen.compat.terrablender.TBNoiseRouterData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetBiomeModifierData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetConfiguredFeatures;
import etcodehome.freeterraforged.data.worldgen.preset.PresetDimensionTypes;
import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseGeneratorSettings;
import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseRouterData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetPlacedFeatures;
import etcodehome.freeterraforged.data.worldgen.preset.PresetStructureRuleData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetWorldPresets;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

import java.util.*;
import java.util.stream.Stream;

public record Preset(WorldSettings world, SurfaceSettings surface, CaveSettings caves, ClimateSettings climate, TerrainSettings terrain, RiverSettings rivers, FlowSettings flow, IslandSettings island, ErosionFilterSettings erosionFilters, MiscellaneousSettings miscellaneous, PresentationSettings presentation) {
	public static final Codec<Preset> DIRECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			WorldSettings.CODEC.fieldOf("world").forGetter(Preset::world),
			SurfaceSettings.CODEC.optionalFieldOf("surface", new SurfaceSettings(new SurfaceSettings.Scree(30, 140, 40, 95, 0.65F, 0.475F, 0.4F))).forGetter(Preset::surface),
			CaveSettings.CODEC.optionalFieldOf("caves", new CaveSettings(0.0F, 1.5625F, 1.0F, 1.0F, 1.0F, 0.14285715F, 0.07F, 0.02F, true, false)).forGetter(Preset::caves),
			ClimateSettings.CODEC.fieldOf("climate").forGetter(Preset::climate),
			TerrainSettings.CODEC.fieldOf("terrain").forGetter(Preset::terrain),
			RiverSettings.CODEC.fieldOf("rivers").forGetter(Preset::rivers),
			FlowSettings.CODEC.optionalFieldOf("flow").xmap(optional -> optional.orElseGet(FlowSettings::makeDefault),Optional::of).forGetter(Preset::flow),
			IslandSettings.CODEC.optionalFieldOf("island").xmap(optional -> optional.orElseGet(IslandSettings::makeDefault),Optional::of).forGetter(Preset::island),
			ErosionFilterSettings.CODEC.optionalFieldOf("erosionFilters").xmap(optional -> optional.orElseGet(ErosionFilterSettings::makeDefault),Optional::of).forGetter(Preset::erosionFilters),
			MiscellaneousSettings.CODEC.fieldOf("miscellaneous").forGetter(Preset::miscellaneous),
			PresentationSettings.CODEC.optionalFieldOf("presentation").xmap(optional -> optional.orElseGet(PresentationSettings::makeDefault),Optional::of).forGetter(Preset::presentation)
	).apply(instance, Preset::new));

	public static final ResourceKey<Preset> KEY = FTFRegistries.createKey(FTFRegistries.PRESET, "preset");

	public Preset copy() {
		return new Preset(this.world.copy(), this.surface.copy(), this.caves.copy(), this.climate.copy(), this.terrain.copy(), this.rivers.copy(), this.flow.copy(), this.island.copy(), this.erosionFilters.copy(), this.miscellaneous.copy(), this.presentation.copy());
	}

	public HolderLookup.Provider buildPatch(HolderLookup.Provider registries) {
		return this.buildPatchedRegistries(registries);
	}

	public HolderLookup.Provider buildPreviewLookups(RegistryAccess registries) {
		RegistryAccess.Frozen selected = registries.freeze();
		Set<ResourceKey<? extends Registry<?>>> overrides = Set.of(
			FTFRegistries.PRESET,
			FTFRegistries.NOISE
		);
		RegistrySetBuilder builder = new RegistrySetBuilder();
		this.addPatch(builder, FTFRegistries.PRESET, (preset, ctx) -> ctx.register(KEY, preset));
		this.addPatch(builder, FTFRegistries.NOISE, PresetNoiseData::bootstrap);
		HolderLookup.Provider patches = builder.buildPatch(
			RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
			this.filterToArmedOnly(selected, overrides)
		);
		return new HolderLookup.Provider() {
			@Override
			public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(
				ResourceKey<? extends Registry<? extends T>> key
			) {
				return overrides.contains(key) ? patches.lookup(key) : selected.lookup(key);
			}
		};
	}

	public HolderLookup.Provider buildPatchedRegistries(HolderLookup.Provider registries) {
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
		this.addPatch(builder, Registries.WORLD_PRESET, PresetWorldPresets::bootstrap);

		return builder.buildPatch(
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
				registries
		);
	}

	/**
	 * Creates a virtual view of the RegistryAccess that hides any folders we don't have a cloner for.
	 */
	private HolderLookup.Provider filterToArmedOnly(HolderLookup.Provider original, Set<ResourceKey<? extends Registry<?>>> armed) {
		return new HolderLookup.Provider() {
			@Override
			public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
				if (!armed.contains(key)) {
					return Optional.empty();
				}
				Optional<HolderLookup.RegistryLookup<T>> found = original.lookup(key);
				if (found.isPresent()) {
					return found;
				}
				return Optional.of(createEmptyLookup(key));
			}
		};
	}

	private static <T> HolderLookup.RegistryLookup<T> createEmptyLookup(ResourceKey<? extends Registry<? extends T>> registryKey) {
		return new HolderLookup.RegistryLookup<T>() {
			@Override
			public ResourceKey<? extends Registry<? extends T>> key() {
				return registryKey;
			}

			@Override
			public Lifecycle registryLifecycle() {
				return Lifecycle.stable();
			}

			@Override
			public Stream<Holder.Reference<T>> listElements() {
				return Stream.empty();
			}

			@Override
			public Stream<HolderSet.Named<T>> listTags() {
				return Stream.empty();
			}

			@Override
			public Optional<Holder.Reference<T>> get(ResourceKey<T> resourceKey) {
				return Optional.empty();
			}

			@Override
			public Optional<HolderSet.Named<T>> get(TagKey<T> tagKey) {
				return Optional.empty();
			}

			@Override
			public boolean canSerializeIn(HolderOwner<T> owner) {
				return owner == this;
			}
		};
	}

	private <T> void addPatch(RegistrySetBuilder builder, ResourceKey<? extends Registry<T>> key, Patch<T> patch) {
		builder.add(key, (ctx) -> patch.apply(this, ctx));
	}

	private interface Patch<T> {
		void apply(Preset preset, BootstapContext<T> ctx);
	}
}
