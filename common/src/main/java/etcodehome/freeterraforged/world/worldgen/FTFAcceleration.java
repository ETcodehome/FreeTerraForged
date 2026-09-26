package etcodehome.freeterraforged.world.worldgen;

public final class FTFAcceleration {

	private static volatile GeneratorContext activeContext;

	public enum Backend {
		CPU,
		GPU
	}

	private static volatile boolean worldActive;
	private static volatile boolean openCLModulePresent;
	private static volatile boolean densityFunctionCompilerBound;
	private static volatile boolean openCLSkipped;
	private static volatile Backend backend = Backend.CPU;

	private FTFAcceleration() {
	}

	public static void setOpenCLModulePresent(boolean present) {
		openCLModulePresent = present;
	}

	public static void setDensityFunctionCompilerBound(boolean bound) {
		densityFunctionCompilerBound = bound;
	}

	public static void markWorldActive() {
		worldActive = true;
	}

	public static void setGeneratorContext(GeneratorContext context) {
		activeContext = context;
	}

	public static GeneratorContext generatorContext() {
		return activeContext;
	}

	public static void markOpenCLSkipped() {
		openCLSkipped = true;
		backend = Backend.CPU;
	}

	public static void markGpuAccelerated() {
		backend = Backend.GPU;
	}

	public static void reset() {
		activeContext = null;
		worldActive = false;
		openCLSkipped = false;
		backend = Backend.CPU;
	}

	public static boolean isWorldActive() {
		return worldActive;
	}

	public static Backend backend() {
		return backend;
	}

	public static String backendLabel() {
		return backend == Backend.GPU ? "GPU" : "CPU";
	}

	public static String detail() {
		if(backend == Backend.GPU) {
			return "OpenCL kernel";
		}
		if(openCLSkipped) {
			return "OpenCL: terrain not compatible";
		}
		if(!openCLModulePresent) {
			return "OpenCL: module not installed";
		}
		return "OpenCL: inactive";
	}

	public static String compilerDetail() {
		return densityFunctionCompilerBound ? "DFC: bound" : "DFC: delegate";
	}
}
