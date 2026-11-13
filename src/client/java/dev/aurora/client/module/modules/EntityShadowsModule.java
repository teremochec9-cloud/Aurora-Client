package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;

/**
 * Тени сущностей: отключает мягкие тени под всеми сущностями.
 * Тень — полупрозрачный quad на земле с сэмплированием блоков под
 * каждым существом каждый кадр; на populated аренах это заметная
 * экономия FPS без влияния на читаемость игры.
 */
public class EntityShadowsModule extends Module {

    public EntityShadowsModule() {
        super("Тени сущностей", "Отключает тени под сущностями — больше FPS",
                Category.PROF);
    }
}
