package etcodehome.freeterraforged.client.gui.widget;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class Slider extends AbstractSliderButton {
	private double min;
	private double max;
	private final float defaultValue;
	private final boolean highlightModified;
	private Component name;
	private Format format;
	@Nullable
	private Callback callback;

	public Slider(int x, int y, int width, int height, float initialValue, float min, float max, Component name, Format format, @Nullable Callback callback, boolean highlightModified, float defaultValue) {
		super(x, y, width, height, CommonComponents.EMPTY, 0.0);
		this.name = name;
		this.defaultValue = defaultValue;
		this.highlightModified = highlightModified;
		this.format = format;
		this.min = min;
		this.max = max;
		this.callback = callback;
		this.setValue(this.getSliderValue(initialValue));
	}

	public void setValue(double value) {
		this.value = Double.isNaN(value) ? 0.0 : Mth.clamp(value, 0.0, 1.0);
		this.updateMessage();
	}

	public double getValue() {
		return this.value;
	}

	public double getMin() {
		return this.min;
	}

	public double getMax() {
		return this.max;
	}

	public float getDefaultValue() {
		return this.defaultValue;
	}

	public void setRange(double min, double max) {
		double current = this.getLerpedValue();
		this.min = min;
		this.max = max;
		this.setValue(this.getSliderValue((float) current));
	}

	public double getSliderValue(float value) {
		double range = this.max - this.min;
		if (range <= 0.0) {
			return 0.0;
		}
		return (Mth.clamp(value, (float) this.min, (float) this.max) - this.min) / range;
	}

	public double getLerpedValue() {
		return this.lerpValue(this.value);
	}

	public double lerpValue(double value) {
		return Mth.lerp(Mth.clamp(value, 0.0, 1.0), this.min, this.max);
	}

	public double scaleValue(double value) {
		return this.format.scale(this.lerpValue(value));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Reset to default value on Shift + Left Click
		if (button == 0 && this.clicked(mouseX, mouseY) && Screen.hasShiftDown()) {
			this.setValue(this.getSliderValue(this.defaultValue));
			this.applyValue();
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void applyValue() {
		if (this.callback != null) {
			this.setValue(this.callback.apply(this, this.value));
		}
	}

	@Override
	protected void updateMessage() {
		double scaledCurrent = this.scaleValue(this.value);
		boolean isModified = this.highlightModified && Math.abs(scaledCurrent - this.format.scale(this.defaultValue)) > 1e-5;

		MutableComponent valueComponent = Component.literal(this.format.getMessage(scaledCurrent));
		if (isModified) {
			valueComponent.withStyle(ChatFormatting.GOLD);
		}

		this.setMessage(CommonComponents.optionNameValue(this.name, valueComponent));
	}

	public static enum Format {
		INT {
			@Override
			public double scale(double input) {
				return (int) input;
			}

			@Override
			public String getMessage(double input) {
				return String.valueOf((int) input);
			}
		},
		FLOAT {
			@Override
			public double scale(double input) {
				return input;
			}

			@Override
			public String getMessage(double input) {
				return String.format("%.3f", input);
			}
		};

		public abstract double scale(double input);

		public abstract String getMessage(double input);
	}

	public interface Callback {
		double apply(Slider slider, double value);
	}
}