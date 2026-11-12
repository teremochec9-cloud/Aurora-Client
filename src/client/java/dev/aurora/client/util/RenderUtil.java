package dev.aurora.client.util;

import dev.aurora.client.theme.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Высокоуровневые утилиты отрисовки Aurora: стеклянные панели и капсулы,
 * текст с кастомным шрифтом Inter (полная кириллица, рендер через NanoVG)
 * и мягкой тенью, перенос строк по словам.
 *
 * <p>Формы делегируются в векторный движок {@link GfxEngine}. Если движок
 * недоступен (например, библиотека не загрузилась), текст плавно
 * деградирует на ванильный шрифт — интерфейс остаётся рабочим.</p>
 */
public final class RenderUtil {
    private RenderUtil() {
    }

    /* ------------------------------------------------------------------ */
    /*  Формы (делегируются в векторный движок)                           */
    /* ------------------------------------------------------------------ */

    /** Скруглённый прямоугольник, залитый цветом. */
    public static void rounded(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        GfxEngine.rounded(g, x, y, w, h, r, color);
    }

    public static void rounded(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        GfxEngine.rounded(g, x, y, w, h, r, color);
    }

    /** Обводка скруглённого прямоугольника толщиной 1 gui-пиксель. */
    public static void roundedOutline(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        GfxEngine.outline(g, x, y, w, h, r, color, 1f);
    }

    public static void roundedOutline(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        GfxEngine.outline(g, x, y, w, h, r, color, 1f);
    }

    /** Гладкий круг. */
    public static void circle(GuiGraphics g, float cx, float cy, float d, int color) {
        GfxEngine.circle(g, cx, cy, d, color);
    }

    public static void circle(GuiGraphics g, int cx, int cy, int d, int color) {
        GfxEngine.circle(g, cx, cy, d, color);
    }

    /** Гладкое кольцо. */
    public static void ring(GuiGraphics g, float cx, float cy, float d, int color, float thickness) {
        GfxEngine.ring(g, cx, cy, d, color, thickness);
    }

    /** Мягкое радиальное свечение (пятно за элементом). */
    public static void glow(GuiGraphics g, float x, float y, float w, float h, int color) {
        float sigma = Math.max(10f, Math.min(w, h) / 2.6f);
        GfxEngine.glow(g, x, y, w, h, Math.min(w, h) / 2f, sigma, color);
    }

    public static void glow(GuiGraphics g, int x, int y, int w, int h, int color) {
        glow(g, (float) x, (float) y, (float) w, (float) h, color);
    }

    /** Виньетка на весь экран. */
    public static void vignette(GuiGraphics g, int w, int h, int color) {
        GfxEngine.vignette(g, w, h, color);
    }

    /** Мягкая тень под панелью (смещение 3px, размытие 7px). */
    public static void softShadow(GuiGraphics g, float x, float y, float w, float h, float r, int alpha) {
        GfxEngine.dropShadow(g, x, y, w, h, r, 3f, 7f, ColorUtil.alpha(0x05060A, alpha));
    }

    public static void softShadow(GuiGraphics g, int x, int y, int w, int h, int r, int alpha) {
        softShadow(g, (float) x, (float) y, (float) w, (float) h, (float) r, alpha);
    }

    /* ------------------------------------------------------------------ */
    /*  Scissor                                                           */
    /* ------------------------------------------------------------------ */

    public static void pushScissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        GfxEngine.pushScissor(g, x1, y1, x2, y2);
    }

    public static void popScissor(GuiGraphics g) {
        GfxEngine.popScissor(g);
    }

    /* ------------------------------------------------------------------ */
    /*  Стекло                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Стеклянная панель (glassmorphism): мягкая тень, полупрозрачная тёмная
     * база, светлый вертикальный градиент-блик, обводка 1px и тонкий кант
     * по верхней кромке. Сквозь панель просвечивает размытый фон.
     */
    public static void glassPanel(GuiGraphics g, float x, float y, float w, float h, float r, Theme theme) {
        softShadow(g, x, y, w, h, r, 120);
        GfxEngine.rounded(g, x, y, w, h, r, theme.panelColor());
        // светлое «стекло»: градиент от верхней кромки вниз
        GfxEngine.roundedGradient(g, x, y, w, h, r,
                ColorUtil.rgba(255, 255, 255, 24), ColorUtil.rgba(255, 255, 255, 3));
        GfxEngine.outline(g, x, y, w, h, r, theme.glassBorder(), 1f);
        // блик
        float inset = Math.min(r + 2, w / 2f);
        GfxEngine.rounded(g, x + inset, y + 1, w - inset * 2, 1, 0.5f, theme.glassHighlight());
    }

    public static void glassPanel(GuiGraphics g, int x, int y, int w, int h, int r, Theme theme) {
        glassPanel(g, (float) x, (float) y, (float) w, (float) h, (float) r, theme);
    }

    /**
     * Стеклянная капсула HUD-элемента: тень, полупрозрачная база,
     * мягкий градиент-блик и обводка — чистое стекло в цвет темы.
     */
    public static void glassCapsule(GuiGraphics g, float x, float y, float w, float h, float r, Theme theme) {
        softShadow(g, x, y, w, h, r, 70);
        GfxEngine.rounded(g, x, y, w, h, r, theme.hudGlassColor());
        GfxEngine.roundedGradient(g, x, y, w, h, r,
                ColorUtil.rgba(255, 255, 255, 16), ColorUtil.rgba(255, 255, 255, 3));
        GfxEngine.outline(g, x, y, w, h, r, ColorUtil.rgba(255, 255, 255, 36), 1f);
        float inset = Math.min(r + 1, w / 2f);
        GfxEngine.rounded(g, x + inset, y + 0.5f, w - inset * 2, 1, 0.5f, ColorUtil.rgba(255, 255, 255, 40));
    }

    public static void glassCapsule(GuiGraphics g, int x, int y, int w, int h, int r, Theme theme) {
        glassCapsule(g, (float) x, (float) y, (float) w, (float) h, (float) r, theme);
    }

    /**
     * Маленькая стеклянная кнопка/плашка: полупрозрачная база,
     * градиент-блик и обводка. color2 управляет обводкой (акцент при наведении).
     */
    public static void glassChip(GuiGraphics g, float x, float y, float w, float h, float r, int base, int border, Theme theme) {
        GfxEngine.rounded(g, x, y, w, h, r, base);
        GfxEngine.roundedGradient(g, x, y, w, h, r,
                ColorUtil.rgba(255, 255, 255, 18), ColorUtil.rgba(255, 255, 255, 2));
        GfxEngine.outline(g, x, y, w, h, r, border, 1f);
        float inset = Math.min(r + 1, w / 2f);
        GfxEngine.rounded(g, x + inset, y + 0.5f, w - inset * 2, 1, 0.5f, ColorUtil.rgba(255, 255, 255, 36));
    }

    /* ------------------------------------------------------------------ */
    /*  Текст                                                             */
    /* ------------------------------------------------------------------ */

    /** Ванильный текст (когда кастомный шрифт выключен или движок недоступен). */
    private static int vanilla(GuiGraphics g, Font font, String s, float x, float y, int color) {
        g.drawString(font, s == null ? "" : s, Math.round(x), Math.round(y), color, false);
        return font.width(s == null ? "" : s);
    }

    /** Высота строки шрифта. */
    public static int fontHeight(Font font) {
        return 9;
    }

    /** Ширина строки с учётом выбранного шрифта. */
    public static int width(Font font, String s, Theme theme) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        if (theme.customFont) {
            return Math.round(GfxEngine.textWidth(s));
        }
        return font.width(s);
    }

    /** Ширина полужирной строки. */
    public static int widthBold(Font font, String s, Theme theme) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        if (theme.customFont) {
            return Math.round(GfxEngine.textWidth(s, GfxEngine.TEXT_SIZE, true));
        }
        return font.width(s);
    }

    /** Рисует текст (с мягкой тенью). Возвращает ширину. */
    public static int drawText(GuiGraphics g, Font font, String s, float x, float y, int color, Theme theme) {
        if (color == 0 || ColorUtil.alphaI(color) == 0) {
            return width(font, s, theme);
        }
        if (theme.customFont && GfxEngine.isAvailable()) {
            int a = ColorUtil.alphaI(color);
            // мягкая тень: полупрозрачная копия со смещением
            GfxEngine.drawText(g, x + 1, y + 1 + GfxEngine.TEXT_BASELINE, s,
                    GfxEngine.TEXT_SIZE, ColorUtil.alpha(0x05060A, a * 45 / 100), false);
            GfxEngine.drawText(g, x, y + GfxEngine.TEXT_BASELINE, s, GfxEngine.TEXT_SIZE, color, false);
            return width(font, s, theme);
        }
        return vanilla(g, font, s, x, y, color);
    }

    public static int drawText(GuiGraphics g, Font font, String s, int x, int y, int color, Theme theme) {
        return drawText(g, font, s, (float) x, (float) y, color, theme);
    }

    /** Текст без тени. */
    public static void drawTextFlat(GuiGraphics g, Font font, String s, float x, float y, int color, Theme theme) {
        if (color == 0 || ColorUtil.alphaI(color) == 0) {
            return;
        }
        if (theme.customFont && GfxEngine.isAvailable()) {
            GfxEngine.drawText(g, x, y + GfxEngine.TEXT_BASELINE, s, GfxEngine.TEXT_SIZE, color, false);
            return;
        }
        vanilla(g, font, s, x, y, color);
    }

    /** Полужирный текст (Inter SemiBold). */
    public static int drawTextBold(GuiGraphics g, Font font, String s, float x, float y, int color, Theme theme) {
        if (color == 0 || ColorUtil.alphaI(color) == 0) {
            return widthBold(font, s, theme);
        }
        if (theme.customFont && GfxEngine.isAvailable()) {
            int a = ColorUtil.alphaI(color);
            GfxEngine.drawText(g, x + 1, y + 1 + GfxEngine.TEXT_BASELINE, s,
                    GfxEngine.TEXT_SIZE, ColorUtil.alpha(0x05060A, a * 45 / 100), true);
            GfxEngine.drawText(g, x, y + GfxEngine.TEXT_BASELINE, s, GfxEngine.TEXT_SIZE, color, true);
            return widthBold(font, s, theme);
        }
        return vanilla(g, font, s, x, y, color);
    }

    /** Текст по центру указанной точки. */
    public static void drawCenteredText(GuiGraphics g, Font font, String s, float cx, float y, int color, Theme theme) {
        drawText(g, font, s, cx - width(font, s, theme) / 2f, y, color, theme);
    }

    /** Градиентный текст: каждый символ интерполируется между двумя цветами. */
    public static int drawGradientText(GuiGraphics g, Font font, String s, float x, float y, int colorA, int colorB, Theme theme) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        if (theme.customFont && GfxEngine.isAvailable()) {
            float total = GfxEngine.textWidth(s);
            int a = ColorUtil.alphaI(colorA);
            // одна общая тень на всю строку: если рисовать тень для каждого
            // символа отдельно, тень соседней буквы ложится поверх соседней
            // буквы и текст выглядит «слипшимся»
            GfxEngine.drawText(g, x + 1, y + 1 + GfxEngine.TEXT_BASELINE, s,
                    GfxEngine.TEXT_SIZE, ColorUtil.alpha(0x05060A, a * 45 / 100), false);
            float cx = x;
            for (int i = 0; i < s.length(); i++) {
                String ch = String.valueOf(s.charAt(i));
                float chw = GfxEngine.textWidth(ch);
                float t = total <= 0 ? 0 : (cx - x) / total;
                GfxEngine.drawText(g, cx, y + GfxEngine.TEXT_BASELINE, ch,
                        GfxEngine.TEXT_SIZE, ColorUtil.lerp(colorA, colorB, t), false);
                cx += chw;
            }
            return Math.round(total);
        }
        int total = width(font, s, theme);
        // ванильная ветвь: сначала вся тень, затем цветные символы
        if (total > 0) {
            vanilla(g, font, s, x + 1, y + 1, ColorUtil.alpha(0x05060A, ColorUtil.alphaI(colorA) * 45 / 100));
        }
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            int chw = width(font, ch, theme);
            float t = total <= 0 ? 0 : (cx - x) / total;
            vanilla(g, font, ch, cx, y, ColorUtil.lerp(colorA, colorB, t));
            cx += chw;
        }
        return total;
    }

    /** Текст со свечением (несколько смещённых полупрозрачных копий). */
    public static void drawGlowText(GuiGraphics g, Font font, String s, float x, float y, int color, int glow, Theme theme) {
        if (theme.customFont && GfxEngine.isAvailable()) {
            if (ColorUtil.alphaI(glow) > 0) {
                float base = y + GfxEngine.TEXT_BASELINE;
                GfxEngine.drawText(g, x + 1, base, s, GfxEngine.TEXT_SIZE, glow, true);
                GfxEngine.drawText(g, x - 1, base, s, GfxEngine.TEXT_SIZE, glow, true);
                GfxEngine.drawText(g, x, base + 1, s, GfxEngine.TEXT_SIZE, glow, true);
                GfxEngine.drawText(g, x, base - 1, s, GfxEngine.TEXT_SIZE, glow, true);
            }
            drawText(g, font, s, x, y, color, theme);
            return;
        }
        if (ColorUtil.alphaI(glow) > 0) {
            g.drawString(font, s == null ? "" : s, Math.round(x) + 1, Math.round(y), glow, false);
            g.drawString(font, s == null ? "" : s, Math.round(x) - 1, Math.round(y), glow, false);
            g.drawString(font, s == null ? "" : s, Math.round(x), Math.round(y) + 1, glow, false);
            g.drawString(font, s == null ? "" : s, Math.round(x), Math.round(y) - 1, glow, false);
        }
        drawText(g, font, s, x, y, color, theme);
    }

    /* ------------------------------------------------------------------ */
    /*  Перенос строк                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Переносит строку по словам, чтобы каждая строка была не шире maxWidth.
     * Слова не разрезаются на части.
     */
    public static List<String> wrapLines(Font font, String s, int maxWidth, Theme theme) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (String paragraph : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(font, candidate, theme) <= maxWidth || line.isEmpty()) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    /**
     * Переносит строку не более чем на maxLines строк; если текст не влезает —
     * последняя строка заканчивается «...» на границе слова (без разреза слова).
     */
    public static List<String> wrapLimited(Font font, String s, int maxWidth, Theme theme, int maxLines) {
        List<String> lines = wrapLines(font, s, maxWidth, theme);
        if (lines.size() <= maxLines) {
            return lines;
        }
        List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
        // поместить «...» в последнюю строку, не разрезая слова
        String last = cut.get(maxLines - 1);
        int dots = width(font, "...", theme);
        while (!last.isEmpty() && width(font, last, theme) + dots > maxWidth) {
            int sp = last.lastIndexOf(' ');
            if (sp <= 0) {
                last = "";
            } else {
                last = last.substring(0, sp);
            }
        }
        cut.set(maxLines - 1, last.isEmpty() ? "..." : last + "...");
        return cut;
    }

    /** Обрезает строку под ширину, добавляя «...» на границе слова. */
    public static String ellipsize(Font font, String s, int maxWidth, Theme theme) {
        if (width(font, s, theme) <= maxWidth) {
            return s;
        }
        List<String> lines = wrapLimited(font, s, maxWidth, theme, 1);
        return lines.isEmpty() ? s : lines.get(0);
    }
}
