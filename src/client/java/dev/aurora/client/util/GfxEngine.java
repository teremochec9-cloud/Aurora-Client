package dev.aurora.client.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Движок отрисовки Aurora на NanoVG.
 *
 * <p>NanoVG — векторный OpenGL-рендерер (модуль org.lwjgl.nanovg из
 * поставки LWJGL): идеально гладкие скруглённые прямоугольники с
 * аналитическим сглаживанием, мягкие гауссовы тени и свечения,
 * обводки любой толщины и кристально чёткий TTF-текст (Inter,
 * полная кириллица) при любом gui scale — никаких текстурных атласов,
 * «пиксельных лесенок» и квадратных вырезов.</p>
 *
 * <p>Как это работает: пока код интерфейса (экраны, HUD) вызывает методы
 * движка, команды не рисуются, а записываются в буфер вместе с текущей
 * матрицей позы и scissor-областью. Миксин {@code GuiRendererMixin}
 * вызывает {@link #flushFrame()} сразу после того, как ванилла выполнит
 * все слои GUI за кадр; в этот момент открывается собственный render pass
 * на главный фреймбуфер и весь буфер проигрывается через NanoVG — поэтому
 * интерфейс Aurora всегда рисуется поверх всего и не конфликтует с
 * сортировкой ванильных слоёв.</p>
 */
public final class GfxEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("Aurora/GfxEngine");

    /** Диагностика движка: AURORA_NVG_DEBUG=1 рисует тестовые примитивы и пишет GL-ошибки. */
    private static final boolean DEBUG = "1".equals(System.getenv("AURORA_NVG_DEBUG"));
    /** Диагностика полигонов: AURORA_PTS_DEBUG=1 логирует точки pathFill. */
    private static final boolean PTS_DEBUG = "1".equals(System.getenv("AURORA_PTS_DEBUG"));

    /** Базовый размер шрифта (в gui-пикселях) — совпадает со строкой ванильного шрифта. */
    public static final float TEXT_SIZE = 9.0f;
    /** Смещение базовой линии текста от верха строки. */
    public static final float TEXT_BASELINE = 7.0f;

    /* ------------------------------------------------------------------ */
    /*  Состояние NanoVG                                                  */
    /* ------------------------------------------------------------------ */

    private static long ctx;
    private static int fontMedium = -1;
    private static int fontSemiBold = -1;
    private static boolean failed;
    /** Диагностика: один раз залогировать параметры первого кадра. */
    private static boolean loggedOnce;
    /** Диагностика: масштаб позы первого кадра. */
    /** Байты шрифтов держим всё время жизни: NanoVG читает их напрямую. */
    private static ByteBuffer fontDataMedium;
    private static ByteBuffer fontDataSemiBold;

    /** Переиспользуемые структуры (без аллокаций в кадре). */
    private static NVGColor colorA;
    private static NVGColor colorB;
    private static NVGPaint paint;
    /** Переиспользуемый габарит команды для ручного scissor. */
    private static final float[] BB = new float[4];

    private GfxEngine() {
    }

    /** Инициализация контекста NanoVG и загрузка шрифтов. Рендер-поток! */
    private static boolean ensureReady() {
        if (ctx != 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) {
            return false; // вне рендер-потока контекст не создаём
        }
        try {
            long created = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS | NanoVGGL3.NVG_STENCIL_STROKES);
            if (created == 0) {
                LOGGER.warn("Aurora: NanoVG со стенсил-заливкой недоступен — фолбэк на ANTIALIAS (вогнутые полигоны будут ломаться)");
                created = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS);
            }
            if (created == 0) {
                throw new IllegalStateException("Не удалось создать NanoVG-контекст");
            }
            ctx = created;
            colorA = NVGColor.malloc();
            colorB = NVGColor.malloc();
            paint = NVGPaint.malloc();

            fontDataMedium = readFont("/assets/aurora/font/inter_medium.ttf");
            fontDataSemiBold = readFont("/assets/aurora/font/inter_semibold.ttf");
            if (fontDataMedium == null || fontDataSemiBold == null) {
                throw new IllegalStateException("Шрифты Inter не найдены в ресурсах");
            }
            fontMedium = NanoVG.nvgCreateFontMem(ctx, "inter", fontDataMedium, false);
            fontSemiBold = NanoVG.nvgCreateFontMem(ctx, "inter-sb", fontDataSemiBold, false);
            if (fontMedium < 0 || fontSemiBold < 0) {
                throw new IllegalStateException("Не удалось загрузить шрифты в NanoVG");
            }
            LOGGER.info("Aurora: NanoVG готов (шрифты Inter Medium/SemiBold, кириллица)");
            return true;
        } catch (Throwable t) {
            LOGGER.error("Aurora: NanoVG недоступен, интерфейс будет отрисован упрощённо", t);
            if (ctx != 0) {
                try {
                    NanoVGGL3.nvgDelete(ctx);
                } catch (Throwable ignored) {
                }
            }
            failed = true;
            ctx = 0;
            return false;
        }
    }

    private static ByteBuffer readFont(String path) throws Exception {
        try (InputStream in = GfxEngine.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            byte[] raw = in.readAllBytes();
            ByteBuffer buf = ByteBuffer.allocateDirect(raw.length);
            buf.put(raw);
            buf.flip();
            return buf;
        }
    }

    /** Публичный прогрев: создать контекст заранее, чтобы не было рывка на первом кадре. */
    public static void warmup() {
        ensureReady();
    }

    /** Доступен ли движок (для мягкой деградации текста на ванильный шрифт). */
    public static boolean isAvailable() {
        return ctx != 0 || (!failed && ensureReady());
    }

    /* ------------------------------------------------------------------ */
    /*  Буфер команд                                                      */
    /* ------------------------------------------------------------------ */

    private static final int FILL = 0;
    private static final int GRADIENT = 1;
    private static final int OUTLINE = 2;
    private static final int SOFT = 3;   // тень/свечение (box gradient)
    private static final int RAMP = 4;   // виньетка (инвертированный box gradient)
    private static final int CIRCLE = 5;
    private static final int TEXT = 6;
    private static final int RADIAL = 7;  // радиальный градиент (дым, свечение)
    private static final int GRADIENT_H = 8; // горизонтальный градиент (виньетки по бокам)
    private static final int ELLIPSE = 9;      // эллипс-контур (кольца Target ESP)
    private static final int PATH_STROKE = 10; // ломаная/полигон — обводка
    private static final int PATH_FILL = 11;   // ломаная/полигон — заливка

    /** Буфер команд: наполняется во время GUI-слоёв, проигрывается в flushFrame. */
    private static List<Cmd> COMMANDS = new ArrayList<>(512);
    /** Второй буфер: пока один наполняется, предыдущий проигрывается. */
    private static List<Cmd> REPLAY = new ArrayList<>(512);

    /** Пул команд: за кадр рисуются сотни примитивов — переиспользуем объекты и матрицы. */
    private static final ArrayDeque<Cmd> CMD_POOL = new ArrayDeque<>();

    private static final class Cmd {
        int type;
        Matrix3x2f pose;
        float sx, sy, sw, sh;   // scissor (или sw <= 0 — без отсечения)
        float x, y, w, h, r;
        float p1, p2;           // sigma / offsetY / thickness / fade
        int argb, argb2;
        String text;
        boolean bold;
        float size;
        // путь (PATH_STROKE / PATH_FILL): вершины и флаг замкнутости
        float[] pts;
        int ptCount;
        boolean closed;

        void scissor(float[] s) {
            if (s == null) {
                sw = -1; // без отсечения
            } else {
                sx = s[0];
                sy = s[1];
                sw = s[2];
                sh = s[3];
            }
        }
    }

    private static Cmd cmd(int type, GuiGraphics g) {
        Cmd c = CMD_POOL.poll();
        if (c == null) {
            c = new Cmd();
        }
        c.type = type;
        if (c.pose == null) {
            c.pose = new Matrix3x2f();
        }
        c.pose.set(g.pose());
        c.text = null;
        c.ptCount = 0;
        c.closed = false;
        float[] s = currentScissor();
        if (s != null) {
            // scissor задаётся в gui-координатах — применяем текущую позу
            Matrix3x2fc pose = c.pose;
            float x0 = s[0], y0 = s[1], x1 = s[0] + s[2], y1 = s[1] + s[3];
            float ax0 = pose.m00() * x0 + pose.m10() * y0 + pose.m20();
            float ay0 = pose.m01() * x0 + pose.m11() * y0 + pose.m21();
            float ax1 = pose.m00() * x1 + pose.m10() * y1 + pose.m20();
            float ay1 = pose.m01() * x1 + pose.m11() * y1 + pose.m21();
            c.scissor(new float[]{Math.min(ax0, ax1), Math.min(ay0, ay1),
                    Math.abs(ax1 - ax0), Math.abs(ay1 - ay0)});
        } else {
            c.scissor(null);
        }
        COMMANDS.add(c);
        return c;
    }

    /* ------------------------------------------------------------------ */
    /*  Scissor                                                           */
    /* ------------------------------------------------------------------ */

    private static final ArrayDeque<float[]> SCISSOR_STACK = new ArrayDeque<>();

    /**
     * Включает отсечение (и в ванильном рендере, и для NanoVG-команд).
     *
     * <p>Координаты клэмпятся в границы экрана: окно меню может быть больше
     * экрана (fitScale &lt; 1), и тогда исходные rect'ы уходят в отрицательные
     * координаты — ванильный GL-scissor с отрицательным origin'ом ломает
     * рендер последующих слоёв (чёрные зоны на экране).</p>
     */
    public static void pushScissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        if (x2 <= x1 || y2 <= y1) {
            SCISSOR_STACK.push(new float[]{x1, y1, 0, 0});
            return;
        }
        // ВАЖНО: ванильный g.enableScissor не вызываем вовсе.
        // 1) Весь контент Aurora рисуется через NanoVG уже ПОСЛЕ ванильных слоёв —
        //    ванильное отсечение нам ничего не даёт.
        // 2) Окно меню бывает больше экрана (fitScale < 1), rect'ы уходят
        //    в отрицательные координаты — ванильный GL-scissor с отрицательным
        //    origin'ом ломает рендер последующих слоёв (чёрные зоны на экране).
        // Поэтому отсечение живёт только в собственном стеке движка.
        float[] rect = {x1, y1, x2 - x1, y2 - y1};
        float[] prev = SCISSOR_STACK.peek();
        if (prev != null) {
            float nx0 = Math.max(rect[0], prev[0]);
            float ny0 = Math.max(rect[1], prev[1]);
            float nx1 = Math.min(rect[0] + rect[2], prev[0] + prev[2]);
            float ny1 = Math.min(rect[1] + rect[3], prev[1] + prev[3]);
            rect = new float[]{nx0, ny0, Math.max(0, nx1 - nx0), Math.max(0, ny1 - ny0)};
        }
        SCISSOR_STACK.push(rect);
    }

    public static void popScissor(GuiGraphics g) {
        SCISSOR_STACK.poll();
    }

    private static float[] currentScissor() {
        return SCISSOR_STACK.peek();
    }

    /* ------------------------------------------------------------------ */
    /*  Публичные примитивы (API сохранён от прошлой версии движка)        */
    /* ------------------------------------------------------------------ */

    /** Гладкий скруглённый прямоугольник (заливка). */
    public static void rounded(GuiGraphics g, float x, float y, float w, float h, float r, int argb) {
        if (skip(w, h, argb)) {
            return;
        }
        Cmd c = cmd(FILL, g);
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        c.r = r;
        c.argb = argb;
    }

    /** Скруглённый прямоугольник с вертикальным градиентом (top → bottom). */
    public static void roundedGradient(GuiGraphics g, float x, float y, float w, float h, float r, int top, int bottom) {
        if (skip(w, h, top, bottom)) {
            return;
        }
        Cmd c = cmd(GRADIENT, g);
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        c.r = r;
        c.argb = top;
        c.argb2 = bottom;
    }

    /** Обводка скруглённого прямоугольника (толщина в gui-пикселях). */
    public static void outline(GuiGraphics g, float x, float y, float w, float h, float r, int argb, float thickness) {
        if (skip(w, h, argb)) {
            return;
        }
        Cmd c = cmd(OUTLINE, g);
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        c.r = r;
        c.p1 = Math.max(0.25f, thickness);
        c.argb = argb;
    }

    /**
     * Мягкая тень: силуэт прямоугольника, смещённый вниз и растворяющийся
     * по гауссовому закону. offsetY/sigma — в gui-пикселях.
     */
    public static void dropShadow(GuiGraphics g, float x, float y, float w, float h, float r,
                                  float offsetY, float sigma, int argb) {
        if (skip(w, h, argb)) {
            return;
        }
        Cmd c = cmd(SOFT, g);
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        c.r = r;
        c.p1 = Math.max(0.5f, sigma);
        c.p2 = offsetY;
        c.argb = argb;
    }

    /** Мягкое свечение вокруг прямоугольника (offsetY = 0). */
    public static void glow(GuiGraphics g, float x, float y, float w, float h, float r, float sigma, int argb) {
        dropShadow(g, x, y, w, h, r, 0, sigma, argb);
    }

    /** Виньетка: альфа нарастает от прямоугольника к краям. */
    public static void rampFrame(GuiGraphics g, float x, float y, float w, float h, float r, float fade, int argb) {
        if (skip(w, h, argb)) {
            return;
        }
        Cmd c = cmd(RAMP, g);
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        c.r = r;
        c.p1 = Math.max(1f, fade);
        c.argb = argb;
    }

    /** Горизонтальный линейный градиент на весь прямоугольник (left → right). */
    public static void gradientH(GuiGraphics g, float x, float y, float w, float h, int left, int right) {
        if (skip(w, h, left, right)) {
            return;
        }
        Cmd c = cmd(GRADIENT_H, g);
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        c.r = 0;
        c.argb = left;
        c.argb2 = right;
    }

    /** Гладкий круг диаметра d. */
    public static void circle(GuiGraphics g, float cx, float cy, float d, int argb) {
        if (d <= 0 || ColorUtil.alphaI(argb) <= 0) {
            return;
        }
        Cmd c = cmd(CIRCLE, g);
        c.x = cx;
        c.y = cy;
        c.w = d;
        c.argb = argb;
    }

    /** Гладкое кольцо (окружность) диаметра d. */
    public static void ring(GuiGraphics g, float cx, float cy, float d, int argb, float thickness) {
        if (d <= 0) {
            return;
        }
        outline(g, cx - d / 2f, cy - d / 2f, d, d, d / 2f, argb, thickness);
    }

    /** Контур эллипса (центр cx/cy, радиусы rx/ry) — кольца Target ESP. */
    public static void ellipse(GuiGraphics g, float cx, float cy, float rx, float ry, int argb, float thickness) {
        if (rx <= 0 || ry <= 0 || ColorUtil.alphaI(argb) <= 0) {
            return;
        }
        Cmd c = cmd(ELLIPSE, g);
        c.x = cx;
        c.y = cy;
        c.w = rx;
        c.h = ry;
        c.p1 = Math.max(0.25f, thickness);
        c.argb = argb;
    }

    /** Обводка ломаной/полигона: xy — пары (x,y), pointCount — число вершин. */
    public static void pathStroke(GuiGraphics g, float[] xy, int pointCount, int argb, float thickness, boolean closed) {
        if (pointCount < 2 || ColorUtil.alphaI(argb) <= 0) {
            return;
        }
        Cmd c = cmd(PATH_STROKE, g);
        storePts(c, xy, pointCount, closed);
        c.p1 = Math.max(0.25f, thickness);
        c.argb = argb;
    }

    /** Заливка полигона: xy — пары (x,y), pointCount — число вершин. */
    public static void pathFill(GuiGraphics g, float[] xy, int pointCount, int argb) {
        if (pointCount < 3 || ColorUtil.alphaI(argb) <= 0) {
            return;
        }
        if (isConvexPolygon(xy, pointCount)) {
            Cmd c = cmd(PATH_FILL, g);
            storePts(c, xy, pointCount, true);
            c.argb = argb;
        } else {
            // ВАЖНО: NanoVG заливает полигон веером от первой вершины —
            // вогнутые полигоны ломаются (самопересечения, «огрызки»).
            // Разрезаем на выпуклые треугольники (ear clipping) — каждый
            // рисуется отдельной командой и заливается корректно.
            fillByEarClipping(g, xy, pointCount, argb);
        }
        if (PTS_DEBUG) {
            LOGGER.info("pathFill n={} argb={} convex={}", pointCount,
                    Integer.toHexString(argb), isConvexPolygon(xy, pointCount));
        }
    }

    /** Копирует вершины в команду и считает bbox — чтобы грубая culling-оценка по x/y/w/h была корректной. */
    private static void storePts(Cmd c, float[] xy, int pointCount, boolean closed) {
        int need = pointCount * 2;
        if (c.pts == null || c.pts.length < need) {
            c.pts = new float[Math.max(32, need)];
        }
        System.arraycopy(xy, 0, c.pts, 0, need);
        c.ptCount = pointCount;
        c.closed = closed;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < pointCount; i++) {
            float px = xy[i * 2], py = xy[i * 2 + 1];
            if (px < minX) minX = px;
            if (py < minY) minY = py;
            if (px > maxX) maxX = px;
            if (py > maxY) maxY = py;
        }
        c.x = minX;
        c.y = minY;
        c.w = maxX - minX;
        c.h = maxY - minY;
    }

    /* ------------------------------------------------------------------ */
    /*  Триангуляция вогнутых полигонов (ear clipping)                     */
    /* ------------------------------------------------------------------ */

    /** Буфер одной треугольной команды (переиспользуется, без аллокаций). */
    private static final float[] TRI = new float[6];

    private static float cross3(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /** Выпуклость полигона: все повороты одного знака (нулевые игнорируем). */
    private static boolean isConvexPolygon(float[] xy, int n) {
        boolean hasPos = false;
        boolean hasNeg = false;
        for (int i = 0; i < n; i++) {
            float ax = xy[((i + n - 1) % n) * 2], ay = xy[((i + n - 1) % n) * 2 + 1];
            float bx = xy[i * 2], by = xy[i * 2 + 1];
            float cx = xy[((i + 1) % n) * 2], cy = xy[((i + 1) % n) * 2 + 1];
            float cr = cross3(ax, ay, bx, by, cx, cy);
            if (cr > 1e-6f) hasPos = true;
            if (cr < -1e-6f) hasNeg = true;
            if (hasPos && hasNeg) {
                return false;
            }
        }
        return true;
    }

    /** Разрезает полигон на треугольники и рисует каждый отдельной командой. */
    private static void fillByEarClipping(GuiGraphics g, float[] xy, int n, int argb) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        // ориентация контура: знак площади
        float area2 = 0;
        for (int i = 0; i < n; i++) {
            int a = i, b = (i + 1) % n;
            area2 += xy[a * 2] * xy[b * 2 + 1] - xy[b * 2] * xy[a * 2 + 1];
        }
        float areaSign = Math.signum(area2);
        if (areaSign == 0) {
            return; // вырожденный полигон
        }
        int count = n;
        int guard = 0;
        while (count > 2 && guard++ < 4 * n + 16) {
            boolean clipped = false;
            for (int i = 0; i < count; i++) {
                int a = idx[(i + count - 1) % count];
                int b = idx[i];
                int c = idx[(i + 1) % count];
                if (isEar(xy, a, b, c, idx, count, areaSign)) {
                    emitTriangle(g, xy[a * 2], xy[a * 2 + 1], xy[b * 2], xy[b * 2 + 1], xy[c * 2], xy[c * 2 + 1], argb);
                    for (int j = i; j < count - 1; j++) {
                        idx[j] = idx[j + 1];
                    }
                    count--;
                    clipped = true;
                    break;
                }
            }
            if (!clipped) {
                break; // числовая деградация — рисуем остаток веером (лучше, чем ничего)
            }
        }
        if (count == 3) {
            emitTriangle(g, xy[idx[0] * 2], xy[idx[0] * 2 + 1],
                    xy[idx[1] * 2], xy[idx[1] * 2 + 1],
                    xy[idx[2] * 2], xy[idx[2] * 2 + 1], argb);
        }
    }

    /** Ушко: вершина b выпуклая и треугольник abc не содержит других вершин. */
    private static boolean isEar(float[] xy, int a, int b, int c, int[] idx, int count, float areaSign) {
        float cr = cross3(xy[a * 2], xy[a * 2 + 1], xy[b * 2], xy[b * 2 + 1], xy[c * 2], xy[c * 2 + 1]);
        if (cr * areaSign <= 1e-6f) {
            return false; // рефлексная вершина или коллинейная
        }
        for (int i = 0; i < count; i++) {
            int p = idx[i];
            if (p == a || p == b || p == c) {
                continue;
            }
            if (pointInTriangle(xy, a, b, c, p)) {
                return false;
            }
        }
        return true;
    }

    private static boolean pointInTriangle(float[] xy, int a, int b, int c, int p) {
        float d1 = cross3(xy[a * 2], xy[a * 2 + 1], xy[b * 2], xy[b * 2 + 1], xy[p * 2], xy[p * 2 + 1]);
        float d2 = cross3(xy[b * 2], xy[b * 2 + 1], xy[c * 2], xy[c * 2 + 1], xy[p * 2], xy[p * 2 + 1]);
        float d3 = cross3(xy[c * 2], xy[c * 2 + 1], xy[a * 2], xy[a * 2 + 1], xy[p * 2], xy[p * 2 + 1]);
        boolean hasNeg = d1 < -1e-6f || d2 < -1e-6f || d3 < -1e-6f;
        boolean hasPos = d1 > 1e-6f || d2 > 1e-6f || d3 > 1e-6f;
        return !(hasNeg && hasPos);
    }

    /** Рисует один выпуклый треугольник отдельной командой PATH_FILL. */
    private static void emitTriangle(GuiGraphics g, float ax, float ay, float bx, float by, float cx, float cy, int argb) {
        TRI[0] = ax;
        TRI[1] = ay;
        TRI[2] = bx;
        TRI[3] = by;
        TRI[4] = cx;
        TRI[5] = cy;
        Cmd c = cmd(PATH_FILL, g);
        storePts(c, TRI, 3, true);
        c.argb = argb;
    }

    /** Виньетка на весь экран. */
    public static void vignette(GuiGraphics g, int w, int h, int argb) {
        float inset = Math.min(w, h) * 0.28f;
        rampFrame(g, inset, inset, Math.max(1, w - inset * 2), Math.max(1, h - inset * 2),
                Math.min(w, h) * 0.45f, Math.max(40, Math.min(w, h) * 0.22f), argb);
    }

    /**
     * Мягкое радиальное пятно — дым, туман, свечение.
     *
     * @param cx, cy центр пятна; d — диаметр
     * @param core  цвет в центре (с альфой)
     * @param edge  цвет на краю (обычно тот же RGB с альфой 0)
     */
    public static void radial(GuiGraphics g, float cx, float cy, float d, int core, int edge) {
        if (d <= 0 || (ColorUtil.alphaI(core) <= 0 && ColorUtil.alphaI(edge) <= 0)) {
            return;
        }
        Cmd c = cmd(RADIAL, g);
        c.x = cx;
        c.y = cy;
        c.w = d;
        c.argb = core;
        c.argb2 = edge;
    }

    /* ------------------------------------------------------------------ */
    /*  Текст (Inter через NanoVG)                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Рисует строку: y — базовая линия в gui-координатах (как у NanoVG).
     * Текущая матрица позы и scissor учитываются автоматически.
     */
    public static void drawText(GuiGraphics g, float x, float baselineY, String s, float size, int argb, boolean bold) {
        if (s == null || s.isEmpty() || ColorUtil.alphaI(argb) <= 0) {
            return;
        }
        Cmd c = cmd(TEXT, g);
        c.text = s;
        c.x = x;
        c.y = baselineY;
        c.size = size;
        c.argb = argb;
        c.bold = bold;
        // bbox для ручного scissor: без него текст «протекал» за границу
        // области обрезки на ~12px запаса (строки карточек под футером меню)
        c.w = textWidth(s, size, bold);
        c.h = size;
    }

    /** Ширина строки при заданном размере (в gui-пикселях). */
    public static float textWidth(String s, float size, boolean bold) {
        if (s == null || s.isEmpty() || !ensureReady()) {
            return 0;
        }
        NanoVG.nvgFontSize(ctx, size);
        NanoVG.nvgFontFaceId(ctx, bold ? fontSemiBold : fontMedium);
        return NanoVG.nvgTextBounds(ctx, 0, 0, s, (float[]) null);
    }

    /** Ширина строки стандартным шрифтом интерфейса. */
    public static float textWidth(String s) {
        return textWidth(s, TEXT_SIZE, false);
    }

    /* ------------------------------------------------------------------ */
    /*  Проигрывание буфера (вызывается миксином после ванильного GUI)     */
    /* ------------------------------------------------------------------ */

    public static void flushFrame() {
        if (COMMANDS.isEmpty()) {
            return;
        }
        // обмен буферов: в COMMANDS уже можно писать следующий кадр, пока
        // этот проигрывается (нулевая копирование-аллокация на кадр)
        List<Cmd> cmds = COMMANDS;
        COMMANDS = REPLAY;
        REPLAY = cmds;
        if (COMMANDS == cmds) { // страховка от рекурсии
            COMMANDS = new ArrayList<>(512);
        }
        try {
            if (!ensureReady()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            RenderTarget target = mc.getMainRenderTarget();
            if (target == null || target.width <= 0 || target.height <= 0) {
                return;
            }
            int guiScale = Math.max(1, mc.getWindow().getGuiScale());
            if (!loggedOnce) {
                loggedOnce = true;
                int[] vp = new int[4];
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
                LOGGER.info("кадр Aurora: таргет {}x{}, guiScale {}, вьюпорт {}x{}+{}x{}",
                        target.width, target.height, guiScale, vp[2], vp[3], vp[0], vp[1]);
                if (DEBUG || PTS_DEBUG) {
                    int[] stencilBits = new int[1];
                    GL30.glGetFramebufferAttachmentParameteriv(GL30.GL_DRAW_FRAMEBUFFER,
                            GL30.GL_STENCIL, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE, stencilBits);
                    int[] depthBits = new int[1];
                    GL30.glGetFramebufferAttachmentParameteriv(GL30.GL_DRAW_FRAMEBUFFER,
                            GL30.GL_DEPTH, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE, depthBits);
                    java.nio.ByteBuffer mask = java.nio.ByteBuffer.allocateDirect(4);
                    GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                    LOGGER.info("GL-состояние: stencilBits={}, depthBits={}, stencilTest={}, stencilWritemask={}, depthTest={}, colorMask={},{},{},{}",
                            stencilBits[0], depthBits[0],
                            GL11.glIsEnabled(GL11.GL_STENCIL_TEST),
                            (GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK) & 0xFF),
                            GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                            mask.get(0), mask.get(1), mask.get(2), mask.get(3));
                }
            }
            // Minecraft может оставить привязанным буфер выгрузки пикселей (PBO) —
            // с ним атлас шрифтов NanoVG заливается пустым и текст пропадает
            GL15.glBindBuffer(GL31.GL_PIXEL_UNPACK_BUFFER, 0);
            // NanoVG работает на текстурном юните 0 и не переключает активный юнит сам —
            // а MC часто оставляет активным другой юнит (тогда текст сэмплирует «пустоту»)
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL33.glBindSampler(0, 0);
            try (RenderPass pass = openPass(target)) {
                // на случай, если ванилла оставила scissor включённым
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);
                // ванильный рендер оставляет мусор в стенсил-буфере главного
                // таргета — NanoVG использует стенсил для заливки контуров,
                // и без очистки примитивы «выпадают» (не проходят стенсил-тест)
                GL11.glStencilMask(0xFF);
                GL11.glClearStencil(0);
                GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);

                // nvgBeginFrame принимает ЛОГИЧЕСКИЙ размер (в поинтах/gui-пикселях),
                // а pixelRatio = сколько физических пикселей на поинт. Раньше сюда
                // передавали физический размер таргета — при guiScale > 1 весь интерфейс
                // сжимался в guiScale раз (масштабировался дважды).
                float logicalW = target.width / (float) guiScale;
                float logicalH = target.height / (float) guiScale;
                NanoVG.nvgBeginFrame(ctx, logicalW, logicalH, guiScale);
                // scissor-состояние NanoVG живёт между кадрами: без сброса
                // команды без собственного отсечения наследуют прошлокадровый
                // rect (проявляется как «чёрные зоны» и пропавшие элементы)
                NanoVG.nvgResetScissor(ctx);
                if (DEBUG) {
                    GL11.glGetError(); // сброс
                    // диагностика: примитив и текст без позы, крупно
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgRoundedRect(ctx, 40, 330, 120, 60, 10);
                    NanoVG.nvgFillColor(ctx, color(0xFFFF3355, colorA));
                    NanoVG.nvgFill(ctx);
                    NanoVG.nvgFontSize(ctx, 48);
                    NanoVG.nvgFontFaceId(ctx, fontMedium);
                    NanoVG.nvgTextAlign(ctx, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_BASELINE);
                    NanoVG.nvgFillColor(ctx, color(0xFFFFFFFF, colorA));
                    NanoVG.nvgText(ctx, 40, 320, "AURORA NVG TEST");
                    int err = GL11.glGetError();
                    LOGGER.info("Aurora: GL error после тестового текста = {}", Integer.toHexString(err));
                }
                for (int i = 0; i < cmds.size(); i++) {
                    try {
                        replay(cmds.get(i));
                    } catch (Throwable t) {
                        // одна сломанная команда не должна ронять весь кадр
                    }
                }
                NanoVG.nvgEndFrame(ctx);
                GL11.glDepthMask(true);
            } catch (Throwable t) {
                LOGGER.error("Aurora: сбой отрисовки NanoVG", t);
            }
        } finally {
            // вернуть команды в пул — на следующий кадр без аллокаций
            for (int i = 0; i < cmds.size(); i++) {
                Cmd c = cmds.get(i);
                c.text = null;
                c.ptCount = 0;
                if (CMD_POOL.size() < 2048) {
                    CMD_POOL.add(c);
                }
            }
            cmds.clear();
        }
    }

    /**
     * Открывает render pass на главный фреймбуфер (без очистки содержимого).
     *
     * <p>Depth-стенсил-аттачмент обязателен: NanoVG рисует ЗАЛИВКУ ПОЛИГОНОВ
     * стенсил-техникой (рёбра пишут в стенсил, затем заливается bbox под
     * стенсил-тестом). Без стенсила вогнутые полигоны (молния-иконка,
     * силуэты Target ESP) отрисовывались «веером треугольников» — огрызками.
     * Глубину НЕ чистим и не пишем (depth test off, depthMask false),
     * поэтому глубинные состояния последующих проходов Minecraft не страдают.</p>
     */
    private static RenderPass openPass(RenderTarget target) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuTextureView depth = target.getDepthTextureView();
        if (depth != null) {
            return encoder.createRenderPass(() -> "Aurora NanoVG",
                    target.getColorTextureView(), OptionalInt.empty(),
                    depth, OptionalDouble.empty());
        }
        return encoder.createRenderPass(() -> "Aurora NanoVG",
                target.getColorTextureView(), OptionalInt.empty());
    }

    /**
     * Габарит команды (x0, y0, x1, y1) в локальных координатах —
     * для ручного scissor. Запасы: SOFT/RAMP расширяются сигмой краски,
     * текст — высотой строки, круги — диаметром.
     */
    private static void commandBBox(Cmd c, float[] out) {
        switch (c.type) {
            case CIRCLE -> {
                out[0] = c.x - c.w / 2f;
                out[1] = c.y - c.w / 2f;
                out[2] = c.x + c.w / 2f;
                out[3] = c.y + c.w / 2f;
            }
            case RADIAL -> {
                out[0] = c.x - c.w / 2f;
                out[1] = c.y - c.w / 2f;
                out[2] = c.x + c.w / 2f;
                out[3] = c.y + c.w / 2f;
            }
            case ELLIPSE -> {
                out[0] = c.x - c.w;
                out[1] = c.y - c.h;
                out[2] = c.x + c.w;
                out[3] = c.y + c.h;
            }
            case TEXT -> {
                out[0] = c.x - 1f;
                out[1] = c.y - c.h * 0.9f;
                out[2] = c.x + c.w + 1f;
                out[3] = c.y + c.h * 0.3f;
            }
            case SOFT, RAMP -> {
                float pad = c.p1 * 2.5f + 2f;
                out[0] = c.x - pad;
                out[1] = c.y - pad + (c.type == SOFT ? c.p2 : 0);
                out[2] = c.x + c.w + pad;
                out[3] = c.y + c.h + pad + (c.type == SOFT ? c.p2 : 0);
            }
            default -> {
                out[0] = c.x;
                out[1] = c.y;
                out[2] = c.x + c.w;
                out[3] = c.y + c.h;
            }
        }
    }

    /**
     * Ручное отсечение команды областью scissor (в глобальных координатах).
     *
     * <p>nvgIntersectScissor в связке с devicePixelRatio &gt; 1 отсекает
     * валидные области (примитивы «пропадали» целыми рядами), поэтому
     * отсечение считается вручную: прямоугольные примитивы без вращения
     * позы клэмпятся точно; остальные проверяются по габариту — полностью
     * вне области отбрасываются, частично пересекающиеся рисуются целиком.</p>
     *
     * @return false — команда полностью вне области
     */
    private static boolean applyManualScissor(Cmd c, Matrix3x2fc p) {
        float sx1 = c.sx + c.sw;
        float sy1 = c.sy + c.sh;
        boolean noRot = Math.abs(p.m01()) < 1e-4f && Math.abs(p.m10()) < 1e-4f
                && Math.abs(p.m00()) > 1e-4f && Math.abs(p.m11()) > 1e-4f;
        boolean rectLike = c.type == FILL || c.type == GRADIENT || c.type == GRADIENT_H
                || c.type == OUTLINE;
        if (rectLike && noRot) {
            float gx0 = p.m00() * c.x + p.m20();
            float gy0 = p.m11() * c.y + p.m21();
            float gx1 = p.m00() * (c.x + c.w) + p.m20();
            float gy1 = p.m11() * (c.y + c.h) + p.m21();
            float x0 = Math.max(Math.min(gx0, gx1), c.sx);
            float y0 = Math.max(Math.min(gy0, gy1), c.sy);
            float x1 = Math.min(Math.max(gx0, gx1), sx1);
            float y1 = Math.min(Math.max(gy0, gy1), sy1);
            if (x1 <= x0 || y1 <= y0) {
                return false;
            }
            c.x = (x0 - p.m20()) / p.m00();
            c.y = (y0 - p.m21()) / p.m11();
            c.w = (x1 - p.m20()) / p.m00() - c.x;
            c.h = (y1 - p.m21()) / p.m11() - c.y;
            return true;
        }
        // габарит с учётом позы: полностью вне области — отбрасываем
        commandBBox(c, BB);
        float x0 = BB[0], y0 = BB[1], x1 = BB[2], y1 = BB[3];
        float ax0 = p.m00() * x0 + p.m10() * y0 + p.m20();
        float ay0 = p.m01() * x0 + p.m11() * y0 + p.m21();
        float ax1 = p.m00() * x1 + p.m10() * y1 + p.m20();
        float ay1 = p.m01() * x1 + p.m11() * y1 + p.m21();
        if (noRot) {
            float bx0 = p.m00() * x0 + p.m20();
            float by0 = p.m11() * y1 + p.m21();
            float bx1 = p.m00() * x1 + p.m20();
            float by1 = p.m11() * y0 + p.m21();
            ax0 = Math.min(ax0, bx0);
            ax1 = Math.max(ax1, bx1);
            ay0 = Math.min(ay0, by0);
            ay1 = Math.max(ay1, by1);
        } else {
            float lox = Math.min(ax0, ax1), hix = Math.max(ax0, ax1);
            float loy = Math.min(ay0, ay1), hiy = Math.max(ay0, ay1);
            ax0 = lox;
            ax1 = hix;
            ay0 = loy;
            ay1 = hiy;
        }
        return ax1 > c.sx && ax0 < sx1 && ay1 > c.sy && ay0 < sy1;
    }

    private static void replay(Cmd c) {
        Matrix3x2fc p = c.pose;
        if (c.sw == 0) {
            return; // пустая scissor-область — команда полностью отсечена
        }
        if (p.m00() * p.m11() - p.m01() * p.m10() == 0f) {
            return; // вырожденная поза
        }
        if (c.sw > 0 && !applyManualScissor(c, p)) {
            return; // вне области отсечения
        }
        NanoVG.nvgSave(ctx);
        try {
            NanoVG.nvgTransform(ctx, p.m00(), p.m01(), p.m10(), p.m11(), p.m20(), p.m21());
            switch (c.type) {
                case FILL -> fillRounded(c.x, c.y, c.w, c.h, c.r, c.argb);
                case CIRCLE -> {
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgCircle(ctx, c.x, c.y, c.w / 2f);
                    NanoVG.nvgFillColor(ctx, color(c.argb, colorA));
                    NanoVG.nvgFill(ctx);
                }
                case RADIAL -> {
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgCircle(ctx, c.x, c.y, c.w / 2f);
                    NanoVG.nvgFillPaint(ctx, NanoVG.nvgRadialGradient(ctx,
                            c.x, c.y, c.w * 0.18f, c.w / 2f,
                            color(c.argb, colorA), transparent(c.argb2, colorB), paint));
                    NanoVG.nvgFill(ctx);
                }
                case GRADIENT -> {
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgRoundedRect(ctx, c.x, c.y, c.w, c.h, radius(c.r, c.w, c.h));
                    NanoVG.nvgFillPaint(ctx, NanoVG.nvgLinearGradient(ctx,
                            c.x, c.y, c.x, c.y + c.h, color(c.argb, colorA), color(c.argb2, colorB), paint));
                    NanoVG.nvgFill(ctx);
                }
                case GRADIENT_H -> {
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgRect(ctx, c.x, c.y, c.w, c.h);
                    NanoVG.nvgFillPaint(ctx, NanoVG.nvgLinearGradient(ctx,
                            c.x, c.y + c.h / 2f, c.x + c.w, c.y + c.h / 2f,
                            color(c.argb, colorA), color(c.argb2, colorB), paint));
                    NanoVG.nvgFill(ctx);
                }
                case OUTLINE -> {
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgRoundedRect(ctx, c.x, c.y, c.w, c.h, radius(c.r, c.w, c.h));
                    NanoVG.nvgStrokeColor(ctx, color(c.argb, colorA));
                    NanoVG.nvgStrokeWidth(ctx, c.p1);
                    NanoVG.nvgStroke(ctx);
                }
                case SOFT -> {
                    float sigma = c.p1;
                    float pad = sigma * 2.5f + 2f;
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgRoundedRect(ctx, c.x - pad, c.y - pad + c.p2, c.w + pad * 2, c.h + pad * 2,
                            radius(c.r + pad, c.w + pad * 2, c.h + pad * 2));
                    // внутренняя область — цвет тени, внешняя — прозрачный тот же RGB
                    NanoVG.nvgFillPaint(ctx, NanoVG.nvgBoxGradient(ctx,
                            c.x, c.y + c.p2, c.w, c.h, radius(c.r, c.w, c.h), sigma,
                            color(c.argb, colorA), transparent(c.argb, colorB), paint));
                    NanoVG.nvgFill(ctx);
                }
                case RAMP -> {
                    float fade = c.p1;
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgRoundedRect(ctx, c.x - fade, c.y - fade, c.w + fade * 2, c.h + fade * 2,
                            radius(c.r + fade, c.w + fade * 2, c.h + fade * 2));
                    NanoVG.nvgFillPaint(ctx, NanoVG.nvgBoxGradient(ctx,
                            c.x, c.y, c.w, c.h, radius(c.r, c.w, c.h), fade,
                            transparent(c.argb, colorA), color(c.argb, colorB), paint));
                    NanoVG.nvgFill(ctx);
                }
                case ELLIPSE -> {
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgEllipse(ctx, c.x, c.y, c.w, c.h);
                    NanoVG.nvgStrokeColor(ctx, color(c.argb, colorA));
                    NanoVG.nvgStrokeWidth(ctx, c.p1);
                    NanoVG.nvgStroke(ctx);
                }
                case PATH_STROKE, PATH_FILL -> {
                    if (PTS_DEBUG) {
                        LOGGER.info("replay path n={} type={} closed={} pts0=({},{}) ptsLast=({},{}) bbox=({},{},{},{})",
                                c.ptCount, c.type, c.closed, c.pts[0], c.pts[1],
                                c.pts[(c.ptCount - 1) * 2], c.pts[(c.ptCount - 1) * 2 + 1],
                                c.x, c.y, c.w, c.h);
                    }
                    NanoVG.nvgBeginPath(ctx);
                    float[] pts = c.pts;
                    NanoVG.nvgMoveTo(ctx, pts[0], pts[1]);
                    for (int i = 1; i < c.ptCount; i++) {
                        NanoVG.nvgLineTo(ctx, pts[i * 2], pts[i * 2 + 1]);
                    }
                    if (c.closed) {
                        NanoVG.nvgClosePath(ctx);
                    }
                    if (c.type == PATH_FILL) {
                        NanoVG.nvgFillColor(ctx, color(c.argb, colorA));
                        NanoVG.nvgFill(ctx);
                    } else {
                        NanoVG.nvgStrokeColor(ctx, color(c.argb, colorA));
                        NanoVG.nvgStrokeWidth(ctx, c.p1);
                        NanoVG.nvgStroke(ctx);
                    }
                }
                case TEXT -> {
                    NanoVG.nvgFontSize(ctx, c.size);
                    NanoVG.nvgFontFaceId(ctx, c.bold ? fontSemiBold : fontMedium);
                    NanoVG.nvgTextAlign(ctx, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_BASELINE);
                    NanoVG.nvgFillColor(ctx, color(c.argb, colorA));
                    NanoVG.nvgText(ctx, c.x, c.y, c.text);
                }
                default -> {
                }
            }
        } finally {
            NanoVG.nvgRestore(ctx);
        }
    }

    private static void fillRounded(float x, float y, float w, float h, float r, int argb) {
        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x, y, w, h, radius(r, w, h));
        NanoVG.nvgFillColor(ctx, color(argb, colorA));
        NanoVG.nvgFill(ctx);
    }

    private static float radius(float r, float w, float h) {
        return Math.max(0f, Math.min(r, Math.min(w, h) / 2f));
    }

    private static boolean skip(float w, float h, int... colors) {
        if (w <= 0 || h <= 0) {
            return true;
        }
        for (int c : colors) {
            if (ColorUtil.alphaI(c) > 0) {
                return false;
            }
        }
        return true;
    }

    /** ARGB → NVGColor (прямой альфа, NanoVG премультиплицирует сам). */
    private static NVGColor color(int argb, NVGColor out) {
        out.r(((argb >> 16) & 0xFF) / 255f);
        out.g(((argb >> 8) & 0xFF) / 255f);
        out.b((argb & 0xFF) / 255f);
        out.a(((argb >>> 24) & 0xFF) / 255f);
        return out;
    }

    private static NVGColor transparent(int argb, NVGColor out) {
        out.r(((argb >> 16) & 0xFF) / 255f);
        out.g(((argb >> 8) & 0xFF) / 255f);
        out.b((argb & 0xFF) / 255f);
        out.a(0f);
        return out;
    }

}
