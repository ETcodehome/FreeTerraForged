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
	private double realValue;
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
		this.setRealValue(initialValue);
	}

	/**
	 * Sets the slider position using normalized 0.0 - 1.0 range (for legacy/external call sites).
	 */
	public void setValue(double normalizedValue) {
		this.value = Double.isNaN(normalizedValue) ? 0.0 : Mth.clamp(normalizedValue, 0.0, 1.0);
		this.realValue = Mth.lerp(this.value, this.min, this.max);
		this.updateMessage();
	}

	/**
	 * Returns the normalized 0.0 - 1.0 slider position (for legacy/external call sites).
	 */
	public double getValue() {
		return this.value;
	}

	/**
	 * Sets the actual unconstrained real value (supports out-of-range values).
	 */
	public void setRealValue(double realValue) {
		this.realValue = Double.isNaN(realValue) ? 0.0 : realValue;
		double range = this.max - this.min;
		if (range <= 0.0) {
			this.value = 0.0;
		} else {
			double normalized = (this.realValue - this.min) / range;
			this.value = Mth.clamp(normalized, 0.0, 1.0);
		}
		this.updateMessage();
	}

	/**
	 * Returns the actual unconstrained real value.
	 */
	public double getRealValue() {
		return this.realValue;
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
		double current = this.realValue;
		this.min = min;
		this.max = max;
		this.setRealValue(current);
	}

	public double getSliderValue(float value) {
		double range = this.max - this.min;
		if (range <= 0.0) {
			return 0.0;
		}
		return (value - this.min) / range;
	}

	public double getLerpedValue() {
		return this.realValue;
	}

	public double lerpValue(double value) {
		return Mth.lerp(Mth.clamp(value, 0.0, 1.0), this.min, this.max);
	}

	public double scaleValue(double value) {
		return this.format.scale(value);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Reset to default value on Shift + Left Click
		if (button == 0 && this.clicked(mouseX, mouseY) && Screen.hasShiftDown()) {
			this.setRealValue(this.defaultValue);
			this.applyValue();
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void applyValue() {
		// When dragged or adjusted via keyboard, map the 0.0-1.0 thumb position to real min/max
		this.realValue = Mth.lerp(this.value, this.min, this.max);
		if (this.callback != null) {
			this.realValue = this.callback.apply(this, this.realValue);
			// Re-sync thumb position in case callback adjusted the value
			double range = this.max - this.min;
			if (range > 0.0) {
				this.value = Mth.clamp((this.realValue - this.min) / range, 0.0, 1.0);
			}
		}
		this.updateMessage();
	}

	@Override
	protected void updateMessage() {
		double scaledCurrent = this.scaleValue(this.realValue);
		boolean isModified = this.highlightModified && Math.abs(scaledCurrent - this.format.scale(this.defaultValue)) > 1e-5;

		MutableComponent valueComponent = Component.literal(this.format.getMessage(scaledCurrent));

		if (this.realValue < this.min || this.realValue > this.max) {
			valueComponent.withStyle(ChatFormatting.RED);
		} else if (isModified) {
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