package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.NumberSetting;

/**
 * Оптимизация самой игры. Флагман — окклюжн-куллинг сущностей: сущность,
 * полностью закрытая непрозрачными блоками, не отдаётся в рендер вообще
 * (ваниль честно строит для неё модель и отправляет в пайплайн).
 *
 * <p>Важно: это единственная крупная оптимизация, которая не меняет
 * картинку вовсе — «скрытый» означает «невидимый»: если лучи от камеры
 * до всех точек AABB упираются в opaque-кубы (стекло и листва прозрачны
 * для проверки, как и для взгляда), сущность нельзя увидеть, поэтому её
 * пропуск ничего не меняет на экране. Проверка выполняется редко
 * (раз в ~0.5 с на сущность) и кэшируется.</p>
 */
public class OptimizeModule extends Module {
    public final BooleanSetting entityCulling = new BooleanSetting("Куллинг сущностей", true,
            "Не рисовать сущности, полностью скрытые непрозрачными блоками");
    public final NumberSetting cullRange = new NumberSetting("Дальность", 32.0f, 8.0f, 64.0f, 1.0f, 0,
            "Максимальная дистанция проверки сущностей (блоки)");

    public OptimizeModule() {
        super("Оптимизация", "Окклюжн-куллинг сущностей: больше FPS без изменения картинки",
                Category.PROF);
        addSettings(entityCulling, cullRange);
    }
}
