package com.ishland.c2me.opts.dfc.common.ast;

import net.minecraft.world.level.levelgen.DensityFunction;

public class FrontendRegistry<E extends AstEmitter<? extends DensityFunction>> {
	public <N extends DensityFunction, E1 extends AstEmitter<N>> void registerExactMatch(Class<N> type, E1 emitter) {
	}

	public <N extends DensityFunction, E1 extends AstEmitter<N>> E1 getOptional(Class<N> type) {
		return null;
	}
}
