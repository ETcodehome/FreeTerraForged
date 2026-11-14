package etcodehome.freeterraforged.world.worldgen.biome;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Common tag spellings and semantic fallbacks used to identify swamp and wetland biomes.
 */
public final class WetlandBiomeTags {
	private static final TagKey<Biome> IS_SWAMP = common("is_swamp");
	private static final TagKey<Biome> SWAMPS = common("swamps");
	private static final TagKey<Biome> IS_WETLAND = common("is_wetland");
	private static final TagKey<Biome> WETLANDS = common("wetlands");

	private WetlandBiomeTags() {
	}

	public static boolean isWetland(Object value) {
		if (!(value instanceof Holder<?> rawHolder)) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Holder<Biome> holder = (Holder<Biome>) rawHolder;

		// 1. Conventional biome tags (NeoForge & Fabric 'c' namespace)
		if (holder.is(IS_SWAMP) || holder.is(SWAMPS) || holder.is(IS_WETLAND) || holder.is(WETLANDS)) {
			return true;
		}

		// 2. Vanilla swamp biomes
		if (holder.is(Biomes.SWAMP) || holder.is(Biomes.MANGROVE_SWAMP)) {
			return true;
		}

		// 3. Resilient semantic fallback for modded biomes lacking conventional tags
		return holder.unwrapKey().map(key -> {
			String path = key.location().getPath();
			return path.contains("swamp")
				|| path.contains("wetland")
				|| path.contains("marsh")
				|| path.contains("bog")
				|| path.contains("bayou")
				|| path.contains("fen");
		}).orElse(false);
	}

	private static TagKey<Biome> common(String path) {
		return TagKey.create(Registries.BIOME, new ResourceLocation("c", path));
	}
}
