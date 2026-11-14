package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.ModuleManager;
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
 *
 * <p>Второй инструмент — «Дальность сущностей»: за выбранным радиусом
 * сущности не отдаются в рендер (аналог OptiFine Entity Distance).
 * Игроки и светящиеся сущности не скрываются никогда — их видно всегда.</p>
 */
public class OptimizeModule extends Module {
    public final BooleanSetting entityCulling = new BooleanSetting("Куллинг сущностей", true,
            "Не рисовать сущности, полностью скрытые непрозрачными блоками");
    public final NumberSetting cullRange = new NumberSetting("Дальность", 32.0f, 8.0f, 64.0f, 1.0f, 0,
            "Максимальная дистанция проверки сущностей (блоки)");
    public final BooleanSetting entityDistance = new BooleanSetting("Дальность сущностей", true,
            "Сущности дальше радиуса не рисуются — экономит FPS в толпе и на фермах");
    public final NumberSetting entityDist = new NumberSetting("Радиус", 64.0f, 16.0f, 128.0f, 1.0f, 0,
            "Сущности дальше этого расстояния от камеры не рисуются (блоки)");

    private static OptimizeModule module;

    public OptimizeModule() {
        super("Оптимизация", "Окклюжн-куллинг и дальность сущностей: больше FPS без изменения картинки",
                Category.PROF);
        addSettings(entityCulling, cullRange, entityDistance, entityDist);
    }

    /**
     * Скрывать ли сущность на квадрате дистанции distSqr (миксин рендера).
     * Игроки и светящиеся сущности не скрываются никогда: их пропуск
     * меняет геймплей, а не только картинку.
     */
    public static boolean shouldCullFar(net.minecraft.world.entity.Entity e, double distSqr) {
        if (module == null) {
            module = ModuleManager.get(OptimizeModule.class);
        }
        if (module == null || !module.isEnabled() || !module.entityDistance.get()) {
            return false;
        }
        if (e instanceof net.minecraft.world.entity.player.Player || e.isCurrentlyGlowing()) {
            return false;
        }
        float r = module.entityDist.get();
        return distSqr > r * r;
    }
}
