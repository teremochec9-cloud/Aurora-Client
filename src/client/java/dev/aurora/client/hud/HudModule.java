package dev.aurora.client.hud;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.NumberSetting;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.GfxEngine;
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

    /** Пиксельная позиция с учётом привязки и масштаба. floor вместо (int):
     *  усечение к нулю на отрицательных координатах давало off-by-one. */
    public int pixelX(int guiWidth) {
        float s = scale.get();
        int w = (int) Math.floor(width() * s);
        int px = (int) Math.floor(x * guiWidth);
        if (anchorX == 1) {
            return px - w / 2;
        }
        if (anchorX == 2) {
            return px - w;
        }
        return px;
    }

    public int pixelY(int guiHeight) {
        float s = scale.get();
        int h = (int) Math.floor(height() * s);
        int py = (int) Math.floor(y * guiHeight);
        if (anchorY == 1) {
            return py - h / 2;
        }
        if (anchorY == 2) {
            return py - h;
        }
        return py;
    }

    /**
     * Доля x, при которой ЛЕВЫЙ край элемента окажется в tlPx. Зависит от якоря:
     * при привязке к правому краю x хранит позицию ПРАВОЙ кромки, поэтому
     * пиксельный таргет драга нельзя писать в x напрямую — знак поедет в
     * обратную сторону. Все таргеты драга считаются только через этот метод.
     */
    public float fractionForTL(int tlPx, int guiWidth) {
        float s = scale.get();
        int w = (int) Math.floor(width() * s);
        return switch (anchorX) {
            case 1 -> (tlPx + w / 2f) / guiWidth;
            case 2 -> (tlPx + w) / (float) guiWidth;
            default -> tlPx / (float) guiWidth;
        };
    }

    /** Аналог {@link #fractionForTL(int, int)} для вертикальной оси. */
    public float fractionForTL_Y(int tlPy, int guiHeight) {
        float s = scale.get();
        int h = (int) Math.floor(height() * s);
        return switch (anchorY) {
            case 1 -> (tlPy + h / 2f) / guiHeight;
            case 2 -> (tlPy + h) / (float) guiHeight;
            default -> tlPy / (float) guiHeight;
        };
    }

    /* ------------------------------------------------------------------ */
    /*  Границы экрана: bbox, кламп и перепривязка якоря                  */
    /* ------------------------------------------------------------------ */

    /**
     * Визуальный bbox на экране с учётом стеклянной капсулы и масштаба:
     * int[]{x, y, w, h} в gui-пикселях. Именно его используют кламп,
     * hit-тест редактора и проверки «элемент в пределах экрана».
     */
    public int[] screenBBox(int guiWidth, int guiHeight) {
        return new int[]{pixelX(guiWidth) - 3, pixelY(guiHeight) - 1,
                effBBoxWidth(), effBBoxHeight()};
    }

    /** Кэш ширины призрачной подписи «(выкл)»: ключ — текст (статичен для
     *  элемента) + шрифт (customFont + экземпляр Font), без scale — он
     *  применяется снаружи. Ничего динамического в подписи нет. */
    private int ghostLabelW = -1;
    private boolean ghostLabelWFontFlag;
    private Font ghostLabelWFontRef;

    private int ghostLabelWidth() {
        Minecraft mc = Minecraft.getInstance();
        boolean fontFlag = AuroraClient.theme().customFont;
        if (ghostLabelW == -1 || ghostLabelWFontFlag != fontFlag || ghostLabelWFontRef != mc.font) {
            ghostLabelW = RenderUtil.width(mc.font, name + " (выкл)", AuroraClient.theme()) + 12;
            ghostLabelWFontFlag = fontFlag;
            ghostLabelWFontRef = mc.font;
        }
        return ghostLabelW;
    }

    /** Эффективная ширина bbox (в gui-px): капсула + призрачная подпись у
     *  выключенных элементов. Единая для рендера, клампа и hit-теста —
     *  иначе кламп и ассерты мерили разные ширины (поймано ассертом). */
    private int effBBoxWidth() {
        float s = scale.get();
        int w = (int) Math.floor((width() + 6) * s);
        if (!isEnabled()) {
            int lw = (int) Math.floor(ghostLabelWidth() * s);
            if (lw > w) {
                w = lw;
            }
        }
        return w;
    }

    /** Эффективная высота bbox (в gui-px). */
    private int effBBoxHeight() {
        return (int) Math.floor((height() + 2) * scale.get());
    }

    /**
     * Пиксельная позиция с чистым клампом в экран: x/y НЕ мутирует.
     * Используется рендером и hit-тестом — resize окна и смена guiScale
     * сразу не дают элементу уехать за край. Персист — при сейве конфига.
     */
    public int clampedPixelX(int guiWidth) {
        int px = pixelX(guiWidth);
        int bw = effBBoxWidth();
        if (px - 3 < 0) {
            return 3;
        }
        if (px - 3 + bw > guiWidth) {
            return Math.max(3, guiWidth - bw + 3);
        }
        return px;
    }

    /** Аналог {@link #clampedPixelX(int)} для вертикальной оси. */
    public int clampedPixelY(int guiHeight) {
        int py = pixelY(guiHeight);
        int bh = effBBoxHeight();
        if (py - 1 < 0) {
            return 1;
        }
        if (py - 1 + bh > guiHeight) {
            return Math.max(1, guiHeight - bh + 1);
        }
        return py;
    }

    /**
     * Меняет привязку якорей, сохраняя пиксельное положение элемента:
     * x/y пересчитываются под новую привязку, bbox остаётся на месте.
     * Никакого «прыжка» при перепривязке быть не должно.
     */
    public void setAnchorKeepPixel(int ax, int ay, int guiWidth, int guiHeight) {
        ax = Math.max(0, Math.min(2, ax));
        ay = Math.max(0, Math.min(2, ay));
        if (ax == anchorX && ay == anchorY) {
            return;
        }
        int px = pixelX(guiWidth);
        int py = pixelY(guiHeight);
        anchorX = ax;
        anchorY = ay;
        // инверсия pixelX/pixelY: долю подбираем так, чтобы пиксельная
        // позиция осталась прежней; коррекция добирает остаток округления
        x = px / (float) guiWidth;
        x += (px - pixelX(guiWidth)) / (float) guiWidth;
        y = py / (float) guiHeight;
        y += (py - pixelY(guiHeight)) / (float) guiHeight;
    }

    /**
     * Прижимает элемент к границам экрана так, чтобы его bbox не выходил
     * за пределы. Работает с любой привязкой: пиксельный сдвиг переводится
     * в доли одинаково при любом якоре. Возвращает true, если сдвинули.
     */
    public boolean clampToScreen(int guiWidth, int guiHeight) {
        int[] bb = screenBBox(guiWidth, guiHeight);
        int sx = 0;
        int sy = 0;
        if (bb[0] < 0) {
            sx = -bb[0];
        } else if (bb[0] + bb[2] > guiWidth) {
            sx = guiWidth - bb[0] - bb[2];
        }
        if (bb[1] < 0) {
            sy = -bb[1];
        } else if (bb[1] + bb[3] > guiHeight) {
            sy = guiHeight - bb[1] - bb[3];
        }
        if (sx == 0 && sy == 0) {
            return false;
        }
        x += sx / (float) guiWidth;
        y += sy / (float) guiHeight;
        return true;
    }

    /** Полная отрисовка элемента (вызывается рендерером HUD). */
    public void render(GuiGraphics g, DeltaTracker dt, Theme theme, Minecraft mc) {
        // плавное появление/скрытие: цель — состояние модуля
        vis.to(isEnabled() ? 1f : 0f);
        float v = vis.update();
        if (v < 0.02f) {
            return;
        }
        float s = scale.get();
        int px = clampedPixelX(g.guiWidth());
        int py = clampedPixelY(g.guiHeight());
        float ease = Animation.smooth(v);
        float cxp = px + width() * s / 2f;
        float cyp = py + height() * s / 2f;
        g.pose().pushMatrix();
        // выезд сверху + лёгкий зум вокруг центра элемента
        float zoom = 0.9f + 0.1f * ease;
        g.pose().translate(cxp, cyp);
        g.pose().scale(zoom, zoom);
        g.pose().translate(-cxp, -cyp);
        g.pose().translate(0, -(1f - ease) * 6f);
        renderInner(g, dt, theme, mc, ease);
        g.pose().popMatrix();
    }

    /** Анимация видимости (1 — показан, 0 — скрыт). */
    private final Animation vis = new Animation(0, 13);

    /**
     * Отрисовка в редакторе HUD: включённые элементы рисуются как в игре,
     * выключенные — «призраками» (подложка + название), чтобы их можно было
     * найти, перетащить и включить.
     */
    public void renderEditor(GuiGraphics g, DeltaTracker dt, Theme theme, Minecraft mc) {
        if (isEnabled()) {
            renderInner(g, dt, theme, mc, 1f);
            return;
        }
        float s = scale.get();
        int px = clampedPixelX(g.guiWidth());
        int py = clampedPixelY(g.guiHeight());
        g.pose().pushMatrix();
        g.pose().translate(px, py);
        g.pose().scale(s, s);
        // стеклянный «призрак» выключенного элемента
        GfxEngine.rounded(g, -3, -1, width() + 6, height() + 2, 7, ColorUtil.rgba(255, 255, 255, 16));
        GfxEngine.roundedGradient(g, -3, -1, width() + 6, height() + 2, 7,
                ColorUtil.rgba(255, 255, 255, 14), ColorUtil.rgba(255, 255, 255, 2));
        GfxEngine.outline(g, -3, -1, width() + 6, height() + 2, 7, ColorUtil.rgba(255, 255, 255, 52), 1f);
        // подпись у правого края элемента — иначе у «прижатых» к правому краю
        // элементов текст призрака уходил за границу экрана
        String label = name + " (выкл)";
        int labelW = RenderUtil.width(mc.font, label, theme);
        float labelX = anchorX == 2 ? width() - labelW - 4 : 4;
        // двухцветная подпись: имя ярче, статус «(выкл)» приглушён
        int nameW = RenderUtil.width(mc.font, name, theme);
        RenderUtil.drawText(g, mc.font, name, labelX, 4,
                ColorUtil.rgba(255, 255, 255, 175), theme);
        RenderUtil.drawText(g, mc.font, " (выкл)", labelX + nameW, 4,
                ColorUtil.rgba(255, 255, 255, 85), theme);
        g.pose().popMatrix();
    }

    /** Общая внутренняя отрисовка (подложка + содержимое). */
    private void renderInner(GuiGraphics g, DeltaTracker dt, Theme theme, Minecraft mc, float alpha) {
        float s = scale.get();
        int px = clampedPixelX(g.guiWidth());
        int py = clampedPixelY(g.guiHeight());
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

    /** Рамка выделения в редакторе HUD: свечение и угловые маркеры при драге;
     *  наведение подсвечивается плавно (по экспоненте, а не скачком). */
    private final Animation editorHover = new Animation(0, 11);

    public void renderEditorFrame(GuiGraphics g, Theme theme, boolean hovered, boolean dragging) {
        float hoverT = editorHover.apply(hovered && !dragging ? 1f : 0f);
        float s = scale.get();
        int px = clampedPixelX(g.guiWidth());
        int py = clampedPixelY(g.guiHeight());
        int w = (int) Math.floor(width() * s);
        int h = (int) Math.floor(height() * s);
        if (dragging) {
            // мягкое акцентное свечение + углы — читается как «держишь элемент»
            GfxEngine.glow(g, px - 8, py - 8, w + 16, h + 16, 10, 9f,
                    ColorUtil.alpha(theme.accentNow(), 85));
            RenderUtil.roundedOutline(g, px - 3, py - 3, w + 6, h + 6, 6, theme.accentNow());
            int corner = theme.accentNow();
            RenderUtil.rounded(g, px - 5, py - 5, 4, 4, 1.5f, corner);
            RenderUtil.rounded(g, px + w + 1, py - 5, 4, 4, 1.5f, corner);
            RenderUtil.rounded(g, px - 5, py + h + 1, 4, 4, 1.5f, corner);
            RenderUtil.rounded(g, px + w + 1, py + h + 1, 4, 4, 1.5f, corner);
        } else {
            // плавная подсветка: от едва заметной рамки к акцентной при наведении
            float k = Animation.smooth(hoverT);
            int a = (int) (80 + 140 * k);
            int col = k > 0.55f
                    ? ColorUtil.alpha(theme.accentNow(), a)
                    : ColorUtil.rgba(255, 255, 255, a);
            if (k > 0.05f) {
                GfxEngine.glow(g, px - 6, py - 6, w + 12, h + 12, 8, 6f,
                        ColorUtil.alpha(theme.accentNow(), (int) (55 * k)));
            }
            RenderUtil.roundedOutline(g, px - 3, py - 3, w + 6, h + 6, 6, col);
        }
    }
}
