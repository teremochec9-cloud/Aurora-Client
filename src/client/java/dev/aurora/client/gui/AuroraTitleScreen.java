package dev.aurora.client.gui;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.config.AuroraConfig;
import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Красивое главное меню: живой градиент, частицы, стеклянные кнопки.
 */
public class AuroraTitleScreen extends Screen {
    private final Theme theme;
    private final List<Particle> particles = new ArrayList<>();
    private final Animation appear = new Animation(0, 6);
    private final List<Hit> hits = new ArrayList<>();
    private int mouseX, mouseY;

    private static class Particle {
        float x, y, speed, size, phase, drift;

        Particle(Random random, int w, int h) {
            x = random.nextFloat() * w;
            y = random.nextFloat() * h;
            speed = 6 + random.nextFloat() * 14;
            size = 1 + random.nextFloat() * 2.5f;
            phase = random.nextFloat() * 6.28f;
            drift = (random.nextFloat() - 0.5f) * 6;
        }
    }

    private static class Hit {
        final int x, y, w, h;
        final Runnable action;

        Hit(int x, int y, int w, int h, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }
    }

    public AuroraTitleScreen() {
        super(Component.literal("Aurora"));
        this.theme = AuroraClient.theme();
    }

    @Override
    protected void init() {
        particles.clear();
        Random random = new Random();
        for (int i = 0; i < 60; i++) {
            particles.add(new Particle(random, this.width, this.height));
        }
        appear.snap(0);
        appear.to(1);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // тёмный вертикальный градиент
        g.fillGradient(0, 0, this.width, this.height,
                ColorUtil.rgb(10, 11, 20), ColorUtil.rgb(22, 17, 38));

        long time = System.currentTimeMillis();
        // мягкие светящиеся «ленты» северного сияния
        for (int i = 0; i < 3; i++) {
            float cx = this.width * (0.2f + 0.3f * i)
                    + (float) Math.sin(time / (9000.0 + 4000 * i) + i * 2.1) * this.width * 0.18f;
            float cy = this.height * (0.3f + 0.18f * ((i + 1) % 3))
                    + (float) Math.cos(time / (11000.0 + 3000 * i) + i) * this.height * 0.1f;
            int colA = ColorUtil.alpha(theme.accent(i / 3.0f), 13);
            int colB = ColorUtil.alpha(theme.accent((i + 1) / 3.0f), 24);
            int rw = this.width / 3;
            int rh = this.height / 3;
            // полностью скруглённые концы — мягкие «полосы света»
            RenderUtil.roundedGradient(g, (int) cx - rw / 2, (int) cy - rh / 2, rw, rh, Math.min(rw, rh) / 2, colA, colB);
        }

        // частицы-светлячки
        for (Particle p : particles) {
            float px = p.x + (float) Math.sin(time / 2000.0 + p.phase) * 8 + p.drift * (time % 10000) / 10000.0f;
            float py = p.y - (time / 1000.0f * p.speed) % (this.height + 40);
            if (py < -10) {
                py += this.height + 40;
            }
            float twinkle = 0.4f + 0.6f * (float) Math.abs(Math.sin(time / 700.0 + p.phase));
            int color = ColorUtil.rgba(255, 255, 255, (int) (60 * twinkle));
            int sz = (int) p.size;
            g.fill((int) px, (int) py, (int) px + sz, (int) py + sz, color);
        }

        // лёгкая виньетка
        g.fillGradient(0, 0, this.width, this.height / 6, ColorUtil.rgba(0, 0, 0, 90), ColorUtil.rgba(0, 0, 0, 0));
        g.fillGradient(0, this.height - this.height / 6, this.width, this.height, ColorUtil.rgba(0, 0, 0, 0), ColorUtil.rgba(0, 0, 0, 110));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.mouseX = mx;
        this.mouseY = my;
        hits.clear();
        float t = Animation.smooth(appear.update());

        renderBackground(g, mx, my, delta);

        Minecraft mc = Minecraft.getInstance();

        // ===== адаптивная вёрстка =====
        boolean compact = this.height < 300; // маленькие экраны / крупный GUI-масштаб
        int logoScale = compact ? 2 : 3;
        int bh = compact ? 24 : 34;
        int gap = compact ? 4 : 8;
        int bw = Math.min(250, this.width - 40);

        // ===== ЛОГО (целиком, градиент полосами через «ножницы») =====
        String title = Theme.CLIENT_NAME;
        int logoW = RenderUtil.width(font, title, theme) * logoScale;
        int logoH = RenderUtil.fontHeight(font) * logoScale;
        int logoCenterY = (int) (this.height * (compact ? 0.16f : 0.24f));
        int lx = this.width / 2 - logoW / 2;
        int ly = logoCenterY - logoH / 2;
        int animOffX = (int) ((1 - t) * 14);

        g.pose().pushMatrix();
        g.pose().translate(lx - animOffX, ly);
        g.pose().scale(logoScale, logoScale);
        int colA = theme.accentNow();
        int colB = theme.accent(1.0f);
        int bandH = logoH + logoScale * 2;
        // три полосы градиента: клип задаётся в экранных координатах,
        // поэтому ножницы включаем при единичной матрице (до трансформаций)
        g.pose().popMatrix();

        int logoLeft = lx - animOffX;
        g.enableScissor(logoLeft - 2, ly - 2, logoLeft + logoW / 3, ly + bandH);
        drawLogoBand(g, title, logoLeft, ly, logoScale, colA);
        g.disableScissor();
        g.enableScissor(logoLeft + logoW / 3, ly - 2, logoLeft + 2 * logoW / 3, ly + bandH);
        drawLogoBand(g, title, logoLeft, ly, logoScale, ColorUtil.lerp(colA, colB, 0.5f));
        g.disableScissor();
        g.enableScissor(logoLeft + 2 * logoW / 3, ly - 2, logoLeft + logoW + 6, ly + bandH);
        drawLogoBand(g, title, logoLeft, ly, logoScale, colB);
        g.disableScissor();

        // подзаголовок и приветствие
        int textY = ly + logoH + 6;
        if (compact) {
            String welcome = "С возвращением, " + mc.getUser().getName();
            RenderUtil.drawCenteredText(g, font, welcome, this.width / 2, textY,
                    ColorUtil.alpha(theme.accentNow(), (int) (255 * t)), theme);
            textY += 12;
        } else {
            String subtitle = "клиент для Minecraft " + Theme.MC_VERSION;
            RenderUtil.drawCenteredText(g, font, subtitle, this.width / 2, textY,
                    ColorUtil.alpha(theme.textSecondary(), (int) (255 * t)), theme);
            String welcome = "С возвращением, " + mc.getUser().getName();
            RenderUtil.drawCenteredText(g, font, welcome, this.width / 2, textY + 12,
                    ColorUtil.alpha(theme.accentNow(), (int) (255 * t)), theme);
            textY += 24;
        }

        // ===== КНОПКИ (гарантированно ниже текста и выше низа) =====
        int stackH = 5 * bh + 4 * gap;
        int by = Math.max(this.height / 2 - stackH / 2 + (compact ? 8 : 24), textY + 6);
        int maxBy = this.height - (compact ? 32 : 48);
        while (by + stackH > maxBy && bh > 16) {
            bh -= 2;
            if (gap > 2) {
                gap -= 1;
            }
            stackH = 5 * bh + 4 * gap;
            by = Math.max(this.height / 2 - stackH / 2, textY + 6);
        }
        int bx = this.width / 2 - bw / 2;

        int idx = 0;
        by = button(g, bx, by, bw, bh, "Одиночная игра", idx++, t, () -> mc.setScreen(new SelectWorldScreen(this)));
        by = button(g, bx, by, bw, bh, "Сетевая игра", idx++, t, () -> mc.setScreen(new JoinMultiplayerScreen(this)));
        by = button(g, bx, by, bw, bh, "Настройки", idx++, t, () -> mc.setScreen(new OptionsScreen(this, mc.options)));
        by = button(g, bx, by, bw, bh, "Настройки Aurora", idx++, t, () -> mc.setScreen(new AuroraClickGui()));
        by = button(g, bx, by, bw, bh, "Выход из игры", idx++, t, () -> mc.stop());

        // ===== нижний ряд мелких кнопок =====
        int sbY = this.height - (compact ? 20 : 44);
        int sbX = this.width - 10;
        sbX = smallButton(g, sbX, sbY, "Спец. возможности", () ->
                mc.setScreen(new AccessibilityOptionsScreen(this, mc.options)));
        smallButton(g, sbX, sbY, "Язык", () ->
                mc.setScreen(new LanguageSelectScreen(this, mc.options, mc.getLanguageManager())));

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

    /** Полоса логотипа: рисуем строку с масштабом через pose. */
    private void drawLogoBand(GuiGraphics g, String title, int x, int y, int scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        RenderUtil.drawText(g, font, title, 0, 0, color, theme);
        g.pose().popMatrix();
    }

    private int button(GuiGraphics g, int x, int y, int w, int h, String label, int order, float appearT, Runnable action) {
        float delay = order * 0.06f;
        float t = Animation.smooth(Math.max(0, Math.min(1, (appearT - delay) / Math.max(0.001f, 1 - delay))));
        int offsetY = (int) ((1 - t) * 14);
        int alpha = (int) (255 * t);
        int by = y + offsetY;
        boolean hover = t > 0.9f && mouseX >= x && mouseX <= x + w && mouseY >= by && mouseY <= by + h;

        RenderUtil.rounded(g, x + 1, by + 2, w, h, 10, ColorUtil.rgba(0, 0, 0, (int) (70 * t)));
        int bgAlpha = (int) ((hover ? 46 : 20) * t);
        RenderUtil.rounded(g, x, by, w, h, 10, ColorUtil.rgba(255, 255, 255, bgAlpha));
        RenderUtil.roundedOutline(g, x, by, w, h, 10, hover
                ? ColorUtil.alpha(theme.accentNow(), alpha)
                : ColorUtil.rgba(255, 255, 255, (int) (34 * t)));
        if (hover) {
            // акцентная полоска слева
            RenderUtil.rounded(g, x + 6, by + 7, 3, h - 14, 1, theme.accentNow());
        }
        RenderUtil.drawCenteredText(g, font, label, x + w / 2, by + h / 2 - 5,
                hover ? ColorUtil.rgb(255, 255, 255) : ColorUtil.alpha(theme.textPrimary(), alpha), theme);

        if (t > 0.9f) {
            hits.add(new Hit(x, by, w, h, action));
        }
        return y + h + 8; // следующая позиция
    }

    private int smallButton(GuiGraphics g, int rightX, int y, String label, Runnable action) {
        int tw = RenderUtil.width(font, label, theme) + 18;
        int bx = rightX - tw;
        boolean hover = mouseX >= bx && mouseX <= rightX && mouseY >= y && mouseY <= y + 20;
        RenderUtil.rounded(g, bx, y, tw, 20, 10, hover ? ColorUtil.alpha(theme.accentNow(), 80) : ColorUtil.rgba(255, 255, 255, 16));
        RenderUtil.drawText(g, font, label, bx + 9, y + 6, hover ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);
        hits.add(new Hit(bx, y, tw, 20, action));
        return bx - 8;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        int mx = (int) e.x();
        int my = (int) e.y();
        if (e.button() == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (mx >= hit.x && mx <= hit.x + hit.w && my >= hit.y && my <= hit.y + hit.h) {
                    AuroraClickGui.playClick();
                    hit.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public void onClose() {
        AuroraConfig.save();
        super.onClose();
    }
}
