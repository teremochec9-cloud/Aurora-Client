package dev.aurora.client.hud;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.config.AuroraConfig;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.GfxEngine;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Редактор HUD: перетаскивай элементы, крути колесо — масштаб, ПКМ — фон.
 */
public class HudEditorScreen extends Screen {
    private final Theme theme;
    private HudModule dragging;
    private float grabDX, grabDY;
    private int mouseX, mouseY;
    private final Animation appear = new Animation(0, 10);

    /** Кнопка «Сбросить позиции» [x, y, w, h]. */
    private int[] resetBtn;

    public HudEditorScreen() {
        super(Component.literal("HUD Editor"));
        this.theme = AuroraClient.theme();
    }

    @Override
    public void added() {
        appear.snap(theme.animations ? 0 : 1);
        appear.to(1);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.mouseX = mx;
        this.mouseY = my;

        // элементы + рамки (формы)
        for (Module m : ModuleManager.all()) {
            if (!(m instanceof HudModule hud)) {
                continue;
            }
            boolean over = isOver(hud, mx, my);
            if (dragging == hud) {
                float nx = (mx - grabDX) / (float) g.guiWidth();
                float ny = (my - grabDY) / (float) g.guiHeight();
                hud.x = Math.max(0f, Math.min(1f, nx));
                hud.y = Math.max(0f, Math.min(1f, ny));
            }
            hud.render(g, Minecraft.getInstance().getDeltaTracker(), theme, Minecraft.getInstance());
            hud.renderEditorFrame(g, theme, over, dragging == hud);
        }

        drawBottomBarShapes(g);

        g.nextStratum();

        drawBottomBarTexts(g);
    }

    /* ------------------------------------------------------------------ */
    /*  Нижняя панель подсказок: ширина подобрана так, чтобы весь текст   */
    /*  помещался целиком — без обрезки троеточием                        */
    /* ------------------------------------------------------------------ */

    private static final String HINT_FULL = "ЛКМ — перетащить · Колесо — масштаб · ПКМ — фон · ESC — готово";
    private static final String HINT_SHORT = "ЛКМ — тянуть · Колесо — размер · ПКМ — фон · ESC";

    private void drawBottomBarShapes(GuiGraphics g) {
        float at = Animation.smooth(appear.get());
        String hint = chooseHint();
        int barH = 28;
        int pad = 10;

        int titleW = RenderUtil.widthBold(font, "HUD-редактор", theme);
        int hintW = RenderUtil.width(font, hint, theme);
        String reset = "Сбросить позиции";
        int rw = RenderUtil.width(font, reset, theme) + 18;

        int contentW = titleW + 16 + hintW + 14 + rw;
        int barW = Math.min(this.width - 16, contentW + pad * 2);
        int barX = (this.width - barW) / 2;
        int barY = this.height - barH - 8;

        int alpha = (int) (255 * at);
        RenderUtil.softShadow(g, barX, barY, barW, barH, 10, (int) (110 * at));
        GfxEngine.rounded(g, barX, barY, barW, barH, 10, ColorUtil.alpha(theme.panelColor(), (int) (235 * at)));
        GfxEngine.outline(g, barX, barY, barW, barH, 10, ColorUtil.rgba(255, 255, 255, (int) (26 * at)), 1f);

        // кнопка сброса
        int rx = barX + barW - pad - rw;
        boolean hoverReset = mouseX >= rx && mouseX <= rx + rw && mouseY >= barY && mouseY <= barY + barH;
        GfxEngine.rounded(g, rx, barY + 3, rw, barH - 6, 9,
                hoverReset ? ColorUtil.alpha(theme.accentNow(), 150) : ColorUtil.rgba(255, 255, 255, (int) (18 * at)));
        resetBtn = new int[]{rx, barY, rw, barH};
    }

    private void drawBottomBarTexts(GuiGraphics g) {
        float at = Animation.smooth(appear.get());
        String hint = chooseHint();
        int barH = 28;
        int pad = 10;

        int titleW = RenderUtil.widthBold(font, "HUD-редактор", theme);
        String reset = "Сбросить позиции";
        int rw = RenderUtil.width(font, reset, theme) + 18;
        int hintW = RenderUtil.width(font, hint, theme);
        int barW = Math.min(this.width - 16, titleW + 16 + hintW + 14 + rw + pad * 2);
        int barX = (this.width - barW) / 2;
        int barY = this.height - barH - 8;

        int textY = barY + barH / 2 - RenderUtil.fontHeight(font) / 2 + 1;
        RenderUtil.drawTextBold(g, font, "HUD-редактор", barX + pad, textY, theme.textPrimary(), theme);
        RenderUtil.drawText(g, font, hint, barX + pad + titleW + 16, textY,
                ColorUtil.alpha(theme.textSecondary(), (int) (255 * at)), theme);

        int rx = barX + barW - pad - rw;
        boolean hoverReset = mouseX >= rx && mouseX <= rx + rw && mouseY >= barY && mouseY <= barY + barH;
        RenderUtil.drawText(g, font, reset, rx + 9, textY,
                hoverReset ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);
    }

    /** Подбирает подсказку, которая гарантированно помещается в панель. */
    private String chooseHint() {
        int titleW = RenderUtil.widthBold(font, "HUD-редактор", theme);
        int rw = RenderUtil.width(font, "Сбросить позиции", theme) + 18;
        int available = this.width - 16 - 20 /* паддинги */ - titleW - 16 - 14 - rw;
        if (RenderUtil.width(font, HINT_FULL, theme) <= available) {
            return HINT_FULL;
        }
        return HINT_SHORT;
    }

    /** Фон редактора: настоящий блюр + затемнение (ровно 1 раз за кадр). */
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        try {
            renderBlurredBackground(g);
        } catch (IllegalStateException e) {
            // блюр в этом кадре уже занят другим слоем — просто затемняем
        }
        g.fillGradient(0, 0, this.width, this.height,
                ColorUtil.rgba(9, 10, 16, 120), ColorUtil.rgba(8, 9, 14, 155));
    }

    private void resetPositions() {
        for (Module m : ModuleManager.all()) {
            if (m instanceof HudModule hud) {
                hud.x = defaultX(hud);
                hud.y = defaultY(hud);
            }
        }
        AuroraConfig.save();
    }

    private float defaultX(HudModule hud) {
        if (hud instanceof WatermarkHud) return 0.005f;
        if (hud instanceof FpsHud) return 0.005f;
        if (hud instanceof PingHud) return 0.005f;
        if (hud instanceof CoordsHud) return 0.005f;
        if (hud instanceof CpsHud) return 0.005f;
        if (hud instanceof KeystrokesHud) return 0.006f;
        if (hud instanceof DirectionHud) return 0.99f;
        if (hud instanceof ClockHud) return 0.5f;
        return hud.x;
    }

    private float defaultY(HudModule hud) {
        if (hud instanceof WatermarkHud) return 0.005f;
        if (hud instanceof FpsHud) return 0.075f;
        if (hud instanceof PingHud) return 0.150f;
        if (hud instanceof CoordsHud) return 0.225f;
        if (hud instanceof CpsHud) return 0.300f;
        if (hud instanceof KeystrokesHud) return 0.55f;
        if (hud instanceof DirectionHud) return 0.005f;
        if (hud instanceof ClockHud) return 0.005f;
        return hud.y;
    }

    private boolean isOver(HudModule hud, int mx, int my) {
        float s = hud.scale.get();
        int px = hud.pixelX(this.width);
        int py = hud.pixelY(this.height);
        int w = (int) (hud.width() * s);
        int h = (int) (hud.height() * s);
        return mx >= px - 4 && mx <= px + w + 4 && my >= py - 4 && my <= py + h + 4;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        int mx = (int) e.x();
        int my = (int) e.y();
        if (e.button() == 0) {
            if (resetBtn != null && mx >= resetBtn[0] && mx <= resetBtn[0] + resetBtn[2] && my >= resetBtn[1] && my <= resetBtn[1] + resetBtn[3]) {
                resetPositions();
                return true;
            }
            for (Module m : ModuleManager.all()) {
                if (m instanceof HudModule hud && isOver(hud, mx, my)) {
                    dragging = hud;
                    grabDX = mx - hud.pixelX(this.width);
                    grabDY = my - hud.pixelY(this.height);
                    return true;
                }
            }
        } else if (e.button() == 1) {
            for (Module m : ModuleManager.all()) {
                if (m instanceof HudModule hud && isOver(hud, mx, my)) {
                    hud.background.toggle();
                    AuroraConfig.save();
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging != null) {
            AuroraConfig.markDirty();
        }
        dragging = null;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        for (Module m : ModuleManager.all()) {
            if (m instanceof HudModule hud && isOver(hud, (int) x, (int) y)) {
                hud.scale.set(hud.scale.get() + (float) (scrollY * 0.05f));
                return true;
            }
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
            AuroraConfig.save();
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void removed() {
        AuroraConfig.save();
        super.removed();
    }
}
