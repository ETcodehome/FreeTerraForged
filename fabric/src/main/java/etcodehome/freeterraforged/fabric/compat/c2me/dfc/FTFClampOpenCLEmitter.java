package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import com.ishland.c2me.opts.dfc.common.gen.meta.ValuesMethodDefF64;
import com.ishland.c2me.opts.dfc.common.gen.opencl.OpenCLCEmitter;
import com.ishland.c2me.opts.dfc.common.gen.opencl.OpenCLCGenFunctionContext;

public final class FTFClampOpenCLEmitter implements OpenCLCEmitter<FTFClampNode> {
	public static final FTFClampOpenCLEmitter INSTANCE = new FTFClampOpenCLEmitter();

	private FTFClampOpenCLEmitter() {
	}

	@Override
	public String doCLGen(FTFClampNode node, OpenCLCGenFunctionContext context, String out) {
		ValuesMethodDefF64 child = context.newVarF64(node.child());
		String value = context.getDelegateVar(child);
		int resolution = node.resolution();

		return out + " = (double) ((float) ((int) (" + value + " * " + resolution + ".0) + 1) / (float) " + resolution + ");\n";
	}
}
