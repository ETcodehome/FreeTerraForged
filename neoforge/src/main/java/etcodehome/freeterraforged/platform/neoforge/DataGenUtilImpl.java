package etcodehome.freeterraforged.platform.neoforge;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

public final class DataGenUtilImpl {

	public static DataProvider createRegistryProvider(PackOutput output, CompletableFuture<RegistrySetBuilder.PatchedRegistries> registries) {
		return new DataProvider() {
			@Override
			public CompletableFuture<?> run(CachedOutput cache) {
				return registries.thenCompose(patched -> {
					// Presets also contribute entries under other namespaces. Export the
					// whole patch, including future contributions, without an allowlist.
					Set<String> namespaces = patched.patches().listRegistries()
						.flatMap(key -> patched.patches().lookupOrThrow(key).listElementIds())
						.map(key -> key.location().getNamespace())
						.collect(Collectors.toSet());
					return new DatapackBuiltinEntriesProvider(
						output, CompletableFuture.completedFuture(patched), namespaces
					).run(cache);
				});
			}

			@Override
			public String getName() {
				return "Registries";
			}
		};
	}
}
