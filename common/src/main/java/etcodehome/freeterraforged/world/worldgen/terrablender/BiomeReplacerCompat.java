package etcodehome.freeterraforged.world.worldgen.terrablender;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeCandidateRoot;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.platform.ModLoaderUtil;

/**
 * Applies Biome Replacer's rules to the TerraBlender candidates FTF acquires.
 *
 * <p>Biome Replacer rewrites dimensions that use a MultiNoiseBiomeSource and TerraBlender's own parameter lists. FTF
 * dimensions use neither (Biome Replacer logs "Skipping minecraft:overworld") because FTF reads each TerraBlender
 * region directly, so without this its rules never reach FTF worlds. Rules are resolved through Biome Replacer's public
 * per-dimension entry point, so dimension sections such as {@code [minecraft:overworld]} apply as configured.
 */
public final class BiomeReplacerCompat {
	private static final String MOD_ID = "biome_replacer";
	private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
	private static final MethodHandle REPLACE_IF_NEEDED = locate();

	private BiomeReplacerCompat() {
	}

	/**
	 * Rewrites a base multi-noise candidate root, such as the vanilla preset FTF retains, with the same rules.
	 */
	public static BiomeCandidateRoot apply(BiomeCandidateRoot root, String dimension) {
		if (REPLACE_IF_NEEDED == null) {
			return root;
		}
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new ArrayList<>(root.entries().size());
		int replaced = 0;
		for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : root.entries()) {
			Holder<Biome> candidate = replace(entry.getSecond(), dimension);
			if (candidate != entry.getSecond()) {
				replaced++;
			}
			entries.add(Pair.of(entry.getFirst(), candidate));
		}
		FTFCommon.LOGGER.info("Biome Replacer rules replaced {} of {} base candidates for {}", replaced, entries.size(), dimension);
		return replaced == 0 ? root : BiomeCandidateRoot.fromEntries(List.copyOf(new LinkedHashSet<>(entries)));
	}

	static boolean isActive() {
		return REPLACE_IF_NEEDED != null;
	}

	@SuppressWarnings("unchecked")
	static Holder<Biome> replace(Holder<Biome> biome, String dimension) {
		if (REPLACE_IF_NEEDED == null) {
			return biome;
		}
		try {
			Holder<Biome> replacement = (Holder<Biome>) REPLACE_IF_NEEDED.invoke(biome, dimension);
			return replacement != null ? replacement : biome;
		} catch (Throwable failure) {
			if (FAILURE_LOGGED.compareAndSet(false, true)) {
				FTFCommon.LOGGER.error("Biome Replacer failed to resolve a replacement; its rules are skipped for FTF candidates", failure);
			}
			return biome;
		}
	}

	private static MethodHandle locate() {
		if (!ModLoaderUtil.isLoaded(MOD_ID)) {
			return null;
		}
		try {
			Class<?> replacer = Class.forName("net.werdei.biome_replacer.replacer.VanillaReplacer");
			return MethodHandles.publicLookup().findStatic(
				replacer, "replaceIfNeeded", MethodType.methodType(Holder.class, Holder.class, String.class)
			);
		} catch (ReflectiveOperationException | LinkageError failure) {
			FTFCommon.LOGGER.warn("Biome Replacer is installed but its replaceIfNeeded hook was not found, so its rules won't apply to FTF worlds", failure);
			return null;
		}
	}
}
