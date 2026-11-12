package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.ColorSetting;
import dev.aurora.client.module.settings.ModeSetting;
import dev.aurora.client.util.Animation;

/**
 * Кастомный прицел вместо ванильного крестика.
 *
 * <p>Формы рисуются NanoVG-движком (гладкие круги и скруглённые линии
 * с мягким свечением), поэтому прицел выглядит аккуратно при любом
 * gui scale. Появление при включении — плавный выезд с нуля.</p>
 */
public class CustomCrosshairModule extends Module {
    public final ModeSetting style = new ModeSetting("Стиль", new String[]{"Точка", "Круг", "Крест", "Точка + круг"}, 0);
    public final BooleanSetting glow = new BooleanSetting("Свечение", true, "Мягкое свечение вокруг прицела");
    public final ColorSetting color = new ColorSetting("Цвет", 0xFFB5FFC8);

    /** Анимация включения: прицел плавно «выезжает». */
    public final Animation appear = new Animation(0f, 12);

    public CustomCrosshairModule() {
        super("Кастомный прицел", "Свой прицел: точка, круг или крест", Category.VISUAL);
        addSettings(style, glow, color);
    }
}
