package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import com.ishland.c2me.opts.dfc.common.gen.opencl.OpenCLCEmitter;
import com.ishland.c2me.opts.dfc.common.gen.opencl.OpenCLCGenFunctionContext;

public final class FTFCellOpenCLEmitter implements OpenCLCEmitter<FTFCellNode> {
	public static final FTFCellOpenCLEmitter INSTANCE = new FTFCellOpenCLEmitter();

	public static final String CONSTANT_PROPERTY = "freeterraforged.gpuConstantCells";
	public static final String FALLBACK_PROPERTY = "freeterraforged.gpuMissingCellValue";

	private FTFCellOpenCLEmitter() {
	}

	private static boolean constantMode() {
		try {
			return Boolean.getBoolean(CONSTANT_PROPERTY);
		} catch (Throwable t) {
			return false;
		}
	}

	private static String fallbackValue() {
		try {
			return Double.toString(Double.parseDouble(System.getProperty(FALLBACK_PROPERTY, "0.0")));
		} catch (Throwable t) {
			return "0.0";
		}
	}

	@Override
	public String doCLGen(FTFCellNode node, OpenCLCGenFunctionContext context, String out) {
		int field = node.sampler().field().ordinal();

		if(constantMode()) {
			return out + " = 0.5;\n";
		}

		int table = context.getGlobalContext().allocGlobalDynamicData(FTFCellData.MARKER);
		String fallback = fallbackValue();

		StringBuilder source = new StringBuilder();
		source.append(out).append(" = ").append(fallback).append(";\n");
		source.append("if (ctx.rw_data) {\n");
		source.append("    global const int * restrict ftfHeader = df_data_offset_global(ctx.rw_data, ").append(table).append(");\n");
		source.append("    if (ftfHeader) {\n");
		source.append("        int ftfLocalX = ctx.x - ftfHeader[").append(FTFCellData.HEADER_START_X).append("];\n");
		source.append("        int ftfLocalZ = ctx.z - ftfHeader[").append(FTFCellData.HEADER_START_Z).append("];\n");
		source.append("        int ftfWidth = ftfHeader[").append(FTFCellData.HEADER_SIZE_X).append("];\n");
		source.append("        int ftfDepth = ftfHeader[").append(FTFCellData.HEADER_SIZE_Z).append("];\n");
		source.append("        ftfLocalX = clamp(ftfLocalX, 0, ftfWidth - 1);\n");
		source.append("        ftfLocalZ = clamp(ftfLocalZ, 0, ftfDepth - 1);\n");
		source.append("        global const float * restrict ftfValues = (global const float * restrict) (ftfHeader + ").append(FTFCellData.HEADER_INTS).append(");\n");
		source.append("        ").append(out).append(" = (double) ftfValues[").append(field).append(" * ftfHeader[").append(FTFCellData.HEADER_STRIDE).append("] + ftfLocalZ * ftfWidth + ftfLocalX];\n");
		source.append("    }\n");
		source.append("}\n");
		return source.toString();
	}
}
