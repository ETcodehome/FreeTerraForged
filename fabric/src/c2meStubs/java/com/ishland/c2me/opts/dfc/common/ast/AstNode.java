package com.ishland.c2me.opts.dfc.common.ast;

public interface AstNode {
	AstNode[] getChildren();

	AstNode transform(AstTransformer transformer);

	boolean relaxedEquals(AstNode other);

	int relaxedHashCode();

	default ReturnType getReturnType() {
		return ReturnType.F64;
	}

	enum ReturnType {
		F64,
		F32
	}
}
