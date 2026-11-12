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
 * по центру стеклянная карточка с градиентным логотипом и скруглёнными
 * кнопками с плавным наведением.
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
        for (int i = 0; i < 46; i++) {
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

        // тёмный градиент поверх
        g.fillGradient(0, 0, this.width, this.height,
                ColorUtil.rgba(9, 10, 17, 125), ColorUtil.rgba(8, 9, 14, 170));

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

        // частицы-светлячки (гладкие точки)
        int pAlpha = (int) (255 * appearT);
        for (Particle p : particles) {
            float px = p.x + (float) Math.sin(time / 2000.0 + p.phase) * 8 + p.drift * (time % 10000) / 10000.0f;
            float py = p.y - (time / 1000.0f * p.speed) % (this.height + 40);
            if (py < -10) {
                py += this.height + 40;
            }
            float twinkle = 0.4f + 0.6f * (float) Math.abs(Math.sin(time / 700.0 + p.phase));
            GfxEngine.circle(g, px, py, Math.max(1.6f, p.size * 1.6f),
                    ColorUtil.rgba(255, 255, 255, (int) (70 * twinkle * pAlpha / 255f)));
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
        boolean compact = this.height < 300;
        int logoScale = compact ? 2 : 3;
        int bh = compact ? 22 : 30;
        int gap = compact ? 4 : 7;
        int bw = Math.min(240, this.width - 60);
        int pad = compact ? 14 : 26;

        // ===== размеры стеклянной карточки =====
        int logoW = RenderUtil.widthBold(font, Theme.CLIENT_NAME, theme) * logoScale;
        int logoH = RenderUtil.fontHeight(font) * logoScale;
        int stackH = BUTTONS.length * bh + (BUTTONS.length - 1) * gap;
        int cardH = pad + logoH + (compact ? 6 : 12) + (compact ? 0 : 11) + (compact ? 0 : 12)
                + (compact ? 10 : 16) + stackH + pad - (compact ? 2 : 6);
        int cardW = bw + pad * 2;
        int cardX = this.width / 2 - cardW / 2;
        int cardY = Math.max(10, this.height / 2 - cardH / 2 - (compact ? 4 : 10));
        cardH = Math.min(cardH, this.height - 20);

        // ===== фаза 1: формы =====
        // плавное появление карточки (масштаб вокруг центра)
        if (theme.animations) {
            g.pose().pushMatrix();
            float sc = 0.97f + 0.03f * t;
            float ccx = this.width / 2f;
            float ccy = this.height / 2f;
            g.pose().translate(ccx, ccy);
            g.pose().scale(sc, sc);
            g.pose().translate(-ccx, -ccy);
        }

        RenderUtil.softShadow(g, cardX, cardY, cardW, cardH, 16, 150);
        GfxEngine.rounded(g, cardX, cardY, cardW, cardH, 16, ColorUtil.alpha(theme.panelColor(), (int) (215 * t)));
        GfxEngine.outline(g, cardX, cardY, cardW, cardH, 16, ColorUtil.rgba(255, 255, 255, (int) (30 * t)), 1f);
        GfxEngine.rounded(g, cardX + 18, cardY + 1, cardW - 36, 1, 0.5f, ColorUtil.rgba(255, 255, 255, (int) (26 * t)));

        // свечение за логотипом
        int lx = this.width / 2 - logoW / 2 - (int) ((1 - t) * 14);
        int ly = cardY + pad;
        RenderUtil.glow(g, lx - logoW, ly - logoH - 4, logoW * 2.4f, logoH * 2.4f,
                ColorUtil.alpha(theme.accentNow(), (int) (46 * t)));

        // кнопки (формы)
        int by = cardY + cardH - pad - stackH + (compact ? 2 : 4);
        int bx = this.width / 2 - bw / 2;
        for (int i = 0; i < BUTTONS.length; i++) {
            by = drawButtonShape(g, bx, by, bw, bh, i, t);
        }

        // нижний ряд мелких кнопок (формы)
        int sbY = this.height - (compact ? 22 : 34);
        int sbX = this.width - 10;
        sbX = smallButtonShape(g, sbX, sbY, "Спец. возможности", BUTTONS.length, () ->
                mc.setScreen(new AccessibilityOptionsScreen(this, mc.options)));
        smallButtonShape(g, sbX, sbY, "Язык", BUTTONS.length + 1, () ->
                mc.setScreen(new LanguageSelectScreen(this, mc.options, mc.getLanguageManager())));

        if (theme.animations) {
            g.pose().popMatrix();
        }

        g.nextStratum();

        // ===== фаза 2: текст =====
        if (theme.animations) {
            g.pose().pushMatrix();
            float sc = 0.97f + 0.03f * t;
            float ccx = this.width / 2f;
            float ccy = this.height / 2f;
            g.pose().translate(ccx, ccy);
            g.pose().scale(sc, sc);
            g.pose().translate(-ccx, -ccy);
        }

        // градиентный логотип по символам
        g.pose().pushMatrix();
        g.pose().translate(lx, ly);
        g.pose().scale(logoScale, logoScale);
        RenderUtil.drawGradientText(g, font, Theme.CLIENT_NAME, 0, 0, theme.accentNow(), theme.accent(1.0f), theme);
        g.pose().popMatrix();

        int textY = ly + logoH + (compact ? 4 : 8);
        if (!compact) {
            RenderUtil.drawCenteredText(g, font, "клиент для Minecraft " + Theme.MC_VERSION,
                    this.width / 2f, textY, ColorUtil.alpha(theme.textSecondary(), (int) (220 * t)), theme);
            textY += 11;
            RenderUtil.drawCenteredText(g, font, "С возвращением, " + mc.getUser().getName(),
                    this.width / 2f, textY, ColorUtil.alpha(theme.accentNow(), (int) (255 * t)), theme);
        }

        // подписи кнопок
        int ty = cardY + cardH - pad - stackH + (compact ? 2 : 4);
        int tx = this.width / 2 - bw / 2;
        for (int i = 0; i < BUTTONS.length; i++) {
            ty = drawButtonText(g, tx, ty, bw, bh, i, t);
        }

        // мелкие кнопки: подписи
        int stx = this.width - 10;
        stx = smallButtonText(g, stx, sbY, "Спец. возможности", BUTTONS.length);
        smallButtonText(g, stx, sbY, "Язык", BUTTONS.length + 1);

        if (theme.animations) {
            g.pose().popMatrix();
        }

        // ===== подписи по углам =====
        if (compact) {
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
    private int drawButtonShape(GuiGraphics g, int x, int y, int w, int h, int index, float appearT) {
        float delay = index * 0.06f;
        float bt = Animation.smooth(Math.max(0, Math.min(1, (appearT - delay) / Math.max(0.001f, 1 - delay))));
        int offsetY = (int) ((1 - bt) * 10);
        int alpha = (int) (255 * bt);
        int by = y + offsetY;
        if (alpha < 6) {
            return y + h + (h > 20 ? 7 : 4);
        }

        Animation hoverAnim = hoverAnims.get(index);
        boolean hover = bt > 0.9f && mouseX >= x && mouseX <= x + w && mouseY >= by && mouseY <= by + h;
        hoverAnim.to(hover ? 1 : 0);
        float hv = hoverAnim.update();

        int fill = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 12), ColorUtil.rgba(255, 255, 255, 30), hv);
        GfxEngine.rounded(g, x, by, w, h, h / 2f, ColorUtil.alpha(fill, (int) (ColorUtil.alphaI(fill) * appearT)));
        GfxEngine.outline(g, x, by, w, h, h / 2f, ColorUtil.lerp(
                ColorUtil.rgba(255, 255, 255, (int) (30 * appearT)),
                ColorUtil.alpha(theme.accentNow(), 190), hv), 1f);

        // акцентная полоска слева при наведении
        if (hv > 0.02f) {
            GfxEngine.rounded(g, x + 10, by + h / 2f - 5, 3, 10, 1.5f,
                    ColorUtil.alpha(theme.accentNow(), (int) (220 * hv)));
        }

        final int fby = by;
        if (bt > 0.9f) {
            hits.add(new Hit(x, fby, w, h, () -> onButton(index)));
        }
        return y + h + (h > 20 ? 7 : 4);
    }

    /** Подпись кнопки. Возвращает y следующей кнопки. */
    private int drawButtonText(GuiGraphics g, int x, int y, int w, int h, int index, float appearT) {
        float delay = index * 0.06f;
        float bt = Animation.smooth(Math.max(0, Math.min(1, (appearT - delay) / Math.max(0.001f, 1 - delay))));
        int offsetY = (int) ((1 - bt) * 10);
        int alpha = (int) (255 * bt);
        int by = y + offsetY;
        if (alpha < 6) {
            return y + h + (h > 20 ? 7 : 4);
        }
        float hv = hoverAnims.get(index).get();
        int textColor = ColorUtil.lerp(theme.textPrimary(), ColorUtil.rgb(255, 255, 255), hv);
        RenderUtil.drawCenteredText(g, font, BUTTONS[index], x + w / 2f, by + h / 2f - RenderUtil.fontHeight(font) / 2f + 1,
                ColorUtil.alpha(textColor, alpha), theme);
        return y + h + (h > 20 ? 7 : 4);
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

    /** Форма мелкой кнопки. Возвращает x для следующей (левее). */
    private int smallButtonShape(GuiGraphics g, int rightX, int y, String label, int animIndex, Runnable action) {
        int tw = RenderUtil.width(font, label, theme) + 18;
        int bx = rightX - tw;
        boolean hover = mouseX >= bx && mouseX <= rightX && mouseY >= y && mouseY <= y + 20;
        Animation hoverAnim = hoverAnims.get(animIndex);
        hoverAnim.to(hover ? 1 : 0);
        float hv = hoverAnim.update();
        int fill = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 14), ColorUtil.alpha(theme.accentNow(), 90), hv);
        GfxEngine.rounded(g, bx, y, tw, 20, 10, fill);
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
            this.minecraft.setScreen(new AuroraClickGui());
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
