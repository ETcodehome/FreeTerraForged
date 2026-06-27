package raccoonman.reterraforged.client.gui.screen.page;

import java.util.Optional;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetEditorPage;
import raccoonman.reterraforged.client.gui.widget.Label;

public abstract class LinkedPageScreen extends Screen {
	public Button previousButton,
				  nextButton,
				  cancelButton,
				  doneButton;
	protected Page currentPage;
	
	protected LinkedPageScreen() {
		super(CommonComponents.EMPTY);
	}
	
	public void setPage(Page page) {
		this.currentPage.onCancel();
		this.currentPage = page;
		this.rebuildWidgets();
	}
	
	@Override
	public void init() {
		super.init();

		int buttonsCenter = this.width/2;
        int buttonWidth = 50;
        int buttonHeight = 20;
        int buttonPad = 2;
        int buttonsRow = this.height - 25;
       
		this.previousButton = Button.builder(Component.literal("<<"), (b) -> {
			this.currentPage.previous().ifPresent(this::setPage);
		}).bounds(buttonsCenter - (buttonWidth * 2 + (buttonPad * 3)), buttonsRow, buttonWidth, buttonHeight).build();
		this.previousButton.active = this.currentPage.previous().isPresent();

		this.nextButton = Button.builder(Component.literal(">>"), (b) -> {
			this.currentPage.next().ifPresent(this::setPage);
		}).bounds(buttonsCenter + buttonWidth + (buttonPad * 3), buttonsRow, buttonWidth, buttonHeight).build();
		this.nextButton.active = this.currentPage.next().isPresent();
		
		this.cancelButton = Button.builder(CommonComponents.GUI_CANCEL, (b) -> {
			this.onClose();
		}).bounds(buttonsCenter - buttonWidth - buttonPad, buttonsRow, buttonWidth, buttonHeight).build();

		this.doneButton = Button.builder(CommonComponents.GUI_DONE, (b) -> {
			this.onDone();
			this.onClose();
		}).bounds(buttonsCenter + buttonPad, buttonsRow, buttonWidth, buttonHeight).build();
		
		this.currentPage.init();

		// Center PresetEditorPage
		if ((this.currentPage instanceof PresetEditorPage)) {
			// 1. Cast the page to grab the actual central column object
			BisectedPage<?, ?, ?> bisectedPage = (BisectedPage<?, ?, ?>) this.currentPage;

			// 2. Find the exact horizontal midpoint of that central box
			int columnCenter = bisectedPage.left.getX() + (bisectedPage.left.getWidth() / 2);

			// 3. Measure the text width and offset it so the string's center aligns with the column's center
			int textWidth = this.font.width(this.currentPage.title());
			int centeredX = columnCenter - (textWidth / 2);

			this.addRenderableOnly(new Label(centeredX, 10, textWidth, 20, this.currentPage.title()));
		} else {
			this.addRenderableOnly(new Label(16, 10, 20, 20, this.currentPage.title()));
		}

		this.addRenderableWidget(this.cancelButton);
		this.addRenderableWidget(this.doneButton);
		this.addRenderableWidget(this.previousButton);
		this.addRenderableWidget(this.nextButton);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.renderBackground(guiGraphics, mouseY, mouseY, partialTicks);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public void onClose() {
		this.currentPage.onCancel();
	}
	
	public void onDone() {
		this.currentPage.onSave();
	}
	
	public interface Page {
		Component title();
		
		void init();
		
		Optional<Page> previous();
		
		Optional<Page> next();
		
		default void onCancel() {
		}
		
		default void onSave() {
		}
	}
}
