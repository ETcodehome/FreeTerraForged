package com.ishland.c2me.opts.dfc.common.gen.opencl;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.gen.meta.ValuesMethodDefF64;

public interface OpenCLCGenFunctionContext {
	OpenCLCGenContext getGlobalContext();

	ValuesMethodDefF64 newVarF64(AstNode node);

	String getDelegateVar(ValuesMethodDefF64 def);

	void appendRaw(String source);
}
