package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeEmitter;
import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeGen;

import net.minecraft.world.level.levelgen.DensityFunction;

public final class FTFCellBytecodeEmitter implements BytecodeEmitter<FTFCellNode> {
	public static final FTFCellBytecodeEmitter INSTANCE = new FTFCellBytecodeEmitter();

	private static final int LOCAL_BLOCK_X = 1;
	private static final int LOCAL_BLOCK_Z = 3;

	private static final Type FUNCTION_TYPE = Type.getType(DensityFunction.class);
	private static final String FUNCTION_DESC = FUNCTION_TYPE.getDescriptor();
	private static final String BINDINGS_INTERNAL_NAME = Type.getInternalName(FTFCellBindings.class);
	private static final String SAMPLE_DESC = Type.getMethodDescriptor(Type.DOUBLE_TYPE, FUNCTION_TYPE, Type.INT_TYPE, Type.INT_TYPE);

	private FTFCellBytecodeEmitter() {
	}

	@Override
	public void doBytecodeGenSingle(FTFCellNode node, BytecodeGen.Context context, InstructionAdapter method, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
		String fieldName = context.newField(DensityFunction.class, node.sampler());
		method.load(0, InstructionAdapter.OBJECT_TYPE);
		method.getfield(context.className, fieldName, FUNCTION_DESC);
		method.load(LOCAL_BLOCK_X, Type.INT_TYPE);
		method.load(LOCAL_BLOCK_Z, Type.INT_TYPE);
		method.invokestatic(BINDINGS_INTERNAL_NAME, "sample", SAMPLE_DESC, false);
		method.areturn(Type.DOUBLE_TYPE);
	}

	@Override
	public void doBytecodeGenMulti(FTFCellNode node, BytecodeGen.Context context, InstructionAdapter method, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
		context.delegateAllToSingle(method, localVarConsumer, node);
		method.areturn(Type.VOID_TYPE);
	}
}
