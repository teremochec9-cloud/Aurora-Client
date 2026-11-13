package dev.aurora.client.util;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.gui.AuroraClickGui;
import dev.aurora.client.hud.HudEditorScreen;
import dev.aurora.client.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Правило владения блюром за меню Aurora.
 *
 * <p>Ванильный gaussian-блюр включается опцией menuBackgroundBlurriness,
 * которая персистится в options.txt — портить её статикой нельзя
 * (при краше юзерово значение навсегда уходит в ноль). Поэтому:</p>
 *
 * <ul>
 *   <li><b>тумблер «Блюр за меню» включён</b> — Aurora плавно доводит
 *       ванильную опцию до выбранной силы на время меню и возвращает
 *       исходное значение при закрытии (applyBlurRamp / originalBlur);</li>
 *   <li><b>тумблер выключен</b> — ванильный блюр гасится миксином
 *       {@code ScreenMixin} (cancel renderBlurredBackground) только пока
 *       открыт экран Aurora: пользовательская опция остаётся нетронутой,
 *       а тумблер не «включён и не работает» (баг 1.0.5).</li>
 * </ul>
 */
public final class BlurGuard {

    private BlurGuard() {
    }

    /** Подавлять ли ванильный блюр в текущем кадре. */
    public static boolean shouldSuppressVanilla() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc == null ? null : mc.screen;
        boolean auroraMenu = screen instanceof AuroraClickGui || screen instanceof HudEditorScreen;
        if (!auroraMenu) {
            return false;
        }
        Theme t = AuroraClient.theme();
        return !t.blur;
    }
}
