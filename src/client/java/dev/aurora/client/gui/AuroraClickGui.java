package dev.aurora.client.gui;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.config.AuroraConfig;
import dev.aurora.client.hud.HudEditorScreen;
import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.settings.BindSetting;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.ColorSetting;
import dev.aurora.client.module.settings.ModeSetting;
import dev.aurora.client.module.settings.NumberSetting;
import dev.aurora.client.module.settings.Setting;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.FrameClock;
import dev.aurora.client.util.GfxEngine;
import dev.aurora.client.util.IconPainter;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClickGUI Aurora: единое стеклянное окно (радиус 12) с боковой панелью,
 * карточками модулей в две колонки (радиус 8), pill-тумблерами, плавными
 * слайдерами, поиском и кнопкой HUD-редактора.
 *
 * <p>Отрисовка разбита на слои (strata): сначала все формы SDF-движка,
 * затем текст — поэтому панели никогда не перекрывают подписи, а
 * ванильный блюр фона выполняется ровно один раз за кадр.</p>
 */
public class AuroraClickGui extends Screen {
    private static final Logger LOG = LoggerFactory.getLogger("Aurora/ClickGui");
    /** Разовая диагностика геометрии. */
    private static boolean geoLogged;
    private static final int WIN_W = 640;
    private static final int WIN_H = 360;
    private static final int WIN_R = 14;
    private static final int SIDEBAR_W = 170;
    private static final int GAP = 8;
    private static final int CARD_R = 10;
    private static final int SEARCH_W = 168;
    /** Высота строки категории и шаг между ними. */
    private static final int CAT_H = 24;
    private static final int CAT_STEP = 26;

    /** Дым за меню: x, y, r, speedY, swayAmp, swayFreq, phase, alpha — 5 медленных пятен. */
    private static final float[] MENU_SMOKE = {
            0.15f, 0.85f, 0.34f, 7f, 26f, 0.05f, 0.0f, 92f,
            0.80f, 0.72f, 0.30f, 9f, 30f, 0.04f, 1.7f, 78f,
            0.45f, 0.10f, 0.40f, 5f, 24f, 0.06f, 3.1f, 70f,
            0.04f, 0.28f, 0.26f, 11f, 20f, 0.045f, 4.4f, 62f,
            0.94f, 0.22f, 0.24f, 8f, 26f, 0.055f, 5.6f, 62f,
    };
    private static final int MENU_PUFFS = 5;
    private static final int MPF = 8;

    private final Theme theme;
    private final Animation openAnim = new Animation(0, 13);
    private final Animation tabAnim = new Animation(0, 12);
    private final Map<Module, Animation> hoverAnims = new HashMap<>();
    private final Map<Module, Animation> toggleAnims = new HashMap<>();
    private final Map<Module, Animation> expandAnims = new HashMap<>();
    private final Map<Module, Boolean> expanded = new HashMap<>();

    /** Плавные hover/active-анимации элементов меню (обновляются покадрово). */
    private final Map<Category, Animation> catHoverAnims = new HashMap<>();
    private final Map<Category, Animation> catActiveAnims = new HashMap<>();
    private final Animation themeRowHoverAnim = new Animation(0, 14);
    private final Animation themeRowActiveAnim = new Animation(0, 12);
    private final Animation hudBtnHoverAnim = new Animation(0, 14);
    private final Animation searchFocusAnim = new Animation(0, 13);
    private final Animation[] footerHoverAnims = {new Animation(0, 14), new Animation(0, 14)};
    /** Кружки палитры в сайдбаре и на вкладке «Тема». */
    private final Animation[] paletteHoverAnims = new Animation[Theme.PRESETS.length];
    private final Animation[] paletteActiveAnims = new Animation[Theme.PRESETS.length];
    private final Animation[] tpPresetHoverAnims = new Animation[Theme.PRESETS.length];
    private final Animation[] tpPresetActiveAnims = new Animation[Theme.PRESETS.length];
    /** Плавное раскрытие пипетки цвета. */
    private final Map<Object, Animation> colorOpenAnims = new HashMap<>();
    /** Текущее плавное значение силы блюра (анимация открытия). */
    private float currentBlur = -1;
    /** Время прошлого кадра — для dt-корректного сглаживания прокрутки. */
    private long lastFrameAt;

    private Category tab = Category.VISUAL;
    private boolean themeTab = false;
    private EditBox search;
    private float scrollTarget = 0;
    private float scrollPos = 0;
    private boolean closing = false;
    private int mouseX;
    private int mouseY;

    /** Захват слайдера: потребитель прогресса + геометрия дорожки. */
    private Consumer<Float> dragActiveSlider;
    private int sliderX, sliderW;

    /** Раскрытая пипетка цвета. */
    private Object openColorTarget;
    private boolean dragColorField;
    private boolean dragColorHue;
    /** Прежнее значение силы блюра, чтобы вернуть при закрытии. */
    private Integer originalBlur;

    /** Захват бинда. */
    private Object listeningBind;

    /** Редактирование никнейма (вкладка «Тема»). */
    private EditBox nickField;
    private boolean nickEditing;
    private String nickError;
    /** Геометрия строки никнейма [x, y, w, h] (обновляется каждый кадр). */
    private int[] nickRowGeom;

    /** Зоны клика, пересобираемые каждый кадр. */
    private final List<Hit> hits = new ArrayList<>();

    private static class Hit {
        final int x, y, w, h;
        final int button; // -1 любой
        final Runnable action;

        Hit(int x, int y, int w, int h, int button, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.button = button;
            this.action = action;
        }
    }

    /** Строка настроек: два прохода — формы и текст. */
    private record Row(Runnable shapes, Runnable texts) {
    }

    /** Карточка модуля: геометрия и два прохода отрисовки. */
    private static final class Card {
        final Module module;
        final int x, y, w, h;
        final int headerH;
        final List<Row> rows;
        final Runnable shapes;
        final Runnable texts;

        Card(Module module, int x, int y, int w, int h, int headerH, List<Row> rows,
             Runnable shapes, Runnable texts) {
            this.module = module;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.headerH = headerH;
            this.rows = rows;
            this.shapes = shapes;
            this.texts = texts;
        }
    }

    /** Разметка текущего кадра. */
    private final List<Card> cards = new ArrayList<>();
    private final List<Row> themeRows = new ArrayList<>();
    private int themeRowsH;
    private int bodyX, bodyY, bodyW, bodyH;
    private int maxScroll;
    /** Количество модулей в текущей вкладке — для чипа в шапке. */
    private int moduleCount;
    /** Карточку нужно проскроллить в зону видимости (раскрытие настроек). */
    private Module revealPending;

    public AuroraClickGui() {
        super(Component.literal("Aurora"));
        this.theme = AuroraClient.theme();
        // смоук-харнесс: сразу открыть вкладку «Тема» (AURORA_TEST_THEME=1)
        if ("1".equals(System.getenv("AURORA_TEST_THEME"))) {
            themeTab = true;
        }
        // смоук-харнесс: стартовая вкладка по имени enum (AURORA_TEST_TAB=PROF)
        String tabEnv = System.getenv("AURORA_TEST_TAB");
        if (tabEnv != null) {
            for (Category c : Category.values()) {
                if (c.name().equalsIgnoreCase(tabEnv)) {
                    tab = c;
                }
            }
        }
        // смоук-харнесс: раскрыть карточку модуля (AURORA_TEST_EXPAND="Target ESP")
        String expandEnv = System.getenv("AURORA_TEST_EXPAND");
        if (expandEnv != null) {
            Module m = ModuleManager.byName(expandEnv);
            if (m != null) {
                tab = m.category;
                themeTab = false;
                expanded.put(m, true);
                expandAnims.computeIfAbsent(m, k -> new Animation(1, 14)).snap(1);
                tabAnim.snap(1);
                // при первом кадре разметки проскроллить список так, чтобы
                // раскрытая карточка была видна целиком
                revealPending = m;
            }
        }
        for (int i = 0; i < paletteHoverAnims.length; i++) {
            paletteHoverAnims[i] = new Animation(0, 14);
            paletteActiveAnims[i] = new Animation(0, 11);
            tpPresetHoverAnims[i] = new Animation(0, 14);
            tpPresetActiveAnims[i] = new Animation(0, 11);
        }
    }

    public static void playClick() {
        try {
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.55f));
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Жизненный цикл                                                    */
    /* ------------------------------------------------------------------ */

    @Override
    protected void init() {
        search = new EditBox(this.font, 0, 0, SEARCH_W - 20, 16, Component.literal("Поиск"));
        search.setMaxLength(40);
        search.setBordered(false);
        // только как обработчик ввода: текст поля рисует наш движок в стекле
        addWidget(search);
        setFocused(search);
        // поле никнейма: текст рисует наш движок (см. nicknameRow)
        nickField = new AuroraTextField(this.font, 0, 0, 130, 12, Component.literal("Ник"));
        nickField.setMaxLength(16);
        nickField.setBordered(false);
        addWidget(nickField);
        // запоминаем пользовательскую силу блюра один раз — дальше анимируем
        try {
            if (originalBlur == null && Minecraft.getInstance().options != null) {
                Integer cur = (Integer) Minecraft.getInstance().options.menuBackgroundBlurriness().get();
                if (cur != null) {
                    originalBlur = cur;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Покадровые анимации (все движения — ровно один пересчёт на кадр)   */
    /* ------------------------------------------------------------------ */

    /**
     * Обновляет все анимации меню один раз за кадр (из render) — движение
     * идеально плавное на любой частоте кадров, без «ступенек» от тиков.
     */
    private void updateAnimations(float dt) {
        tabAnim.update();
        for (Animation a : hoverAnims.values()) {
            a.update();
        }
        for (Animation a : toggleAnims.values()) {
            a.update();
        }
        for (Animation a : expandAnims.values()) {
            a.update();
        }
        for (Animation a : catHoverAnims.values()) {
            a.update();
        }
        for (Animation a : catActiveAnims.values()) {
            a.update();
        }
        for (Animation a : colorOpenAnims.values()) {
            a.update();
        }
        for (Animation a : paletteHoverAnims) {
            a.update();
        }
        for (Animation a : paletteActiveAnims) {
            a.update();
        }
        for (Animation a : tpPresetHoverAnims) {
            a.update();
        }
        for (Animation a : tpPresetActiveAnims) {
            a.update();
        }
        themeRowHoverAnim.update();
        themeRowActiveAnim.update();
        hudBtnHoverAnim.update();
        searchFocusAnim.update();
        footerHoverAnims[0].update();
        footerHoverAnims[1].update();
        // прокрутка — экспоненциальное сглаживание, корректное по dt
        float k = 1.0f - (float) Math.exp(-13.0 * dt);
        scrollPos += (scrollTarget - scrollPos) * k;
        if (Math.abs(scrollTarget - scrollPos) < 0.35f) {
            scrollPos = scrollTarget;
        }
    }

    /**
     * «Размытие» открытия: ванильный gaussian-блюр плавно нарастает от 0
     * до выбранной силы вместе с анимацией окна и так же плавно гаснет
     * при закрытии. Исходное значение опции возвращается при выходе.
     */
    private void applyBlurRamp(float t) {
        if (!theme.blur) {
            return;
        }
        try {
            var options = Minecraft.getInstance().options;
            if (options == null) {
                return;
            }
            float v = theme.blurStrength * Animation.smooth(t);
            if (Math.abs(v - currentBlur) > 0.25f) {
                currentBlur = v;
                options.menuBackgroundBlurriness().set(Math.round(v));
            }
        } catch (Exception ignored) {
        }
    }

    /** Прогресс плавного раскрытия пипетки цвета. */
    private float colorOpenT(Object s) {
        boolean open = s.equals(openColorTarget);
        Animation a = colorOpenAnims.computeIfAbsent(s, k -> new Animation(0, 16));
        a.to(open ? 1 : 0);
        return a.get();
    }

    /* ------------------------------------------------------------------ */
    /*  Фон: gaussian-блюр + затемнение (ровно один вызов за кадр)         */
    /* ------------------------------------------------------------------ */

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        try {
            renderBlurredBackground(g);
        } catch (IllegalStateException e) {
            // блюр в этом кадре уже занят другим слоем — просто затемняем
        }
        g.fillGradient(0, 0, this.width, this.height,
                ColorUtil.rgba(9, 10, 16, 102), ColorUtil.rgba(8, 9, 14, 138));
        drawMenuSmoke(g);
    }

    /** Живой дым за окном меню: пять больших медленных пятен, ноль аллокаций. */
    private void drawMenuSmoke(GuiGraphics g) {
        float t = FrameClock.seconds();
        int w = this.width;
        int h = this.height;
        float base = Math.max(w, h);
        int slate = ColorUtil.rgb(96, 108, 148);
        int col = ColorUtil.lerp(slate, theme.accentNow(), 0.5f);
        for (int i = 0; i < MENU_PUFFS; i++) {
            float x0 = MENU_SMOKE[i * MPF + 0] * w;
            float y0 = MENU_SMOKE[i * MPF + 1] * h;
            float r = MENU_SMOKE[i * MPF + 2] * base;
            float speed = MENU_SMOKE[i * MPF + 3];
            float amp = MENU_SMOKE[i * MPF + 4];
            float freq = MENU_SMOKE[i * MPF + 5];
            float phase = MENU_SMOKE[i * MPF + 6];
            float alpha = MENU_SMOKE[i * MPF + 7];
            float span = h + r * 3;
            float y = ((y0 - t * speed) % span + span) % span - r * 1.5f;
            float x = x0 + (float) Math.sin(t * freq + phase) * amp;
            float breathe = 0.8f + 0.2f * (float) Math.sin(t * 0.3f + phase);
            GfxEngine.radial(g, x, y, r * 2,
                    ColorUtil.alpha(col, (int) (alpha * breathe)),
                    ColorUtil.alpha(col, 0));
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Рендер                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        float t = openAnim.update();
        if (closing) {
            if (openAnim.isDone() && openAnim.getTarget() == 0) {
                super.onClose();
                return;
            }
        }

        // все анимации — покадрово: наведение, тумблеры, раскрытие,
        // прокрутка и нарастающий блюр становятся плавными и мягкими
        long now = FrameClock.now();
        float dt = Math.min(0.1f, Math.max(0.001f, (now - lastFrameAt) / 1000.0f));
        lastFrameAt = now;
        updateAnimations(dt);
        applyBlurRamp(t);

        hits.clear();
        cards.clear();
        themeRows.clear();

        // масштаб: вписываем окно в экран + анимация открытия
        float fit = fitScale();
        float scale = fit * (theme.animations ? 0.95f + 0.05f * Animation.easeOutQuint(t) : 1.0f);
        this.uiScale = scale;
        float cx = this.width / 2f;
        float cy = this.height / 2f;
        this.mouseX = Math.round((mx - cx) / scale + cx);
        this.mouseY = Math.round((my - cy) / scale + cy);

        int winX = (this.width - WIN_W) / 2;
        int winY = (this.height - WIN_H) / 2;

        // разовая диагностика геометрии (для скриншот-самоконтроля)
        if (!geoLogged) {
            geoLogged = true;
            try {
                LOG.info("меню: gui {}x{}, scale {}, fit {}, winX {}, winY {}, uiScale {}",
                        this.width, this.height,
                        Minecraft.getInstance().getWindow().getGuiScale(),
                        String.format("%.3f", fitScale()), winX, winY,
                        String.format("%.3f", scale));
            } catch (Exception ignored) {
            }
        }

        // ===== геометрия тела =====
        bodyX = winX + SIDEBAR_W + 6;
        bodyY = winY + 10;
        bodyW = winX + WIN_W - 14 - bodyX;
        bodyH = WIN_H - 20;

        // строка поиска следует за окном при масштабе
        if (search != null) {
            search.setPosition(searchFieldX() + 24, bodyY + 9);
            search.setWidth(SEARCH_W - 34);
        }

        if (theme.animations) {
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().scale(scale, scale);
            g.pose().translate(-cx, -cy);
        }

        // ===== фаза 1: формы =====
        drawWindowShapes(g, winX, winY, t);
        drawSidebarShapes(g, winX, winY, t);
        drawHeaderShapes(g, t);
        layoutContent(g, winX, winY);
        drawBodyShapes(g);
        drawFooterShapes(g, winX, winY);

        g.nextStratum();

        // ===== фаза 2: текст =====
        drawSidebarText(g, winX, winY, t);
        drawHeaderText(g);
        drawBodyTexts(g);
        // мягкое затухание списка рисуем ПОСЛЕ текста: иначе обрезанный
        // текст оставался резким поверх дымки (замечено на скриншоте)
        drawListFade(g);
        drawFooterTexts(g, winX, winY);

        if (theme.animations) {
            g.pose().popMatrix();
        }

        super.render(g, mx, my, delta);
    }

    /** Масштаб вписывания окна в экран. */
    private float fitScale() {
        float fw = (this.width - 12f) / WIN_W;
        float fh = (this.height - 12f) / WIN_H;
        return Math.max(0.55f, Math.min(1f, Math.min(fw, fh)));
    }

    private float uiScale = 1f;

    /* ------------------------------------------------------------------ */
    /*  Окно                                                              */
    /* ------------------------------------------------------------------ */

    private void drawWindowShapes(GuiGraphics g, int winX, int winY, float t) {
        int alpha = (int) (255 * Animation.smooth(t));
        if (alpha < 4) {
            return;
        }
        // глубокая тень + мягкое акцентное свечение — окно «парит» над дымом
        GfxEngine.dropShadow(g, winX, winY + 3, WIN_W, WIN_H, WIN_R, 7f, 13f,
                ColorUtil.rgba(0, 0, 0, (int) (150 * t)));
        GfxEngine.glow(g, winX, winY, WIN_W, WIN_H, WIN_R, 16f,
                ColorUtil.alpha(theme.accentNow(), (int) (26 * t)));
        // стеклянная заливка с вертикальным градиентом — глубина вместо плоскости
        int fillTop = ColorUtil.alpha(ColorUtil.rgb(27, 29, 46), (int) (alpha * 0.92f + 20));
        int fillBottom = ColorUtil.alpha(ColorUtil.rgb(14, 15, 25), (int) (alpha * 0.92f + 20));
        GfxEngine.roundedGradient(g, winX, winY, WIN_W, WIN_H, WIN_R, fillTop, fillBottom);
        GfxEngine.outline(g, winX, winY, WIN_W, WIN_H, WIN_R,
                ColorUtil.rgba(255, 255, 255, (int) (30 * t)), 1f);
        // блик по верхней кромке
        GfxEngine.rounded(g, winX + WIN_R + 4, winY + 1, WIN_W - (WIN_R + 4) * 2, 1, 0.5f,
                ColorUtil.rgba(255, 255, 255, (int) (28 * t)));
    }

    /* ------------------------------------------------------------------ */
    /*  Боковая панель                                                    */
    /* ------------------------------------------------------------------ */

    private void drawSidebarShapes(GuiGraphics g, int winX, int winY, float t) {
        int sx = winX + 10;
        int sy = winY + 10;
        int sw = SIDEBAR_W - 24;
        // сайдбар заканчивается НАД футером: раньше его стекло залезало
        // под подсказку «ЛКМ — вкл/выкл…» и под кнопки (замечено на скриншоте)
        int sh = WIN_H - 20 - FOOTER_H + 6;

        GfxEngine.rounded(g, sx, sy, sw, sh, 12, ColorUtil.rgba(255, 255, 255, (int) (6 * t)));
        GfxEngine.outline(g, sx, sy, sw, sh, 12, ColorUtil.rgba(255, 255, 255, (int) (13 * t)), 1f);

        // ===== бренд-иконка Aurora + чип версии =====
        IconPainter.draw(g, sx + 22, sy + 22, 22, (int) (34 * t));
        int logoW = RenderUtil.widthBold(font, Theme.CLIENT_NAME, theme);
        int chipW = RenderUtil.width(font, Theme.CLIENT_VERSION, theme) + 12;
        GfxEngine.rounded(g, sx + 40 + logoW + 8, sy + 13, chipW, 13, 6.5f,
                ColorUtil.alpha(theme.accentNow(), (int) (115 * t)));

        // ===== категории =====
        int cy = sy + 48;
        for (Category cat : Category.values()) {
            boolean active = !themeTab && tab == cat;
            boolean hover = mouseX >= sx + 4 && mouseX < sx + sw - 4 && mouseY >= cy && mouseY < cy + CAT_H;
            Animation hvA = catHoverAnims.computeIfAbsent(cat, k -> new Animation(0, 14));
            hvA.to(hover && !active ? 1 : 0);
            float hv = hvA.get();
            Animation acA = catActiveAnims.computeIfAbsent(cat, k -> new Animation(0, 12));
            acA.to(active ? 1 : 0);
            float ac = acA.get();
            // подложка: hover-подсветка — плавная
            if (hv > 0.01f) {
                GfxEngine.rounded(g, sx + 4, cy, sw - 8, CAT_H, 8,
                        ColorUtil.rgba(255, 255, 255, (int) (14 * hv * t)));
            }
            if (ac > 0.01f) {
                // активная строка — градиентная заливка акцентом
                int gTop = ColorUtil.alpha(theme.accent(0.05f), (int) (56 * ac * t));
                int gBot = ColorUtil.alpha(theme.accent(0.95f), (int) (56 * ac * t));
                GfxEngine.roundedGradient(g, sx + 4, cy, sw - 8, CAT_H, 8, gTop, gBot);
                // акцентная полоска слева — мягко вырастает
                GfxEngine.rounded(g, sx + 4, cy + 5 + (1 - ac) * 3.5f, 3, 14 * ac, 1.5f,
                        ColorUtil.alpha(theme.accentNow(), (int) (255 * ac)));
            }
            drawCategoryIcon(g, cat, sx + 13, cy + 6, hv, ac);
            hits.add(new Hit(sx + 4, cy, sw - 8, CAT_H, -1, () -> {
                playClick();
                themeTab = false;
                tab = cat;
                scrollTarget = 0;
                tabAnim.snap(0);
                tabAnim.to(1);
            }));
            cy += CAT_STEP;
        }

        // ===== «Тема» =====
        int themeY = cy + 2;
        boolean themeActive = themeTab;
        boolean themeHover = mouseX >= sx + 4 && mouseX < sx + sw - 4 && mouseY >= themeY && mouseY < themeY + CAT_H;
        themeRowHoverAnim.to(themeHover && !themeActive ? 1 : 0);
        float thv = themeRowHoverAnim.get();
        themeRowActiveAnim.to(themeActive ? 1 : 0);
        float tac = themeRowActiveAnim.get();
        if (thv > 0.01f) {
            GfxEngine.rounded(g, sx + 4, themeY, sw - 8, CAT_H, 8,
                    ColorUtil.rgba(255, 255, 255, (int) (14 * thv * t)));
        }
        if (tac > 0.01f) {
            int gTop = ColorUtil.alpha(theme.accent(0.05f), (int) (56 * tac * t));
            int gBot = ColorUtil.alpha(theme.accent(0.95f), (int) (56 * tac * t));
            GfxEngine.roundedGradient(g, sx + 4, themeY, sw - 8, CAT_H, 8, gTop, gBot);
            GfxEngine.rounded(g, sx + 4, themeY + 5 + (1 - tac) * 3.5f, 3, 14 * tac, 1.5f,
                    ColorUtil.alpha(theme.accentNow(), (int) (255 * tac)));
        }
        // иконка «палитра художника»: кольцо + три капли-кружка
        int palIcon = ColorUtil.lerp(theme.textSecondary(), theme.accentNow(), tac);
        GfxEngine.ring(g, sx + 19, themeY + 12, 11, palIcon, 1.2f);
        GfxEngine.circle(g, sx + 15.5f, themeY + 9.5f, 2.4f, theme.accentNow());
        GfxEngine.circle(g, sx + 21.5f, themeY + 8.5f, 2.4f, ColorUtil.rgb(255, 255, 255));
        GfxEngine.circle(g, sx + 23.5f, themeY + 14.5f, 2.4f, theme.accent(1.0f));
        hits.add(new Hit(sx + 4, themeY, sw - 8, CAT_H, -1, () -> {
            playClick();
            themeTab = true;
            scrollTarget = 0;
            tabAnim.snap(0);
            tabAnim.to(1);
        }));

        // ===== цветовая схема: аккуратные кружки с отступами (2 ряда по 6) =====
        int palY = themeY + 42;
        int px = sx + 13;
        int row = 0;
        for (int pi = 0; pi < Theme.PRESETS.length; pi++) {
            String[] preset = Theme.PRESETS[pi];
            int c1 = (int) (0xFF000000L | Long.parseLong(preset[1], 16));
            boolean isActive = theme.accent == c1 && !theme.rainbow;
            boolean hoverP = mouseX >= px - 2 && mouseX < px + 11 && mouseY >= palY - 2 && mouseY < palY + 11;
            Animation phvA = paletteHoverAnims[pi];
            phvA.to(hoverP ? 1 : 0);
            float phv = phvA.get();
            Animation pacA = paletteActiveAnims[pi];
            pacA.to(isActive ? 1 : 0);
            float pac = pacA.get();
            if (phv > 0.01f) {
                // мягкое «дыхание» вокруг кружка при наведении
                GfxEngine.circle(g, px + 4.5f, palY + 4.5f, 9 + 5 * phv,
                        ColorUtil.rgba(255, 255, 255, (int) (30 * phv)));
            }
            GfxEngine.circle(g, px + 4.5f, palY + 4.5f, 9, c1);
            if (pac > 0.02f) {
                GfxEngine.ring(g, px + 4.5f, palY + 4.5f, 13.5f + 1.5f * (1 - pac),
                        ColorUtil.alpha(ColorUtil.rgb(255, 255, 255), (int) (255 * pac)), 1.2f);
            }
            final int f1 = c1;
            final int f2 = (int) (0xFF000000L | Long.parseLong(preset[2], 16));
            hits.add(new Hit(px - 2, palY - 2, 13, 13, 0, () -> {
                theme.accent = f1;
                theme.accent2 = f2;
                theme.rainbow = false;
                playClick();
                AuroraConfig.save();
            }));
            px += 14;
            row++;
            if (row == 6) { // второй ряд пресетов
                row = 0;
                px = sx + 13;
                palY += 15;
            }
        }

        // ===== мини-статистика включённых модулей (40px, чтобы палитра
        // и статистика не слипались при укороченном сайдбаре) =====
        int statY = sy + sh - 46 - 46;
        GfxEngine.rounded(g, sx + 4, statY, sw - 8, 40, 10, ColorUtil.rgba(255, 255, 255, (int) (8 * t)));
        GfxEngine.outline(g, sx + 4, statY, sw - 8, 40, 10, ColorUtil.rgba(255, 255, 255, (int) (13 * t)), 1f);
        int total = ModuleManager.all().size();
        int enabled = ModuleManager.enabled().size();
        float ratio = total <= 0 ? 0 : enabled / (float) total;
        int barW = sw - 28;
        int barY = statY + 28;
        GfxEngine.rounded(g, sx + 14, barY, barW, 3, 1.5f, ColorUtil.rgba(255, 255, 255, 26));
        int fw = Math.round(barW * ratio);
        if (fw > 2) {
            GfxEngine.roundedGradient(g, sx + 14, barY, fw, 3, 1.5f,
                    ColorUtil.alpha(theme.accent(0.2f), 255),
                    ColorUtil.alpha(theme.accent(0.9f), 255));
        }

        // ===== профиль =====
        int profY = sy + sh - 46;
        GfxEngine.rounded(g, sx + 4, profY, sw - 8, 38, 10, ColorUtil.rgba(255, 255, 255, (int) (9 * t)));
        GfxEngine.outline(g, sx + 4, profY, sw - 8, 38, 10, ColorUtil.rgba(255, 255, 255, (int) (15 * t)), 1f);
        GfxEngine.circle(g, sx + 16, profY + 19, 24, theme.accentNow());
        GfxEngine.circle(g, sx + 16, profY + 19, 17, ColorUtil.lerp(theme.accentNow(), ColorUtil.rgb(17, 18, 27), 0.4f));
        // точка статуса «в сети»
        GfxEngine.circle(g, sx + 26, profY + 28, 5, ColorUtil.rgb(74, 222, 128));
        GfxEngine.ring(g, sx + 26, profY + 28, 6.5f, ColorUtil.rgb(14, 15, 25), 1.2f);
    }

    private void drawSidebarText(GuiGraphics g, int winX, int winY, float t) {
        int sx = winX + 10;
        int sy = winY + 10;
        int sw = SIDEBAR_W - 24;

        // логотип — полужирный, рядом с иконкой бренда
        RenderUtil.drawTextBold(g, font, Theme.CLIENT_NAME, sx + 40, sy + 13, theme.textPrimary(), theme);
        int logoW = RenderUtil.widthBold(font, Theme.CLIENT_NAME, theme);
        RenderUtil.drawText(g, font, Theme.CLIENT_VERSION, sx + 40 + logoW + 14, sy + 16,
                ColorUtil.rgb(255, 255, 255), theme);
        RenderUtil.drawText(g, font, "клиент для MC " + Theme.MC_VERSION, sx + 40, sy + 27,
                ColorUtil.alpha(theme.textSecondary(), 150), theme);

        // категории: активная — белым полужирным
        int cy = sy + 48;
        for (Category cat : Category.values()) {
            boolean active = !themeTab && tab == cat;
            Animation hvA = catHoverAnims.computeIfAbsent(cat, k -> new Animation(0, 14));
            float hv = hvA.get();
            if (active) {
                RenderUtil.drawTextBold(g, font, cat.title, sx + 33, cy + 8, ColorUtil.rgb(255, 255, 255), theme);
            } else {
                int col = ColorUtil.lerp(theme.textSecondary(), ColorUtil.rgb(255, 255, 255), hv * 0.6f);
                RenderUtil.drawText(g, font, cat.title, sx + 33, cy + 8, col, theme);
            }
            cy += CAT_STEP;
        }

        // «Тема»
        float thv = themeRowHoverAnim.get();
        if (themeTab) {
            RenderUtil.drawTextBold(g, font, "Тема", sx + 33, cy + 2 + 8, ColorUtil.rgb(255, 255, 255), theme);
        } else {
            RenderUtil.drawText(g, font, "Тема", sx + 33, cy + 2 + 8,
                    ColorUtil.lerp(theme.textSecondary(), ColorUtil.rgb(255, 255, 255), thv * 0.6f), theme);
        }

        // подпись схемы (зазор от строки «Тема» и до кружков — не слипаются)
        RenderUtil.drawText(g, font, "Цветовая схема", sx + 12, cy + 33, theme.textSecondary(), theme);

        // мини-статистика (геометрия синхронна с drawSidebarShapes)
        int statY = sy + (WIN_H - 20 - FOOTER_H + 6) - 46 - 46;
        int total = ModuleManager.all().size();
        int enabled = ModuleManager.enabled().size();
        RenderUtil.drawText(g, font, "Включено модулей", sx + 14, statY + 7, theme.textSecondary(), theme);
        RenderUtil.drawTextBold(g, font, enabled + " из " + total, sx + 14, statY + 17,
                theme.textPrimary(), theme);

        // профиль
        int profY = sy + (WIN_H - 20 - FOOTER_H + 6) - 46;
        String nick = Minecraft.getInstance().getUser().getName();
        String initial = nick.isEmpty() ? "A" : nick.substring(0, 1).toUpperCase();
        RenderUtil.drawCenteredText(g, font, initial, sx + 16, profY + 15, ColorUtil.rgb(255, 255, 255), theme);
        RenderUtil.drawTextBold(g, font, RenderUtil.ellipsize(font, nick, sw - 56, theme),
                sx + 32, profY + 12, theme.textPrimary(), theme);
        RenderUtil.drawText(g, font, "Профиль", sx + 32, profY + 23, theme.textSecondary(), theme);
    }

    /**
     * Иконки категорий 12x12 — тонкие, «штриховые» (в духе Lucide):
     * активная категорь получает акцентный цвет, при наведении — светлеет.
     */
    private void drawCategoryIcon(GuiGraphics g, Category cat, float x, float y, float brightness, float activeT) {
        brightness = Math.max(0f, Math.min(1f, brightness));
        activeT = Math.max(0f, Math.min(1f, activeT));
        // база: серый; при активности плавно уходит в акцент
        int base = ColorUtil.lerp(theme.textSecondary(), theme.accentNow(), Math.min(1f, activeT * 1.2f));
        int color = ColorUtil.lerp(base, ColorUtil.rgb(255, 255, 255), brightness * 0.35f * (1 - activeT));
        switch (cat) {
            case VISUAL -> {
                // «глаз»: контур-капсула + радужка-кольцо + зрачок
                GfxEngine.outline(g, x, y + 2.5f, 12, 7, 3.5f, color, 1.1f);
                GfxEngine.ring(g, x + 6, y + 6, 4.6f, color, 1.1f);
                GfxEngine.circle(g, x + 6, y + 6, 1.7f, color);
            }
            case HUD -> {
                // «раскладка»: рамка экрана с панелями (схема HUD)
                GfxEngine.outline(g, x, y + 0.5f, 12, 11, 3f, color, 1.1f);
                GfxEngine.rounded(g, x + 2.5f, y + 3, 7, 2.1f, 1f, color);
                GfxEngine.rounded(g, x + 2.5f, y + 7.2f, 3.1f, 2.6f, 0.9f, color);
                GfxEngine.rounded(g, x + 6.4f, y + 7.2f, 3.1f, 2.6f, 0.9f, color);
            }
            case CHAT -> {
                // «пузырь» с хвостиком и точками сообщения
                GfxEngine.outline(g, x, y + 1, 12, 8.5f, 3.5f, color, 1.1f);
                GfxEngine.circle(g, x + 3.4f, y + 5.2f, 1.6f, color);
                GfxEngine.circle(g, x + 6, y + 5.2f, 1.6f, color);
                GfxEngine.circle(g, x + 8.6f, y + 5.2f, 1.6f, color);
                g.pose().pushMatrix();
                g.pose().translate(x + 3.1f, y + 9.6f);
                g.pose().rotate((float) Math.toRadians(45));
                GfxEngine.rounded(g, -1.3f, -1.3f, 2.6f, 2.6f, 0.6f, color);
                g.pose().popMatrix();
            }
            case PROF -> {
                // «молния»: классический значок производительности
                GfxEngine.pathFill(g, new float[]{
                        x + 6.8f, y + 0.5f,
                        x + 10.6f, y + 0.5f,
                        x + 6.4f, y + 5.6f,
                        x + 9.6f, y + 5.6f,
                        x + 3.4f, y + 11.5f,
                        x + 5.2f, y + 6.1f,
                        x + 2.2f, y + 6.1f,
                }, 7, color);
            }
        }
    }

    /** Шеврон: две палочки, плавно поворачивающиеся на 180° при раскрытии настроек. */
    private void drawChevron(GuiGraphics g, float cx, float cy, float t, int color, float alpha) {
        t = Math.max(0f, Math.min(1f, t));
        float rot = (float) Math.toRadians(180.0) * Animation.smooth(t);
        int col = ColorUtil.alpha(color, (int) (alpha * 230));
        for (int s = 0; s < 2; s++) {
            float base = s == 0 ? (float) Math.toRadians(45) : (float) Math.toRadians(-45);
            float mx = s == 0 ? -2.0f : 2.0f;
            g.pose().pushMatrix();
            g.pose().translate(cx + mx, cy - 0.5f);
            g.pose().rotate(base + rot);
            GfxEngine.rounded(g, -2.7f, -0.8f, 5.4f, 1.6f, 0.8f, col);
            g.pose().popMatrix();
        }
    }

    /** Иконка-прицел для кнопки HUD-редактора. */
    private void drawHudIcon(GuiGraphics g, float cx, float cy, int color) {
        GfxEngine.ring(g, cx, cy, 8, color, 1.1f);
        GfxEngine.circle(g, cx, cy, 2, color);
        for (int i = 0; i < 4; i++) {
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().rotate((float) (Math.PI / 2 * i));
            GfxEngine.rounded(g, 3.6f, -0.6f, 3, 1.2f, 0.6f, color);
            g.pose().popMatrix();
        }
    }

    /** Иконка-лупа для строки поиска. */
    private void drawSearchIcon(GuiGraphics g, float cx, float cy, int color) {
        GfxEngine.ring(g, cx, cy, 6.6f, color, 1.1f);
        g.pose().pushMatrix();
        g.pose().translate(cx + 2.1f, cy + 2.1f);
        g.pose().rotate((float) Math.toRadians(45));
        GfxEngine.rounded(g, 1.6f, -0.6f, 3.4f, 1.2f, 0.6f, color);
        g.pose().popMatrix();
    }

    /* ------------------------------------------------------------------ */
    /*  Шапка                                                             */
    /* ------------------------------------------------------------------ */

    private int hudButtonW() {
        return RenderUtil.width(font, "HUD-редактор", theme) + 34;
    }

    private int searchFieldX() {
        return bodyX + bodyW - hudButtonW() - 8 - SEARCH_W;
    }

    private void drawHeaderShapes(GuiGraphics g, float t) {
        // кнопка HUD-редактора — плавная подсветка при наведении
        int hudW = hudButtonW();
        int hudX = bodyX + bodyW - hudW;
        boolean hudHover = mouseX >= hudX && mouseX < hudX + hudW && mouseY >= bodyY + 2 && mouseY < bodyY + 24;
        hudBtnHoverAnim.to(hudHover ? 1 : 0);
        float hhv = hudBtnHoverAnim.get();
        if (hhv > 0.02f) {
            // мягкое свечение — «размытая» подсветка
            GfxEngine.dropShadow(g, hudX - 1, bodyY + 1, hudW + 2, 24, 11, 1f, 7f,
                    ColorUtil.alpha(theme.accentNow(), (int) (50 * hhv * t)));
        }
        GfxEngine.rounded(g, hudX, bodyY + 2, hudW, 22, 11,
                ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, (int) (16 * t)),
                        ColorUtil.alpha(theme.accentNow(), 130), hhv));
        GfxEngine.outline(g, hudX, bodyY + 2, hudW, 22, 11,
                ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, (int) (24 * t)),
                        ColorUtil.alpha(theme.accentNow(), 180), hhv), 1f);
        drawHudIcon(g, hudX + 12, bodyY + 13,
                ColorUtil.lerp(theme.textSecondary(), ColorUtil.rgb(255, 255, 255), Math.max(hhv, 0.4f)));
        hits.add(new Hit(hudX, bodyY + 2, hudW, 22, 0, () -> {
            playClick();
            Minecraft.getInstance().setScreen(new HudEditorScreen());
        }));

        // строка поиска: клик по полю возвращает фокус ввода
        hits.add(new Hit(searchFieldX(), bodyY + 2, SEARCH_W, 22, 0, () -> setFocused(search)));

        // строка поиска: обводка плавно разгорается акцентом при фокусе
        boolean focused = search != null && search.isFocused();
        searchFocusAnim.to(focused ? 1 : 0);
        float sf = searchFocusAnim.get();
        GfxEngine.rounded(g, searchFieldX(), bodyY + 2, SEARCH_W, 22, 11, ColorUtil.rgba(255, 255, 255, (int) (12 * t)));
        GfxEngine.outline(g, searchFieldX(), bodyY + 2, SEARCH_W, 22, 11,
                ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, (int) (18 * t)),
                        ColorUtil.alpha(theme.accentNow(), 150), sf), 1f);
        drawSearchIcon(g, searchFieldX() + 11, bodyY + 13,
                ColorUtil.lerp(theme.textSecondary(), theme.accentNow(), sf));
    }

    private void drawHeaderText(GuiGraphics g) {
        String title = themeTab ? "Тема" : tab.title;
        RenderUtil.drawTextSize(g, font, title, bodyX + 2, bodyY + 6, 12f, theme.textPrimary(), true, theme);

        // чип с количеством модулей в категории
        if (!themeTab) {
            String count = moduleCount + " " + plural(moduleCount);
            float tw = RenderUtil.widthSize(title, 12f, true);
            float cw = RenderUtil.width(font, count, theme) + 10;
            GfxEngine.rounded(g, bodyX + 2 + tw + 8, bodyY + 7, cw, 12, 6,
                    ColorUtil.alpha(theme.accentNow(), 46));
            RenderUtil.drawText(g, font, count, bodyX + 2 + tw + 13, bodyY + 9,
                    ColorUtil.alpha(theme.textPrimary(), 200), theme);
        }

        int hudW = hudButtonW();
        int hudX = bodyX + bodyW - hudW;
        float hhv = hudBtnHoverAnim.get();
        RenderUtil.drawText(g, font, "HUD-редактор", hudX + 22, bodyY + 8,
                ColorUtil.lerp(theme.textSecondary(), ColorUtil.rgb(255, 255, 255), hhv), theme);

        // строка поиска: значение, курсор и плейсхолдер — всё через движок
        if (search != null) {
            String value = search.getValue();
            boolean focused = search.isFocused();
            float textX = searchFieldX() + 24;
            float textY = bodyY + 8;
            if (value.isEmpty() && !focused) {
                RenderUtil.drawText(g, font, "Поиск...", textX, textY, ColorUtil.rgba(235, 236, 244, 95), theme);
            } else {
                // каретка и текст обязаны идти от одной строки — иначе курсор
                // уезжал за пределы поля, когда текст обрезан троеточием
                String shown = RenderUtil.ellipsize(font, value, SEARCH_W - 32, theme);
                RenderUtil.drawText(g, font, shown, textX, textY, theme.textPrimary(), theme);
                if (focused && FrameClock.now() % 1000 < 500) {
                    float caretX = textX + RenderUtil.width(font, shown, theme) + 1;
                    GfxEngine.rounded(g, caretX, textY + 0.5f, 1, 8, 0.5f, ColorUtil.alpha(theme.accentNow(), 220));
                }
            }
        }
    }

    /** Русская форма множественного числа для слова «модуль». */
    private static String plural(int n) {
        int a = Math.abs(n) % 100;
        int b = a % 10;
        if (a > 10 && a < 20) {
            return "модулей";
        }
        if (b == 1 && a != 11) {
            return "модуль";
        }
        if (b > 1 && b < 5) {
            return "модуля";
        }
        return "модулей";
    }

    /* ------------------------------------------------------------------ */
    /*  Разметка тела (карточки или вкладка «Тема»)                       */
    /* ------------------------------------------------------------------ */

    private List<Module> currentModules() {
        String q = search != null ? search.getValue().trim().toLowerCase() : "";
        List<Module> out = new ArrayList<>();
        if (!q.isEmpty()) {
            for (Module m : ModuleManager.all()) {
                if (m.name.toLowerCase().contains(q) || m.description.toLowerCase().contains(q)) {
                    out.add(m);
                }
            }
            return out;
        }
        return ModuleManager.byCategory(tab);
    }

    private float expandT(Module m) {
        Animation a = expandAnims.get(m);
        return a == null ? (Boolean.TRUE.equals(expanded.get(m)) ? 1f : 0f) : a.get();
    }

    private void layoutContent(GuiGraphics g, int winX, int winY) {
        // смена вкладки: контент мягко выезжает снизу и проявляется
        float tabE = Animation.easeOutCubic(tabAnim.get());
        int slide = Math.round((1 - tabE) * 12);
        int cardsTop = bodyY + 32 + slide;
        moduleCount = 0;
        if (themeTab) {
            themeRows.clear();
            int colW = Math.min(320, bodyW - 12);
            int rx = bodyX + (bodyW - colW) / 2;
            themeRowsH = buildThemeRows(g, themeRows, rx, cardsTop + 18 - (int) scrollPos, colW);
            maxScroll = Math.max(0, themeRowsH + 18 - (bodyH - 49 - 4));
            scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
            scrollPos = Math.min(scrollPos, maxScroll);
            return;
        }

        List<Module> modules = currentModules();
        moduleCount = modules.size();
        // правые 8px тела — жёлоб скроллбара: карточки его не перекрывают
        int colW = (bodyW - 8 - GAP) / 2;
        int[] colYs = new int[2];
        int contentH = 0;
        int idx = 0;
        // высота видимой области СОВПАДАЕТ с областью отсечения (минус зазор):
        // раньше вьюпорт был на 13px выше скиссора — нижняя строка карточки
        // вечно резалась пополам у футера (поймано на скриншоте смоук-теста)
        int viewH = bodyH - 49 - 4;

        for (Module m : modules) {
            int col = colYs[0] <= colYs[1] ? 0 : 1;
            int x = bodyX + col * (colW + GAP);
            int y = cardsTop + colYs[col] - (int) scrollPos;
            float et = expandT(m);

            // описание не должно налезать на тумблер: от левого поля 19px,
            // зона тумблера 38px + 3px запаса
            String desc = RenderUtil.ellipsize(font, m.description, colW - 60, theme);
            int headerH = 34;
            int rowsH = settingsHeight(m);
            int h = headerH + 8 + Math.round(rowsH * et);

            List<Row> rows = et > 0.01f ? buildModuleRows(g, m, x + 12, y + headerH + 8, colW - 24) : List.of();

            colYs[col] += h + GAP;
            contentH = Math.max(contentH, colYs[col]);

            cards.add(buildCard(g, m, x, y, colW, h, headerH, desc, rows, et, idx));
            idx++;
        }

        maxScroll = Math.max(0, contentH - viewH);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scrollPos = Math.min(scrollPos, maxScroll);

        // раскрытие настроек: прокручиваем список так, чтобы карточка
        // (её полная раскрытая высота) поместилась в видимую область —
        // раньше низ настроек резался футером и строки «заезжали» под него
        if (revealPending != null) {
            Module rm = revealPending;
            revealPending = null;
            for (Card card : cards) {
                if (card.module == rm) {
                    float fullH = card.headerH + 8f + settingsHeight(rm);
                    // позиция карточки в контенте (без учёта текущего скролла)
                    float contentY = card.y + scrollPos;
                    // низ карточки должен попасть на 4px выше нижней границы
                    float viewportBottom = bodyY + bodyH - 19;
                    float need = contentY + fullH - viewportBottom + 4f;
                    if (need > scrollPos + 1f) {
                        // полный maxScroll ещё не посчитан (карточка растёт
                        // анимацией) — расширяем его вручную и едем вниз
                        scrollTarget = need;
                        maxScroll = (int) Math.max(maxScroll, need);
                        scrollPos = Math.max(scrollPos, Math.min(scrollTarget, maxScroll));
                    }
                    break;
                }
            }
        }
    }

    private int settingsHeight(Module m) {
        int h = 0;
        for (Setting s : m.settings()) {
            if (s instanceof NumberSetting) {
                h += 26;
            } else if (s instanceof ColorSetting) {
                // пипетка раскрывается плавно — высота растёт вместе с анимацией
                h += 18 + Math.round(96 * colorOpenT(s));
            } else {
                h += 18;
            }
        }
        // строка бинда есть у каждого модуля + 6px нижнего поля:
        // последняя строка не прижимается к рамке карточки
        return h + 24;
    }

    private Card buildCard(GuiGraphics g, Module m, int x, int y, int w, int h, int headerH, String desc,
                           List<Row> rows, float et, int idx) {
        // у каждого модуля есть строка бинда — карточка всегда раскрывается
        boolean hasSettings = true;
        boolean open = Boolean.TRUE.equals(expanded.get(m));

        Runnable shapes = () -> {
            // каскад появления: карточки «всплывают» одна за другой при смене вкладки
            float tabT = Animation.easeOutCubic(tabAnim.get());
            float ca = Math.max(0f, Math.min(1f, (tabT - idx * 0.07f) / 0.65f));
            if (ca <= 0.01f) {
                return;
            }
            float dy = (1 - Animation.smooth(ca)) * 14;
            g.pose().pushMatrix();
            g.pose().translate(0, dy);

            Animation ha = hoverAnims.computeIfAbsent(m, k -> new Animation(0, 11));
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + h;
            ha.to(hovered ? 1 : 0);
            float hover = ha.get();
            float toggleT = toggleAnims.computeIfAbsent(m, k -> new Animation(m.isEnabled() ? 1 : 0, 14)).get();
            int a = Math.round(255 * ca);
            if (a < 4) {
                g.pose().popMatrix();
                return;
            }

            // «парение»: мягкое свечение под карточкой при наведении и во включённом состоянии
            float onGlow = Math.max(hover, toggleT * 0.7f);
            if (onGlow > 0.02f) {
                GfxEngine.dropShadow(g, x - 1, y - 1, w + 2, h + 3, CARD_R, 1.5f, 10f,
                        ColorUtil.alpha(theme.accentNow(), (int) (54 * onGlow * a / 255f)));
            }

            // фон карточки: стекло + подсветка при наведении/включении
            int base = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 9), ColorUtil.rgba(255, 255, 255, 20), hover);
            int fill = ColorUtil.lerp(base, ColorUtil.alpha(theme.accentNow(), 30), toggleT * 0.85f);
            GfxEngine.rounded(g, x, y, w, h, CARD_R, ColorUtil.alpha(fill, (int) (ColorUtil.alphaI(fill) * a / 255f)));
            int outline = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 17), ColorUtil.alpha(theme.accentNow(), 130), toggleT);
            if (hovered) {
                outline = ColorUtil.lerp(outline, ColorUtil.rgba(255, 255, 255, 64), hover * 0.6f);
            }
            GfxEngine.outline(g, x, y, w, h, CARD_R, ColorUtil.alpha(outline, (int) (ColorUtil.alphaI(outline) * a / 255f)), 1f);

            // LED-индикатор слева: разгорается акцентом у включённых модулей
            float ledX = x + 11;
            float ledY = y + 13;
            if (toggleT > 0.02f) {
                GfxEngine.circle(g, ledX, ledY, 8,
                        ColorUtil.alpha(theme.accentNow(), (int) (60 * toggleT * a / 255f)));
            }
            GfxEngine.circle(g, ledX, ledY, 4,
                    ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 38), theme.accentNow(), toggleT));

            // шеврон раскрытия настроек (плавно поворачивается на 180°)
            if (hasSettings) {
                drawChevron(g, x + w - 18, y + 13, et,
                        open ? theme.accentNow() : theme.textSecondary(), a / 255f);
            }

            // pill-тумблер
            drawPill(g, x + w - 38, y + 7, 24, 12, toggleT);

            // настройки под шапкой
            if (!rows.isEmpty() && et > 0.01f) {
                int divY = y + headerH + 2;
                GfxEngine.rounded(g, x + 12, divY, w - 24, 1, 0.5f, ColorUtil.rgba(255, 255, 255, 18));
                RenderUtil.pushScissor(g, x + 1, divY + 1, x + w - 1, y + h - 1);
                for (Row row : rows) {
                    row.shapes().run();
                }
                RenderUtil.popScissor(g);
            }
            g.pose().popMatrix();

            // зоны клика (после отрисовки: чем позже добавлена — тем выше приоритет)
            if (hasSettings) {
                hits.add(new Hit(x, y, w, headerH, 1, () -> toggleExpanded(m)));
                hits.add(new Hit(x, y, w, headerH, 0, () -> {
                    m.toggle();
                    playClick();
                    toggleAnims.get(m).to(m.isEnabled() ? 1 : 0);
                    AuroraConfig.markDirty();
                }));
                hits.add(new Hit(x + w - 30, y, 18, headerH, 0, () -> toggleExpanded(m)));
            } else {
                hits.add(new Hit(x, y, w, headerH, 0, () -> {
                    m.toggle();
                    playClick();
                    toggleAnims.get(m).to(m.isEnabled() ? 1 : 0);
                    AuroraConfig.markDirty();
                }));
            }
        };

        Runnable texts = () -> {
            float tabT = Animation.easeOutCubic(tabAnim.get());
            float ca = Math.max(0f, Math.min(1f, (tabT - idx * 0.07f) / 0.65f));
            if (ca <= 0.01f) {
                return;
            }
            float dy = (1 - Animation.smooth(ca)) * 14;
            g.pose().pushMatrix();
            g.pose().translate(0, dy);
            int a = Math.round(255 * ca);
            // имя модуля — крупнее, полужирное; включённый — белым.
            // эллипсис: длинное имя не должно налезать на тумблер
            int nameColor = m.isEnabled() ? ColorUtil.rgb(255, 255, 255) : theme.textPrimary();
            RenderUtil.drawTextSize(g, font,
                    RenderUtil.ellipsizeSize(m.name, 10f, m.isEnabled(), w - 60),
                    x + 19, y + 5, 10f,
                    ColorUtil.alpha(nameColor, a), m.isEnabled(), theme);
            RenderUtil.drawText(g, font, desc, x + 19, y + 21,
                    ColorUtil.alpha(theme.textSecondary(), (int) (a * 0.95f)), theme);
            if (!rows.isEmpty() && expandT(m) > 0.01f) {
                RenderUtil.pushScissor(g, x + 1, y + headerH + 3, x + w - 1, y + h - 1);
                for (Row row : rows) {
                    row.texts().run();
                }
                RenderUtil.popScissor(g);
            }
            g.pose().popMatrix();
        };

        return new Card(m, x, y, w, h, headerH, rows, shapes, texts);
    }

    private void drawBodyShapes(GuiGraphics g) {
        // обрезка тела ДОЛЖНА заканчиваться над футером: иначе раскрытые карточки
        // заезжали под текст «ЛКМ — вкл/выкл...» и кнопки «Сброс/Сохранить»
        RenderUtil.pushScissor(g, bodyX, bodyY + 30, bodyX + bodyW, bodyY + bodyH - 19);
        if (themeTab) {
            for (Row row : themeRows) {
                row.shapes().run();
            }
        } else {
            for (Card card : cards) {
                if (card.y + card.h < bodyY || card.y > bodyY + bodyH + 10) {
                    continue;
                }
                card.shapes.run();
            }
        }
        RenderUtil.popScissor(g);

        // полоса прокрутки — акцентная капсула (заметная, но не кричащая)
        if (maxScroll > 0) {
            int trackH = bodyH - 55;
            float ratio = (float) (bodyH - 30) / (maxScroll + bodyH - 30);
            int thumbH = Math.max(18, (int) (trackH * ratio));
            int thumbY = bodyY + 32 + (int) ((trackH - thumbH) * (scrollPos / (float) maxScroll));
            GfxEngine.rounded(g, bodyX + bodyW - 5, thumbY, 4, thumbH, 2f,
                    ColorUtil.alpha(theme.accentNow(), 185));
        }
    }

    /** Мягкое затухание списка у нижней границы: обрезанная строка
     *  растворяется в дымке цвета панели, а не режется «в лоб». */
    private void drawListFade(GuiGraphics g) {
        if (themeTab || cards.isEmpty() || maxScroll <= 0) {
            return;
        }
        int fadeBottom = bodyY + bodyH - 19;
        int fadeH = 15;
        // почти непрозрачная снизу: панель сама тёмная, слабая дымка
        // на ней не читалась вовсе (проверено пиксельным замером смоук-теста)
        GfxEngine.roundedGradient(g, bodyX + 2, fadeBottom - fadeH, bodyW - 4, fadeH, 0,
                ColorUtil.alpha(theme.panelColor(), 0),
                ColorUtil.alpha(0x11121B, 235));
    }

    private void drawBodyTexts(GuiGraphics g) {
        RenderUtil.pushScissor(g, bodyX, bodyY + 30, bodyX + bodyW, bodyY + bodyH - 19);
        if (themeTab) {
            for (Row row : themeRows) {
                row.texts().run();
            }
        } else {
            if (cards.isEmpty()) {
                RenderUtil.drawCenteredTextSize(g, font, "Ничего не найдено",
                        bodyX + bodyW / 2f, bodyY + 44, 11f, theme.textSecondary(), false, theme);
            }
            for (Card card : cards) {
                if (card.y + card.h < bodyY || card.y > bodyY + bodyH + 10) {
                    continue;
                }
                card.texts.run();
            }
        }
        RenderUtil.popScissor(g);
    }

    private void toggleExpanded(Module m) {
        playClick();
        boolean now = !Boolean.TRUE.equals(expanded.get(m));
        expanded.put(m, now);
        expandAnims.computeIfAbsent(m, k -> new Animation(now ? 0 : 1, 13)).to(now ? 1 : 0);
        if (now) {
            revealPending = m; // при следующей разметке — прокрутить к карточке
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Генераторы строк-контролов                                        */
    /* ------------------------------------------------------------------ */

    private List<Row> buildModuleRows(GuiGraphics g, Module m, int x, int y, int w) {
        List<Row> rows = new ArrayList<>();
        int cy = y;
        for (Setting s : m.settings()) {
            int h = s instanceof NumberSetting ? 26
                    : (s instanceof ColorSetting ? 18 + Math.round(96 * colorOpenT(s)) : 18);
            if (s instanceof BooleanSetting bs) {
                rows.add(boolRow(g, s.name, x, cy, w, bs::get, v -> {
                    bs.set(v);
                    AuroraConfig.markDirty();
                }));
            } else if (s instanceof NumberSetting ns) {
                rows.add(sliderRow(g, s.name, x, cy, w, ns::progress, ns::display, p -> ns.setByProgress(p)));
            } else if (s instanceof ColorSetting cs) {
                rows.add(colorRow(g, s.name, x, cy, w, cs::get, cs::set, cs));
            } else if (s instanceof ModeSetting ms) {
                rows.add(modeRow(g, s.name, x, cy, w, ms));
            } else if (s instanceof BindSetting bind) {
                rows.add(bindRow(g, s.name, x, cy, w, bind::get, v -> {
                    bind.set(v);
                    AuroraConfig.save();
                }, bind));
            }
            cy += h;
        }
        // строка бинда у каждого модуля — раньше бинды были недоступны из меню (баг 1.0.5)
        rows.add(bindRow(g, m.bind.name, x, cy, w, m.bind::get, v -> {
            m.bind.set(v);
            AuroraConfig.save();
        }, m.bind));
        return rows;
    }

    /** Строка-переключатель. */
    private Row boolRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Boolean> get, Consumer<Boolean> set) {
        final String lbl = RenderUtil.ellipsize(font, label, w - 30, theme);
        return new Row(() -> {
            float t = get.get() ? 1f : 0f;
            drawPill(g, x + w - 24, y + 3, 22, 12, t);
            hits.add(new Hit(x, y - 1, w, 17, 0, () -> {
                set.accept(!get.get());
                playClick();
            }));
        }, () -> RenderUtil.drawText(g, font, lbl, x, y + 5, theme.textSecondary(), theme));
    }

    /** Строка-слайдер. */
    private Row sliderRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Float> progress,
                          Supplier<String> display, Consumer<Float> set) {
        return new Row(() -> {
            int trackY = y + 15;
            int trackX = x + 2;
            int trackW = w - 4;
            float p = Math.max(0f, Math.min(1f, progress.get()));
            GfxEngine.rounded(g, trackX, trackY, trackW, 5, 2.5f, ColorUtil.rgba(255, 255, 255, 30));
            int fillW = (int) (trackW * p);
            if (fillW > 3) {
                // заполнение — градиент акцента
                GfxEngine.roundedGradient(g, trackX, trackY, fillW, 5, 2.5f,
                        ColorUtil.alpha(theme.accent(0.1f), 255),
                        ColorUtil.alpha(theme.accent(0.95f), 255));
            }
            GfxEngine.circle(g, trackX + fillW, trackY + 2.5f, 8, ColorUtil.rgb(255, 255, 255));
            GfxEngine.ring(g, trackX + fillW, trackY + 2.5f, 9.5f, ColorUtil.rgba(0, 0, 0, 60), 1f);
            hits.add(new Hit(trackX - 4, trackY - 7, trackW + 8, 18, 0, () -> {
                set.accept(clampP((mouseX - trackX) / (float) trackW));
                sliderX = trackX;
                sliderW = trackW;
                dragActiveSlider = set;
                playClick();
            }));
        }, () -> {
            String disp = display.get();
            // подпись не должна налезать на значение справа — эллипсируем
            String lbl = RenderUtil.ellipsize(font, label, w - RenderUtil.width(font, disp, theme) - 10, theme);
            RenderUtil.drawText(g, font, lbl, x, y + 2, theme.textSecondary(), theme);
            RenderUtil.drawText(g, font, disp, x + w - RenderUtil.width(font, disp, theme), y + 2,
                    theme.textPrimary(), theme);
        });
    }

    /** Строка цвета (с плавно раскрывающейся пипеткой). */
    private Row colorRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Integer> get, Consumer<Integer> set, Object targetId) {
        return new Row(() -> {
            int sw = 26;
            int sxp = x + w - sw;
            boolean openRow = targetId.equals(openColorTarget);
            float openT = colorOpenT(targetId);
            GfxEngine.rounded(g, sxp, y + 2, sw, 12, 5, get.get());
            GfxEngine.outline(g, sxp, y + 2, sw, 12, 5,
                    ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 70),
                            ColorUtil.alpha(theme.accentNow(), 160), openT), 1f);
            hits.add(new Hit(sxp - 4, y - 2, sw + 8, 18, 0, () -> {
                openColorTarget = openRow ? null : targetId;
                playClick();
            }));
            if (openT > 0.01f) {
                drawColorPickerShapes(g, sxp - 64, y + 20, 90, 88, get.get(), set, openT);
            }
        }, () -> RenderUtil.drawText(g, font,
                RenderUtil.ellipsize(font, label, w - 34, theme), x, y + 4, theme.textSecondary(), theme));
    }

    /** Строка режима (значение со стрелками). */
    private Row modeRow(GuiGraphics g, String label, int x, int y, int w, ModeSetting ms) {
        return new Row(() -> {
            int valW = RenderUtil.width(font, ms.display(), theme) + 30;
            int vx = x + w - valW;
            GfxEngine.rounded(g, vx, y + 1, valW, 14, 7, ColorUtil.rgba(255, 255, 255, 18));
            hits.add(new Hit(vx, y, 13, 15, 0, () -> {
                ms.cycle(-1);
                playClick();
            }));
            hits.add(new Hit(vx + valW - 13, y, 13, 15, 0, () -> {
                ms.cycle(1);
                playClick();
            }));
        }, () -> {
            int valW = RenderUtil.width(font, ms.display(), theme) + 30;
            String lbl = RenderUtil.ellipsize(font, label, w - valW - 8, theme);
            RenderUtil.drawText(g, font, lbl, x, y + 4, theme.textSecondary(), theme);
            int vx = x + w - valW;
            RenderUtil.drawText(g, font, "<", vx + 5, y + 4, theme.textSecondary(), theme);
            RenderUtil.drawText(g, font, ms.display(), vx + 15, y + 4, theme.textPrimary(), theme);
            RenderUtil.drawText(g, font, ">", vx + valW - 10, y + 4, theme.textSecondary(), theme);
        });
    }

    /** Строка бинда. id — уникальный идентификатор строки (объект настройки или метка меню). */
    private Row bindRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Integer> get, Consumer<Integer> set, Object id) {
        return new Row(() -> {
            boolean isListening = listeningBind == id;
            String keyText = isListening ? "..." : keyName(get.get());
            int bw = Math.max(42, RenderUtil.width(font, keyText, theme) + 16);
            int bx = x + w - bw;
            GfxEngine.rounded(g, bx, y + 1, bw, 14, 7,
                    isListening ? theme.accentNow() : ColorUtil.rgba(255, 255, 255, 18));
            hits.add(new Hit(bx, y, bw, 15, 0, () -> {
                listeningBind = id;
                playClick();
            }));
        }, () -> {
            boolean isListening = listeningBind == id;
            String keyText = isListening ? "..." : keyName(get.get());
            int bw = Math.max(42, RenderUtil.width(font, keyText, theme) + 16);
            int bx = x + w - bw;
            String lbl = RenderUtil.ellipsize(font, label, w - bw - 8, theme);
            RenderUtil.drawText(g, font, lbl, x, y + 4, theme.textSecondary(), theme);
            RenderUtil.drawText(g, font, keyText, bx + bw / 2f - RenderUtil.width(font, keyText, theme) / 2f, y + 4,
                    isListening ? ColorUtil.rgb(255, 255, 255) : theme.textPrimary(), theme);
        });
    }

    private String keyName(int key) {
        if (key == 0) {
            return "Нет";
        }
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            return "R-Shift";
        }
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT) {
            return "L-Shift";
        }
        if (key == GLFW.GLFW_KEY_LEFT_CONTROL) {
            return "L-Ctrl";
        }
        if (key == GLFW.GLFW_KEY_RIGHT_CONTROL) {
            return "R-Ctrl";
        }
        if (key == GLFW.GLFW_KEY_LEFT_ALT) {
            return "L-Alt";
        }
        if (key == GLFW.GLFW_KEY_LEFT_SUPER) {
            return "L-Super";
        }
        String n = GLFW.glfwGetKeyName(key, 0);
        return n != null ? n.toUpperCase() : ("Код " + key);
    }

    /** Строки вкладки «Тема». Возвращает высоту. */
    private int buildThemeRows(GuiGraphics g, List<Row> rows, int x, int y, int w) {
        int cy = y;
        cy += 14; // место под заголовок «Цветовая схема» (текст в текстовом проходе)

        // пресеты (2 ряда по 6)
        final int presetY = cy;
        rows.add(new Row(() -> {
            int px = x + 4;
            int py = presetY;
            int row = 0;
            for (int pi = 0; pi < Theme.PRESETS.length; pi++) {
                String[] preset = Theme.PRESETS[pi];
                int c1 = (int) (0xFF000000L | Long.parseLong(preset[1], 16));
                int c2 = (int) (0xFF000000L | Long.parseLong(preset[2], 16));
                boolean isActive = theme.accent == c1 && theme.accent2 == c2 && !theme.rainbow;
                boolean hovered = mouseX >= px && mouseX < px + 30 && mouseY >= py && mouseY < py + 18;
                Animation phvA = tpPresetHoverAnims[pi];
                phvA.to(hovered ? 1 : 0);
                float phv = phvA.get();
                Animation pacA = tpPresetActiveAnims[pi];
                pacA.to(isActive ? 1 : 0);
                float pac = pacA.get();
                if (phv > 0.01f) {
                    // подложка проявляется и мягко расширяется
                    float grow = 2f * phv;
                    GfxEngine.rounded(g, px - 3 - grow, py - 3 - grow,
                            36 + grow * 2, 24 + grow * 2, 9 + grow,
                            ColorUtil.rgba(255, 255, 255, (int) (26 * phv)));
                }
                GfxEngine.rounded(g, px, py, 13, 18, 6.5f, c1);
                GfxEngine.rounded(g, px + 15, py, 13, 18, 6.5f, c2);
                if (pac > 0.02f) {
                    GfxEngine.outline(g, px - 2, py - 2, 32, 22, 8,
                            ColorUtil.alpha(ColorUtil.rgb(255, 255, 255), (int) (255 * pac)), 1.2f);
                }
                final int f1 = c1;
                final int f2 = c2;
                final int fpy = py;
                hits.add(new Hit(px, fpy, 28, 18, 0, () -> {
                    theme.accent = f1;
                    theme.accent2 = f2;
                    theme.rainbow = false;
                    playClick();
                    AuroraConfig.save();
                }));
                px += 34;
                row++;
                if (row == 6) {
                    row = 0;
                    px = x + 4;
                    py += 24;
                }
            }
        }, () -> RenderUtil.drawTextSize(g, font, "Цветовая схема", x, presetY - 18, 11f, theme.textPrimary(), true, theme)));
        cy += 50; // два ряда пресетов

        rows.add(colorRow(g, "Акцент", x, cy, w, () -> theme.accent, c -> theme.accent = c, "accent"));
        cy += 17 + Math.round(96 * colorOpenT("accent"));
        rows.add(colorRow(g, "Второй цвет градиента", x, cy, w, () -> theme.accent2, c -> theme.accent2 = c, "accent2"));
        cy += 17 + Math.round(96 * colorOpenT("accent2"));
        rows.add(boolRow(g, "Радужный акцент", x, cy, w, () -> theme.rainbow, v -> {
            theme.rainbow = v;
            AuroraConfig.save();
        }));
        cy += 17;
        rows.add(sliderRow(g, "Прозрачность панелей", x, cy, w,
                () -> theme.backgroundAlpha, () -> String.format("%.2f", theme.backgroundAlpha),
                p -> theme.backgroundAlpha = Math.max(0.05f, Math.min(1.0f, p))));
        cy += 25;
        rows.add(boolRow(g, "Блюр за меню", x, cy, w, () -> theme.blur, v -> {
            theme.blur = v;
            AuroraConfig.save();
        }));
        cy += 17;
        rows.add(sliderRow(g, "Сила блюра", x, cy, w,
                () -> theme.blurStrength / 10.0f, () -> String.valueOf(theme.blurStrength),
                p -> theme.blurStrength = Math.round(p * 10)));
        cy += 25;
        rows.add(boolRow(g, "Анимации", x, cy, w, () -> theme.animations, v -> {
            theme.animations = v;
            AuroraConfig.save();
        }));
        cy += 17;
        rows.add(boolRow(g, "Шрифт Inter", x, cy, w, () -> theme.customFont, v -> {
            theme.customFont = v;
            AuroraConfig.save();
        }));
        cy += 17;
        rows.add(menuKeyRow(g, "Клавиша меню", x, cy, w));
        cy += 17;

        // никнейм профиля (оффлайн-имя в меню и на серверах без авторизации)
        nickRowGeom = new int[]{x, cy, w, 17};
        rows.add(nicknameRow(g, x, cy, w));
        int nickExtra = 0;
        if (nickError != null) {
            nickExtra = 13;
            final String nickErr = nickError;
            final int nickErrY = cy;
            rows.add(new Row(() -> {
            }, () -> RenderUtil.drawText(g, font, nickErr, x, nickErrY + 18, ColorUtil.rgb(252, 165, 165), theme)));
        }
        cy += 17 + nickExtra;

        return cy - y + 8;
    }

    /** Строка клавиши открытия меню. */
    private Row menuKeyRow(GuiGraphics g, String label, int x, int y, int w) {
        return new Row(() -> {
            boolean isListening = "menu".equals(listeningBind);
            String keyText = isListening ? "..." : keyName(theme.menuKey);
            int bw = Math.max(42, RenderUtil.width(font, keyText, theme) + 16);
            int bx = x + w - bw;
            GfxEngine.rounded(g, bx, y + 1, bw, 14, 7,
                    isListening ? theme.accentNow() : ColorUtil.rgba(255, 255, 255, 18));
            hits.add(new Hit(bx, y, bw, 15, 0, () -> {
                listeningBind = "menu";
                playClick();
            }));
        }, () -> {
            boolean isListening = "menu".equals(listeningBind);
            String keyText = isListening ? "..." : keyName(theme.menuKey);
            int bw = Math.max(42, RenderUtil.width(font, keyText, theme) + 16);
            int bx = x + w - bw;
            RenderUtil.drawText(g, font, label, x, y + 4, theme.textSecondary(), theme);
            RenderUtil.drawText(g, font, keyText, bx + bw / 2f - RenderUtil.width(font, keyText, theme) / 2f, y + 4,
                    isListening ? ColorUtil.rgb(255, 255, 255) : theme.textPrimary(), theme);
        });
    }

    /** Строка никнейма: клик — редактирование, Enter — применить, Esc — отмена. */
    private Row nicknameRow(GuiGraphics g, int x, int y, int w) {
        return new Row(() -> {
            if (nickEditing && nickField != null) {
                nickField.setPosition(x + w - 132, y);
                nickField.setWidth(132);
            }
            GfxEngine.rounded(g, x + w - 132, y, 132, 15, 7,
                    nickEditing ? ColorUtil.alpha(theme.accentNow(), 40) : ColorUtil.rgba(255, 255, 255, 18));
            GfxEngine.outline(g, x + w - 132, y, 132, 15, 7,
                    nickEditing ? ColorUtil.alpha(theme.accentNow(), 170) : ColorUtil.rgba(255, 255, 255, 24), 1f);
            hits.add(new Hit(x + w - 132, y, 132, 17, 0, this::beginNickEdit));
            hits.add(new Hit(x, y, w - 136, 17, 0, this::beginNickEdit));
        }, () -> {
            RenderUtil.drawText(g, font, "Никнейм", x, y + 4, theme.textSecondary(), theme);
            if (nickEditing && nickField != null) {
                String value = nickField.getValue();
                RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, value, 116, theme),
                        x + w - 126, y + 4, theme.textPrimary(), theme);
                if (FrameClock.now() % 1000 < 500) {
                    int cursor = Math.max(0, Math.min(value.length(), nickField.getCursorPosition()));
                    String before = value.substring(0, cursor);
                    float caretX = x + w - 126 + Math.min(116, RenderUtil.width(font, before, theme));
                    GfxEngine.rounded(g, caretX, y + 3.5f, 1, 8, 0.5f, ColorUtil.alpha(theme.accentNow(), 220));
                }
            } else {
                String nick = dev.aurora.client.profile.ProfileManager.currentName();
                RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, nick, 120, theme),
                        x + w - 126, y + 4, theme.textPrimary(), theme);
            }
        });
    }

    private void beginNickEdit() {
        if (nickEditing || nickField == null) {
            return;
        }
        nickEditing = true;
        nickError = null;
        nickField.setValue(AuroraConfig.nickname.isEmpty()
                ? dev.aurora.client.profile.ProfileManager.currentName()
                : AuroraConfig.nickname);
        search.setFocused(false);
        setFocused(nickField);
        playClick();
    }

    private void commitNick() {
        if (!nickEditing || nickField == null) {
            return;
        }
        String value = nickField.getValue().trim();
        if (dev.aurora.client.profile.ProfileManager.isValid(value)) {
            AuroraConfig.nickname = value;
            dev.aurora.client.profile.ProfileManager.apply(value);
            AuroraConfig.save();
            nickError = null;
            nickEditing = false;
            setFocused(search);
            playClick();
        } else {
            nickError = "Ник: 3–16 символов, латиница/цифры/_";
        }
    }

    private void cancelNick() {
        nickEditing = false;
        nickError = null;
        setFocused(search);
    }

    /* ------------------------------------------------------------------ */
    /*  Контролы                                                          */
    /* ------------------------------------------------------------------ */

    /** Pill-тумблер: выключен — стеклянная дорожка, включён — градиент акцента со свечением. */
    private void drawPill(GuiGraphics g, float x, float y, float w, float h, float t) {
        t = Math.max(0f, Math.min(1f, t));
        GfxEngine.rounded(g, x, y, w, h, h / 2f, ColorUtil.rgba(255, 255, 255, 34));
        if (t > 0.01f) {
            int gTop = ColorUtil.alpha(theme.accent(0.15f), (int) (255 * t));
            int gBot = ColorUtil.alpha(theme.accent(0.95f), (int) (255 * t));
            GfxEngine.roundedGradient(g, x, y, w, h, h / 2f, gTop, gBot);
            GfxEngine.glow(g, x, y, w, h, h / 2f, 4.5f,
                    ColorUtil.alpha(theme.accentNow(), (int) (48 * t)));
        }
        float knob = h - 4;
        float kx = x + 2 + (w - knob - 4) * t;
        GfxEngine.circle(g, kx + knob / 2, y + h / 2, knob, ColorUtil.rgb(255, 255, 255));
        GfxEngine.ring(g, kx + knob / 2, y + h / 2, knob + 1.8f, ColorUtil.rgba(0, 0, 0, 45), 0.9f);
    }

    private static float clampP(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /* ------------------------------------------------------------------ */
    /*  Пипетка цвета                                                     */
    /* ------------------------------------------------------------------ */

    private void drawColorPickerShapes(GuiGraphics g, int x, int y, int w, int h, int current, Consumer<Integer> setter, float openT) {
        // пипетка мягко «вырастает» из мимки: масштаб вокруг неё + проявление
        float ot = Animation.easeOutCubic(openT);
        g.pose().pushMatrix();
        g.pose().translate(x + w / 2f, y);
        float psc = 0.7f + 0.3f * ot;
        g.pose().scale(psc, psc);
        g.pose().translate(-(x + w / 2f), -y);
        int fade = Math.round(ot * 255);

        GfxEngine.dropShadow(g, x - 2, y - 2, w + 4, h + 6, 8, 2f, 9f,
                ColorUtil.alpha(0x05060A, (int) (110 * ot)));
        GfxEngine.rounded(g, x, y, w, h, 8, ColorUtil.alpha(ColorUtil.rgba(12, 13, 20, 240), fade));
        GfxEngine.outline(g, x, y, w, h, 8, ColorUtil.alpha(ColorUtil.rgba(255, 255, 255, 36), fade), 1f);

        float[] hsb = ColorUtil.hsb(current);
        int fx = x + 8;
        int fy = y + 8;
        int fw = w - 16;
        int fh = h - 42;
        // SV-поле: ячейки тем же движком — попадают в слой поверх фона
        int cols = 12;
        int rows = 8;
        float cw = fw / (float) cols;
        float chh = fh / (float) rows;
        for (int r = 0; r < rows; r++) {
            float v = 1.0f - (float) r / (rows - 1);
            for (int c = 0; c < cols; c++) {
                float sat = (float) c / (cols - 1);
                int col = ColorUtil.fromHsb(hsb[0], sat, Math.max(0.04f, v));
                GfxEngine.rounded(g, fx + c * cw, fy + r * chh, cw + 0.6f, chh + 0.6f, 0, col);
            }
        }
        GfxEngine.outline(g, fx, fy, fw, fh, 3, ColorUtil.rgba(255, 255, 255, 80), 1f);
        // курсор
        float ccx = fx + hsb[1] * (fw - 4);
        float ccy = fy + (1 - hsb[2]) * (fh - 4);
        GfxEngine.circle(g, ccx + 2, ccy + 2, 7, ColorUtil.rgba(255, 255, 255, 230));
        GfxEngine.circle(g, ccx + 2, ccy + 2, 3, ColorUtil.rgb(0, 0, 0));

        // hue-полоса
        int hueY = fy + fh + 6;
        int hueSegs = 12;
        float segW = fw / (float) hueSegs;
        for (int i = 0; i < hueSegs; i++) {
            int c1 = ColorUtil.fromHsb((float) i / hueSegs, 1.0f, 1.0f);
            GfxEngine.rounded(g, fx + i * segW, hueY, segW + 0.6f, 8, 0, c1);
        }
        GfxEngine.outline(g, fx, hueY, fw, 8, 3, ColorUtil.rgba(0, 0, 0, 110), 1f);
        float hx = fx + hsb[0] * (fw - 4);
        GfxEngine.rounded(g, hx, hueY - 2, 4, 12, 2, ColorUtil.rgb(255, 255, 255));

        hits.add(new Hit(fx, fy, fw, fh, 0, () -> {
            dragColorField = true;
            applyColorFromField(fx, fy, fw, fh, hsb[0], setter);
        }));
        hits.add(new Hit(fx, hueY, fw, 10, 0, () -> {
            dragColorHue = true;
            applyHueFromBar(fx, fw, current, setter);
        }));

        colorFieldGeom = new int[]{fx, fy, fw, fh, hueY};
        colorSetter = setter;
        colorCurrent = current;

        g.pose().popMatrix();
    }

    private int[] colorFieldGeom;
    private Consumer<Integer> colorSetter;
    private int colorCurrent;

    private void applyColorFromField(int fx, int fy, int fw, int fh, float hue, Consumer<Integer> setter) {
        float s = clampP((mouseX - fx) / (float) fw);
        float v = 1.0f - clampP((mouseY - fy) / (float) fh);
        setter.accept(ColorUtil.fromHsb(hue, s, Math.max(0.04f, v)));
    }

    private void applyHueFromBar(int fx, int fw, int current, Consumer<Integer> setter) {
        float h = clampP((mouseX - fx) / (float) fw);
        float[] hsb = ColorUtil.hsb(current);
        setter.accept(ColorUtil.fromHsb(h, hsb[1] == 0 ? 1 : hsb[1], hsb[2] == 0 ? 1 : hsb[2]));
    }

    /* ------------------------------------------------------------------ */
    /*  Футер                                                             */
    /* ------------------------------------------------------------------ */

    private void drawFooterShapes(GuiGraphics g, int winX, int winY) {
        int footY = winY + WIN_H - FOOTER_H + 3;
        int btnX = winX + WIN_W - 14;
        btnX = footerButton(g, btnX, footY, "Сохранить", true, 0, () -> {
            AuroraConfig.save();
            playClick();
        });
        footerButton(g, btnX, footY, "Сброс", false, 1, () -> {
            theme.resetToDefault();
            playClick();
        });
    }

    private void drawFooterTexts(GuiGraphics g, int winX, int winY) {
        int footY = winY + WIN_H - FOOTER_H + 3;
        // подсказка начинается от тела, а не от левого края окна: раньше
        // текст лежал поверх стекла сайдбара и выглядел «обрезанным» им
        RenderUtil.drawText(g, font, "ЛКМ — вкл/выкл · ПКМ — настройки · Колесо — прокрутка",
                bodyX + 2, footY + 5, theme.textSecondary(), theme);
        int btnX = winX + WIN_W - 14;
        String save = "Сохранить";
        int saveW = RenderUtil.width(font, save, theme) + 22;
        float shv = footerHoverAnims[0].get();
        RenderUtil.drawText(g, font, save, btnX - saveW + 11, footY + 5,
                ColorUtil.lerp(ColorUtil.rgb(255, 255, 255), ColorUtil.rgb(255, 255, 255), shv), theme);
        String reset = "Сброс";
        int resetW = RenderUtil.width(font, reset, theme) + 22;
        int resetX = btnX - saveW - 8 - resetW;
        float rhv = footerHoverAnims[1].get();
        RenderUtil.drawText(g, font, reset, resetX + 11, footY + 5,
                ColorUtil.lerp(theme.textPrimary(), ColorUtil.rgb(255, 255, 255), rhv), theme);
    }

    private static final int FOOTER_H = 28;

    private int footerButton(GuiGraphics g, int rightX, int y, String label, boolean primary, int animIndex, Runnable action) {
        int tw = RenderUtil.width(font, label, theme) + 22;
        int bx = rightX - tw;
        boolean hover = mouseX >= bx && mouseX < rightX && mouseY >= y && mouseY < y + 19;
        Animation hvA = footerHoverAnims[animIndex];
        hvA.to(hover ? 1 : 0);
        float hv = hvA.get();
        if (primary) {
            // главная кнопка — градиент акцента, при наведении светится сильнее
            int top = ColorUtil.lerp(theme.accent(0.1f), theme.accent(0.35f), hv);
            int bottom = ColorUtil.lerp(theme.accent(0.95f), theme.accent(0.75f), hv);
            GfxEngine.roundedGradient(g, bx, y, tw, 19, 9.5f,
                    ColorUtil.alpha(top, 235), ColorUtil.alpha(bottom, 235));
            if (hv > 0.02f) {
                GfxEngine.glow(g, bx, y, tw, 19, 9.5f, 6f,
                        ColorUtil.alpha(theme.accentNow(), (int) (56 * hv)));
            }
        } else {
            if (hv > 0.02f) {
                // мягкое свечение второстепенной кнопки при наведении
                GfxEngine.dropShadow(g, bx - 1, y - 1, tw + 2, 21, 10, 1f, 7f,
                        ColorUtil.alpha(theme.accentNow(), (int) (40 * hv)));
            }
            GfxEngine.rounded(g, bx, y, tw, 19, 9.5f, ColorUtil.lerp(
                    ColorUtil.rgba(255, 255, 255, 16), ColorUtil.alpha(theme.accentNow(), 85), hv));
        }
        hits.add(new Hit(bx, y, tw, 19, 0, action));
        return bx - 8;
    }

    /* ------------------------------------------------------------------ */
    /*  Ввод                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        float cx = this.width / 2f;
        float cy = this.height / 2f;
        float s = Math.max(0.01f, uiScale);
        mouseX = Math.round((float) (e.x() - cx) / s + cx);
        mouseY = Math.round((float) (e.y() - cy) / s + cy);
        if (listeningBind != null) {
            return true; // ждём клавишу
        }
        // редактирование никнейма: клик мимо строки применяет/отменяет ввод
        if (nickEditing) {
            int[] nr = nickRowGeom;
            boolean inside = nr != null && mouseX >= nr[0] && mouseX <= nr[0] + nr[2]
                    && mouseY >= nr[1] && mouseY <= nr[1] + nr[3];
            if (!inside) {
                commitNick();
            }
            return true;
        }
        int btn = e.button();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (mouseX >= hit.x && mouseX <= hit.x + hit.w && mouseY >= hit.y && mouseY <= hit.y + hit.h
                    && (hit.button == -1 || hit.button == btn)) {
                hit.action.run();
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragActiveSlider = null;
        dragColorField = false;
        dragColorHue = false;
        AuroraConfig.markDirty();
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dragX, double dragY) {
        float cx = this.width / 2f;
        float cy = this.height / 2f;
        float s = Math.max(0.01f, uiScale);
        mouseX = Math.round((float) (e.x() - cx) / s + cx);
        mouseY = Math.round((float) (e.y() - cy) / s + cy);
        if (dragActiveSlider != null) {
            float p = clampP((mouseX - sliderX) / (float) sliderW);
            dragActiveSlider.accept(p);
            return true;
        }
        if (dragColorField && colorFieldGeom != null && colorSetter != null) {
            int[] gm = colorFieldGeom;
            float[] hsb = ColorUtil.hsb(colorCurrent);
            applyColorFromField(gm[0], gm[1], gm[2], gm[3], hsb[0], colorSetter);
            return true;
        }
        if (dragColorHue && colorFieldGeom != null && colorSetter != null) {
            int[] gm = colorFieldGeom;
            applyHueFromBar(gm[0], gm[2], colorCurrent, colorSetter);
            return true;
        }
        return super.mouseDragged(e, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scrollTarget -= scrollY * 26;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (nickEditing) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commitNick();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                cancelNick();
                return true;
            }
            return super.keyPressed(e); // ввод идёт в EditBox
        }
        if (listeningBind != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                listeningBind = null;
            } else {
                if ("menu".equals(listeningBind)) {
                    theme.menuKey = key;
                } else if (listeningBind instanceof BindSetting bs) {
                    bs.set(key);
                }
                listeningBind = null;
                AuroraConfig.save();
                playClick();
            }
            return true;
        }
        if (search != null && search.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                search.setFocused(false);
                return true;
            }
            // меню-клавиша НЕ закрывает меню при сфокусированном поиске:
            // Ctrl+C (копирование) начинался с keydown Ctrl — меню закрывалось (баг 1.0.5)
            return super.keyPressed(e);
        }
        if (key == GLFW.GLFW_KEY_ESCAPE || key == theme.menuKey) {
            closeAnimated();
            return true;
        }
        return super.keyPressed(e);
    }

    private void closeAnimated() {
        if (closing) {
            super.onClose();
            return;
        }
        closing = true;
        openAnim.to(0);
        openAnim.snap(Math.max(0.02f, openAnim.get()));
    }

    @Override
    public void onClose() {
        closeAnimated();
    }

    @Override
    public void removed() {
        AuroraConfig.flush();
        try {
            if (originalBlur != null && Minecraft.getInstance().options != null) {
                Minecraft.getInstance().options.menuBackgroundBlurriness().set(originalBlur);
            }
        } catch (Exception ignored) {
        }
        currentBlur = -1;
        super.removed();
    }

    @Override
    public void added() {
        openAnim.snap(theme.animations ? 0.0f : 1.0f);
        openAnim.to(1);
        tabAnim.snap(0.0f);
        tabAnim.to(1);
        // смоук-харнесс: раскрыть все карточки текущей вкладки (AURORA_TEST_EXPAND=1)
        if ("1".equals(System.getenv("AURORA_TEST_EXPAND"))) {
            for (Module m : ModuleManager.byCategory(tab)) {
                expanded.put(m, true);
                expandAnims.put(m, new Animation(1, 13));
            }
        }
    }
}
