package dev.aurora.client.hud;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.config.AuroraConfig;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
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
        try {
            renderBackground(g, mx, my, delta);
        } catch (IllegalStateException e) {
            // блюр в этом кадре уже применён другим слоем — просто затемняем
            g.fill(0, 0, this.width, this.height, 0x8C10101A);
        }

        for (Module m : ModuleManager.all()) {
            if (!(m instanceof HudModule hud)) {
                continue;
            }
            boolean over = isOver(hud, mx, my);
            // перетаскивание
            if (dragging == hud) {
                float nx = (mx - grabDX) / (float) g.guiWidth();
                float ny = (my - grabDY) / (float) g.guiHeight();
                hud.x = Math.max(0f, Math.min(1f, nx));
                hud.y = Math.max(0f, Math.min(1f, ny));
            }
            hud.render(g, Minecraft.getInstance().getDeltaTracker(), theme, Minecraft.getInstance());
            hud.renderEditorFrame(g, theme, over, dragging == hud);
        }

        // ===== верхняя панель подсказок =====
        int barH = 26;
        RenderUtil.glassPanel(g, 8, 8, this.width - 16, barH, 10, theme);
        String hint = "ЛКМ — перетащить · Колесо — масштаб · ПКМ — фон · ESC — готово";
        RenderUtil.drawCenteredText(g, font, hint, this.width / 2, 8 + 9, theme.textSecondary(), theme);

        // заголовок
        RenderUtil.drawText(g, font, "HUD-редактор", 16, 8 + 9, theme.textPrimary(), theme);

        // кнопка сброса
        String reset = "Сбросить позиции";
        int rw = RenderUtil.width(font, reset, theme) + 16;
        int rx = this.width - 16 - rw;
        boolean hoverReset = mx >= rx && mx <= rx + rw && my >= 10 && my <= 10 + 22;
        RenderUtil.rounded(g, rx, 10, rw, 22, 11, hoverReset ? ColorUtil.alpha(theme.accentNow(), 90) : ColorUtil.rgba(255, 255, 255, 16));
        RenderUtil.drawText(g, font, reset, rx + 8, 10 + 7, hoverReset ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);
        resetBtn = new int[]{rx, 10, rw, 22};
    }

    private int[] resetBtn;

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
        if (hud instanceof FpsHud) return 0.048f;
        if (hud instanceof PingHud) return 0.091f;
        if (hud instanceof CoordsHud) return 0.134f;
        if (hud instanceof CpsHud) return 0.177f;
        if (hud instanceof KeystrokesHud) return 0.5f;
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
            AuroraConfig.save();
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
