package etcodehome.freeterraforged.world.worldgen.search;

import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/** A validated biome ID or biome tag accepted by FTF surface searches. */
public final class BiomeSearchTarget implements Predicate<Holder<Biome>> {
	private final String input;
	private final ResourceKey<Biome> biome;
	private final TagKey<Biome> tag;

	private BiomeSearchTarget(String input, ResourceKey<Biome> biome, TagKey<Biome> tag) {
		this.input = input;
		this.biome = biome;
		this.tag = tag;
	}

	public static BiomeSearchTarget parse(String input) {
		String value = Objects.requireNonNull(input, "input").trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("Enter a biome ID or biome tag.");
		}
		if (!value.startsWith("#") && !value.contains(":")) {
			throw new IllegalArgumentException(
				"Biome IDs need a namespace, such as minecraft:mangrove_swamp. "
					+ "Tags start with #, such as #c:is_beach."
			);
		}
		try {
			if (value.startsWith("#")) {
				ResourceLocation id = ResourceLocation.parse(value.substring(1));
				return new BiomeSearchTarget(value, null, TagKey.create(Registries.BIOME, id));
			}
			ResourceLocation id = ResourceLocation.parse(value);
			return new BiomeSearchTarget(value, ResourceKey.create(Registries.BIOME, id), null);
		} catch (RuntimeException failure) {
			throw new IllegalArgumentException("Invalid biome ID or tag: " + value + ".", failure);
		}
	}

	public String input() {
		return this.input;
	}

	@Override
	public boolean test(Holder<Biome> holder) {
		return this.tag != null ? holder.is(this.tag) : holder.is(this.biome);
	}
}
