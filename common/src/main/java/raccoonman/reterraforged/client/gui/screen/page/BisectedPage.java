package raccoonman.reterraforged.client.gui.screen.page;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import raccoonman.reterraforged.client.gui.ColumnAlignment;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import raccoonman.reterraforged.client.gui.widget.WidgetList;

public abstract class BisectedPage<S extends Screen, L extends AbstractWidget, R extends AbstractWidget> implements Page {
	protected S screen;
	protected WidgetList<L> left;
	protected WidgetList<R> right;
	
	public BisectedPage(S screen) {
		this.screen = screen;
	}
	
	@Override
	public void init() {
		ColumnAlignment alignment = new ColumnAlignment(this.screen, 4, 0, 10, 30);
		this.left = alignment.addColumn(0.7F, this::createAndPositionColumn);
		this.right = alignment.addColumn(0.3F, this::createAndPositionColumn);
	}

	private <T extends AbstractWidget> WidgetList<T> createAndPositionColumn(int left, int top, int columnWidth, int height, int horizontalPadding, int verticalPadding) {
		final int padding = 30;
		WidgetList<T> list = new WidgetList<>(this.screen.minecraft, columnWidth, height - 65, padding, getSlotHeight());
		list.setX(left);
		return list;
	}

	public int getSlotHeight() {
		return 25;
	}

	/**
	 * Calculates the total vertical space occupied by the widgets currently in the list.
	 */
	public int getTotalListHeight(WidgetList<?> list) {
		return list.children().size() * this.getSlotHeight();
	}

}