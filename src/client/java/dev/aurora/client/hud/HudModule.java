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
            // стеклянная капсула: тень, подложка, обводка
            RenderUtil.glassCapsule(g, -3, -1, width() + 6, height() + 2, 7, theme);
            // текст должен рисоваться в отдельном слое поверх капсулы
            g.nextStratum();
        }
        renderContent(g, mc.font, dt, theme, mc);
        g.pose().popMatrix();
    }

    /* ------------------------------------------------------------------ */
    /*  Кэш строк HUD (меньше мусора и строковых операций в кадре)        */
    /* ------------------------------------------------------------------ */

    private static final java.util.Map<String, long[]> STAMPS = new java.util.HashMap<>();
    private static final java.util.Map<String, String> CACHE = new java.util.HashMap<>();

    /**
     * Возвращает закэшированное значение, обновляя его не чаще intervalMs.
     * Ключи уникальны для каждого значения (fps, ping, clock, ...).
     */
    protected static String cached(String key, long intervalMs, java.util.function.Supplier<String> supplier) {
        long now = System.currentTimeMillis();
        long[] stamp = STAMPS.get(key);
        if (stamp == null || now - stamp[0] >= intervalMs) {
            CACHE.put(key, supplier.get());
            if (stamp == null) {
                stamp = new long[1];
                STAMPS.put(key, stamp);
            }
            stamp[0] = now;
        }
        return CACHE.get(key);
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
