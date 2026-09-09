package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import raccoonman.reterraforged.RTFCommon;

public final class WorldgenPreServerFinalizer {
	private WorldgenPreServerFinalizer() {
	}

	public static Report finalize(PreServerWorldgenContext context) {
		if (!hasOwnedRoot(context)) {
			return new Report(List.of());
		}
		List<TerraForgedChunkGenerator> owners = generators(context).stream()
			.filter(TerraForgedChunkGenerator.class::isInstance)
			.map(TerraForgedChunkGenerator.class::cast)
			.toList();
		List<WorldgenProviderCatalog> existing = owners.stream()
			.map(TerraForgedChunkGenerator::existingProviderCatalog)
			.flatMap(Optional::stream)
			.distinct()
			.toList();
		WorldgenProviderCatalog catalog = existing.size() == 1
			? existing.getFirst()
			: WorldgenCapabilityDiscovery.discover(
				WorldgenPreServerFinalizer.class.getClassLoader()
			);
		return finalize(context, catalog);
	}

	static Report finalize(PreServerWorldgenContext context, WorldgenProviderCatalog catalog) {
		return catalog.inAcquisitionSession(() -> finalizeAcquired(context, catalog));
	}

	private static Report finalizeAcquired(
		PreServerWorldgenContext context,
		WorldgenProviderCatalog catalog
	) {
		List<ChunkGenerator> generators = generators(context);
		if (generators.stream().noneMatch(TerraForgedChunkGenerator.class::isInstance)) {
			return new Report(List.of());
		}
		WorldgenProviderCatalog.Resolution resolution = catalog.resolvePreServer();
		Map<ResourceLocation, CapabilityFailure> rejected = new LinkedHashMap<>();
		resolution.failures().forEach(failed -> rejected.put(failed.metadata().id(), failed.failure()));
		for (WorldgenProviderCatalog.ProviderBinding binding : resolution.providers()) {
			WorldgenCapabilityProvider provider = binding.provider();
			try {
				provider.finalizePreServer(context);
			} catch (Exception | LinkageError failure) {
				rejected.put(
					binding.metadata().id(),
					CapabilityFailure.of("provider_pre_server_finalization_failed", failure)
				);
			}
		}
		for (ChunkGenerator generator : generators) {
			if (generator instanceof TerraForgedChunkGenerator terraForged) {
				terraForged.publishPreServerCatalog(catalog, rejected);
			}
		}
		List<WorldgenProviderDiagnostic> diagnostics = new ArrayList<>(resolution.diagnostics());
		rejected.forEach((provider, failure) -> diagnostics.add(new WorldgenProviderDiagnostic(
			"pre_server_finalization", Optional.of(provider), Optional.empty(), failure
		)));
		Report report = new Report(diagnostics);
		for (WorldgenProviderDiagnostic diagnostic : report.diagnostics()) {
			RTFCommon.LOGGER.warn(
				"FTF pre-server capability failure provider={} code={} detail={}",
				diagnostic.provider().map(Object::toString).orElse("unknown"),
				diagnostic.failure().code(), diagnostic.failure().message()
			);
		}
		return report;
	}

	private static boolean hasOwnedRoot(PreServerWorldgenContext context) {
		return generators(context).stream().anyMatch(TerraForgedChunkGenerator.class::isInstance);
	}

	private static List<ChunkGenerator> generators(PreServerWorldgenContext context) {
		return context.dimensions().dimensions().values().stream()
			.map(stem -> stem.generator())
			.distinct()
			.toList();
	}

	static Optional<CapabilityFailure> failure(ChunkGenerator generator, ResourceLocation provider) {
		return generator instanceof TerraForgedChunkGenerator terraForged
			? terraForged.preServerFailure(provider)
			: Optional.empty();
	}

	public record Report(List<WorldgenProviderDiagnostic> diagnostics) {
		public Report {
			diagnostics = List.copyOf(diagnostics);
		}
	}
}
