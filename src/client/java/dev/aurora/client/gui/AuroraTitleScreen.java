package dev.aurora.client.gui;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.config.AuroraConfig;
import dev.aurora.client.module.ModuleManager;
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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Главное меню Aurora — «Дым».
 *
 * <p>Глубокий градиент с живым многослойным дымом: три слоя мягких радиальных
 * пятен медленно всплывают, покачиваются и реагируют параллаксом на курсор.
 * Позиция каждой точки дыма — чистая функция времени кадра ({@link FrameClock}),
 * поэтому анимация идеально плавная при любой частоте кадров и без аллокаций.</p>
 *
 * <p>Центр композиции — бренд-иконка Aurora (процедурная «A» на фиолетовом
 * сквиркле, {@link IconPainter}): плавно всплывает при появлении, мягко
 * «дышит» свечением и чуть покачивается. Под ней крупное приветствие
 * «С возвращением, игрок» и кнопки: первая — акцентная (primary), остальные —
 * стекло. Экран затемнён по бокам (мягкие боковые градиенты), центр — светлее.</p>
 *
 * <p>Оптимизация: ни панорамы, ни полного экранного gaussian-блюра; параметры
 * дыма в плоских массивах, строки кэшируются, время читается один раз.</p>
 */
public class AuroraTitleScreen extends Screen {
    private static final Logger LOG = LoggerFactory.getLogger("Aurora/Title");
    private final Theme theme;

    /* ------------------------------------------------------------------ */
    /*  Дым: параметры в плоских массивах (нулевые аллокации в кадре)      */
    /* ------------------------------------------------------------------ */

    private static final int LAYER_FAR = 10;  // дальний дым: большой, медленный, тусклее
    private static final int LAYER_MID = 8;   // средний слой — основной объём дыма
    private static final int LAYER_NEAR = 8;  // ближний дым поверх контента (по углам)
    private static final int PUFFS = LAYER_FAR + LAYER_MID + LAYER_NEAR;

    /** Шаг в массиве puff: x, y, r, speed, swayAmp, swayFreq, phase, alpha. */
    private static final int PF = 8;
    private final float[] puff = new float[PUFFS * PF];

    /** Пылинки: x, y, speed, size, phase. */
    private static final int DUST = 26;
    private static final int DF = 5;
    private final float[] dust = new float[DUST * DF];

    /** Слой точки дыма: 0 — дальний, 1 — средний, 2 — ближний. */
    private static int layerOf(int i) {
        return i < LAYER_FAR ? 0 : (i < LAYER_FAR + LAYER_MID ? 1 : 2);
    }

    /* ------------------------------------------------------------------ */
    /*  Кнопки                                                            */
    /* ------------------------------------------------------------------ */

    private static final String[] BT = {
            "Одиночная игра", "Сетевая игра",
            "Настройки", "Меню Aurora", "Выход"
    };
    private static final int MAIN_BTNS = 5;

    /** Плавное наведение: 5 главных + 2 мелкие; нажатие — только главные. */
    private final Animation[] hoverAnims = new Animation[MAIN_BTNS + 2];
    private final Animation[] pressAnims = new Animation[MAIN_BTNS];
    /** Плавный параллакс дыма за курсором. */
    private final Animation parallaxX = new Animation(0, 4.5f);
    private final Animation parallaxY = new Animation(0, 4.5f);

    /** Момент рождения экрана — от него отсчитываются все каскады появления. */
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
        // init() вызывается и при смене размера окна — тогда только плавно
        // масштабируем уже существующий дым, чтобы картинка не «прыгала»
        if (lastW <= 0 || lastH <= 0 || puff[2] <= 0) {
            seedPuffs(this.width, this.height);
        } else if (this.width != lastW || this.height != lastH) {
            rescalePuffs(lastW, lastH, this.width, this.height);
        }
        lastW = this.width;
        lastH = this.height;

        Minecraft mc = Minecraft.getInstance();
        try {
            LOG.info("титул: gui {}x{}, окно {}x{}, scale {}",
                    this.width, this.height,
                    mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getWindow().getGuiScale());
        } catch (Exception ignored) {
        }
        if (versionLine == null) {
            try {
                nick = mc.getUser().getName();
            } catch (Exception ignored) {
            }
            versionLine = "Aurora " + Theme.CLIENT_VERSION + " · клиент для Minecraft " + Theme.MC_VERSION;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Генерация дыма                                                    */
    /* ------------------------------------------------------------------ */

    private void seedPuffs(int w, int h) {
        Random random = new Random(0xC0FFEEL);
        for (int i = 0; i < PUFFS; i++) {
            int layer = layerOf(i);
            float k = w / 640f;
            float base = layer == 0 ? 0.42f : (layer == 1 ? 0.26f : 0.18f);
            puff[i * PF + 0] = random.nextFloat() * w;                                  // x
            puff[i * PF + 1] = random.nextFloat() * h;                                  // y
            puff[i * PF + 2] = h * (base + random.nextFloat() * (base * 0.8f));          // радиус
            puff[i * PF + 3] = (layer == 0 ? 2.6f : layer == 1 ? 5.0f : 8.5f)
                    * (0.7f + random.nextFloat() * 0.6f);                               // скорость всплытия
            puff[i * PF + 4] = (12 + random.nextFloat() * 30) * k;                       // амплитуда покачивания
            puff[i * PF + 5] = 0.08f + random.nextFloat() * 0.12f;                       // частота покачивания
            puff[i * PF + 6] = random.nextFloat() * 6.2831853f;                          // фаза
            puff[i * PF + 7] = layer == 0 ? 52 + random.nextFloat() * 16
                    : layer == 1 ? 46 + random.nextFloat() * 15
                    : 18 + random.nextFloat() * 8;                                      // альфа
        }
        for (int i = 0; i < DUST; i++) {
            dust[i * DF + 0] = random.nextFloat() * w;
            dust[i * DF + 1] = random.nextFloat() * h;
            dust[i * DF + 2] = 2.5f + random.nextFloat() * 6f;
            dust[i * DF + 3] = 0.9f + random.nextFloat() * 1.3f;
            dust[i * DF + 4] = random.nextFloat() * 6.2831853f;
        }
    }

    private void rescalePuffs(int oldW, int oldH, int newW, int newH) {
        float kx = newW / (float) oldW;
        float ky = newH / (float) oldH;
        for (int i = 0; i < PUFFS; i++) {
            puff[i * PF + 0] *= kx;
            puff[i * PF + 1] *= ky;
            puff[i * PF + 2] *= ky;
            puff[i * PF + 4] *= kx;
        }
        for (int i = 0; i < DUST; i++) {
            dust[i * DF + 0] *= kx;
            dust[i * DF + 1] *= ky;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Фон: градиент + северное сияние + дым + пыль + затемнение боков    */
    /* ------------------------------------------------------------------ */

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        this.mouseX = mx;
        this.mouseY = my;
        float elapsed = (FrameClock.now() - bornAt) / 1000.0f;
        float t = FrameClock.seconds();
        // дым «проявляется» первые ~2.2 секунды
        float dens = Animation.smooth(elapsed / 2.2f);

        // параллакс дыма за курсором (плавный, инерционный)
        parallaxX.to(mx - this.width / 2f);
        parallaxY.to(my - this.height / 2f);
        float px = parallaxX.update();
        float py = parallaxY.update();

        int h = this.height;
        int w = this.width;

        // ===== градиент: центр светлее, края уйдут в затемнение =====
        g.fillGradient(0, 0, w, h / 2,
                ColorUtil.rgb(13, 15, 27), ColorUtil.rgb(21, 23, 41));
        g.fillGradient(0, h / 2, w, h,
                ColorUtil.rgb(21, 23, 41), ColorUtil.rgb(9, 10, 20));

        int accent = theme.accentNow();

        // ===== «северное сияние»: широкая медленная полоса над центром =====
        float bandX = w / 2f + (float) Math.sin(t * 0.05f) * w * 0.18f;
        int auroraCol = ColorUtil.lerp(ColorUtil.rgb(96, 108, 148), accent, 0.55f);
        GfxEngine.radial(g, bandX, h * 0.16f, Math.max(w, h) * 0.85f,
                ColorUtil.alpha(auroraCol, (int) (30 * dens)),
                ColorUtil.alpha(auroraCol, 0));

        // ===== световой колодец по центру (иконка будет «в свете») =====
        GfxEngine.radial(g, w / 2f, h * 0.30f, Math.max(w, h) * 0.62f,
                ColorUtil.alpha(ColorUtil.rgb(120, 130, 175), (int) (20 * dens)),
                ColorUtil.alpha(ColorUtil.rgb(120, 130, 175), 0));

        // ===== дым =====
        // цвета слоёв: холодный сине-серый с примесью акцента
        int slate = ColorUtil.rgb(96, 108, 148);
        int colFar = ColorUtil.lerp(slate, accent, 0.42f);
        int colMid = ColorUtil.lerp(slate, accent, 0.58f);

        for (int i = 0; i < PUFFS; i++) {
            if (layerOf(i) == 2) {
                continue; // ближний слой рисуется поверх контента
            }
            drawPuff(g, i, t, dens, px, py, colFar, colMid);
        }

        // ===== свечение снизу — «дышит» =====
        float pulse = 0.72f + 0.28f * (float) Math.sin(t * 0.6);
        GfxEngine.radial(g, w / 2f, h * 1.04f, h * 1.55f,
                ColorUtil.alpha(accent, (int) (52 * pulse * dens)),
                ColorUtil.alpha(accent, 0));

        // ===== пылинки =====
        for (int i = 0; i < DUST; i++) {
            float x0 = dust[i * DF + 0];
            float speed = dust[i * DF + 2];
            float size = dust[i * DF + 3];
            float phase = dust[i * DF + 4];
            float span = h + 20;
            float y = wrap(x0 - t * speed, -10, span);
            float x = x0 + (float) Math.sin(t * 0.4f + phase) * 6f;
            float twinkle = 0.45f + 0.55f * (float) Math.abs(Math.sin(t * 0.9f + phase * 2.1f));
            GfxEngine.circle(g, x, y, size,
                    ColorUtil.rgba(255, 255, 255, (int) (74 * twinkle * dens)));
        }

        // ===== затемнение по бокам (вместо сплошной чёрной подложки) =====
        float side = 0.34f; // доля ширины, которую занимает каждое затемнение
        GfxEngine.gradientH(g, 0, 0, w * side, h,
                ColorUtil.rgba(0, 0, 0, (int) (132 * dens)),
                ColorUtil.rgba(0, 0, 0, 0));
        GfxEngine.gradientH(g, w * (1f - side), 0, w * side, h,
                ColorUtil.rgba(0, 0, 0, 0),
                ColorUtil.rgba(0, 0, 0, (int) (132 * dens)));

        // мягкая общая виньетка — не глушит дым по углам
        RenderUtil.vignette(g, w, h, ColorUtil.rgba(0, 0, 0, 40));
    }

    /** Одна точка дыма: всплытие + покачивание + параллакс слоя. */
    private void drawPuff(GuiGraphics g, int i, float t, float dens, float px, float py, int colFar, int colMid) {
        int layer = layerOf(i);
        float x0 = puff[i * PF + 0];
        float y0 = puff[i * PF + 1];
        float r = puff[i * PF + 2];
        float speed = puff[i * PF + 3];
        float amp = puff[i * PF + 4];
        float freq = puff[i * PF + 5];
        float phase = puff[i * PF + 6];
        float alpha = puff[i * PF + 7];

        float span = this.height + r * 4;
        float y = wrap(y0 - t * speed, -r * 2, span);
        float x = x0 + (float) Math.sin(t * freq + phase) * amp;

        // параллакс: дальний слой почти не двигается, средний — заметнее
        float kx = layer == 0 ? 0.022f : 0.05f;
        float ky = layer == 0 ? 0.012f : 0.026f;
        x += px * kx;
        y += py * ky;

        // медленное «дыхание» плотности
        float breathe = 0.82f + 0.18f * (float) Math.sin(t * 0.33f + phase);
        int col = layer == 0 ? colFar : colMid;
        int core = ColorUtil.alpha(col, (int) (alpha * breathe * dens));
        if (ColorUtil.alphaI(core) <= 0) {
            return;
        }
        GfxEngine.radial(g, x, y, r * 2, core, ColorUtil.alpha(col, 0));
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

        // все анимации кадра обновляются ровно один раз — здесь
        for (Animation a : hoverAnims) {
            a.update();
        }
        for (Animation a : pressAnims) {
            a.update();
        }

        boolean compact = this.height < 300;
        int w = this.width;
        int h = this.height;

        // ===== бренд-иконка =====
        int iconSize = compact ? 44 : 64;
        float it = Animation.easeOutBack(clamp01((elapsed - 0.05f) / 0.75f));
        float iconCy = h * 0.26f + (1 - it) * -26;
        float bob = (float) Math.sin(t * 0.8f) * 2.2f; // мягкое покачивание
        float iconAlpha = clamp01(it);

        // дыхание свечения вокруг иконки
        float gpulse = 0.62f + 0.38f * (float) Math.sin(t * 0.9f);
        g.pose().pushMatrix();
        float isc = 0.82f + 0.18f * it;
        g.pose().translate(w / 2f, iconCy + bob);
        g.pose().scale(isc, isc);
        g.pose().translate(-w / 2f, -(iconCy + bob));
        IconPainter.draw(g, w / 2f, iconCy + bob, iconSize,
                (int) (72 * gpulse * iconAlpha));
        g.pose().popMatrix();

        // ===== приветствие: «С возвращением, <ник>» =====
        float ht = Animation.smooth(clamp01((elapsed - 0.35f) / 0.65f));
        float headSize = compact ? 10.5f : 14f;
        String prefix = "С возвращением, ";
        float pw = RenderUtil.widthSize(prefix, headSize, false);
        float nw = RenderUtil.widthSize(nick, headSize, true);
        float headY = iconCy + iconSize / 2f + 10 + (1 - ht) * 10;
        if (ht > 0.01f) {
            float hx = w / 2f - (pw + nw) / 2f;
            RenderUtil.drawTextSize(g, font, prefix, hx, headY, headSize,
                    ColorUtil.alpha(theme.textPrimary(), (int) (240 * ht)), false, theme);
            RenderUtil.drawTextSize(g, font, nick, hx + pw, headY, headSize,
                    ColorUtil.alpha(theme.accentNow(), (int) (255 * ht)), true, theme);
        }

        // ===== подпись с версией =====
        float st = Animation.smooth(clamp01((elapsed - 0.5f) / 0.6f));
        float subY = headY + headSize + 5;
        if (st > 0.01f) {
            RenderUtil.drawCenteredText(g, font, versionLine, w / 2f, subY,
                    ColorUtil.alpha(theme.textSecondary(), (int) (205 * st)), theme);
        }

        // ===== кнопки =====
        int row1H = compact ? 26 : 36;
        int row2H = compact ? 22 : 30;
        int row1W = compact ? 178 : 224;
        int row2W = compact ? 116 : 146;
        int gap = compact ? 6 : 10;
        int row1Y = Math.round(subY + (compact ? 13 : 22));
        int row2Y = row1Y + row1H + gap + 2;

        int bx = w / 2 - row1W - gap / 2;
        button(g, 0, bx, row1Y, row1W, row1H, elapsed);
        button(g, 1, bx + row1W + gap, row1Y, row1W, row1H, elapsed);

        int row2Total = row2W * 3 + gap * 2;
        bx = w / 2 - row2Total / 2;
        for (int i = 2; i < 5; i++) {
            button(g, i, bx, row2Y, row2W, row2H, elapsed);
            bx += row2W + gap;
        }

        // ===== ближний дым (поверх контента, по нижним углам, едва заметный) =====
        int accent = theme.accentNow();
        int slate = ColorUtil.rgb(96, 108, 148);
        int colNear = ColorUtil.lerp(slate, accent, 0.68f);
        float dens = Animation.smooth(elapsed / 2.2f);
        for (int i = 0; i < PUFFS; i++) {
            if (layerOf(i) != 2) {
                continue;
            }
            drawNearPuff(g, i, t, dens, colNear);
        }

        // ===== мелкие кнопки справа снизу =====
        float ft = Animation.smooth(clamp01((elapsed - 0.85f) / 0.6f));
        int sbY = this.height - (compact ? 22 : 34);
        int sbX = this.width - 10;
        sbX = smallButton(g, sbX, sbY, "Спец. возможности", MAIN_BTNS, ft, () ->
                mc.setScreen(new AccessibilityOptionsScreen(this, mc.options)));
        smallButton(g, sbX, sbY, "Язык", MAIN_BTNS + 1, ft, () ->
                mc.setScreen(new LanguageSelectScreen(this, mc.options, mc.getLanguageManager())));

        // ===== подписи по углам =====
        if (ft > 0.01f) {
            if (compact) {
                RenderUtil.drawText(g, font, "Aurora " + Theme.CLIENT_VERSION + " · " + mc.getFps() + " fps",
                        8, this.height - 12, ColorUtil.alpha(theme.textSecondary(), (int) (200 * ft)), theme);
            } else {
                RenderUtil.drawText(g, font, versionLine, 8, this.height - 12,
                        ColorUtil.alpha(theme.textSecondary(), (int) (215 * ft)), theme);
                // fps обновляем не чаще 2 раз в секунду
                long now = FrameClock.now();
                if (now - fpsLineAt > 500) {
                    fpsLineAt = now;
                    fpsLine = mc.getFps() + " fps";
                }
                RenderUtil.drawText(g, font, fpsLine,
                        this.width - 10 - RenderUtil.width(font, fpsLine, theme), this.height - 12,
                        ColorUtil.alpha(theme.textSecondary(), (int) (215 * ft)), theme);
            }
        }
    }

    /** Ближний дым: всплывает снизу вдоль углов, чтобы не мешать кнопкам. */
    private void drawNearPuff(GuiGraphics g, int i, float t, float dens, int colNear) {
        float x0 = puff[i * PF + 0];
        float y0 = puff[i * PF + 1];
        float r = puff[i * PF + 2];
        float speed = puff[i * PF + 3];
        float amp = puff[i * PF + 4];
        float freq = puff[i * PF + 5];
        float phase = puff[i * PF + 6];
        float alpha = puff[i * PF + 7];

        // прижимаем к нижней половине экрана
        float span = this.height + r * 4;
        float y = this.height * 0.55f + wrap(y0 - t * speed, 0, span * 0.6f);
        float x = x0 + (float) Math.sin(t * freq + phase) * amp + parallaxX.get() * 0.09f;
        float breathe = 0.8f + 0.2f * (float) Math.sin(t * 0.4f + phase);
        int core = ColorUtil.alpha(colNear, (int) (alpha * breathe * dens));
        if (ColorUtil.alphaI(core) > 0) {
            GfxEngine.radial(g, x, y, r * 2, core, ColorUtil.alpha(colNear, 0));
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Кнопки                                                            */
    /* ------------------------------------------------------------------ */

    private void button(GuiGraphics g, int index, int x, int y, int w, int h, float elapsed) {
        // каскад: каждая кнопка появляется чуть позже предыдущей
        float bt = Animation.easeOutCubic(clamp01((elapsed - 0.3f - index * 0.08f) / 0.5f));
        if (bt <= 0.01f) {
            return;
        }
        float by = y + (1 - bt) * 16;

        Animation hover = hoverAnims[index];
        boolean inside = bt > 0.85f && mouseX >= x && mouseX <= x + w && mouseY >= by && mouseY <= by + h;
        hover.to(inside ? 1 : 0);
        float hv = hover.get();
        float press = pressAnims[index].get();

        boolean primary = index == 0; // «Одиночная игра» — акцентная

        // мягкое свечение за кнопкой при наведении
        if (hv > 0.02f) {
            GfxEngine.dropShadow(g, x - 1, by - 1, w + 2, h + 2, h / 2f, 1.5f, 9f,
                    ColorUtil.alpha(theme.accentNow(), (int) ((primary ? 76 : 58) * hv * bt)));
        }

        // масштаб: наведение чуть увеличивает, нажатие — мягко сжимает
        float sc = (1f + 0.028f * hv) * (1f - 0.035f * press);
        g.pose().pushMatrix();
        g.pose().translate(x + w / 2f, by + h / 2f);
        g.pose().scale(sc, sc);
        g.pose().translate(-(x + w / 2f), -(by + h / 2f));

        if (primary) {
            // primary: фирменный градиент акцента + белая обводка
            int gradTop = ColorUtil.lerp(theme.accent(0.05f), theme.accent(0.3f), hv);
            int gradBottom = ColorUtil.lerp(theme.accent(0.95f), theme.accent(0.7f), hv);
            GfxEngine.roundedGradient(g, x, by, w, h, h / 3f,
                    ColorUtil.alpha(gradTop, (int) (238 * bt)),
                    ColorUtil.alpha(gradBottom, (int) (238 * bt)));
            GfxEngine.outline(g, x, by, w, h, h / 3f,
                    ColorUtil.rgba(255, 255, 255, (int) ((58 + 40 * hv) * bt)), 1f);
            int textColor = ColorUtil.rgb(255, 255, 255);
            RenderUtil.drawCenteredText(g, font, BT[index], x + w / 2f,
                    by + h / 2f - RenderUtil.fontHeight(font) / 2f + 1,
                    ColorUtil.alpha(textColor, (int) (255 * bt)), theme);
        } else {
            int fill = ColorUtil.lerp(ColorUtil.rgba(27, 30, 46, 208), ColorUtil.rgba(56, 61, 92, 242), hv);
            GfxEngine.rounded(g, x, by, w, h, h / 3f,
                    ColorUtil.alpha(fill, (int) (ColorUtil.alphaI(fill) * bt)));
            int outline = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 34),
                    ColorUtil.alpha(theme.accentNow(), 210), hv);
            GfxEngine.outline(g, x, by, w, h, h / 3f,
                    ColorUtil.alpha(outline, (int) (ColorUtil.alphaI(outline) * bt)), 1f);

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
                    ColorUtil.alpha(theme.accentNow(), 76), hv);
            GfxEngine.rounded(g, bx, y, tw, 20, 10,
                    ColorUtil.alpha(fill, (int) (ColorUtil.alphaI(fill) * appearT)));
            int outline = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 16),
                    ColorUtil.alpha(theme.accentNow(), 130), hv);
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
