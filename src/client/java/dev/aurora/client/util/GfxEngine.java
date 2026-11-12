package dev.aurora.client.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
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
    /** Байты шрифтов держим всё время жизни: NanoVG читает их напрямую. */
    private static ByteBuffer fontDataMedium;
    private static ByteBuffer fontDataSemiBold;

    /** Переиспользуемые структуры (без аллокаций в кадре). */
    private static NVGColor colorA;
    private static NVGColor colorB;
    private static NVGPaint paint;

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

    private static final List<Cmd> COMMANDS = new ArrayList<>(512);
    /** Пул команд: переиспользуем объекты, чтобы не создавать сотни аллокаций в кадр. */
    private static final ArrayDeque<Cmd> FREE_CMDS = new ArrayDeque<>();

    private static Cmd allocCmd() {
        Cmd c = FREE_CMDS.pollFirst();
        return c != null ? c : new Cmd();
    }

    private static final class Cmd {
        int type;
        /** Переиспользуемая матрица (не выделяем новую на каждую команду). */
        final Matrix3x2f pose = new Matrix3x2f();
        float sx, sy, sw, sh;   // scissor (или sw <= 0 — без отсечения)
        float x, y, w, h, r;
        float p1, p2;           // sigma / offsetY / thickness / fade
        int argb, argb2;
        String text;
        boolean bold;
        float size;

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
        Cmd c = allocCmd();
        c.text = null;
        c.type = type;
        c.pose.set(g.pose());
        float[] s = currentScissor();
        if (s != null) {
            // scissor хранится в сырых gui-координатах: NanoVG сам применит к
            // нему текущую матрицу воспроизведения (поза * guiScale) при
            // отрисовке. Раньше scissor трансформировался дважды — области
            // «уезжали» и текст настроек вылезал за границы карточек
            c.sx = s[0];
            c.sy = s[1];
            c.sw = s[2];
            c.sh = s[3];
        } else {
            c.sw = -1; // без отсечения
        }
        COMMANDS.add(c);
        return c;
    }

    /* ------------------------------------------------------------------ */
    /*  Scissor                                                           */
    /* ------------------------------------------------------------------ */

    private static final ArrayDeque<float[]> SCISSOR_STACK = new ArrayDeque<>();

    /** Включает отсечение (и в ванильном рендере, и для NanoVG-команд). */
    public static void pushScissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        if (x2 <= x1 || y2 <= y1) {
            SCISSOR_STACK.push(new float[]{x1, y1, 0, 0});
            g.enableScissor(x1, y1, x1, y1);
            return;
        }
        g.enableScissor(x1, y1, x2, y2);
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
        g.disableScissor();
        SCISSOR_STACK.poll();
    }

    private static float[] currentScissor() {
        return SCISSOR_STACK.peek();
    }

    /** Масштаб GUI: сколько физических пикселей в одном gui-пикселе. */
    private static float frameGuiScale() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getWindow() != null) {
                return Math.max(1f, mc.getWindow().getGuiScale());
            }
        } catch (Throwable ignored) {
        }
        return 1f;
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

    /** Виньетка на весь экран. */
    public static void vignette(GuiGraphics g, int w, int h, int argb) {
        float inset = Math.min(w, h) * 0.28f;
        rampFrame(g, inset, inset, Math.max(1, w - inset * 2), Math.max(1, h - inset * 2),
                Math.min(w, h) * 0.45f, Math.max(40, Math.min(w, h) * 0.22f), argb);
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
        if (!ensureReady()) {
            // команды всё равно надо освободить, чтобы не копить мусор
            releaseCommands();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null || target.width <= 0 || target.height <= 0) {
            releaseCommands();
            return;
        }
        int guiScale = Math.max(1, mc.getWindow().getGuiScale());
        currentReplayScale = guiScale;
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

            NanoVG.nvgBeginFrame(ctx, target.width, target.height, guiScale);
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
            for (int i = 0; i < COMMANDS.size(); i++) {
                try {
                    replay(COMMANDS.get(i));
                } catch (Throwable t) {
                    // одна сломанная команда не должна ронять весь кадр
                }
            }
            NanoVG.nvgEndFrame(ctx);
        } catch (Throwable t) {
            LOGGER.error("Aurora: сбой отрисовки NanoVG", t);
        } finally {
            releaseCommands();
        }
    }

    /** Возвращает все команды в пул и очищает буфер кадра. */
    private static void releaseCommands() {
        for (int i = 0; i < COMMANDS.size(); i++) {
            Cmd c = COMMANDS.get(i);
            c.text = null; // отпускаем ссылку на строку
            FREE_CMDS.addLast(c);
        }
        COMMANDS.clear();
        if (FREE_CMDS.size() > 4096) {
            FREE_CMDS.clear(); // страховка от разрастания пула
        }
    }

    /** Открывает render pass на главный фреймбуфер (без очистки содержимого). */
    private static RenderPass openPass(RenderTarget target) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var depth = target.getDepthTextureView();
        if (depth != null) {
            return encoder.createRenderPass(() -> "Aurora NanoVG",
                    target.getColorTextureView(), OptionalInt.empty(),
                    depth, OptionalDouble.empty());
        }
        return encoder.createRenderPass(() -> "Aurora NanoVG",
                target.getColorTextureView(), OptionalInt.empty());
    }

    /** Масштаб gui для кадров, проигрываемых сейчас (см. replay). */
    private static float currentReplayScale = 1f;

    private static void replay(Cmd c) {
        Matrix3x2fc p = c.pose;
        if (c.sw == 0) {
            return; // пустая scissor-область — команда полностью отсечена
        }
        float gs = currentReplayScale;
        if (p.m00() * gs * p.m11() * gs - p.m01() * gs * p.m10() * gs == 0f) {
            return; // вырожденная поза
        }
        NanoVG.nvgSave(ctx);
        try {
            // Поза GuiGraphics содержит только координаты gui (без guiScale),
            // а NanoVG рисует прямо в физические пиксели фреймбуфера —
            // поэтому домножаем матрицу на guiScale, иначе при масштабе
            // интерфейса 2+ весь интерфейс Aurora сжимается в угол экрана
            NanoVG.nvgTransform(ctx, p.m00() * gs, p.m01() * gs, p.m10() * gs, p.m11() * gs,
                    p.m20() * gs, p.m21() * gs);
            if (c.sw > 0) {
                NanoVG.nvgIntersectScissor(ctx, c.sx, c.sy, c.sw, c.sh);
            }
            switch (c.type) {
                case FILL -> fillRounded(c.x, c.y, c.w, c.h, c.r, c.argb);
                case CIRCLE -> {
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgCircle(ctx, c.x, c.y, c.w / 2f);
                    NanoVG.nvgFillColor(ctx, color(c.argb, colorA));
                    NanoVG.nvgFill(ctx);
                }
                case GRADIENT -> {
                    NanoVG.nvgBeginPath(ctx);
                    NanoVG.nvgRoundedRect(ctx, c.x, c.y, c.w, c.h, radius(c.r, c.w, c.h));
                    NanoVG.nvgFillPaint(ctx, NanoVG.nvgLinearGradient(ctx,
                            c.x, c.y, c.x, c.y + c.h, color(c.argb, colorA), color(c.argb2, colorB), paint));
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
