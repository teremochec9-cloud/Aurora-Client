package dev.aurora.client.gui;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.config.AuroraConfig;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.FrameClock;
import dev.aurora.client.util.GfxEngine;
import dev.aurora.client.util.IconPainter;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Главное меню Aurora — «Полночь».
 *
 * <p>Полностью новый титульный экран в чёрном стиле: глубокий
 * чёрный градиент, мерцающее звёздное поле, медленные ленты северного
 * сияния в фирменном акценте, дрейфующие пылинки и мягкое «дыхание»
 * света за иконкой. Всё движение — чистые функции времени кадра
 * ({@link FrameClock}), поэтому анимация идеально плавная при любой
 * частоте кадров и без аллокаций.</p>
 *
 * <p>Композиция: анимированная иконка «Полярная звезда»
 * ({@link IconPainter#drawAnimated}) → вордмарк AURORA с переливом
 * по буквам → приветствие с ником → две строки кнопок. Каскад
 * появления: каждый элемент выезжает чуть позже предыдущего.
 * Курсор мягко сдвигает слои фона (параллакс с инерцией).</p>
 */
public class AuroraTitleScreen extends Screen {
    private static final Logger LOG = LoggerFactory.getLogger("Aurora/Title");
    private final Theme theme;

    /* ------------------------------------------------------------------ */
    /*  Звёзды, сияние, пыль — плоские массивы (ноль аллокаций в кадре)    */
    /* ------------------------------------------------------------------ */

    /** Звёзды: x, y, размер, фаза — детерминированный сид. */
    private static final int STARS = 110;
    private static final int SF = 4;
    private final float[] stars = new float[STARS * SF];

    /** Пылинки: x, y, скорость, размер, фаза. */
    private static final int MOTES = 20;
    private static final int MF = 5;
    private final float[] motes = new float[MOTES * MF];

    /** Слои сияния: базовая высота (доля), амплитуда, частота, радиус, альфа, параллакс. */
    private static final float[][] RIBBONS = {
            {0.12f, 26f, 0.11f, 44f, 18f, 0.010f},
            {0.20f, 34f, 0.07f, 58f, 14f, 0.016f},
            {0.29f, 22f, 0.05f, 38f, 10f, 0.024f},
    };
    /** Шаг сэмплов ленты (gui-пиксели). */
    private static final int RIBBON_STEP = 30;
    /** Буфер дуги сияния (до 96 точек). */
    private final float[] arcBuf = new float[192];

    /* ------------------------------------------------------------------ */
    /*  Кнопки                                                            */
    /* ------------------------------------------------------------------ */

    private static final String[] BT = {
            "Одиночная игра", "Сетевая игра",
            "Настройки", "Меню Aurora", "Выход"
    };
    private static final int MAIN_BTNS = 5;

    /** Плавное наведение: 5 главных + 2 мелкие. */
    private final Animation[] hoverAnims = new Animation[MAIN_BTNS + 2];
    private final Animation[] pressAnims = new Animation[MAIN_BTNS];
    /** Инерционный параллакс фона за курсором. */
    private final Animation parallaxX = new Animation(0, 4.5f);
    private final Animation parallaxY = new Animation(0, 4.5f);

    /** Момент рождения экрана — от него идёт каскад появления. */
    private long bornAt;

    /** Кэш строк, чтобы не собирать их каждый кадр. */
    private String nick = "игрок";
    private String versionLine;
    private String fpsLine = "0 fps";
    private long fpsLineAt;

    private int lastW = -1;
    private int lastH = -1;
    private int mouseX;
    private int mouseY;

    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    public AuroraTitleScreen() {
        super(Component.literal("Aurora"));
        this.theme = AuroraClient.theme();
        this.bornAt = FrameClock.now();
        for (int i = 0; i < hoverAnims.length; i++) {
            hoverAnims[i] = new Animation(0, 13);
        }
        for (int i = 0; i < pressAnims.length; i++) {
            pressAnims[i] = new Animation(0, 20);
        }
    }

    @Override
    protected void init() {
        // init() вызывается и при смене размера окна — пересаживаем звёзды
        // с масштабированием, чтобы картинка не «прыгала»
        if (lastW <= 0 || lastH <= 0) {
            seedSky(this.width, this.height);
        } else if (this.width != lastW || this.height != lastH) {
            rescaleSky(lastW, lastH, this.width, this.height);
        }
        lastW = this.width;
        lastH = this.height;

        if (versionLine == null) {
            try {
                nick = Minecraft.getInstance().getUser().getName();
            } catch (Exception ignored) {
            }
            versionLine = "Aurora " + Theme.CLIENT_VERSION + " · клиент для Minecraft " + Theme.MC_VERSION;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Генерация звёздного неба                                          */
    /* ------------------------------------------------------------------ */

    private void seedSky(int w, int h) {
        Random random = new Random(0x501A1CEL);
        for (int i = 0; i < STARS; i++) {
            stars[i * SF + 0] = random.nextFloat() * w;                      // x
            stars[i * SF + 1] = random.nextFloat() * h * 0.72f;              // y — верхние 72%
            stars[i * SF + 2] = 0.7f + random.nextFloat() * 1.1f;            // размер
            stars[i * SF + 3] = random.nextFloat() * 6.2831853f;             // фаза
        }
        for (int i = 0; i < MOTES; i++) {
            motes[i * MF + 0] = random.nextFloat() * w;
            motes[i * MF + 1] = random.nextFloat() * h;
            motes[i * MF + 2] = 2.0f + random.nextFloat() * 4.5f;            // скорость всплытия
            motes[i * MF + 3] = 0.8f + random.nextFloat() * 1.2f;            // размер
            motes[i * MF + 4] = random.nextFloat() * 6.2831853f;             // фаза
        }
    }

    private void rescaleSky(int oldW, int oldH, int newW, int newH) {
        float kx = newW / (float) oldW;
        float ky = newH / (float) oldH;
        for (int i = 0; i < STARS; i++) {
            stars[i * SF + 0] *= kx;
            stars[i * SF + 1] *= ky;
        }
        for (int i = 0; i < MOTES; i++) {
            motes[i * MF + 0] *= kx;
            motes[i * MF + 1] *= ky;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Фон: чёрный градиент + звёзды + сияние + пыль                     */
    /* ------------------------------------------------------------------ */

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        this.mouseX = mx;
        this.mouseY = my;
        float elapsed = (FrameClock.now() - bornAt) / 1000.0f;
        float t = FrameClock.seconds();
        boolean anim = theme.animations;
        // dens: фон проявляется первые ~1.8 с (или мгновенно без анимаций)
        float dens = anim ? Animation.smooth(clamp01(elapsed / 1.8f)) : 1f;
        if (dens <= 0.01f) {
            g.fillGradient(0, 0, this.width, this.height, 0xFF05060A, 0xFF05060A);
            return;
        }

        // параллакс: инерционное следование за курсором
        parallaxX.to((mx - this.width / 2f) * (anim ? 1f : 0f));
        parallaxY.to((my - this.height / 2f) * (anim ? 1f : 0f));
        float px = parallaxX.update();
        float py = parallaxY.update();

        int w = this.width;
        int h = this.height;
        int accent = theme.accentNow();
        int accent2 = theme.accent(1.0f);

        // ===== глубокий чёрный градиент (стиль «Полночь») =====
        g.fillGradient(0, 0, w, h / 2,
                ColorUtil.rgb(4, 5, 9), ColorUtil.rgb(8, 10, 16));
        g.fillGradient(0, h / 2, w, h,
                ColorUtil.rgb(8, 10, 16), ColorUtil.rgb(3, 4, 6));

        // ===== дыхание света за иконкой — рисуем ПЕРВЫМ, под звёздами и
        // лентами: свечение — лишь намёк, фон обязан оставаться чёрным
        float pulse = 0.72f + 0.28f * (float) Math.sin(t * 0.7f);
        GfxEngine.radial(g, w / 2f, h * 0.30f, Math.max(w, h) * 0.40f,
                ColorUtil.alpha(accent, (int) (15 * pulse * dens)),
                ColorUtil.alpha(accent, 0));

        // ===== тёплое зарево у нижней кромки — едва заметное.
        // ФИКС: раньше ядро было полностью непрозрачным (ColorUtil.lerp двух
        // opaque-цветов давал alpha 255) и рисовал гигантский серый овал
        // поверх звёзд и лент. Теперь — полупрозрачный цвет, под всем
        // остальным, и радиус меньше, чтобы край не выходил за экран.
        GfxEngine.radial(g, w / 2f, h * 1.18f, h * 1.1f,
                ColorUtil.alpha(ColorUtil.lerp(ColorUtil.rgb(13, 15, 26), accent, 0.16f),
                        (int) (26 * dens)),
                ColorUtil.alpha(ColorUtil.rgb(3, 4, 6), 0));

        // ===== звёзды: мерцание + параллакс дальнего слоя =====
        for (int i = 0; i < STARS; i++) {
            float sx = stars[i * SF + 0] + px * 0.014f;
            float sy = stars[i * SF + 1] + py * 0.008f;
            float size = stars[i * SF + 2];
            float phase = stars[i * SF + 3];
            float twinkle = 0.35f + 0.65f * (float) Math.abs(Math.sin(t * 0.7f + phase * 2.3f));
            int a = (int) (150 * twinkle * dens);
            if (a <= 1) {
                continue;
            }
            GfxEngine.circle(g, sx, sy, size, ColorUtil.rgba(235, 238, 250, a));
        }

        // ===== северное сияние: три слоя мягких лент из светящихся пятен =====
        for (float[] rb : RIBBONS) {
            drawRibbon(g, rb, t, dens, px, py, accent, accent2, w);
        }

        // ===== тонкая дуга-«стрежень» поверх лент — графичная деталь =====
        drawAuroraArc(g, t, dens, px, accent, w, h);

        // ===== пылинки =====
        for (int i = 0; i < MOTES; i++) {
            float x0 = motes[i * MF + 0];
            float speed = motes[i * MF + 2];
            float size = motes[i * MF + 3];
            float phase = motes[i * MF + 4];
            float span = h + 16;
            float y = wrap(motes[i * MF + 1] - t * speed, -8, span);
            float x = x0 + (float) Math.sin(t * 0.35f + phase) * 7f + px * 0.03f;
            float twinkle = 0.4f + 0.6f * (float) Math.abs(Math.sin(t * 0.8f + phase * 1.9f));
            GfxEngine.circle(g, x, y, size,
                    ColorUtil.rgba(255, 255, 255, (int) (60 * twinkle * dens)));
        }

        // виньетка — мягко уводит углы в черноту
        RenderUtil.vignette(g, w, h, ColorUtil.rgba(0, 0, 0, 46));
    }

    /** Один слой сияния: ряд мягких радиальных пятен по синусоиде. */
    private void drawRibbon(GuiGraphics g, float[] rb, float t, float dens,
                            float px, float py, int accent, int accent2, int w) {
        float baseYF = rb[0];
        float amp = rb[1];
        float freq = rb[2];
        float rad = rb[3];
        float alpha = rb[4];
        float par = rb[5];

        for (float x = -rad; x <= w + rad; x += RIBBON_STEP) {
            float f = x / Math.max(1f, w);
            float y = this.height * baseYF
                    + (float) Math.sin(t * freq + x * 0.013f) * amp
                    + (float) Math.sin(t * freq * 0.53f + x * 0.029f + 1.7f) * amp * 0.45f
                    + py * par;
            float breathe = 0.75f + 0.25f * (float) Math.sin(t * 0.4f + x * 0.021f);
            int col = ColorUtil.lerp(accent, accent2, f);
            int a = (int) (alpha * breathe * dens);
            if (a <= 1) {
                continue;
            }
            GfxEngine.radial(g, x + px * par * 1.6f, y, rad * 2.4f,
                    ColorUtil.alpha(col, a), ColorUtil.alpha(col, 0));
        }
    }

    /** Тонкая светлая дуга поверх лент — «стрежень» сияния. */
    private void drawAuroraArc(GuiGraphics g, float t, float dens, float px, int accent, int w, int h) {
        int n = 32;
        if (arcBuf.length < (n + 1) * 2) {
            return;
        }
        for (int i = 0; i <= n; i++) {
            float f = i / (float) n;
            float x = f * w;
            float y = h * 0.14f
                    + (float) Math.sin(t * 0.09f + f * 4.4f) * h * 0.035f
                    + (float) Math.sin(t * 0.05f + f * 9.1f) * h * 0.016f;
            arcBuf[i * 2] = x + px * 0.02f;
            arcBuf[i * 2 + 1] = y;
        }
        GfxEngine.pathStroke(g, arcBuf, n + 1,
                ColorUtil.alpha(ColorUtil.lerp(accent, ColorUtil.rgb(255, 255, 255), 0.45f),
                        (int) (44 * dens)), 1.2f, false);
    }

    /** Циклическое значение в диапазоне [min, max). */
    private static float wrap(float v, float min, float max) {
        float range = max - min;
        if (range <= 0) {
            return min;
        }
        float f = (v - min) % range;
        if (f < 0) {
            f += range;
        }
        return min + f;
    }

    /* ------------------------------------------------------------------ */
    /*  Рендер                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        hits.clear();
        Minecraft mc = Minecraft.getInstance();
        float elapsed = (FrameClock.now() - bornAt) / 1000.0f;
        float t = FrameClock.seconds();
        boolean anim = theme.animations;

        for (Animation a : hoverAnims) {
            a.update();
        }
        for (Animation a : pressAnims) {
            a.update();
        }

        boolean compact = this.height < 300;
        int w = this.width;
        int h = this.height;

        // ===== иконка «Полярная звезда»: всплытие + пульс свечения =====
        int iconSize = compact ? 52 : 76;
        float it = anim ? Animation.easeOutBack(clamp01((elapsed - 0.05f) / 0.75f)) : 1f;
        float iconCy = h * 0.255f + (1 - it) * -24;
        float bob = anim ? (float) Math.sin(t * 0.75f) * 2.0f : 0f;
        float iconAlpha = clamp01(it);

        float gpulse = 0.62f + 0.38f * (float) Math.sin(t * 0.9f);
        g.pose().pushMatrix();
        float isc = anim ? 0.84f + 0.16f * it : 1f;
        g.pose().translate(w / 2f, iconCy + bob);
        g.pose().scale(isc, isc);
        g.pose().translate(-w / 2f, -(iconCy + bob));
        IconPainter.drawAnimated(g, w / 2f, iconCy + bob, iconSize,
                (int) (92 * gpulse * iconAlpha));
        g.pose().popMatrix();

        // ===== вордмарк AURORA: межбуквенный разряд + перелив =====
        float wmT = anim ? Animation.smooth(clamp01((elapsed - 0.3f) / 0.6f)) : 1f;
        float wmSize = compact ? 13.5f : 19f;
        float wmSpacing = wmSize * 0.46f;
        float wmY = iconCy + iconSize / 2f + (compact ? 8 : 11) + (1 - wmT) * 9;
        if (wmT > 0.01f) {
            drawWordmark(g, Theme.CLIENT_NAME.toUpperCase(), w / 2f, wmY, wmSize, wmSpacing,
                    (int) (255 * wmT), t);
        }

        // ===== приветствие: «С возвращением, <ник>» =====
        float ht = anim ? Animation.smooth(clamp01((elapsed - 0.45f) / 0.55f)) : 1f;
        float headSize = compact ? 10f : 13f;
        String prefix = "С возвращением, ";
        float pw = RenderUtil.widthSize(prefix, headSize, false);
        float nw = RenderUtil.widthSize(nick, headSize, true);
        float headY = wmY + wmSize * 1.25f + (compact ? 2 : 4) + (1 - ht) * 8;
        if (ht > 0.01f) {
            float hx = w / 2f - (pw + nw) / 2f;
            RenderUtil.drawTextSize(g, font, prefix, hx, headY, headSize,
                    ColorUtil.alpha(theme.textPrimary(), (int) (235 * ht)), false, theme);
            RenderUtil.drawTextSize(g, font, nick, hx + pw, headY, headSize,
                    ColorUtil.alpha(theme.accentNow(), (int) (255 * ht)), true, theme);
        }

        // ===== подпись с версией =====
        float st = anim ? Animation.smooth(clamp01((elapsed - 0.55f) / 0.5f)) : 1f;
        float subY = headY + headSize + (compact ? 3 : 5);
        if (st > 0.01f) {
            RenderUtil.drawCenteredText(g, font, versionLine, w / 2f, subY,
                    ColorUtil.alpha(theme.textSecondary(), (int) (190 * st)), theme);
        }

        // ===== кнопки =====
        int row1H = compact ? 24 : 34;
        int row2H = compact ? 20 : 28;
        int row1W = compact ? 168 : 220;
        int row2W = compact ? 110 : 144;
        int gap = compact ? 6 : 10;
        int row1Y = Math.round(subY + (compact ? 12 : 20));
        int row2Y = row1Y + row1H + gap + 2;

        int bx = w / 2 - row1W - gap / 2;
        button(g, 0, bx, row1Y, row1W, row1H, elapsed, anim);
        button(g, 1, bx + row1W + gap, row1Y, row1W, row1H, elapsed, anim);

        int row2Total = row2W * 3 + gap * 2;
        bx = w / 2 - row2Total / 2;
        for (int i = 2; i < 5; i++) {
            button(g, i, bx, row2Y, row2W, row2H, elapsed, anim);
            bx += row2W + gap;
        }

        // ===== мелкие кнопки справа снизу =====
        float ft = anim ? Animation.smooth(clamp01((elapsed - 0.85f) / 0.55f)) : 1f;
        int sbY = h - (compact ? 22 : 34);
        int sbX = w - 10;
        sbX = smallButton(g, sbX, sbY, "Спец. возможности", MAIN_BTNS, ft, () ->
                mc.setScreen(new AccessibilityOptionsScreen(this, mc.options)));
        smallButton(g, sbX, sbY, "Язык", MAIN_BTNS + 1, ft, () ->
                mc.setScreen(new LanguageSelectScreen(this, mc.options, mc.getLanguageManager())));

        // ===== подписи по углам =====
        if (ft > 0.01f) {
            if (compact) {
                RenderUtil.drawText(g, font, "Aurora " + Theme.CLIENT_VERSION + " · " + mc.getFps() + " fps",
                        8, h - 12, ColorUtil.alpha(theme.textSecondary(), (int) (200 * ft)), theme);
            } else {
                RenderUtil.drawText(g, font, versionLine, 8, h - 12,
                        ColorUtil.alpha(theme.textSecondary(), (int) (210 * ft)), theme);
                // fps обновляем не чаще 2 раз в секунду
                long now = FrameClock.now();
                if (now - fpsLineAt > 500) {
                    fpsLineAt = now;
                    fpsLine = mc.getFps() + " fps";
                }
                RenderUtil.drawText(g, font, fpsLine,
                        w - 10 - RenderUtil.width(font, fpsLine, theme), h - 12,
                        ColorUtil.alpha(theme.textSecondary(), (int) (210 * ft)), theme);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Вордмарк                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * «AURORA» с межбуквенным разрядом: одна общая тень на строку,
     * затем буквы с плавным переливом белого → акцент. Шиммер бежит
     * по буквам медленной волной — марка «живая», но не мигает резко.
     */
    private void drawWordmark(GuiGraphics g, String s, float cx, float y, float size,
                              float spacing, int alpha, float t) {
        int len = s.length();
        if (len <= 0) {
            return;
        }
        float[] widths = new float[len];
        float total = 0;
        for (int i = 0; i < len; i++) {
            widths[i] = GfxEngine.textWidth(s.substring(i, i + 1), size, true) + spacing;
            total += widths[i];
        }
        total -= spacing;
        float x = cx - total / 2f;
        float baseline = y + size * 0.78f;

        // одна общая тень на всю строку (тень по буквам «слипается»)
        GfxEngine.drawText(g, x + 1, baseline + 1, s, size,
                ColorUtil.alpha(0x000000, (int) (alpha * 0.55f)), true);

        int accent = theme.accentNow();
        int accent2 = theme.accent(1.0f);
        for (int i = 0; i < len; i++) {
            String ch = s.substring(i, i + 1);
            float f = len <= 1 ? 0 : i / (float) (len - 1);
            float shimmer = 0.5f + 0.5f * (float) Math.sin(t * 1.05f - i * 0.5f);
            int hue = ColorUtil.lerp(accent, accent2, f);
            int col = ColorUtil.lerp(ColorUtil.rgb(244, 245, 250), hue, 0.22f + 0.34f * shimmer);
            GfxEngine.drawText(g, x, baseline, ch, size, ColorUtil.alpha(col, alpha), true);
            x += widths[i];
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Кнопки                                                            */
    /* ------------------------------------------------------------------ */

    private void button(GuiGraphics g, int index, int x, int y, int w, int h, float elapsed, boolean anim) {
        // каскад: каждая кнопка появляется чуть позже предыдущей
        float bt = anim ? Animation.easeOutCubic(clamp01((elapsed - 0.4f - index * 0.08f) / 0.5f)) : 1f;
        if (bt <= 0.01f) {
            return;
        }
        float by = y + (1 - bt) * 14;

        Animation hover = hoverAnims[index];
        boolean inside = bt > 0.85f && mouseX >= x && mouseX <= x + w && mouseY >= by && mouseY <= by + h;
        hover.to(inside ? 1 : 0);
        float hv = hover.get();
        float press = pressAnims[index].get();

        boolean primary = index == 0; // «Одиночная игра» — акцентная

        // мягкое свечение за кнопкой при наведении
        if (hv > 0.02f) {
            GfxEngine.dropShadow(g, x - 1, by - 1, w + 2, h + 2, h / 2f, 1.5f, 9f,
                    ColorUtil.alpha(theme.accentNow(), (int) ((primary ? 70 : 52) * hv * bt)));
        }

        // масштаб: наведение чуть увеличивает, нажатие — мягко сжимает
        float sc = (1f + 0.026f * hv) * (1f - 0.035f * press);
        g.pose().pushMatrix();
        g.pose().translate(x + w / 2f, by + h / 2f);
        g.pose().scale(sc, sc);
        g.pose().translate(-(x + w / 2f), -(by + h / 2f));

        if (primary) {
            // primary: фирменный градиент акцента на чёрном — главный акцент экрана
            int gradTop = ColorUtil.lerp(theme.accent(0.05f), theme.accent(0.3f), hv);
            int gradBottom = ColorUtil.lerp(theme.accent(0.95f), theme.accent(0.7f), hv);
            GfxEngine.roundedGradient(g, x, by, w, h, h / 3.2f,
                    ColorUtil.alpha(gradTop, (int) (240 * bt)),
                    ColorUtil.alpha(gradBottom, (int) (240 * bt)));
            GfxEngine.outline(g, x, by, w, h, h / 3.2f,
                    ColorUtil.rgba(255, 255, 255, (int) ((52 + 42 * hv) * bt)), 1f);
            RenderUtil.drawCenteredText(g, font, BT[index], x + w / 2f,
                    by + h / 2f - RenderUtil.fontHeight(font) / 2f + 1,
                    ColorUtil.alpha(ColorUtil.rgb(255, 255, 255), (int) (255 * bt)), theme);
        } else {
            // вторичные: тёмное стекло — глубже ванильных, читается на черноте
            int fill = ColorUtil.lerp(ColorUtil.rgba(17, 19, 30, 226),
                    ColorUtil.rgba(33, 37, 56, 240), hv);
            GfxEngine.rounded(g, x, by, w, h, h / 3.2f,
                    ColorUtil.alpha(fill, (int) (ColorUtil.alphaI(fill) * bt)));
            int outline = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 30),
                    ColorUtil.alpha(theme.accentNow(), 200), hv);
            GfxEngine.outline(g, x, by, w, h, h / 3.2f,
                    ColorUtil.alpha(outline, (int) (ColorUtil.alphaI(outline) * bt)), 1f);
            // тонкая светлая кромка сверху — «стекло»
            GfxEngine.rounded(g, x + h / 3.2f + 2, by + 1, w - (h / 3.2f + 2) * 2, 1, 0.5f,
                    ColorUtil.rgba(255, 255, 255, (int) (24 * bt)));

            int textColor = ColorUtil.lerp(theme.textSecondary(), ColorUtil.rgb(255, 255, 255), hv);
            RenderUtil.drawCenteredText(g, font, BT[index], x + w / 2f,
                    by + h / 2f - RenderUtil.fontHeight(font) / 2f + 1,
                    ColorUtil.alpha(textColor, (int) (255 * bt)), theme);
        }
        g.pose().popMatrix();

        if (bt > 0.85f) {
            final int fby = Math.round(by);
            hits.add(new Hit(x, fby, w, h, () -> {
                pressAnims[index].snap(1);
                pressAnims[index].to(0);
                onButton(index);
            }));
        }
    }

    private void onButton(int index) {
        Minecraft mc = Minecraft.getInstance();
        AuroraClickGui.playClick();
        switch (index) {
            case 0 -> mc.setScreen(new SelectWorldScreen(this));
            case 1 -> mc.setScreen(new JoinMultiplayerScreen(this));
            case 2 -> mc.setScreen(new OptionsScreen(this, mc.options));
            case 3 -> mc.setScreen(new AuroraClickGui());
            default -> mc.stop();
        }
    }

    /** Мелкая кнопка-пилюля. Возвращает x для следующей (левее). */
    private int smallButton(GuiGraphics g, int rightX, int y, String label, int animIndex, float appearT, Runnable action) {
        int tw = RenderUtil.width(font, label, theme) + 18;
        int bx = rightX - tw;
        if (appearT > 0.01f) {
            Animation hover = hoverAnims[animIndex];
            boolean inside = mouseX >= bx && mouseX <= rightX && mouseY >= y && mouseY <= y + 20;
            hover.to(inside ? 1 : 0);
            float hv = hover.get();

            int fill = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 10),
                    ColorUtil.alpha(theme.accentNow(), 72), hv);
            GfxEngine.rounded(g, bx, y, tw, 20, 10,
                    ColorUtil.alpha(fill, (int) (ColorUtil.alphaI(fill) * appearT)));
            int outline = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 18),
                    ColorUtil.alpha(theme.accentNow(), 125), hv);
            GfxEngine.outline(g, bx, y, tw, 20, 10,
                    ColorUtil.alpha(outline, (int) (ColorUtil.alphaI(outline) * appearT)), 1f);
            int col = ColorUtil.lerp(theme.textSecondary(), ColorUtil.rgb(255, 255, 255), hv);
            RenderUtil.drawText(g, font, label, bx + 9, y + 10 - RenderUtil.fontHeight(font) / 2f + 1,
                    ColorUtil.alpha(col, (int) (235 * appearT)), theme);

            hits.add(new Hit(bx, y, tw, 20, action));
        }
        return bx - 8;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /* ------------------------------------------------------------------ */
    /*  Ввод                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        int mx = (int) e.x();
        int my = (int) e.y();
        if (e.button() == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (mx >= hit.x && mx <= hit.x + hit.w && my >= hit.y && my <= hit.y + hit.h) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    /** Левый Ctrl (или другая клавиша меню) открывает ClickGUI прямо из главного меню. */
    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == theme.menuKey) {
            AuroraClickGui.playClick();
            this.minecraft.setScreen(new AuroraClickGui());
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        AuroraConfig.save();
        super.onClose();
    }
}
