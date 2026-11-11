package dev.aurora.client.util;

import dev.aurora.client.theme.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Утилиты отрисовки: скруглённые панели, градиенты, свечение, текст.
 * Всё рисуется средствами GuiGraphics, без шейдеров — работает везде.
 */
public final class RenderUtil {
    /** Идентификатор кастомного шрифта (assets/aurora/font/aurora.json). */
    public static final Identifier FONT_ID = Identifier.fromNamespaceAndPath("aurora", "aurora");

    private RenderUtil() {
    }

    /* ------------------------------------------------------------------ */
    /*  Скруглённые фигуры                                                */
    /* ------------------------------------------------------------------ */

    /** Скруглённый прямоугольник залитый цветом. */
    public static void rounded(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        // центральная часть на всю ширину
        g.fill(x, y + r, x + w, y + h - r, color);
        // верхняя и нижняя полосы
        g.fill(x + r, y, x + w - r, y + r, color);
        g.fill(x + r, y + h - r, x + w - r, y + h, color);
        // скруглённые углы — построчно
        for (int dy = 0; dy < r; dy++) {
            float half = (float) Math.sqrt(r * r - (r - 0.5f - dy) * (r - 0.5f - dy));
            int inset = (int) Math.floor(r - half);
            if (inset <= 0) {
                continue;
            }
            g.fill(x + inset, y + dy, x + r, y + dy + 1, color);
            g.fill(x + w - r, y + dy, x + w - inset, y + dy + 1, color);
            g.fill(x + inset, y + h - 1 - dy, x + r, y + h - dy, color);
            g.fill(x + w - r, y + h - 1 - dy, x + w - inset, y + h - dy, color);
        }
    }

    /** Скруглённый прямоугольник с вертикальным градиентом. */
    public static void roundedGradient(GuiGraphics g, int x, int y, int w, int h, int r, int colorTop, int colorBottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        for (int row = 0; row < h; row++) {
            float t = h == 1 ? 0 : (float) row / (h - 1);
            int color = ColorUtil.lerp(colorTop, colorBottom, t);
            int ry = Math.min(r, Math.min(row, h - 1 - row));
            if (ry <= 0) {
                g.fill(x, y + row, x + w, y + row + 1, color);
            } else {
                float half = (float) Math.sqrt(ry * ry - (ry - 0.5f) * (ry - 0.5f));
                int inset = (int) Math.floor(ry - half);
                g.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
            }
        }
    }

    /** Обводка скруглённого прямоугольника толщиной 1px. */
    public static void roundedOutline(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        g.fill(x + r, y, x + w - r, y + 1, color);
        g.fill(x + r, y + h - 1, x + w - r, y + h, color);
        g.fill(x, y + r, x + 1, y + h - r, color);
        g.fill(x + w - 1, y + r, x + w, y + h - r, color);
        for (int dy = 0; dy < r; dy++) {
            float half = (float) Math.sqrt(r * r - (r - 0.5f - dy) * (r - 0.5f - dy));
            int inset = (int) Math.floor(r - half);
            if (inset < 0) {
                inset = 0;
            }
            g.fill(x + inset, y + dy, x + inset + 1, y + dy + 1, color);
            g.fill(x + w - 1 - inset, y + dy, x + w - inset, y + dy + 1, color);
            g.fill(x + inset, y + h - 1 - dy, x + inset + 1, y + h - dy, color);
            g.fill(x + w - 1 - inset, y + h - 1 - dy, x + w - inset, y + h - dy, color);
        }
    }

    /** Стеклянная панель: тень + полупрозрачная заливка + обводка + блик сверху. */
    public static void glassPanel(GuiGraphics g, int x, int y, int w, int h, int r, Theme theme) {
        // мягкая тень только вниз
        rounded(g, x, y + 2, w, h, r, ColorUtil.rgba(0, 0, 0, (int) (theme.backgroundAlpha * 55)));
        // заливка
        rounded(g, x, y, w, h, r, theme.panelColor());
        // обводка
        roundedOutline(g, x, y, w, h, r, theme.borderColor());
        // блик по верхней кромке
        g.fill(x + r, y + 1, x + w - r, y + 2, ColorUtil.rgba(255, 255, 255, 22));
    }

    /* ------------------------------------------------------------------ */
    /*  Текст                                                             */
    /* ------------------------------------------------------------------ */

    private static MutableComponent styled(String s) {
        return Component.literal(s == null ? "" : s).setStyle(Style.EMPTY.withFont(new FontDescription.Resource(FONT_ID)));
    }

    /** Оборачивает строку в Component с кастомным шрифтом, если он включён. */
    public static Component text(String s, Theme theme) {
        return theme.customFont ? styled(s) : Component.literal(s == null ? "" : s);
    }

    /** Ширина строки с учётом выбранного шрифта. */
    public static int width(Font font, String s, Theme theme) {
        return font.width(theme.customFont ? styled(s).getString() : s);
    }

    /** Рисует текст (с тенью). Возвращает ширину. */
    public static int drawText(GuiGraphics g, Font font, String s, int x, int y, int color, Theme theme) {
        if (theme.customFont) {
            g.drawString(font, styled(s), x, y, color, true);
        } else {
            g.drawString(font, s, x, y, color, true);
        }
        return width(font, s, theme);
    }

    /** Рисует текст по центру указанной области. */
    public static void drawCenteredText(GuiGraphics g, Font font, String s, int cx, int y, int color, Theme theme) {
        drawText(g, font, s, cx - width(font, s, theme) / 2, y, color, theme);
    }

    /** Градиентный текст: каждый символ interpolируется между двумя цветами. */
    public static int drawGradientText(GuiGraphics g, Font font, String s, int x, int y, int colorA, int colorB, Theme theme) {
        int total = width(font, s, theme);
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            int chw = width(font, ch, theme);
            float t = total <= 0 ? 0 : (float) (cx - x) / total;
            drawText(g, font, ch, cx, y, ColorUtil.lerp(colorA, colorB, t), theme);
            cx += chw;
        }
        return total;
    }

    /** Текст со свечением (несколько смещённых полупрозрачных копий). */
    public static void drawGlowText(GuiGraphics g, Font font, String s, int x, int y, int color, int glow, Theme theme) {
        g.drawString(font, theme.customFont ? styled(s) : Component.literal(s), x + 1, y, glow, false);
        g.drawString(font, theme.customFont ? styled(s) : Component.literal(s), x - 1, y, glow, false);
        g.drawString(font, theme.customFont ? styled(s) : Component.literal(s), x, y + 1, glow, false);
        g.drawString(font, theme.customFont ? styled(s) : Component.literal(s), x, y - 1, glow, false);
        drawText(g, font, s, x, y, color, theme);
    }

    /** Высота строки шрифта. */
    public static int fontHeight(Font font) {
        return font.lineHeight;
    }
}
