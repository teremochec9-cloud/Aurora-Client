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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Редактор HUD: перетаскивай элементы, крути колесо — масштаб, ПКМ — фон.
 */
public class HudEditorScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Aurora/Hud");

    private final Theme theme;
    /** Экран, на который вернуться при закрытии (обычно — меню клиента). */
    private final Screen parent;
    private HudModule dragging;
    private float grabDX, grabDY;
    private int mouseX, mouseY;
    private final Animation appear = new Animation(0, 10);

    /** Плавное перетаскивание: элементы догоняют курсор по экспоненте. */
    private long lastFrameNanos = System.nanoTime();
    /** Целевой масштаб при прокрутке колесом (элемент плавно его достигает). */
    private final Map<HudModule, Float> scaleTargets = new HashMap<>();

    /** Кнопка «Сбросить позиции» [x, y, w, h]. */
    private int[] resetBtn;

    /** Смоук-харнесс: bbox-ассерты логируются один раз после открытия. */
    private long openedAt;
    private boolean bboxLogged;

    public HudEditorScreen() {
        this(null);
    }

    public HudEditorScreen(Screen parent) {
        super(Component.literal("HUD Editor"));
        this.theme = AuroraClient.theme();
        this.parent = parent;
    }

    @Override
    public void added() {
        appear.snap(theme.animations ? 0 : 1);
        appear.to(1);
        openedAt = System.currentTimeMillis();
        bboxLogged = false;
    }

    @Override
    public void tick() {
        // без обновления каждый тик анимация появления застывала на нуле:
        // альфа панели и подсказки оставалась 0 и текст был невидим
        appear.update();
        // программный ассерт приёмки: «все элементы в экране», раз на открытие
        if (!bboxLogged && System.currentTimeMillis() - openedAt > 1500) {
            bboxLogged = true;
            logBBoxes("при открытии");
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.mouseX = mx;
        this.mouseY = my;
        float dt = frameDelta();

        // красивая сетка редактора (под элементами) + перекрестие курсора
        drawEditorGrid(g);

        // элементы + рамки (формы)
        for (Module m : ModuleManager.all()) {
            if (!(m instanceof HudModule hud)) {
                continue;
            }
            boolean over = isOver(hud, mx, my);
            if (dragging == hud) {
                // таргет драга — пиксель левого края, переводим в долю с учётом
                // якоря: при привязке справа x хранит правую кромку, прямой
                // перенос пикселя в долю поехал бы в обратную сторону.
                // Привязка к сетке: шаг 5 gui-пикселей (как в современных клиентах)
                int tlPx = Math.round(mx - grabDX);
                int tlPy = Math.round(my - grabDY);
                if (theme.hudGrid) {
                    tlPx = Math.round(tlPx / 5f) * 5;
                    tlPy = Math.round(tlPy / 5f) * 5;
                }
                float tx = Math.max(0f, Math.min(1f, hud.fractionForTL(tlPx, g.guiWidth())));
                float ty = Math.max(0f, Math.min(1f, hud.fractionForTL_Y(tlPy, g.guiHeight())));
                float k = 1f - (float) Math.exp(-16f * dt);
                hud.x += (tx - hud.x) * k;
                hud.y += (ty - hud.y) * k;
                // bbox-кламп: элемент не покидает экран ни на один кадр
                // (при любом якоре и масштабе)
                hud.clampToScreen(g.guiWidth(), g.guiHeight());
            }
            // плавное изменение масштаба колесом
            Float target = scaleTargets.get(hud);
            if (target != null) {
                float k = 1f - (float) Math.exp(-14f * dt);
                float nv = hud.scale.get() + (target - hud.scale.get()) * k;
                hud.scale.set(nv);
                if (Math.abs(target - nv) < 0.002f) {
                    hud.scale.set(target);
                    scaleTargets.remove(hud);
                    AuroraConfig.markDirty();
                }
            }
            hud.renderEditor(g, Minecraft.getInstance().getDeltaTracker(), theme, Minecraft.getInstance());
            hud.renderEditorFrame(g, theme, over, dragging == hud);
        }

        drawBottomBarShapes(g);

        g.nextStratum();

        drawBottomBarTexts(g);
    }

    /** Дельта времени между кадрами (для анимаций, независимых от FPS). */
    private float frameDelta() {
        long now = System.nanoTime();
        float dt = (now - lastFrameNanos) / 1e9f;
        lastFrameNanos = now;
        return Math.max(0.001f, Math.min(0.1f, dt));
    }

    /* ------------------------------------------------------------------ */
    /*  Сетка редактора: мелкая линия + акцентные мажорные линии +        */
    /*  перекрестие курсора — как в современных HUD-редакторах            */
    /* ------------------------------------------------------------------ */

    /** Смоук-тест полигонов: AURORA_PTS_DEBUG=1. */
    private static final boolean PTS_DEBUG_SHAPE = "1".equals(System.getenv("AURORA_PTS_DEBUG"));
    /** Шаг сетки (gui-пиксели). */
    private static final int GRID_CELL = 10;
    /** Каждая 5-я линия — мажорная (50 px). */
    private static final int GRID_MAJOR = 5;

    private void drawEditorGrid(GuiGraphics g) {
        // смоук-тест полигонов: звезда (вогнутый, 10 вершин) и треугольник (выпуклый)
        if (PTS_DEBUG_SHAPE) {
            GfxEngine.pathFill(g, new float[]{
                    470, 295, 475.9f, 311.9f, 493.8f, 312.3f, 479.5f, 323.1f,
                    484.7f, 340.2f, 470, 330, 455.3f, 340.2f, 460.5f, 323.1f,
                    446.2f, 312.3f, 464.1f, 311.9f,
            }, 10, ColorUtil.rgba(255, 80, 80, 220));
            GfxEngine.pathFill(g, new float[]{
                    540, 300, 580, 300, 560, 340,
            }, 3, ColorUtil.rgba(80, 255, 120, 220));
        }
        if (!theme.hudGrid) {
            return;
        }
        float at = Animation.smooth(appear.get());
        if (at < 0.02f) {
            return;
        }
        int w = this.width;
        int h = this.height;
        int minor = ColorUtil.rgba(255, 255, 255, (int) (13 * at));
        int major = ColorUtil.alpha(theme.accentNow(), (int) (26 * at));
        for (int x = 0, i = 0; x <= w; x += GRID_CELL, i++) {
            GfxEngine.rounded(g, x, 0, 1, h, 0, i % GRID_MAJOR == 0 ? major : minor);
        }
        for (int y = 0, i = 0; y <= h; y += GRID_CELL, i++) {
            GfxEngine.rounded(g, 0, y, w, 1, 0, i % GRID_MAJOR == 0 ? major : minor);
        }
        // перекрестие под курсором — акцентное, с мягким пятном
        int cross = ColorUtil.alpha(theme.accentNow(), (int) (60 * at));
        GfxEngine.rounded(g, mouseX, 0, 1, h, 0, cross);
        GfxEngine.rounded(g, 0, mouseY, w, 1, 0, cross);
        GfxEngine.circle(g, mouseX, mouseY, 10, ColorUtil.alpha(theme.accentNow(), (int) (26 * at)));
    }

    /* ------------------------------------------------------------------ */
    /*  Нижняя панель подсказок: ширина подобрана так, чтобы весь текст   */
    /*  помещался целиком — без обрезки троеточием                        */
    /* ------------------------------------------------------------------ */

    private static final String HINT_FULL = "ЛКМ — перетащить · Колесо — масштаб · ПКМ — фон · G — сетка · ESC — готово";
    private static final String HINT_SHORT = "ЛКМ — тянуть · Колесо — размер · ПКМ — фон · G — сетка · ESC";
    private static final String HINT_MINI = "ЛКМ · Колесо · ПКМ · G · ESC";

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
        // стеклянная нижняя панель
        GfxEngine.rounded(g, barX, barY, barW, barH, 10,
                ColorUtil.alpha(theme.panelColor(), (int) (ColorUtil.alphaI(theme.panelColor()) * at)));
        GfxEngine.roundedGradient(g, barX, barY, barW, barH, 10,
                ColorUtil.rgba(255, 255, 255, (int) (22 * at)), ColorUtil.rgba(255, 255, 255, (int) (3 * at)));
        GfxEngine.outline(g, barX, barY, barW, barH, 10, ColorUtil.rgba(255, 255, 255, (int) (42 * at)), 1f);
        GfxEngine.rounded(g, barX + 16, barY + 1, Math.max(4, barW - 32), 1, 0.5f,
                ColorUtil.rgba(255, 255, 255, (int) (56 * at)));

        // кнопка сброса (стекло)
        int rx = barX + barW - pad - rw;
        boolean hoverReset = mouseX >= rx && mouseX <= rx + rw && mouseY >= barY && mouseY <= barY + barH;
        RenderUtil.glassChip(g, rx, barY + 3, rw, barH - 6, 9,
                hoverReset ? ColorUtil.alpha(theme.accentNow(), 130) : ColorUtil.rgba(255, 255, 255, (int) (18 * at)),
                hoverReset ? ColorUtil.alpha(theme.accentNow(), 200) : ColorUtil.rgba(255, 255, 255, (int) (30 * at)), theme);
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
        // подсказка гарантированно не заходит на кнопку сброса:
        // выбираем самый короткий вариант и дополнительно обрезаем
        int avail = this.width - 16 - 20 - titleW - 16 - 14 - rw;
        String hintSafe = RenderUtil.ellipsize(font, hint, Math.max(40, avail), theme);
        RenderUtil.drawTextBold(g, font, "HUD-редактор", barX + pad, textY, theme.textPrimary(), theme);
        RenderUtil.drawText(g, font, hintSafe, barX + pad + titleW + 16, textY,
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
        if (RenderUtil.width(font, HINT_SHORT, theme) <= available) {
            return HINT_SHORT;
        }
        return HINT_MINI;
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
                // якоря восстанавливаем к заводским — у части элементов они
                // нестандартные (Часы — центр, Сторона света — правый край),
                // сброс в (0,0) уезжал их за экран (баг 1.0.5)
                hud.anchorX = defaultAnchorX(hud);
                hud.anchorY = defaultAnchorY(hud);
            }
        }
        AuroraConfig.save();
    }

    private int defaultAnchorX(HudModule hud) {
        if (hud instanceof DirectionHud) return 2;
        if (hud instanceof ClockHud) return 1;
        return 0;
    }

    private int defaultAnchorY(HudModule hud) {
        if (hud instanceof KeystrokesHud) return 1;
        return 0;
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
        int px = hud.clampedPixelX(this.width);
        int py = hud.clampedPixelY(this.height);
        int w = (int) Math.floor(hud.width() * s);
        int h = (int) Math.floor(hud.height() * s);
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
                    grabDX = mx - hud.clampedPixelX(this.width);
                    grabDY = my - hud.clampedPixelY(this.height);
                    scaleTargets.remove(hud);
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
            rebindAnchor(dragging);
            AuroraConfig.markDirty();
            logBBoxes("после перетаскивания");
        }
        if (dragging != null || !scaleTargets.isEmpty()) {
            AuroraConfig.markDirty();
        }
        dragging = null;
        return super.mouseReleased(e);
    }

    /**
     * Перепривязка якоря к ближайшему краю/центру после драга. Критерий
     * приёмки: элемент НЕ смещается ни на один пиксель — меняется только
     * точка отсчёта. Проверяется ассертом на месте.
     */
    private void rebindAnchor(HudModule hud) {
        int[] bb = hud.screenBBox(this.width, this.height);
        int ax = hud.anchorX;
        int ay = hud.anchorY;
        boolean left = bb[0] <= 3;
        boolean right = bb[0] + bb[2] >= this.width - 3;
        boolean midX = Math.abs(bb[0] + bb[2] / 2f - this.width / 2f) <= 3;
        if (left) {
            ax = 0;
        } else if (right) {
            ax = 2;
        } else if (midX) {
            ax = 1;
        }
        boolean top = bb[1] <= 3;
        boolean bottom = bb[1] + bb[3] >= this.height - 3;
        boolean midY = Math.abs(bb[1] + bb[3] / 2f - this.height / 2f) <= 3;
        if (top) {
            ay = 0;
        } else if (bottom) {
            ay = 2;
        } else if (midY) {
            ay = 1;
        }
        if (ax == hud.anchorX && ay == hud.anchorY) {
            return;
        }
        hud.setAnchorKeepPixel(ax, ay, this.width, this.height);
        // ассерт: bbox до отпускания мыши и после — совпадают
        int[] ab = hud.screenBBox(this.width, this.height);
        long drift = Math.abs((long) ab[0] - bb[0]) + Math.abs((long) ab[1] - bb[1])
                + Math.abs((long) ab[2] - bb[2]) + Math.abs((long) ab[3] - bb[3]);
        if (drift > 0) {
            LOGGER.warn("AURORA_HUD_ANCHOR: '{}' сдвинулся на {} px при перепривязке якоря ({},{}) — БАГ",
                    hud.name, drift, ax, ay);
        } else {
            LOGGER.info("AURORA_HUD_ANCHOR: '{}' якорь ({},{}) перепривязан, bbox на месте", hud.name, ax, ay);
        }
    }

    /** Смоук-харнесс: программный ассерт «bbox каждого элемента в экране». */
    private void logBBoxes(String phase) {
        for (Module m : ModuleManager.all()) {
            if (!(m instanceof HudModule hud)) {
                continue;
            }
            int[] bb = hud.screenBBox(this.width, this.height);
            int px = hud.clampedPixelX(this.width);
            int py = hud.clampedPixelY(this.height);
            // рендер-bbox начинается в (px-3, py-1) — капсула рисуется
            // с полями -3/-1 от точки перевода; проверяем именно его
            boolean ok = px - 3 >= 0 && py - 1 >= 0
                    && px - 3 + bb[2] <= this.width && py - 1 + bb[3] <= this.height;
            LOGGER.info("AURORA_HUD_BBOX [{}]: {} px=({},{}), размер={}x{}, экран={}x{}, вЭкране={}",
                    phase, hud.name, px, py, bb[2], bb[3], this.width, this.height, ok);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        for (Module m : ModuleManager.all()) {
            if (m instanceof HudModule hud && isOver(hud, (int) x, (int) y)) {
                // не меняем масштаб мгновенно — задаём цель, к которой
                // элемент плавно прибудет (см. render)
                float target = scaleTargets.getOrDefault(hud, hud.scale.get());
                target += (float) (scrollY * 0.05f);
                target = Math.max(hud.scale.min, Math.min(hud.scale.max, target));
                scaleTargets.put(hud, target);
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
        // G — тумблер сетки и привязки
        if (e.key() == GLFW.GLFW_KEY_G) {
            theme.hudGrid = !theme.hudGrid;
            AuroraConfig.markDirty();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        // возвращаемся туда, откуда открыли редактор (минуя «пустой» экран)
        this.minecraft.setScreen(parent != null ? parent : null);
    }

    @Override
    public void removed() {
        // докидываем недоигранную анимацию масштаба: иначе при быстром ESC
        // элемент сохранялся с промежуточным масштабом (баг 1.0.5)
        for (var entry : scaleTargets.entrySet()) {
            entry.getKey().scale.set(entry.getValue());
        }
        scaleTargets.clear();
        AuroraConfig.save();
        super.removed();
    }
}
