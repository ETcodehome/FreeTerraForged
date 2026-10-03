package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeEmitter;
import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeGen;
import com.ishland.c2me.opts.dfc.common.gen.meta.ValuesMethodDefF64;

public final class FTFClampBytecodeEmitter implements BytecodeEmitter<FTFClampNode> {
	public static final FTFClampBytecodeEmitter INSTANCE = new FTFClampBytecodeEmitter();

	private FTFClampBytecodeEmitter() {
	}

	@Override
	public void doBytecodeGenSingle(FTFClampNode node, BytecodeGen.Context context, InstructionAdapter method, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
		ValuesMethodDefF64 child = context.newSingleMethodF64(node.child());
		context.callDelegateSingle(method, child);

		int resolution = node.resolution();
		method.dconst(resolution);
		method.mul(Type.DOUBLE_TYPE);
		method.cast(Type.DOUBLE_TYPE, Type.INT_TYPE);
		method.iconst(1);
		method.add(Type.INT_TYPE);
		method.cast(Type.INT_TYPE, Type.FLOAT_TYPE);
		method.fconst(resolution);
		method.div(Type.FLOAT_TYPE);
		method.cast(Type.FLOAT_TYPE, Type.DOUBLE_TYPE);
		method.areturn(Type.DOUBLE_TYPE);
	}

	@Override
	public void doBytecodeGenMulti(FTFClampNode node, BytecodeGen.Context context, InstructionAdapter method, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
		context.delegateAllToSingle(method, localVarConsumer, node);
		method.areturn(Type.VOID_TYPE);
	}
}
