package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

public final class PreviewSourceNegotiator {
	private PreviewSourceNegotiator() {
	}

	public static Result resolve(
		PreviewSourceContext context,
		List<? extends WorldgenCapabilityProvider> providers
	) {
		return resolve(context, WorldgenProviderCatalog.of(providers));
	}

	public static Result resolve(
		PreviewSourceContext context,
		WorldgenProviderCatalog catalog
	) {
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(catalog, "catalog");
		return catalog.inAcquisitionSession(
			context.cancelled(), () -> resolveAcquired(context, catalog)
		);
	}

	private static Result resolveAcquired(
		PreviewSourceContext context,
		WorldgenProviderCatalog catalog
	) {
		context.checkCancelled();
		BiomeSource realized = context.realizedSource();
		WorldgenProviderCatalog.Resolution resolution = catalog.resolvePreview();
		context.checkCancelled();
		if (!resolution.failures().isEmpty()) {
			WorldgenProviderCatalog.FailedProvider failed = resolution.failures().getFirst();
			throw new PreviewSourceNegotiationException(
				Optional.of(failed.metadata().id()), failed.failure()
			);
		}
		Result selected = null;
		for (WorldgenProviderCatalog.ProviderBinding binding : resolution.providers()) {
			checkCancelled(context, selected);
			WorldgenCapabilityProvider provider = binding.provider();
			if (!provider.ownerTypes().contains(WorldgenOwnerType.PREVIEW_REQUEST)) {
				continue;
			}
			Optional<RequestOwnedBiomeSource> candidate = null;
			try {
				candidate = Objects.requireNonNull(
					provider.previewSource(context), "provider preview source result"
				);
				context.checkCancelled();
			} catch (CancellationException failure) {
				if (candidate != null && candidate.isPresent()) {
					closeOwned(candidate.orElseThrow(), failure);
				}
				closeAfterFailure(selected, failure);
				throw failure;
			} catch (RuntimeException | LinkageError failure) {
				closeAfterFailure(selected, failure);
				throw providerFailure(provider.id(), failure);
			} catch (Exception failure) {
				closeAfterFailure(selected, failure);
				throw providerFailure(provider.id(), failure);
			} catch (Error failure) {
				closeAfterFailure(selected, failure);
				throw failure;
			}
			if (candidate.isEmpty()) {
				continue;
			}
			RequestOwnedBiomeSource owned = Objects.requireNonNull(
				candidate.orElseThrow(), "provider preview source"
			);
			try {
				validateFresh(realized, owned, provider.id());
				validateCompleteFactoryResult(owned, provider.id());
			} catch (RuntimeException | Error failure) {
				closeAfterFailure(selected, failure);
				throw failure;
			}
			Result next = new Result(owned, Optional.of(provider.id()));
			if (selected != null) {
				PreviewSourceNegotiationException failure = failure(
					provider.id(), "preview_source_factory_conflict",
					"Request-owned preview source was supplied by both "
						+ selected.provider().orElseThrow() + " and " + provider.id(), null
				);
				closeAfterFailure(next, failure);
				closeAfterFailure(selected, failure);
				throw failure;
			}
			selected = next;
		}
		if (selected == null && realized instanceof MultiNoiseBiomeSource) {
			context.checkCancelled();
			BiomeCandidateRoot root = MinecraftBiomeSourceGraphs.multiNoiseRoot(
				realized, context.lookups()
			);
			context.checkCancelled();
			BiomeSource source = MultiNoiseBiomeSource.createFromList(root.candidates());
			return new Result(RequestOwnedBiomeSource.immutable(source, root), Optional.empty());
		}
		if (selected == null && realized instanceof RequestOwnedBiomeSourceFactory factory) {
			context.checkCancelled();
			RequestOwnedBiomeSource owned = null;
			try {
				owned = Objects.requireNonNull(
					factory.createRequestOwnedSource(context), "custom request-owned source"
				);
				context.checkCancelled();
			} catch (CancellationException failure) {
				if (owned != null) {
					closeOwned(owned, failure);
				}
				throw failure;
			} catch (Exception | LinkageError failure) {
				if (owned != null) {
					closeOwned(owned, failure);
				}
				throw providerFailure(factory.requestOwnedFactoryId(), failure);
			} catch (Error failure) {
				if (owned != null) {
					closeOwned(owned, failure);
				}
				throw failure;
			}
			validateFresh(realized, owned, factory.requestOwnedFactoryId());
			validateCompleteFactoryResult(owned, factory.requestOwnedFactoryId());
			return new Result(owned, Optional.of(factory.requestOwnedFactoryId()));
		}
		if (selected == null) {
			throw new PreviewSourceNegotiationException(
				Optional.empty(),
				CapabilityFailure.unavailable(
					"preview_source_factory_missing",
					"The selected custom biome source exposes no complete request-owned factory: "
						+ realized.getClass().getName()
				)
			);
		}
		checkCancelled(context, selected);
		return selected;
	}

	private static void validateCompleteFactoryResult(
		RequestOwnedBiomeSource owned,
		ResourceLocation factory
	) {
		if (owned.source() instanceof MultiNoiseBiomeSource || owned.planInput().isPresent()) {
			return;
		}
		PreviewSourceNegotiationException failure = failure(
			factory,
			"preview_source_plan_missing",
			"A custom request-owned source must normalize to multi-noise or supply a complete BiomeSourcePlanInput",
			null
		);
		try {
			owned.close();
		} catch (Throwable cleanup) {
			failure.addSuppressed(cleanup);
		}
		throw failure;
	}

	private static void validateFresh(
		BiomeSource realized,
		RequestOwnedBiomeSource owned,
		ResourceLocation factory
	) {
		if (owned.source() == realized) {
			PreviewSourceNegotiationException failure = failure(
				factory, "preview_source_factory_not_fresh",
				"Request-owned source factory returned the realized source identity", null
			);
			try {
				owned.close();
			} catch (Throwable cleanup) {
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	private static PreviewSourceNegotiationException providerFailure(
		ResourceLocation provider,
		Throwable cause
	) {
		return failure(
			provider, "preview_source_factory_failed",
			"Preview source provider failed to create request-owned state", cause
		);
	}

	private static PreviewSourceNegotiationException failure(
		ResourceLocation provider,
		String code,
		String message,
		Throwable cause
	) {
		CapabilityFailure failure = cause == null
			? CapabilityFailure.unavailable(code, message)
			: CapabilityFailure.of(code, cause);
		return new PreviewSourceNegotiationException(Optional.of(provider), failure, cause);
	}

	private static void closeAfterFailure(Result result, Throwable failure) {
		if (result == null) {
			return;
		}
		try {
			result.owned().close();
		} catch (Throwable cleanup) {
			failure.addSuppressed(cleanup);
		}
	}

	private static void closeOwned(RequestOwnedBiomeSource owned, Throwable failure) {
		try {
			owned.close();
		} catch (Throwable cleanup) {
			failure.addSuppressed(cleanup);
		}
	}

	private static void checkCancelled(PreviewSourceContext context, Result selected) {
		try {
			context.checkCancelled();
		} catch (CancellationException failure) {
			closeAfterFailure(selected, failure);
			throw failure;
		}
	}

	public record Result(
		RequestOwnedBiomeSource owned,
		Optional<ResourceLocation> provider
	) {
		public Result {
			owned = Objects.requireNonNull(owned, "owned");
			provider = Objects.requireNonNull(provider, "provider");
		}
	}
}
