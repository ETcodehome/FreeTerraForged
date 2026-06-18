package raccoonman.reterraforged.data.worldgen;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class RuntimeRegistryExporter implements DataProvider {
    private final PackOutput output;
    private final RegistryAccess registries;

    public RuntimeRegistryExporter(PackOutput output, RegistryAccess registries) {
        this.output = output;
        this.registries = registries;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        // THE FIX: We explicitly create a pure Vanilla RegistryOps.
        // This ensures NeoForge's Datagen Mixins cannot inject ConditionalOps,
        // preventing the inline serialization crash.
        DynamicOps<JsonElement> pureOps = RegistryOps.create(JsonOps.INSTANCE, this.registries);
        List<CompletableFuture<?>> futures = new ArrayList<>();

        // Iterate over standard data-driven registries (biomes, configured features, etc.)
        for (RegistryDataLoader.RegistryData<?> registryData : RegistryDataLoader.WORLDGEN_REGISTRIES) {
            dumpRegistry(cache, pureOps, registryData, futures);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private <T> void dumpRegistry(CachedOutput cache, DynamicOps<JsonElement> ops, RegistryDataLoader.RegistryData<T> registryData, List<CompletableFuture<?>> futures) {
        Optional<Registry<T>> optionalRegistry = this.registries.registry(registryData.key());
        if (optionalRegistry.isEmpty()) {
            return;
        }

        Registry<T> registry = optionalRegistry.get();
        PackOutput.PathProvider pathProvider = this.output.createRegistryElementsPathProvider(registryData.key());

        for (Holder.Reference<T> holder : registry.holders().toList()) {
            // Encode using the un-tainted pure Vanilla ops
            registryData.elementCodec().encodeStart(ops, holder.value())
                    .resultOrPartial(err -> {
                        // Optional: You can log errors here if specific elements fail
                    })
                    .ifPresent(json -> {
                        Path path = pathProvider.json(holder.key().location());
                        futures.add(DataProvider.saveStable(cache, json, path));
                    });
        }
    }

    @Override
    public String getName() {
        return "Runtime Registry Exporter";
    }
}