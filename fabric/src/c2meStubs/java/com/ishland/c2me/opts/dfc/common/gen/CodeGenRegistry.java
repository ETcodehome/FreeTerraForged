package com.ishland.c2me.opts.dfc.common.gen;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;

public class CodeGenRegistry<E extends CodeEmitter<? extends AstNode>> {
	public <N extends AstNode, E1 extends CodeEmitter<N>> void registerExactMatch(Class<N> type, E1 emitter) {
	}

	public <N extends AstNode, E1 extends CodeEmitter<N>> E1 get(Class<N> type) {
		return null;
	}
}
