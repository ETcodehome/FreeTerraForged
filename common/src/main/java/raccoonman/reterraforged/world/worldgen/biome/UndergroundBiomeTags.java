package raccoonman.reterraforged.world.worldgen.biome;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/** Common tag spellings used by biome mods to identify cave biomes. */
public final class UndergroundBiomeTags {
	private static final TagKey<Biome> IS_CAVE = common("is_cave");
	private static final TagKey<Biome> IS_UNDERGROUND = common("is_underground");
	private static final TagKey<Biome> CAVES = common("caves");

	private UndergroundBiomeTags() {
	}

	public static boolean isCave(Object value) {
		if (!(value instanceof Holder<?> rawHolder)) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Holder<Biome> holder = (Holder<Biome>) rawHolder;
		return holder.is(IS_CAVE) || holder.is(IS_UNDERGROUND) || holder.is(CAVES);
	}

	private static TagKey<Biome> common(String path) {
		return TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", path));
	}
}
