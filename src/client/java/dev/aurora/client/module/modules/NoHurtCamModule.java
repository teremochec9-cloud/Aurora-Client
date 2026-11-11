package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;

/**
 * Отключает тряску камеры при получении урона.
 */
public class NoHurtCamModule extends Module {
    public NoHurtCamModule() {
        super("Без тряски камеры", "Убирает покачивание камеры при уроне", Category.VISUAL);
    }
}
