package dev.aurora.client.hud;

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
 */
public class KeystrokesHud extends HudModule {
    private static final int KEY = 20;
    private static final int GAP = 2;

    public KeystrokesHud() {
        super("Клавиши", "Подсветка нажатий WASD и кнопок мыши", 0.006f, 0.5f);
        anchorY = 1; // центрировать по вертикали относительно точки
    }

    @Override
    public int width() {
        return KEY * 3 + GAP * 2;
    }

    @Override
    public int height() {
        return KEY * 3 + GAP * 2;
    }

    private Theme theme() {
        return dev.aurora.client.AuroraClient.theme();
    }

    @Override
    protected void renderContent(GuiGraphics g, Font font, DeltaTracker dt, Theme theme, Minecraft mc) {
        boolean w = mc.options != null && mc.options.keyUp != null && mc.options.keyUp.isDown();
        boolean a = mc.options != null && mc.options.keyLeft != null && mc.options.keyLeft.isDown();
        boolean s = mc.options != null && mc.options.keyDown != null && mc.options.keyDown.isDown();
        boolean d = mc.options != null && mc.options.keyRight != null && mc.options.keyRight.isDown();
        boolean lmb = ClickTracker.leftDown;
        boolean rmb = ClickTracker.rightDown;

        int x0 = 0;
        int y0 = 0;
        // W
        key(g, font, x0 + KEY + GAP, y0, "W", w, theme);
        // A S D
        key(g, font, x0, y0 + KEY + GAP, "A", a, theme);
        key(g, font, x0 + KEY + GAP, y0 + KEY + GAP, "S", s, theme);
        key(g, font, x0 + (KEY + GAP) * 2, y0 + KEY + GAP, "D", d, theme);
        // ЛКМ / ПКМ с CPS
        mouseKey(g, font, x0, y0 + (KEY + GAP) * 2, "ЛКМ " + ClickTracker.leftCps(), lmb, theme);
        mouseKey(g, font, x0 + KEY + GAP + (KEY + GAP) / 2, y0 + (KEY + GAP) * 2, "ПКМ " + ClickTracker.rightCps(), rmb, theme);
    }

    private void key(GuiGraphics g, Font font, int x, int y, String label, boolean pressed, Theme theme) {
        int bg = pressed ? theme.accentNow() : ColorUtil.rgba(255, 255, 255, pressed ? 60 : 26);
        RenderUtil.rounded(g, x, y, KEY, KEY, 4, bg);
        RenderUtil.drawText(g, font, label, x + KEY / 2 - RenderUtil.width(font, label, theme) / 2,
                y + KEY / 2 - 4, pressed ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);
    }

    private void mouseKey(GuiGraphics g, Font font, int x, int y, String label, boolean pressed, Theme theme) {
        int w = KEY * 2 + GAP / 2;
        int bg = pressed ? theme.accentNow() : ColorUtil.rgba(255, 255, 255, 26);
        RenderUtil.rounded(g, x, y, w, KEY, 4, bg);
        RenderUtil.drawText(g, font, label, x + w / 2 - RenderUtil.width(font, label, theme) / 2,
                y + KEY / 2 - 4, pressed ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);
    }
}
