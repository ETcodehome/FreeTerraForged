package com.ishland.c2me.opts.dfc.common.gen.jvm;

import org.objectweb.asm.commons.InstructionAdapter;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.gen.CodeEmitter;

public interface BytecodeEmitter<T extends AstNode> extends CodeEmitter<T> {
	void doBytecodeGenSingle(T node, BytecodeGen.Context context, InstructionAdapter method, BytecodeGen.Context.LocalVarConsumer localVarConsumer);

	void doBytecodeGenMulti(T node, BytecodeGen.Context context, InstructionAdapter method, BytecodeGen.Context.LocalVarConsumer localVarConsumer);
}
