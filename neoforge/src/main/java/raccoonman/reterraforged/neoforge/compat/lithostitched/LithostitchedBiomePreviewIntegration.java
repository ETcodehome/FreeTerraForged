package raccoonman.reterraforged.neoforge.compat.lithostitched;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.mojang.serialization.Lifecycle;

import dev.worldgen.lithostitched.api.registry.LithostitchedRegistries;
import dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FastNoiseConfig;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.BiomeInjectorManager;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewIntegration;

public final class LithostitchedBiomePreviewIntegration implements BiomePreviewIntegration {
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
			Initialization initialization = this.initializations.get(context.generator());
			if (initialization == null) {
				try {
					this.applyInjectors(context);
					initialization = Initialization.SUCCESS;
				} catch (RuntimeException | LinkageError error) {
					initialization = new Initialization(error);
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

		RegistryAccess baseRegistries = context.registries();
		var biomeRegistry = baseRegistries.registryOrThrow(Registries.BIOME);

		RegistryAccess safeRegistries = new RegistryAccess.Frozen() {
			@Override
			@SuppressWarnings("unchecked")
			public <E> java.util.Optional<net.minecraft.core.Registry<E>> registry(net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<? extends E>> key) {
				if (key.equals(Registries.BIOME)) {
					return java.util.Optional.of((net.minecraft.core.Registry<E>) biomeRegistry);
				}
				return baseRegistries.registry(key);
			}

			@Override
			@SuppressWarnings("unchecked")
			public <E> java.util.Optional<net.minecraft.core.HolderLookup.RegistryLookup<E>> lookup(net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<? extends E>> key) {
				return baseRegistries.lookup(key);
			}

			@Override
			@SuppressWarnings("unchecked")
			public <E> net.minecraft.core.HolderLookup.RegistryLookup<E> lookupOrThrow(net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<? extends E>> key) {
				return baseRegistries.lookupOrThrow(key);
			}

			@Override
			public java.util.stream.Stream<RegistryEntry<?>> registries() {
				return baseRegistries.registries();
			}
		};

		BiomeInjectorManager.applyBiomeInjectors(safeRegistries, dimensions, context.seed());
	}

	private void bindFastNoiseConfigs(Context context) {
		context.registries().lookupOrThrow(LithostitchedRegistries.FAST_NOISE_CONFIG)
				.listElements()
				.map(holder -> (FastNoiseConfig) holder.value())
				.forEach(config -> config.bind(context.seed()));
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
}
