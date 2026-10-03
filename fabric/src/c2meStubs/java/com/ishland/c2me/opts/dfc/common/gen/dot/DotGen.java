package com.ishland.c2me.opts.dfc.common.gen.dot;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;

public class DotGen {

	public static class Context {
		public int generate(AstNode node) {
			return 0;
		}

		public Builder createExtraBuilder() {
			return null;
		}

		public interface Builder {
			Builder boxShape();

			Builder label(String label);

			Builder tooltip(String tooltip);

			Edge edge(int target);

			int build();

			interface Edge {
				Edge label(String label);

				Edge color(String color);

				Builder finish();
			}
		}
	}
}
