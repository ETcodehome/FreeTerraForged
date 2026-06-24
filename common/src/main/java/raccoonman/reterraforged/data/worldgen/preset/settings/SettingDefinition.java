package raccoonman.reterraforged.data.worldgen.preset.settings;

import java.util.List;

public record SettingDefinition<T>(
        SettingToken<T> token,
        SettingKind kind,
        T defaultValue,
        T min,
        T max,
        List<T> choices,
        String translationKey
) {

    @SuppressWarnings("unchecked")
    public T clamp(T value) {
        if (kind == SettingKind.CYCLE && choices != null) {
            return choices.contains(value) ? value : defaultValue;
        }

        if (value instanceof Number num && min instanceof Number minNum && max instanceof Number maxNum) {
            double val = num.doubleValue();
            double clamped = Math.max(minNum.doubleValue(), Math.min(maxNum.doubleValue(), val));

            if (token.type() == Float.class) {
                return (T) Float.valueOf((float) clamped);
            }
            if (token.type() == Integer.class) {
                return (T) Integer.valueOf((int) clamped);
            }
        }
        return value;
    }
}