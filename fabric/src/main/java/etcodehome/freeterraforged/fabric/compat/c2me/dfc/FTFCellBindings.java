package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import com.ishland.c2me.opts.dfc.common.ast.McToAst;
import com.ishland.c2me.opts.dfc.common.gen.dot.DotGenRegistry;
import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeGenRegistry;
import com.ishland.c2me.opts.dfc.common.gen.opencl.OpenCLCGenData;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.FTFAcceleration;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;
import etcodehome.freeterraforged.world.worldgen.densityfunction.ClampToNearestUnit;
import etcodehome.freeterraforged.world.worldgen.densityfunction.MutableFunctionContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.fabricmc.loader.api.FabricLoader;

public final class FTFCellBindings {
	private static final String DFC_MODULE_ID = "c2me-opts-dfc";
	private static final ThreadLocal<MutableFunctionContext> CONTEXT = ThreadLocal.withInitial(MutableFunctionContext::new);

	private static boolean registered;

	private FTFCellBindings() {
	}

	public static double sample(DensityFunction function, int blockX, int blockZ) {
		return function.compute(CONTEXT.get().at(blockX, 0, blockZ));
	}

	public static synchronized void register() {
		if(registered || !isDfcLoaded()) {
			return;
		}

		try {
			BytecodeGenRegistry.REGISTRY.registerExactMatch(FTFCellNode.class, FTFCellBytecodeEmitter.INSTANCE);
			DotGenRegistry.REGISTRY.registerExactMatch(FTFCellNode.class, FTFCellDotEmitter.INSTANCE);
			BytecodeGenRegistry.REGISTRY.registerExactMatch(FTFClampNode.class, FTFClampBytecodeEmitter.INSTANCE);
			DotGenRegistry.REGISTRY.registerExactMatch(FTFClampNode.class, FTFClampDotEmitter.INSTANCE);

			if(isOpenCLLoaded()) {
				OpenCLCGenData.REGISTRY.registerExactMatch(FTFCellNode.class, FTFCellOpenCLEmitter.INSTANCE);
				OpenCLCGenData.REGISTRY.registerExactMatch(FTFClampNode.class, FTFClampOpenCLEmitter.INSTANCE);
			}

			McToAst.REGISTRY.registerExactMatch(CellSampler.class, FTFCellFrontend.INSTANCE);
			McToAst.REGISTRY.registerExactMatch(ClampToNearestUnit.class, FTFClampFrontend.INSTANCE);
			registered = true;
			FTFAcceleration.setDensityFunctionCompilerBound(true);
			FTFCommon.LOGGER.info("Registered FreeTerraForged density function bindings with the C2ME density function compiler");
		} catch (Throwable t) {
			FTFCommon.LOGGER.warn("Could not register FreeTerraForged bindings with the C2ME density function compiler, falling back to its generic delegate path", t);
		}
	}

	private static boolean isOpenCLLoaded() {
		try {
			return FabricLoader.getInstance().isModLoaded("c2me-opts-accel-opencl");
		} catch (Throwable t) {
			return false;
		}
	}

	private static boolean isDfcLoaded() {
		try {
			return FabricLoader.getInstance().isModLoaded(DFC_MODULE_ID);
		} catch (Throwable t) {
			return false;
		}
	}
}
