package dev.aurora.client.util;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Бренд-иконка Aurora, нарисованная процедурно (эквивалент assets/aurora/icon.png):
 * сквиркл с фиолетовым вертикальным градиентом, глянцевый блик сверху и
 * белая полужирная «A» по центру.
 *
 * <p>Рисуется в любом размере (_resolution-independent_, NanoVG сглаживание),
 * без загрузки текстур и без аллокаций в кадре — иконку можно плавно
 * масштабировать и подсвечивать свечением любой силы.</p>
 */
public final class IconPainter {

    /** Фирменные цвета иконки (сэмпл с icon.png). */
    public static final int TOP = ColorUtil.rgb(180, 144, 248);   // #B490F8 — лаванда сверху
    public static final int BOTTOM = ColorUtil.rgb(56, 48, 156);  // #38309C — глубокий фиолет снизу

    private IconPainter() {
    }

    /**
     * Рисует иконку Aurora с центром в (cx, cy) и стороной {@code size}.
     *
     * @param glowAlpha сила свечения вокруг иконки (0..255)
     */
    public static void draw(GuiGraphics g, float cx, float cy, float size, float glowAlpha) {
        float x = cx - size / 2f;
        float y = cy - size / 2f;
        float r = size * 0.29f;

        // мягкое свечение вокруг (фирменный лавандовый)
        if (glowAlpha > 0.5f) {
            GfxEngine.glow(g, x, y, size, size, r, size * 0.55f,
                    ColorUtil.alpha(TOP, (int) glowAlpha));
        }

        // сквиркл с фирменным градиентом
        GfxEngine.roundedGradient(g, x, y, size, size, r, TOP, BOTTOM);

        // глянцевый блик на верхней трети (как на icon.png)
        GfxEngine.roundedGradient(g, x + size * 0.045f, y + size * 0.05f,
                size * 0.91f, size * 0.40f, r * 0.85f,
                ColorUtil.rgba(255, 255, 255, 44), ColorUtil.rgba(255, 255, 255, 0));

        // тонкая внутренняя обводка-контур
        GfxEngine.outline(g, x + 0.5f, y + 0.5f, size - 1f, size - 1f, r,
                ColorUtil.rgba(255, 255, 255, 30), 1f);

        // белая «A» (Inter SemiBold) визуально по центру
        float fs = size * 0.64f;
        float tw = GfxEngine.textWidth("A", fs, true);
        float baseline = cy + fs * 0.355f;
        GfxEngine.drawText(g, cx - tw / 2f + size * 0.02f, baseline + size * 0.02f, "A", fs,
                ColorUtil.alpha(0x1A1244, 60), true);
        GfxEngine.drawText(g, cx - tw / 2f, baseline, "A", fs, ColorUtil.rgb(255, 255, 255), true);
    }
}
