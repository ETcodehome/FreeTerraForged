package raccoonman.reterraforged.world.worldgen.feature.placement;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;

public final class ChunkLocalPlacementClassifier {
	private ChunkLocalPlacementClassifier() {
	}

	public static Classification classify(
		PlacedFeature feature,
		Optional<ResourceLocation> featureId,
		HolderLookup.Provider registries
	) {
		try {
			return classifyChecked(feature, featureId, registries);
		} catch (RuntimeException | LinkageError failure) {
			return Classification.failed("CHUNK_LOCAL_PLACEMENT_INSPECTION_FAILED", failure);
		}
	}

	private static Classification classifyChecked(
		PlacedFeature feature,
		Optional<ResourceLocation> featureId,
		HolderLookup.Provider registries
	) {
		if (featureId.isEmpty()) {
			return Classification.rejected("MISSING_PLACED_FEATURE_IDENTITY");
		}
		if (!isChunkLocalRoot(feature.placement(), registries)) {
			return Classification.rejected("NOT_CHUNK_LOCAL_ROOT");
		}
		Set<RandomOffsetPlacement> offsets = identitySet();
		Set<PlacedFeature> visited = identitySet();
		if (!collectConfiguredOffsets(feature.feature(), offsets, visited, registries)) {
			return Classification.rejected("UNSUPPORTED_NESTED_PLACEMENT_PIPELINE");
		}
		if (offsets.isEmpty()) {
			return Classification.rejected("NO_NESTED_HORIZONTAL_OFFSET");
		}
		return Classification.eligible(new ChunkConfinement(featureId.get(), offsets));
	}

	private static boolean isChunkLocalRoot(
		List<PlacementModifier> modifiers,
		HolderLookup.Provider registries
	) {
		int selectors = 0;
		boolean repeated = false;
		for (PlacementModifier modifier : modifiers) {
			if (modifier instanceof InSquarePlacement) {
				selectors++;
			} else if (modifier instanceof RandomOffsetPlacement offset) {
				if (!isWholeChunkOffset(offset, registries)) {
					return false;
				}
				selectors++;
			} else if (modifier instanceof RepeatingPlacement) {
				repeated = true;
			} else if (!preservesHorizontalPosition(modifier)) {
				return false;
			}
		}
		return repeated && selectors == 1;
	}

	private static boolean collectConfiguredOffsets(
		Holder<ConfiguredFeature<?, ?>> configured,
		Set<RandomOffsetPlacement> offsets,
		Set<PlacedFeature> visited,
		HolderLookup.Provider registries
	) {
		if (!(configured.value().config() instanceof RandomFeatureConfiguration random)) {
			return true;
		}
		for (WeightedPlacedFeature weighted : random.features) {
			if (!collectPlacedOffsets(weighted.feature, offsets, visited, registries)) {
				return false;
			}
		}
		return collectPlacedOffsets(random.defaultFeature, offsets, visited, registries);
	}

	private static boolean collectPlacedOffsets(
		Holder<PlacedFeature> holder,
		Set<RandomOffsetPlacement> offsets,
		Set<PlacedFeature> visited,
		HolderLookup.Provider registries
	) {
		PlacedFeature feature = holder.value();
		if (!visited.add(feature)) {
			return true;
		}
		int horizontalOffsets = 0;
		for (PlacementModifier modifier : feature.placement()) {
			if (modifier instanceof RandomOffsetPlacement offset) {
				Bounds bounds = horizontalBounds(offset, registries);
				if (bounds == null) {
					return false;
				}
				if (bounds.min() != 0 || bounds.max() != 0) {
					horizontalOffsets++;
					offsets.add(offset);
				}
			} else if (!preservesHorizontalPosition(modifier)) {
				return false;
			}
		}
		return horizontalOffsets <= 1
			&& collectConfiguredOffsets(feature.feature(), offsets, visited, registries);
	}

	private static boolean preservesHorizontalPosition(PlacementModifier modifier) {
		return modifier instanceof PlacementFilter
			|| modifier instanceof RepeatingPlacement
			|| modifier instanceof HeightmapPlacement
			|| modifier instanceof HeightRangePlacement
			|| modifier instanceof EnvironmentScanPlacement;
	}

	private static boolean isWholeChunkOffset(
		RandomOffsetPlacement placement,
		HolderLookup.Provider registries
	) {
		JsonObject encoded = encode(placement, registries);
		Bounds horizontal = bounds(encoded, "xz_spread", registries);
		Bounds vertical = bounds(encoded, "y_spread", registries);
		return horizontal != null && vertical != null
			&& horizontal.min() == 0 && horizontal.max() == 15
			&& vertical.min() == 0 && vertical.max() == 0;
	}

	private static Bounds horizontalBounds(
		RandomOffsetPlacement placement,
		HolderLookup.Provider registries
	) {
		return bounds(encode(placement, registries), "xz_spread", registries);
	}

	private static JsonObject encode(
		RandomOffsetPlacement placement,
		HolderLookup.Provider registries
	) {
		JsonElement encoded = RandomOffsetPlacement.CODEC.codec()
			.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), placement)
			.result()
			.orElse(null);
		return encoded != null && encoded.isJsonObject() ? encoded.getAsJsonObject() : null;
	}

	private static Bounds bounds(
		JsonObject encoded,
		String member,
		HolderLookup.Provider registries
	) {
		if (encoded == null || !encoded.has(member)) {
			return null;
		}
		IntProvider provider = IntProvider.CODEC
			.parse(RegistryOps.create(JsonOps.INSTANCE, registries), encoded.get(member))
			.result()
			.orElse(null);
		return provider == null ? null : new Bounds(provider.getMinValue(), provider.getMaxValue());
	}

	private static <T> Set<T> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}

	private record Bounds(int min, int max) {
	}

	public record ChunkConfinement(
		ResourceLocation featureId,
		Set<RandomOffsetPlacement> offsets
	) {
		public ChunkConfinement {
			featureId = java.util.Objects.requireNonNull(featureId, "featureId");
			Set<RandomOffsetPlacement> copy = identitySet();
			copy.addAll(offsets);
			offsets = Collections.unmodifiableSet(copy);
		}

		public boolean contains(RandomOffsetPlacement placement) {
			return this.offsets.contains(placement);
		}
	}

	public record Classification(ChunkConfinement confinement, String reasonCode, String failure) {
		public Classification {
			reasonCode = java.util.Objects.requireNonNull(reasonCode, "reasonCode");
		}

		public static Classification eligible(ChunkConfinement confinement) {
			return new Classification(
				java.util.Objects.requireNonNull(confinement, "confinement"),
				"SUPPORTED_CHUNK_LOCAL_PLACEMENT_CONTRACT",
				null
			);
		}

		public static Classification rejected() {
			return rejected("NOT_CLASSIFIED");
		}

		public static Classification rejected(String reasonCode) {
			return new Classification(null, reasonCode, null);
		}

		public static Classification failed(String reasonCode, Throwable failure) {
			String detail = failure.getClass().getName() + ": "
				+ java.util.Optional.ofNullable(failure.getMessage()).orElse("<no message>");
			return new Classification(null, reasonCode, detail);
		}

		public boolean eligible() {
			return this.confinement != null;
		}

		public boolean failed() {
			return this.failure != null;
		}
	}
}
