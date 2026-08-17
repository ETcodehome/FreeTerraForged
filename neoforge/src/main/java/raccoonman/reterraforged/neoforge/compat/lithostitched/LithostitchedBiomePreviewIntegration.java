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
		BiomeInjectorManager.applyBiomeInjectors(context.registries(), dimensions, context.seed());
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
