package com.ishland.c2me.opts.dfc.common.gen.opencl;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.gen.CodeEmitter;

public interface OpenCLCEmitter<T extends AstNode> extends CodeEmitter<T> {
	String doCLGen(T node, OpenCLCGenFunctionContext context, String out);
}
