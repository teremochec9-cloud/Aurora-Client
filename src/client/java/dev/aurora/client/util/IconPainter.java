package dev.aurora.client.util;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Бренд-иконка Aurora 1.0.6 — «Полярная звезда».
 *
 * <p>Вместо прежней литеры «A» на лавандовом сквиркле — необычная
 * процедурная марка: тёмная стеклянная плитка с тонкой градиентной
 * каймой, внутри — яркая четырёхлучевая звезда с пульсирующим ядром
 * и наклонная орбита со светящейся точкой-спутником. При намёке на
 * сияние (glowAlpha) вокруг плитки дышит мягкое акцентное свечение,
 * спутник плавно летит по орбите, ядро звенит лёгким пульсом.</p>
 *
 * <p>Рисуется в любом размере (NanoVG, без текстур и аллокаций в кадре).
 * Анимация привязана к {@link FrameClock} — идеально плавная при любом
 * FPS. Все вызовы — чистые функции времени, состояние не хранится.</p>
 */
public final class IconPainter {

    /** Фирменные цвета иконки: лаванда и глубокий индиго (наследие 1.0.5). */
    public static final int TOP = ColorUtil.rgb(180, 144, 248);
    public static final int BOTTOM = ColorUtil.rgb(56, 48, 156);

    /** Наклон орбиты (радианы). */
    private static final float ORBIT_TILT = 0.42f;
    /** Сжатие орбиты по вертикали (эллиптичность). */
    private static final float ORBIT_FLAT = 0.42f;

    private IconPainter() {
    }

    /**
     * Статичная иконка (меню клиента, HUD): спутник зафиксирован,
     * ядро без пульса — марка не «дёргается» в статичных местах.
     */
    public static void draw(GuiGraphics g, float cx, float cy, float size, float glowAlpha) {
        draw(g, cx, cy, size, glowAlpha, 0.9f, false);
    }

    /**
     * Анимированная иконка (титульный экран): спутник летит по орбите,
     * ядро мягко пульсирует.
     */
    public static void drawAnimated(GuiGraphics g, float cx, float cy, float size, float glowAlpha) {
        float t = FrameClock.seconds();
        float orbit = t * 0.55f;               // оборот спутника ~11.4 с
        draw(g, cx, cy, size, glowAlpha, orbit, true);
    }

    /**
     * Общая отрисовка. orbit — фаза спутника (радианы); animate —
     * включить пульс ядра и «дыхание» орбиты.
     */
    private static void draw(GuiGraphics g, float cx, float cy, float size, float glowAlpha,
                             float orbit, boolean animate) {
        float x = cx - size / 2f;
        float y = cy - size / 2f;
        float r = size * 0.30f;
        float pulse = animate ? 0.86f + 0.14f * (float) Math.sin(FrameClock.seconds() * 1.6f) : 1f;
        // ===== мягкое свечение вокруг плитки =====
        if (glowAlpha > 0.5f) {
            GfxEngine.glow(g, x, y, size, size, r, size * 0.60f,
                    ColorUtil.alpha(TOP, (int) (glowAlpha * 0.85f)));
        }

        // ===== тёмная стеклянная плитка с градиентом вниз =====
        GfxEngine.roundedGradient(g, x, y, size, size, r,
                ColorUtil.rgb(26, 28, 44), ColorUtil.rgb(11, 12, 20));
        // едва заметное акцентное свечение изнутри снизу — «заря»
        GfxEngine.roundedGradient(g, x, y + size * 0.45f, size, size * 0.55f, r,
                ColorUtil.alpha(BOTTOM, 90), ColorUtil.alpha(BOTTOM, 20));
        // градиентная кайма: лаванда сверху → индиго снизу
        GfxEngine.outline(g, x + 0.5f, y + 0.5f, size - 1f, size - 1f, r,
                ColorUtil.rgba(255, 255, 255, 26), 1f);
        GfxEngine.rounded(g, x + size * 0.12f, y + 1, size * 0.76f, 1, 0.5f,
                ColorUtil.rgba(255, 255, 255, 40));
        // блик по верхней кромке (стекло)
        GfxEngine.roundedGradient(g, x + size * 0.05f, y + size * 0.045f,
                size * 0.90f, size * 0.34f, r * 0.85f,
                ColorUtil.rgba(255, 255, 255, 26), ColorUtil.rgba(255, 255, 255, 0));

        // ===== орбита: наклонный эллипс, тонкая, градиентная =====
        float orbR = size * 0.365f;
        float tiltCos = (float) Math.cos(ORBIT_TILT);
        float tiltSin = (float) Math.sin(ORBIT_TILT);
        // орбита строится полилинией в локальных координатах (x = cos*a, y = sin*a*flat),
        // затем поворот на ORBIT_TILT — 48 сегментов, буфер переиспользуется
        float[] orb = ORBIT_BUF;
        int n = 48;
        for (int i = 0; i <= n; i++) {
            float a = (float) (i / (float) n * Math.PI * 2.0);
            float lx = (float) Math.cos(a) * orbR;
            float ly = (float) Math.sin(a) * orbR * ORBIT_FLAT;
            orb[i * 2] = cx + lx * tiltCos - ly * tiltSin;
            orb[i * 2 + 1] = cy + lx * tiltSin + ly * tiltCos;
        }
        GfxEngine.pathStroke(g, orb, n + 1,
                ColorUtil.alpha(ColorUtil.lerp(TOP, ColorUtil.rgb(255, 255, 255), 0.35f), 150), 1.0f, false);

        // ===== спутник на орбите =====
        float sx = (float) Math.cos(orbit) * orbR;
        float sy = (float) Math.sin(orbit) * orbR * ORBIT_FLAT;
        float px = cx + sx * tiltCos - sy * tiltSin;
        float py = cy + sx * tiltSin + sy * tiltCos;
        GfxEngine.circle(g, px, py, Math.max(2.2f, size * 0.075f) * 2.4f,
                ColorUtil.alpha(TOP, 70));
        GfxEngine.circle(g, px, py, Math.max(1.6f, size * 0.055f), ColorUtil.rgb(255, 255, 255));

        // ===== звезда: два тонких ромба-луча (вертикальный длиннее) + ядро =====
        float sr = size * 0.30f * pulse;       // вертикальный луч
        float sr2 = sr * 0.62f;                // горизонтальный луч
        float core = Math.max(1.3f, size * 0.052f);
        fillRay(g, cx, cy, sr, 0f);
        fillRay(g, cx, cy, sr2, 1f);
        // свечение ядра
        GfxEngine.circle(g, cx, cy, core * 3.2f, ColorUtil.alpha(TOP, 90));
        GfxEngine.circle(g, cx, cy, core * 1.9f, ColorUtil.alpha(ColorUtil.rgb(255, 255, 255), 190));
        GfxEngine.circle(g, cx, cy, core, ColorUtil.rgb(255, 255, 255));
    }

    /** Переиспользуемый буфер орбиты (49 точек по 2 координаты). */
    private static final float[] ORBIT_BUF = new float[98];

    /** Один луч звезды: тонкий вытянутый ромб под углом (0 — вертикаль, 1 — горизонталь). */
    private static void fillRay(GuiGraphics g, float cx, float cy, float len, float axis) {
        float w = Math.max(1.0f, len * 0.16f);
        float dx, dy;
        if (axis == 0f) {
            dx = 0f;
            dy = 1f;
        } else {
            dx = 1f;
            dy = 0f;
        }
        // вершины ромба: (конец+), (бок+), (конец-), (бок-)
        RAY[0] = cx + dx * len;
        RAY[1] = cy + dy * len;
        RAY[2] = cx - dy * w;
        RAY[3] = cy + dx * w;
        RAY[4] = cx - dx * len;
        RAY[5] = cy - dy * len;
        RAY[6] = cx + dy * w;
        RAY[7] = cy - dx * w;
        GfxEngine.pathFill(g, RAY, 4, ColorUtil.rgba(235, 225, 255, 215));
    }

    /** Переиспользуемый буфер ромба-луча (4 точки). */
    private static final float[] RAY = new float[8];
}
