package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;

/**
 * Позволяет скрыть отдельные элементы ванильного интерфейса.
 */
public class CleanHudModule extends Module {
    public final BooleanSetting hideHotbar = new BooleanSetting("Скрыть хотбар", false);
    public final BooleanSetting hideCrosshair = new BooleanSetting("Скрыть прицел", false);
    public final BooleanSetting hidePlayerList = new BooleanSetting("Скрыть список игроков", false);
    public final BooleanSetting hideScoreboard = new BooleanSetting("Скрыть скорборд", false);
    public final BooleanSetting hideBossBar = new BooleanSetting("Скрыть полосу босса", false);
    public final BooleanSetting hideEffects = new BooleanSetting("Скрыть эффекты зелий", false);
    public final BooleanSetting hideItemName = new BooleanSetting("Скрыть имя предмета", false);

    public CleanHudModule() {
        super("Чистый интерфейс", "Скрывает выбранные элементы HUD", Category.VISUAL);
        addSettings(hideHotbar, hideCrosshair, hidePlayerList, hideScoreboard, hideBossBar, hideEffects, hideItemName);
    }
}
