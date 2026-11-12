package dev.aurora.client.gui;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.config.AuroraConfig;
import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.GfxEngine;
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

/**
 * Главное меню Aurora: размытая панорама с виньеткой и «северным сиянием»,
 * по центру стеклянная карточка с крупным градиентным логотипом и
 * скруглёнными кнопками с плавным наведением.
 *
 * <p>Вёрстка адаптивная и рассчитана на реальные gui-высоты: на типичном
 * окне 854x480 (gui 427x240) карточка остаётся с логотипом, подписью и
 * полноразмерными кнопками — «компактный» режим включается только при
 * действительно малой высоте.</p>
 */
public class AuroraTitleScreen extends Screen {
    private final Theme theme;
    private final List<Particle> particles = new ArrayList<>();
    private final Animation appear = new Animation(0, 6);
    private final List<Hit> hits = new ArrayList<>();
    private final List<Animation> hoverAnims = new ArrayList<>();
    private int mouseX, mouseY;

    private static final String[] BUTTONS = {
            "Одиночная игра",
            "Сетевая игра",
            "Настройки",
            "Настройки Aurora",
            "Выход из игры"
    };

    private static class Particle {
        float x, y, speed, size, phase, drift;

        Particle(Random random, int w, int h) {
            x = random.nextFloat() * w;
            y = random.nextFloat() * h;
            speed = 6 + random.nextFloat() * 14;
            size = 1 + random.nextFloat() * 1.8f;
            phase = random.nextFloat() * 6.28f;
            drift = (random.nextFloat() - 0.5f) * 6;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    public AuroraTitleScreen() {
        super(Component.literal("Aurora"));
        this.theme = AuroraClient.theme();
    }

    @Override
    protected void init() {
        particles.clear();
        Random random = new Random();
        int count = this.height < 210 ? 24 : 46;
        for (int i = 0; i < count; i++) {
            particles.add(new Particle(random, this.width, this.height));
        }
        if (hoverAnims.isEmpty()) {
            for (int i = 0; i < BUTTONS.length + 2; i++) {
                hoverAnims.add(new Animation(0, 11));
            }
        }
        appear.snap(appear.get() < 1 ? 0 : 1);
        appear.to(1);
    }

    /* ------------------------------------------------------------------ */
    /*  Фон: панорама + gaussian-блюр + аврора + виньетка                 */
    /* ------------------------------------------------------------------ */

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // вращающаяся панорама мира
        try {
            renderPanorama(g, delta);
        } catch (Exception ignored) {
            g.fill(0, 0, this.width, this.height, ColorUtil.rgb(10, 11, 20));
        }
        // настоящее размытие фона (гауссово) — ровно один вызов за кадр
        try {
            renderBlurredBackground(g);
        } catch (IllegalStateException ignored) {
        }

        // тёмный градиент поверх — мягче, чтобы панорама просвечивала сквозь стекло
        g.fillGradient(0, 0, this.width, this.height,
                ColorUtil.rgba(9, 10, 17, 95), ColorUtil.rgba(8, 9, 14, 135));

        // мягкие «ленты северного сияния» — большие мягкие пятна света
        long time = System.currentTimeMillis();
        float appearT = Animation.smooth(appear.get());
        for (int i = 0; i < 3; i++) {
            float gx = this.width * (0.22f + 0.28f * i)
                    + (float) Math.sin(time / (9000.0 + 4000 * i) + i * 2.1) * this.width * 0.16f;
            float gy = this.height * (0.30f + 0.20f * ((i + 1) % 3))
                    + (float) Math.cos(time / (11000.0 + 3000 * i) + i) * this.height * 0.10f;
            int rw = this.width / 2;
            int rh = this.height / 2;
            int alpha = (int) (26 * appearT);
            RenderUtil.glow(g, gx - rw / 2f, gy - rh / 2f, rw, rh,
                    ColorUtil.alpha(theme.accent((i + 0.5f) / 3f), alpha));
        }

        // частицы-светлячки: мягкий ореол + яркое ядро
        int pAlpha = (int) (255 * appearT);
        for (Particle p : particles) {
            float px = p.x + (float) Math.sin(time / 2000.0 + p.phase) * 8 + p.drift * (time % 10000) / 10000.0f;
            float py = p.y - (time / 1000.0f * p.speed) % (this.height + 40);
            if (py < -10) {
                py += this.height + 40;
            }
            float twinkle = 0.4f + 0.6f * (float) Math.abs(Math.sin(time / 700.0 + p.phase));
            float d = Math.max(1.6f, p.size * 1.6f);
            int tw = (int) (70 * twinkle * pAlpha / 255f);
            GfxEngine.circle(g, px, py, d * 2.6f, ColorUtil.rgba(255, 255, 255, tw / 3));
            GfxEngine.circle(g, px, py, d, ColorUtil.rgba(255, 255, 255, tw));
        }

        // виньетка
        RenderUtil.vignette(g, this.width, this.height, ColorUtil.rgba(0, 0, 0, 125));
    }

    /* ------------------------------------------------------------------ */
    /*  Рендер                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.mouseX = mx;
        this.mouseY = my;
        hits.clear();
        float t = Animation.smooth(appear.update());

        Minecraft mc = Minecraft.getInstance();

        // ===== адаптивная вёрстка =====
        boolean tiny = this.height < 210;
        boolean full = !tiny && this.height >= 310;
        int logoScale = tiny ? 2 : full ? 3 : 2;
        int bh = tiny ? 20 : full ? 30 : 24;
        int gap = tiny ? 3 : full ? 7 : 4;
        int bw = Math.min(260, this.width - 64);
        int pad = tiny ? 10 : full ? 24 : 16;

        // ===== размеры стеклянной карточки =====
        int logoW = RenderUtil.widthBold(font, Theme.CLIENT_NAME, theme) * logoScale;
        int logoH = RenderUtil.fontHeight(font) * logoScale;
        int stackH = BUTTONS.length * bh + (BUTTONS.length - 1) * gap;
        int subH = tiny ? 0 : full ? 26 : 12;
        int cardH = pad + logoH + 8 + subH + (tiny ? 8 : 12) + stackH + pad - 4;
        int cardW = bw + pad * 2;
        int cardX = this.width / 2 - cardW / 2;
        int cardY = Math.max(8, this.height / 2 - cardH / 2);
        cardH = Math.min(cardH, this.height - 16);

        // ===== фаза 1: формы =====
        // плавное появление карточки (масштаб вокруг центра)
        g.pose().pushMatrix();
        float sc = theme.animations ? 0.97f + 0.03f * t : 1f;
        float ccx = this.width / 2f;
        float ccy = this.height / 2f;
        g.pose().translate(ccx, ccy);
        g.pose().scale(sc, sc);
        g.pose().translate(-ccx, -ccy);

        RenderUtil.softShadow(g, cardX, cardY, cardW, cardH, 18, 150);
        // стеклянная карточка: база + светлый градиент-блик + обводка
        GfxEngine.rounded(g, cardX, cardY, cardW, cardH, 18,
                ColorUtil.alpha(theme.panelColor(), (int) (ColorUtil.alphaI(theme.panelColor()) * t)));
        GfxEngine.roundedGradient(g, cardX, cardY, cardW, cardH, 18,
                ColorUtil.rgba(255, 255, 255, (int) (26 * t)), ColorUtil.rgba(255, 255, 255, (int) (4 * t)));
        GfxEngine.outline(g, cardX, cardY, cardW, cardH, 18, ColorUtil.rgba(255, 255, 255, (int) (44 * t)), 1f);
        GfxEngine.rounded(g, cardX + 20, cardY + 1, cardW - 40, 1, 0.5f, ColorUtil.rgba(255, 255, 255, (int) (70 * t)));

        // логотип и линия-акцент под ним
        int lx = this.width / 2 - logoW / 2 - (int) ((1 - t) * 14);
        int ly = cardY + pad;
        RenderUtil.glow(g, lx - logoW / 2f, ly - logoH / 2f - 2, logoW * 2.4f, logoH * 2.4f,
                ColorUtil.alpha(theme.accentNow(), (int) (46 * t)));
        float lineW = Math.max(24, logoW * 0.36f);
        GfxEngine.rounded(g, this.width / 2f - lineW / 2, ly + logoH + 3.5f, lineW, 1.5f, 0.75f,
                ColorUtil.alpha(theme.accentNow(), (int) (190 * t)));

        // кнопки (формы)
        int by = cardY + cardH - pad - stackH;
        int bx = this.width / 2 - bw / 2;
        for (int i = 0; i < BUTTONS.length; i++) {
            by = drawButtonShape(g, bx, by, bw, bh, i, t, gap);
        }

        // нижний ряд мелких кнопок (формы)
        int sbY = this.height - (tiny ? 20 : 32);
        int sbX = this.width - 10;
        sbX = smallButtonShape(g, sbX, sbY, "Спец. возможности", BUTTONS.length, () ->
                mc.setScreen(new AccessibilityOptionsScreen(this, mc.options)));
        smallButtonShape(g, sbX, sbY, "Язык", BUTTONS.length + 1, () ->
                mc.setScreen(new LanguageSelectScreen(this, mc.options, mc.getLanguageManager())));

        g.pose().popMatrix();

        g.nextStratum();

        // ===== фаза 2: текст =====
        g.pose().pushMatrix();
        g.pose().translate(ccx, ccy);
        g.pose().scale(sc, sc);
        g.pose().translate(-ccx, -ccy);

        // градиентный логотип по символам
        g.pose().pushMatrix();
        g.pose().translate(lx, ly);
        g.pose().scale(logoScale, logoScale);
        RenderUtil.drawGradientText(g, font, Theme.CLIENT_NAME, 0, 0, theme.accentNow(), theme.accent(1.0f), theme);
        g.pose().popMatrix();

        int textY = ly + logoH + 8;
        if (full) {
            RenderUtil.drawCenteredText(g, font, "клиент для Minecraft " + Theme.MC_VERSION,
                    this.width / 2f, textY, ColorUtil.alpha(theme.textSecondary(), (int) (220 * t)), theme);
            textY += 12;
            RenderUtil.drawCenteredText(g, font, "С возвращением, " + mc.getUser().getName(),
                    this.width / 2f, textY, ColorUtil.alpha(theme.accentNow(), (int) (255 * t)), theme);
        } else if (!tiny) {
            RenderUtil.drawCenteredText(g, font, "С возвращением, " + mc.getUser().getName(),
                    this.width / 2f, textY, ColorUtil.alpha(theme.textSecondary(), (int) (220 * t)), theme);
        }

        // подписи кнопок
        int ty = cardY + cardH - pad - stackH;
        int tx = this.width / 2 - bw / 2;
        for (int i = 0; i < BUTTONS.length; i++) {
            ty = drawButtonText(g, tx, ty, bw, bh, i, t, gap);
        }

        // мелкие кнопки: подписи
        int stx = this.width - 10;
        stx = smallButtonText(g, stx, sbY, "Спец. возможности", BUTTONS.length);
        smallButtonText(g, stx, sbY, "Язык", BUTTONS.length + 1);

        g.pose().popMatrix();

        // ===== подписи по углам =====
        if (tiny) {
            String line = "Aurora " + Theme.CLIENT_VERSION + " · " + mc.getFps() + " fps";
            RenderUtil.drawText(g, font, line, 8, this.height - 12, theme.textSecondary(), theme);
        } else {
            RenderUtil.drawText(g, font, "Aurora Client " + Theme.CLIENT_VERSION + " · " + Theme.MC_VERSION,
                    8, this.height - 12, theme.textSecondary(), theme);
            int enabledCount = ModuleManager.enabled().size();
            RenderUtil.drawText(g, font, "© terem90 · модулей включено: " + enabledCount,
                    8, this.height - 24, ColorUtil.alpha(theme.textSecondary(), 130), theme);
            String fps = mc.getFps() + " fps";
            RenderUtil.drawText(g, font, fps, this.width - 10 - RenderUtil.width(font, fps, theme),
                    this.height - 12, theme.textSecondary(), theme);
        }
    }

    /** Форма кнопки с lerp-наведением. Возвращает y следующей кнопки. */
    private int drawButtonShape(GuiGraphics g, int x, int y, int w, int h, int index, float appearT, int gap) {
        float delay = index * 0.06f;
        float bt = Animation.smooth(Math.max(0, Math.min(1, (appearT - delay) / Math.max(0.001f, 1 - delay))));
        int offsetY = (int) ((1 - bt) * 10);
        int alpha = (int) (255 * bt);
        int by = y + offsetY;
        if (alpha < 6) {
            return y + h + gap;
        }

        Animation hoverAnim = hoverAnims.get(index);
        boolean hover = bt > 0.9f && mouseX >= x && mouseX <= x + w && mouseY >= by && mouseY <= by + h;
        hoverAnim.to(hover ? 1 : 0);
        float hv = hoverAnim.update();

        // кнопка мягко увеличивается при наведении
        g.pose().pushMatrix();
        float scb = 1f + 0.018f * hv;
        g.pose().translate(x + w / 2f, by + h / 2f);
        g.pose().scale(scb, scb);
        g.pose().translate(-(x + w / 2f), -(by + h / 2f));

        // акцентное свечение под кнопкой при наведении
        if (hv > 0.05f) {
            GfxEngine.glow(g, x, by, w, h, h / 2f, 9f,
                    ColorUtil.alpha(theme.accentNow(), (int) (60 * hv)));
        }

        // стеклянная кнопка: полупрозрачная база + блик-градиент + обводка
        int baseA = (int) (110 * bt);
        int hoverA = (int) (150 * hv);
        GfxEngine.rounded(g, x, by, w, h, h / 2f,
                ColorUtil.lerp(ColorUtil.rgba(12, 14, 22, baseA), ColorUtil.rgba(18, 20, 32, hoverA), hv));
        GfxEngine.roundedGradient(g, x, by, w, h, h / 2f,
                ColorUtil.rgba(255, 255, 255, (int) (18 * bt)), ColorUtil.rgba(255, 255, 255, (int) (3 * bt)));
        GfxEngine.outline(g, x, by, w, h, h / 2f, ColorUtil.lerp(
                ColorUtil.rgba(255, 255, 255, (int) (40 * bt)),
                ColorUtil.alpha(theme.accentNow(), 200), hv), 1f);
        // светлая кромка сверху
        float inset = Math.min(h / 2f, w / 2f);
        GfxEngine.rounded(g, x + inset, by + 1, Math.max(2, w - inset * 2), 1, 0.5f,
                ColorUtil.rgba(255, 255, 255, (int) (46 * bt)));

        g.pose().popMatrix();

        final int fby = by;
        if (bt > 0.9f) {
            hits.add(new Hit(x, fby, w, h, () -> onButton(index)));
        }
        return y + h + gap;
    }

    /** Подпись кнопки. Возвращает y следующей кнопки. */
    private int drawButtonText(GuiGraphics g, int x, int y, int w, int h, int index, float appearT, int gap) {
        float delay = index * 0.06f;
        float bt = Animation.smooth(Math.max(0, Math.min(1, (appearT - delay) / Math.max(0.001f, 1 - delay))));
        int offsetY = (int) ((1 - bt) * 10);
        int alpha = (int) (255 * bt);
        int by = y + offsetY;
        if (alpha < 6) {
            return y + h + gap;
        }
        float hv = hoverAnims.get(index).get();
        int textColor = ColorUtil.lerp(theme.textPrimary(), ColorUtil.rgb(255, 255, 255), hv);

        // тот же hover-масштаб, что у формы — текст не отходит от кнопки
        g.pose().pushMatrix();
        float scb = 1f + 0.018f * hv;
        g.pose().translate(x + w / 2f, by + h / 2f);
        g.pose().scale(scb, scb);
        g.pose().translate(-(x + w / 2f), -(by + h / 2f));
        RenderUtil.drawCenteredText(g, font, BUTTONS[index], x + w / 2f, by + h / 2f - RenderUtil.fontHeight(font) / 2f + 1,
                ColorUtil.alpha(textColor, alpha), theme);
        // стрелка-шеврон слева: раньше тут была «каретка»-полоска, которую
        // можно было спутать с текстовым курсором
        if (hv > 0.03f) {
            RenderUtil.drawText(g, font, "›", x + 14, by + h / 2f - RenderUtil.fontHeight(font) / 2f + 1,
                    ColorUtil.alpha(theme.accentNow(), (int) (230 * hv)), theme);
        }
        g.pose().popMatrix();
        return y + h + gap;
    }

    private void onButton(int index) {
        Minecraft mc = Minecraft.getInstance();
        AuroraClickGui.playClick();
        switch (index) {
            case 0 -> mc.setScreen(new SelectWorldScreen(this));
            case 1 -> mc.setScreen(new JoinMultiplayerScreen(this));
            case 2 -> mc.setScreen(new OptionsScreen(this, mc.options));
            case 3 -> mc.setScreen(new AuroraClickGui(this));
            default -> mc.stop();
        }
    }

    /** Форма мелкой кнопки. Возвращает x для следующей (левее). */
    private int smallButtonShape(GuiGraphics g, int rightX, int y, String label, int animIndex, Runnable action) {
        int tw = RenderUtil.width(font, label, theme) + 18;
        int bx = rightX - tw;
        boolean hover = mouseX >= bx && mouseX <= rightX && mouseY >= y && mouseY <= y + 20;
        Animation hoverAnim = hoverAnims.get(animIndex);
        hoverAnim.to(hover ? 1 : 0);
        float hv = hoverAnim.update();
        int fill = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 16), ColorUtil.alpha(theme.accentNow(), 110), hv);
        RenderUtil.glassChip(g, bx, y, tw, 20, 10, fill,
                hover ? ColorUtil.alpha(theme.accentNow(), 180) : ColorUtil.rgba(255, 255, 255, 34), theme);
        hits.add(new Hit(bx, y, tw, 20, action));
        return bx - 8;
    }

    /** Подпись мелкой кнопки. Возвращает x для следующей (левее). */
    private int smallButtonText(GuiGraphics g, int rightX, int y, String label, int animIndex) {
        int tw = RenderUtil.width(font, label, theme) + 18;
        int bx = rightX - tw;
        float hv = hoverAnims.get(animIndex).get();
        RenderUtil.drawText(g, font, label, bx + 9, y + 10 - RenderUtil.fontHeight(font) / 2f + 1,
                ColorUtil.lerp(theme.textSecondary(), ColorUtil.rgb(255, 255, 255), hv), theme);
        return bx - 8;
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
            this.minecraft.setScreen(new AuroraClickGui(this));
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void tick() {
        appear.update();
        for (Animation a : hoverAnims) {
            a.update();
        }
    }

    @Override
    public void onClose() {
        AuroraConfig.save();
        super.onClose();
    }
}
