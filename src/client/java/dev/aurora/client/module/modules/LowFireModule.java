package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.NumberSetting;

/**
 * Низкий огонь: пламя на экране становится меньше и не закрывает обзор.
 */
public class LowFireModule extends Module {
    /** 1.0 — обычный огонь, меньше — ниже. */
    public final NumberSetting height = new NumberSetting("Высота", 0.55f, 0.2f, 1.0f, 0.05f, 2, "Насколько низким делать огонь");
    public final NumberSetting offset = new NumberSetting("Смещение", 0.0f, -0.2f, 0.2f, 0.01f, 2, "Поднять/опустить дополнительно");

    public LowFireModule() {
        super("Низкий огонь", "Огонь на экране не закрывает обзор", Category.VISUAL);
        addSettings(height, offset);
    }
}
