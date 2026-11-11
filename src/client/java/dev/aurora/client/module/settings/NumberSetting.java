package dev.aurora.client.module.settings;

/** Слайдер с дробным шагом. */
public class NumberSetting extends Setting {
    private float value;
    public final float min;
    public final float max;
    public final float step;
    /** Знаков после запятой для отображения. */
    public final int decimals;

    public NumberSetting(String name, float defaultValue, float min, float max, float step) {
        this(name, defaultValue, min, max, step, 1, "");
    }

    public NumberSetting(String name, float defaultValue, float min, float max, float step, int decimals, String description) {
        super(name, description);
        this.min = min;
        this.max = max;
        this.step = step;
        this.decimals = decimals;
        this.value = defaultValue;
    }

    public float get() {
        return value;
    }

    public int getInt() {
        return (int) value;
    }

    public void set(float v) {
        v = Math.max(min, Math.min(max, v));
        if (step > 0) {
            v = Math.round(v / step) * step;
        }
        this.value = Math.max(min, Math.min(max, v));
    }

    /** Устанавливает значение по позиции ползунка 0..1. */
    public void setByProgress(float p) {
        set(min + (max - min) * Math.max(0f, Math.min(1f, p)));
    }

    public float progress() {
        return max == min ? 0 : (value - min) / (max - min);
    }

    @Override
    public String display() {
        return String.format("%." + decimals + "f", value);
    }
}
