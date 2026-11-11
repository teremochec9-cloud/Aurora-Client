package dev.aurora.client.module.settings;

/** Включатель (checkbox). */
public class BooleanSetting extends Setting {
    private boolean value;

    public BooleanSetting(String name, boolean defaultValue) {
        this(name, defaultValue, "");
    }

    public BooleanSetting(String name, boolean defaultValue, String description) {
        super(name, description);
        this.value = defaultValue;
    }

    public boolean get() {
        return value;
    }

    public void set(boolean value) {
        this.value = value;
    }

    public void toggle() {
        this.value = !this.value;
    }

    @Override
    public String display() {
        return value ? "Вкл" : "Выкл";
    }
}
