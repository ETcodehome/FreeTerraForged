package raccoonman.reterraforged.neoforge.compat.lithostitched;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import com.mojang.serialization.Lifecycle;

import dev.worldgen.lithostitched.api.registry.LithostitchedRegistries;
import dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FastNoiseConfig;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.BiomeInjectorManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewIntegration;

public final class LithostitchedBiomePreviewIntegration implements BiomePreviewIntegration {
	/**
	 * Session-lifetime latch: once applyInjectors() is known to fail (e.g. due to a
	 * Lithostitched/Biolith compatibility issue where Biolith's BiomeCoordinator
	 * biome-lookup singleton is populated with an incompatible registry snapshot from
	 * elsewhere in the game session), we stop retrying entirely rather than re-running
	 * the failing setup on every single preview regenerate. A fresh NoiseBasedChunkGenerator
	 * is constructed by BiomePreviewResolver.create() on every regenerate, so the
	 * per-generator `initializations` cache below never actually gets a cache hit for
	 * repeated failures - this flag is what actually prevents repeated retries/logging.
	 * Reset is not attempted mid-session since the underlying incompatibility (if any)
	 * won't resolve itself without a restart or mod update.
	 */
	private static final AtomicBoolean KNOWN_INCOMPATIBLE = new AtomicBoolean(false);

	private final Map<ChunkGenerator, Initialization> initializations = new WeakHashMap<>();
	private final ReentrantLock sessionLock = new ReentrantLock();

	@Override
	public String id() {
		return "reterraforged:lithostitched-neoforge";
	}

	@Override
	public boolean supports(Context context) {
		return context.generator() instanceof NoiseBasedChunkGenerator;
	}

	@Override
	public Session open(Context context) {
		this.sessionLock.lock();
		boolean opened = false;
		try {
			if (KNOWN_INCOMPATIBLE.get()) {
				RTFCommon.LOGGER.debug(
						"Skipping Lithostitched biome preview integration; already known to be incompatible this session, using fallback"
				);
				throw INCOMPATIBLE_SENTINEL;
			}

			Initialization initialization = this.initializations.get(context.generator());
			if (initialization == null) {
				try {
					this.applyInjectors(context);
					initialization = Initialization.SUCCESS;
				} catch (RuntimeException | LinkageError error) {
					initialization = new Initialization(error);
					if (KNOWN_INCOMPATIBLE.compareAndSet(false, true)) {
						RTFCommon.LOGGER.warn(
								"Lithostitched biome preview integration failed to initialize; this is a known "
										+ "compatibility issue when Biolith is also installed and its biome lookup was "
										+ "already populated elsewhere this session. Preview will use its fallback "
										+ "parameter tree for the rest of this session; this will not be logged again.",
								error
						);
					}
				}
				this.initializations.put(context.generator(), initialization);
			}
			initialization.rethrowFailure();
			this.bindFastNoiseConfigs(context);
			opened = true;
			return this.sessionLock::unlock;
		} finally {
			if (!opened) {
				this.sessionLock.unlock();
			}
		}
	}

	private void applyInjectors(Context context) {
		MappedRegistry<LevelStem> dimensions = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable());
		dimensions.register(LevelStem.OVERWORLD, context.levelStem(), RegistrationInfo.BUILT_IN);
		dimensions.freeze();

		RegistryAccess safeRegistries = wrapSafeRegistryAccess(context.registries());
		BiomeInjectorManager.applyBiomeInjectors(safeRegistries, dimensions, context.seed());
	}

	private void bindFastNoiseConfigs(Context context) {
		RegistryAccess safeRegistries = wrapSafeRegistryAccess(context.registries());
		safeRegistries.lookupOrThrow(LithostitchedRegistries.FAST_NOISE_CONFIG)
				.listElements()
				.map(holder -> (FastNoiseConfig) holder.value())
				.forEach(config -> config.bind(context.seed()));
	}

	private static RegistryAccess wrapSafeRegistryAccess(RegistryAccess delegate) {
		if (delegate instanceof SafeRegistryAccess safe) {
			return safe;
		}
		return new SafeRegistryAccess(delegate);
	}

	private static final class SafeRegistryAccess implements RegistryAccess.Frozen {
		private final RegistryAccess delegate;

		private SafeRegistryAccess(RegistryAccess delegate) {
			this.delegate = delegate;
		}

		@Override
		public <E> Optional<Registry<E>> registry(ResourceKey<? extends Registry<? extends E>> key) {
			return this.delegate.registry(key);
		}

		@Override
		public Stream<RegistryEntry<?>> registries() {
			return this.delegate.registries();
		}

		@Override
		public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
			return this.delegate.registry(key).map(Registry::asLookup);
		}

		@Override
		public <T> HolderLookup.RegistryLookup<T> lookupOrThrow(ResourceKey<? extends Registry<? extends T>> key) {
			return this.delegate.registryOrThrow(key).asLookup();
		}

		@Override
		public RegistryAccess.Frozen freeze() {
			return this;
		}
	}

	private record Initialization(Throwable failure) {
		private static final Initialization SUCCESS = new Initialization(null);

		private void rethrowFailure() {
			if (this.failure instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (this.failure instanceof LinkageError linkageError) {
				throw linkageError;
			}
		}
	}

	/**
	 * Lightweight, stackless sentinel thrown when we've already given up on this
	 * integration for the session. Avoids paying for a fresh stack trace capture on
	 * every regenerate once the outcome is already known.
	 */
	private static final RuntimeException INCOMPATIBLE_SENTINEL = new RuntimeException(
			"Lithostitched biome preview integration is disabled for this session", null, false, false
	) {
	};
}