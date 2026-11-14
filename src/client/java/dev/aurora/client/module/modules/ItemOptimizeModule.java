package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.NumberSetting;

/**
 * «Оптимизация дропов»: выпавшие предметы дальше выбранной дистанции
 * не отдаются в рендер. Внутри спавн-комнат, на фермах и после зачистки
 * мобов на земле лежат сотни стаков — каждый рендерится моделью целиком,
 * хотя игрок их всё равно не различает. Отсечение по дистанции даёт
 * заметный прирост FPS именно там, где тормозит (базы, фермы).
 *
 * <p>Врезка — в тот же RETURN, что и окклюжн-куллинг: модуль может лишь
 * снять дроп с рендера, показать лишний — никогда. Светящиеся и
 * поименованные предметы не скрываются (игрок наверняка ищет именно их).</p>
 */
public class ItemOptimizeModule extends Module {
    public final NumberSetting distance = new NumberSetting("Дистанция", 24.0f, 8.0f, 64.0f, 1.0f, 0,
            "Дроп дальше этого радиуса не рисуется (блоки)");

    private static ItemOptimizeModule module;

    public ItemOptimizeModule() {
        super("Оптимизация дропов", "Не рисует выпавшие предметы вдали — больше FPS на базах",
                Category.PROF);
        addSettings(distance);
    }

    /**
     * Скрывать ли предмет на квадрате дистанции distSqr. Вызывается на
     * рендер-потоке из миксина; быстрый путь — две проверки.
     */
    public static boolean shouldCull(double distSqr, boolean glowing) {
        if (module == null) {
            module = dev.aurora.client.module.ModuleManager.get(ItemOptimizeModule.class);
        }
        if (module == null || !module.isEnabled() || glowing) {
            return false;
        }
        float r = module.distance.get();
        return distSqr > r * r;
    }
}
