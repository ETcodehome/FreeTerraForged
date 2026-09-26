package etcodehome.freeterraforged.fabric.compat.c2me.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ishland.c2me.opts.accel.opencl.common.compiler.GeneratedCLSource;
import com.ishland.c2me.opts.accel.opencl.common.compiler.emitters.misc.CLBlockStateMappings;
import com.ishland.c2me.opts.accel.opencl.common.gen.cache.Stage1Cache;

import etcodehome.freeterraforged.fabric.compat.c2me.dfc.FTFGpuTerrain;
import etcodehome.freeterraforged.world.worldgen.FTFAcceleration;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

@Mixin(targets = "com.ishland.c2me.opts.accel.opencl.common.gen.CLDataUtil", remap = false)
public class MixinCLDataUtil {

	@Inject(
		method = "worldgen_data_root$createForArea",
		at = @At("HEAD"),
		remap = false,
		require = 0
	)
	private static void freeterraforged$prepareCellData(
		ChunkPos areaPos,
		int cellCountY,
		StaticCache2D chunks,
		NoiseBasedChunkGenerator generator,
		RandomState randomState,
		StaticCache2D structures,
		CLBlockStateMappings mappings,
		GeneratedCLSource source,
		Stage1Cache.AreaCacheEntry areaCache,
		CallbackInfoReturnable<ByteBuffer> callback
	) {
		if(!FTFGpuTerrain.isEnabled()) {
			return;
		}

		if(!((Object) randomState instanceof FTFRandomState ftfRandomState)) {
			return;
		}

		FTFGpuTerrain.prepare(source, ftfRandomState.generatorContext(), areaPos.x, areaPos.z);
	}

	@Inject(
		method = "worldgen_data_root$createForArea",
		at = @At("RETURN"),
		remap = false,
		require = 0
	)
	private static void freeterraforged$clearCellData(
		ChunkPos areaPos,
		int cellCountY,
		StaticCache2D chunks,
		NoiseBasedChunkGenerator generator,
		RandomState randomState,
		StaticCache2D structures,
		CLBlockStateMappings mappings,
		GeneratedCLSource source,
		Stage1Cache.AreaCacheEntry areaCache,
		CallbackInfoReturnable<ByteBuffer> callback
	) {
		FTFGpuTerrain.clear();
	}

	@Inject(
		method = "worldgen_data_root$createForFlatCacheOnly",
		at = @At("HEAD"),
		remap = false,
		require = 0
	)
	private static void freeterraforged$prepareFlatCacheCellData(
		ChunkPos areaPos,
		int cellCountY,
		GeneratedCLSource source,
		double[] prefill,
		boolean flag,
		CallbackInfoReturnable<ByteBuffer> callback
	) {
		if(!FTFGpuTerrain.isEnabled()) {
			return;
		}

		FTFGpuTerrain.prepare(source, FTFAcceleration.generatorContext(), areaPos.x, areaPos.z);
	}

	@Inject(
		method = "worldgen_data_root$createForFlatCacheOnly",
		at = @At("RETURN"),
		remap = false,
		require = 0
	)
	private static void freeterraforged$clearFlatCacheCellData(
		ChunkPos areaPos,
		int cellCountY,
		GeneratedCLSource source,
		double[] prefill,
		boolean flag,
		CallbackInfoReturnable<ByteBuffer> callback
	) {
		FTFGpuTerrain.clear();
	}
}
