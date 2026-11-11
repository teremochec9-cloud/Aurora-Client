package dev.aurora.client.module.settings;

import dev.aurora.client.util.ColorUtil;

/** Цвет с выбором через пипетку в меню. */
public class ColorSetting extends Setting {
    private int value;

    public ColorSetting(String name, int defaultArgb) {
        this(name, defaultArgb, "");
    }

    public ColorSetting(String name, int defaultArgb, String description) {
        super(name, description);
        this.value = defaultArgb;
    }

    public int get() {
        return value;
    }

    public void set(int argb) {
        this.value = argb;
    }

    public int rgb() {
        return value & 0xFFFFFF;
    }

    public int withAlpha(int alpha) {
        return ColorUtil.alpha(value, alpha);
    }

    @Override
    public String display() {
        return String.format("#%06X", rgb());
    }
}
