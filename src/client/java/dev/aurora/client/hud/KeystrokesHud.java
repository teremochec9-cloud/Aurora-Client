package dev.aurora.client.hud;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.ClickTracker;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Keystrokes: подсветка нажатий WASD, ЛКМ и ПКМ.
 *
 * <p>Кнопки мыши шире сетки клавиш: их размер подбирается под текст
 * «ЛКМ/ПКМ + CPS», поэтому счётчик никогда не налезает на подпись.
 * Клавиатурный блок центрируется над ними.</p>
 */
public class KeystrokesHud extends HudModule {
    private static final int KEY = 20;
    private static final int GAP = 2;
    private static final int KEYS_W = KEY * 3 + GAP * 2;

    public KeystrokesHud() {
        super("Клавиши", "Подсветка нажатий WASD и кнопок мыши", 0.006f, 0.55f);
        anchorY = 1; // центрировать по вертикали относительно точки
    }

    /** Ширина кнопки мыши: по самому длинному ожидаемому тексту (до 3 цифр CPS). */
    private int mouseW(Font font, Theme theme) {
        int need = Math.max(
                RenderUtil.width(font, "ЛКМ 000", theme),
                RenderUtil.width(font, "ПКМ 000", theme));
        return Math.max(44, need + 12);
    }

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        return mouseW(mc.font, AuroraClient.theme()) * 2 + GAP;
    }

    @Override
    public int height() {
        return KEY * 3 + GAP * 2;
    }

    @Override
    protected void renderContent(GuiGraphics g, Font font, DeltaTracker dt, Theme theme, Minecraft mc) {
        boolean w = mc.options != null && mc.options.keyUp != null && mc.options.keyUp.isDown();
        boolean a = mc.options != null && mc.options.keyLeft != null && mc.options.keyLeft.isDown();
        boolean s = mc.options != null && mc.options.keyDown != null && mc.options.keyDown.isDown();
        boolean d = mc.options != null && mc.options.keyRight != null && mc.options.keyRight.isDown();
        boolean lmb = ClickTracker.leftDown;
        boolean rmb = ClickTracker.rightDown;

        int totalW = width();
        int mw = mouseW(font, theme);
        // клавиатурный блок центрируется над кнопками мыши
        int keyX = (totalW - KEYS_W) / 2;
        int mouseRowY = (KEY + GAP) * 2;

        // W
        key(g, font, keyX + KEY + GAP, 0, "W", w, theme);
        // A S D
        key(g, font, keyX, KEY + GAP, "A", a, theme);
        key(g, font, keyX + KEY + GAP, KEY + GAP, "S", s, theme);
        key(g, font, keyX + (KEY + GAP) * 2, KEY + GAP, "D", d, theme);
        // ЛКМ / ПКМ: две независимые кнопки, текст не пересекается
        mouseKey(g, font, 0, mouseRowY, mw, "ЛКМ " + ClickTracker.leftCps(), lmb, theme);
        mouseKey(g, font, mw + GAP, mouseRowY, mw, "ПКМ " + ClickTracker.rightCps(), rmb, theme);
    }

    private void key(GuiGraphics g, Font font, int x, int y, String label, boolean pressed, Theme theme) {
        int bg = pressed ? theme.accentNow() : ColorUtil.rgba(255, 255, 255, pressed ? 60 : 26);
        RenderUtil.rounded(g, x, y, KEY, KEY, 4, bg);
        RenderUtil.drawCenteredText(g, font, label, x + KEY / 2f, y + (KEY - RenderUtil.fontHeight(font)) / 2f + 1,
                pressed ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);
    }

    private void mouseKey(GuiGraphics g, Font font, int x, int y, int w, String label, boolean pressed, Theme theme) {
        int bg = pressed ? theme.accentNow() : ColorUtil.rgba(255, 255, 255, 26);
        RenderUtil.rounded(g, x, y, w, KEY, 4, bg);
        RenderUtil.drawCenteredText(g, font, label, x + w / 2f, y + (KEY - RenderUtil.fontHeight(font)) / 2f + 1,
                pressed ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);
    }
}
