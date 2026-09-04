package raccoonman.reterraforged.world.worldgen.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;

/** Public registry/tag identities used for diagnostics and invalidation, never guessed provenance. */
public final class WorldgenFingerprints {
	private WorldgenFingerprints() {
	}

	public static String tags(RegistryAccess registries) {
		MessageDigest digest = sha256();
		registries.registries()
			.sorted(Comparator.comparing(entry -> entry.key().location().toString()))
			.forEach(entry -> appendRegistryTags(digest, entry.value()));
		return HexFormat.of().formatHex(digest.digest());
	}

	public static String resourceLayers(MinecraftServer server, long revision) {
		if (revision < 0L) {
			throw new IllegalArgumentException("Resource revision must be non-negative");
		}
		MessageDigest digest = sha256();
		append(digest, Long.toString(revision));
		server.getPackRepository().getSelectedIds().forEach(id -> append(digest, id));
		return HexFormat.of().formatHex(digest.digest());
	}

	private static <T> void appendRegistryTags(MessageDigest digest, Registry<T> registry) {
		List<Pair<TagKey<T>, HolderSet.Named<T>>> tags = registry.getTags()
			.sorted(Comparator.comparing(value -> value.getFirst().location().toString()))
			.toList();
		for (Pair<TagKey<T>, HolderSet.Named<T>> tag : tags) {
			append(digest, registry.key().location().toString());
			append(digest, tag.getFirst().location().toString());
			tag.getSecond().stream()
				.map(Holder::unwrapKey)
				.map(key -> key.orElseThrow(() -> new IllegalStateException("Tag contains an unkeyed registry value")))
				.map(key -> key.location().toString())
				.sorted()
				.forEach(value -> append(digest, value));
		}
	}

	private static void append(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) 0);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is unavailable", error);
		}
	}
}
