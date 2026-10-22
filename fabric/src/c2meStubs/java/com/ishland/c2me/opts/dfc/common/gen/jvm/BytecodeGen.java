package com.ishland.c2me.opts.dfc.common.gen.jvm;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.InstructionAdapter;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.gen.meta.ValuesMethodDefF64;

public class BytecodeGen {

	public static class Context {
		public static final String SINGLE_DESC_F64 = null;
		public static final String SINGLE_DESC_F32 = null;
		public static final String MULTI_DESC_F64 = null;
		public static final String MULTI_DESC_F32 = null;
		public ClassWriter classWriter;
		public String className;
		public String classDesc;

		public <T> String newField(Class<T> type, T value) {
			return null;
		}

		public void delegateAllToSingle(InstructionAdapter method, LocalVarConsumer localVarConsumer, AstNode node) {
		}

		public ValuesMethodDefF64 newSingleMethodF64(AstNode node) {
			return null;
		}

		public void callDelegateSingle(InstructionAdapter method, ValuesMethodDefF64 def) {
		}

		public interface LocalVarConsumer {
			int createLocalVariable(String name, String desc);
		}
	}
}
