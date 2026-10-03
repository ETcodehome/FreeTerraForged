package com.ishland.c2me.opts.dfc.common.gen.opencl;

public interface OpenCLCGenContext {
	int allocGlobalDynamicData(Object key);

	int getGlobalDynamicDataOffset(Object key);

	void appendRaw(String source);
}
