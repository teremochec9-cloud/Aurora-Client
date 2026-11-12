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
    /** Анимация бейджа «×N.NN»: плавное появление/скрытие. */
    private final Map<HudModule, Animation> badgeAnims = new HashMap<>();

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

        // фоновая сетка редактора (появляется вместе с экраном)
        drawGrid(g);

        // элементы + рамки (формы)
        for (Module m : ModuleManager.all()) {
            if (!(m instanceof HudModule hud)) {
                continue;
            }
            boolean over = isOver(hud, mx, my);
            if (dragging == hud) {
                // таргет драга — пиксель левого края, переводим в долю с учётом
                // якоря: при привязке справа x хранит правую кромку, прямой
                // перенос пикселя в долю поехал бы в обратную сторону
                int rawX = (int) (mx - grabDX);
                int rawY = (int) (my - grabDY);
                // прилипание к сетке редактора: край, оказавшийся ближе 4px
                // к линии сетки (шаг 10px), прилипает к ней — позиции выравниваются
                int snapStep = 10;
                int snapX = Math.round(rawX / (float) snapStep) * snapStep;
                int snapY = Math.round(rawY / (float) snapStep) * snapStep;
                if (Math.abs(snapX - rawX) <= 4) {
                    rawX = snapX;
                }
                if (Math.abs(snapY - rawY) <= 4) {
                    rawY = snapY;
                }
                float tx = Math.max(0f, Math.min(1f,
                        hud.fractionForTL(rawX, g.guiWidth())));
                float ty = Math.max(0f, Math.min(1f,
                        hud.fractionForTL_Y(rawY, g.guiHeight())));
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
            // направляющие при драге + бейдж масштаба
            if (dragging == hud) {
                drawAnchorGuides(g, hud);
            }
            drawScaleBadge(g, hud); // сам решает, виден ли бейдж (по scaleTargets)
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
    /*  Сетка, направляющие и бейдж масштаба                              */
    /* ------------------------------------------------------------------ */

    /**
     * Фоновая сетка редактора: мягкие точки с шагом 20px, акцентные
     * «крестики» на крупных узлах каждые 100px и оси по центру.
     * Раньше сетка была сплошными линиями — рябила и спорила с элементами.
     */
    private void drawGrid(GuiGraphics g) {
        float at = Animation.smooth(appear.get());
        if (at < 0.05f) {
            return;
        }
        int w = this.width;
        int h = this.height;
        int step = 20;
        int majorEvery = 5; // каждый 5-й узел = 100px
        // мягкие точки сетки
        int dotA = (int) (30 * at);
        for (int x = 0; x <= w; x += step) {
            boolean majorX = (x / step) % majorEvery == 0;
            for (int y = 0; y <= h; y += step) {
                boolean major = majorX && (y / step) % majorEvery == 0;
                if (major) {
                    // крупный узел — акцентная точка с «крестиком»
                    int a = (int) (85 * at);
                    int accent = theme.accentNow();
                    GfxEngine.rounded(g, x - 2.5f, y - 0.6f, 6, 1.2f, 0.6f, ColorUtil.alpha(accent, a));
                    GfxEngine.rounded(g, x - 0.6f, y - 2.5f, 1.2f, 6, 0.6f, ColorUtil.alpha(accent, a));
                } else {
                    GfxEngine.circle(g, x, y, 1.7f, ColorUtil.rgba(255, 255, 255, dotA));
                }
            }
        }
        // центральные оси — акцентом темы с мягким основанием
        int axisA = (int) (58 * at);
        int accent = theme.accentNow();
        GfxEngine.rounded(g, w / 2f - 1.5f, 0, 3, h, 1.5f, ColorUtil.alpha(accent, axisA / 3));
        GfxEngine.rounded(g, w / 2f, 0, 1, h, 0.5f, ColorUtil.alpha(accent, axisA));
        GfxEngine.rounded(g, 0, h / 2f - 1.5f, w, 3, 1.5f, ColorUtil.alpha(accent, axisA / 3));
        GfxEngine.rounded(g, 0, h / 2f, w, 1, 0.5f, ColorUtil.alpha(accent, axisA));
    }

    /** Направляющие при драге: подсветка совпадения краёв/центра с осями и краями экрана. */
    private void drawAnchorGuides(GuiGraphics g, HudModule hud) {
        int[] bb = hud.screenBBox(this.width, this.height);
        int cx = bb[0] + bb[2] / 2;
        int cy = bb[1] + bb[3] / 2;
        int snap = 6;
        if (Math.abs(cx - this.width / 2) <= snap) {
            vGuide(g, this.width / 2);
        }
        if (Math.abs(bb[0]) <= snap) {
            vGuide(g, 0);
        }
        if (Math.abs(bb[0] + bb[2] - this.width) <= snap) {
            vGuide(g, this.width - 1);
        }
        if (Math.abs(cy - this.height / 2) <= snap) {
            hGuide(g, this.height / 2);
        }
        if (Math.abs(bb[1]) <= snap) {
            hGuide(g, 0);
        }
        if (Math.abs(bb[1] + bb[3] - this.height) <= snap) {
            hGuide(g, this.height - 1);
        }
    }

    /** Вертикальная направляющая: широкое мягкое основание + яркая жила. */
    private void vGuide(GuiGraphics g, int x) {
        int accent = theme.accentNow();
        GfxEngine.rounded(g, x - 1, 0, 3, this.height, 1.5f, ColorUtil.alpha(accent, 70));
        GfxEngine.rounded(g, x, 0, 1, this.height, 0.5f, ColorUtil.alpha(accent, 220));
    }

    /** Горизонтальная направляющая. */
    private void hGuide(GuiGraphics g, int y) {
        int accent = theme.accentNow();
        GfxEngine.rounded(g, 0, y - 1, this.width, 3, 1.5f, ColorUtil.alpha(accent, 70));
        GfxEngine.rounded(g, 0, y, this.width, 1, 0.5f, ColorUtil.alpha(accent, 220));
    }

    /** Бейдж «×1.25» над элементом во время плавного изменения масштаба. */
    private void drawScaleBadge(GuiGraphics g, HudModule hud) {
        // плавное появление/скрытие бейджа: цель зависит от того,
        // меняется ли сейчас масштаб элемента
        Animation anim = badgeAnims.computeIfAbsent(hud, k -> new Animation(0, 14));
        float ba = anim.apply(theme.animations && scaleTargets.containsKey(hud) ? 1f : 0f);
        if (ba < 0.03f) {
            return;
        }
        String text = "×" + String.format("%.2f", hud.scale.get());
        int[] bb = hud.screenBBox(this.width, this.height);
        int wpx = RenderUtil.width(font, text, theme) + 12;
        int bx = bb[0] + bb[2] / 2 - wpx / 2;
        int by = Math.max(2, bb[1] - 17);
        float pop = Animation.easeOutBack(Animation.smooth(ba));
        g.pose().pushMatrix();
        g.pose().translate(bx + wpx / 2f, by + 6.5f);
        float ps = 0.85f + 0.15f * pop;
        g.pose().scale(ps, ps);
        g.pose().translate(-(bx + wpx / 2f), -(by + 6.5f));
        GfxEngine.glow(g, bx - 2, by - 2, wpx + 4, 17, 6.5f, 4f, ColorUtil.alpha(theme.accentNow(), (int) (90 * ba)));
        GfxEngine.rounded(g, bx, by, wpx, 13, 6.5f, ColorUtil.alpha(theme.accentNow(), (int) (205 * ba)));
        RenderUtil.drawCenteredText(g, font, text, bb[0] + bb[2] / 2f, by + 3,
                ColorUtil.rgba(255, 255, 255, (int) (255 * ba)), theme);
        g.pose().popMatrix();
    }

    /* ------------------------------------------------------------------ */
    /*  Нижняя панель подсказок: ширина подобрана так, чтобы весь текст   */
    /*  помещался целиком — без обрезки троеточием                        */
    /* ------------------------------------------------------------------ */

    private static final String HINT_FULL = "ЛКМ — перетащить · Колесо — масштаб · ПКМ — фон · ESC — готово";
    private static final String HINT_SHORT = "ЛКМ — тянуть · Колесо — размер · ПКМ — фон · ESC";
    private static final String HINT_MINI = "ЛКМ · Колесо · ПКМ · ESC";

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
                hud.anchorX = 0;
                hud.anchorY = 0;
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
        AuroraConfig.save();
        super.removed();
    }
}
