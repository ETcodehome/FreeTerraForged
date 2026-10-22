package com.ishland.c2me.opts.dfc.common.gen.dot;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.gen.CodeEmitter;

public interface DotEmitter<T extends AstNode> extends CodeEmitter<T> {
	int doDotGen(T node, DotGen.Context context, DotGen.Context.Builder builder);
}
