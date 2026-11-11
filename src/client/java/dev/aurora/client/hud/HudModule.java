package dev.aurora.client.hud;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.NumberSetting;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

/**
 * Базовый класс HUD-элемента: позиция (в долях экрана), масштаб, фон.
 */
public abstract class HudModule extends Module {
    /** Позиция в долях экрана (0..1), пересчитывается под любое разрешение. */
    public float x;
    public float y;
    /** Привязка: 0 — левый край, 1 — центр, 2 — правый край. */
    public int anchorX = 0;
    public int anchorY = 0;

    public final NumberSetting scale = new NumberSetting("Масштаб", 1.0f, 0.6f, 2.0f, 0.05f, 2, "Размер элемента");
    public final BooleanSetting background = new BooleanSetting("Фон", true, "Стеклянная подложка");

    protected HudModule(String name, String description, float defX, float defY) {
        super(name, description, Category.HUD);
        this.x = defX;
        this.y = defY;
        addSettings(scale, background);
    }

    /** Ширина элемента без масштаба (для рамки в редакторе). */
    public abstract int width();

    /** Высота элемента без масштаба. */
    public abstract int height();

    /** Отрисовка содержимого. Координаты (0,0) — левый верхний угол элемента. */
    protected abstract void renderContent(GuiGraphics g, Font font, DeltaTracker dt, Theme theme, Minecraft mc);

    /** Пиксельная позиция с учётом привязки и масштаба. */
    public int pixelX(int guiWidth) {
        float s = scale.get();
        int w = (int) (width() * s);
        if (anchorX == 1) {
            return (int) (x * guiWidth) - w / 2;
        }
        if (anchorX == 2) {
            return (int) (x * guiWidth) - w;
        }
        return (int) (x * guiWidth);
    }

    public int pixelY(int guiHeight) {
        float s = scale.get();
        int h = (int) (height() * s);
        if (anchorY == 1) {
            return (int) (y * guiHeight) - h / 2;
        }
        if (anchorY == 2) {
            return (int) (y * guiHeight) - h;
        }
        return (int) (y * guiHeight);
    }

    /** Полная отрисовка элемента (вызывается рендерером HUD и редактором). */
    public void render(GuiGraphics g, DeltaTracker dt, Theme theme, Minecraft mc) {
        if (!isEnabled()) {
            return;
        }
        float s = scale.get();
        int px = pixelX(g.guiWidth());
        int py = pixelY(g.guiHeight());
        g.pose().pushMatrix();
        g.pose().translate(px, py);
        g.pose().scale(s, s);
        if (background.get()) {
            RenderUtil.rounded(g, -1, -1, width() + 2, height() + 2, 5, ColorUtil.rgba(10, 11, 18, (int) (theme.backgroundAlpha * 200)));
            RenderUtil.roundedOutline(g, -1, -1, width() + 2, height() + 2, 5, ColorUtil.rgba(255, 255, 255, 20));
        }
        renderContent(g, mc.font, dt, theme, mc);
        g.pose().popMatrix();
    }

    /** Рамка выделения в редакторе HUD. */
    public void renderEditorFrame(GuiGraphics g, Theme theme, boolean hovered, boolean dragging) {
        float s = scale.get();
        int px = pixelX(g.guiWidth());
        int py = pixelY(g.guiHeight());
        int w = (int) (width() * s);
        int h = (int) (height() * s);
        int color = dragging ? theme.accentNow() : hovered ? ColorUtil.rgba(255, 255, 255, 170) : ColorUtil.rgba(255, 255, 255, 90);
        RenderUtil.roundedOutline(g, px - 3, py - 3, w + 6, h + 6, 6, color);
    }
}
