package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.ColorSetting;
import dev.aurora.client.module.settings.ModeSetting;
import dev.aurora.client.module.settings.NumberSetting;

/**
 * Кастомный прицел вместо ванильного крестика.
 */
public class CustomCrosshairModule extends Module {
    public final ModeSetting style = new ModeSetting("Стиль", new String[]{"Точка", "Круг", "Крест", "Точка + круг"}, 0);
    public final ColorSetting color = new ColorSetting("Цвет", 0xFFB5FFC8);
    public final NumberSetting size = new NumberSetting("Размер", 4.0f, 1.0f, 8.0f, 1.0f, 0,
            "Размер точки/креста и радиус круга");

    public CustomCrosshairModule() {
        super("Кастомный прицел", "Свой прицел: точка, круг или крест", Category.VISUAL);
        addSettings(style, color, size);
    }
}
