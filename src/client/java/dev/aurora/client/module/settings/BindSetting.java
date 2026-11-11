package dev.aurora.client.module.settings;

import org.lwjgl.glfw.GLFW;

/** Клавиша-бинд модуля (0 — не задана). */
public class BindSetting extends Setting {

    public BindSetting(String name) {
        this(name, 0);
    }

    public BindSetting(String name, int defaultKey) {
        super(name, "");
        this.key = defaultKey;
    }

    private int key;

    public int get() {
        return key;
    }

    public void set(int key) {
        this.key = key;
    }

    /** Человекочитаемое имя клавиши. */
    public String keyName() {
        if (key == 0) {
            return "Нет";
        }
        String name = GLFW.glfwGetKeyName(key, 0);
        if (name != null) {
            return name.toUpperCase();
        }
        switch (key) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT: return "R-Shift";
            case GLFW.GLFW_KEY_LEFT_SHIFT: return "L-Shift";
            case GLFW.GLFW_KEY_RIGHT_CONTROL: return "R-Ctrl";
            case GLFW.GLFW_KEY_LEFT_CONTROL: return "L-Ctrl";
            case GLFW.GLFW_KEY_SPACE: return "Space";
            case GLFW.GLFW_KEY_TAB: return "Tab";
            case GLFW.GLFW_KEY_CAPS_LOCK: return "Caps";
            default: return "Код " + key;
        }
    }

    @Override
    public String display() {
        return keyName();
    }
}
