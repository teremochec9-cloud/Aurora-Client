package dev.aurora.client.module.settings;

/** Выбор одного варианта из списка (переключатель «стрелками»). */
public class ModeSetting extends Setting {
    private int index;
    public final String[] modes;

    public ModeSetting(String name, String[] modes, int defaultIndex) {
        this(name, modes, defaultIndex, "");
    }

    public ModeSetting(String name, String[] modes, int defaultIndex, String description) {
        super(name, description);
        this.modes = modes;
        this.index = Math.max(0, Math.min(modes.length - 1, defaultIndex));
    }

    public String get() {
        return modes[index];
    }

    public int index() {
        return index;
    }

    public void setIndex(int i) {
        this.index = Math.max(0, Math.min(modes.length - 1, i));
    }

    public void cycle(int dir) {
        int n = modes.length;
        index = ((index + dir) % n + n) % n;
    }

    @Override
    public String display() {
        return get();
    }
}
