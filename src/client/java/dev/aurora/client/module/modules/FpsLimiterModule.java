package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.NumberSetting;
import net.minecraft.client.Minecraft;

/**
 * «Лимит FPS»: ограничивает частоту кадров, экономя батарею и снижая
 * нагрев ноутбука. Использует ванильную опцию framerateLimit — без
 * миксинов, безопасно и совместимо.
 *
 * <p>При включении запоминает текущее значение и выставляет выбранное;
 * при выключении возвращает исходное. Если пользователь двигает слайдер
 * прямо в меню — лимит применяется сразу (без переподключения).
 * Значение «260» фактически эквивалентно лимиту vsync-фри рендера.</p>
 */
public class FpsLimiterModule extends Module {
    public final NumberSetting limit = new NumberSetting("Кадров в секунду", 60.0f, 10.0f, 260.0f, 5.0f, 0,
            "Максимум кадров, который рисует игра");

    /** Значение опции до включения модуля. */
    private Integer previous;

    public FpsLimiterModule() {
        super("Лимит FPS", "Ограничивает FPS — экономит батарею и снижает нагрев",
                Category.PROF);
        addSettings(limit);
    }

    @Override
    protected void onEnable() {
        apply();
    }

    @Override
    protected void onDisable() {
        restore();
    }

    @Override
    public void onTick() {
        // слайдер двигают при включённом модуле — сразу доводим лимит до цели
        apply();
    }

    private void apply() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return;
        }
        int target = limit.getInt();
        if (previous == null) {
            previous = mc.options.framerateLimit().get();
        }
        if (mc.options.framerateLimit().get() != target) {
            mc.options.framerateLimit().set(target);
        }
    }

    private void restore() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && previous != null) {
            mc.options.framerateLimit().set(previous);
        }
        previous = null;
    }
}
