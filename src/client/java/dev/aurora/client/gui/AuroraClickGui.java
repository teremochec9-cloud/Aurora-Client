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
import dev.aurora.client.profile.ProfileManager;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.GfxEngine;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

/**
 * ClickGUI Aurora: тёмное окно (радиус 12) с боковой панелью-полосой и
 * разделителем, карточками модулей в две колонки (имя / статус / описание,
 * pill-тумблер справа), тегами включённых модулей в шапке, поиском с
 * иконкой-лупой и кнопкой HUD-редактора.
 *
 * <p>Компоновка выровнена по референсу (ровная сетка карточек, теги в шапке,
 * статус «Включен/Выключен»), но сохраняет айдентику Aurora: акцентный
 * градиент, стекло и анимации.</p>
 *
 * <p>Отрисовка разбита на слои (strata): сначала все формы SDF-движка,
 * затем текст — поэтому панели никогда не перекрывают подписи, а
 * ванильный блюр фона выполняется ровно один раз за кадр.</p>
 */
public class AuroraClickGui extends Screen {
    private static final int WIN_W = 660;
    private static final int WIN_H = 384;
    private static final int WIN_R = 12;
    private static final int SIDEBAR_W = 148;
    private static final int GAP = 10;
    private static final int CARD_R = 10;
    private static final int SEARCH_W = 150;
    /** Высота шапки тела: заголовок + теги + поиск. */
    private static final int HEADER_H = 36;

    private final Theme theme;
    /** Экран, на который возвращаемся при закрытии (например, главное меню). */
    private final Screen parent;
    private final Animation openAnim = new Animation(0, 13);
    private final Animation tabAnim = new Animation(0, 12);
    private final Animation dialogAnim = new Animation(0, 15);
    /** Живой скроллбар: подсветка ползунка при активности/наведении на тело. */
    private final Animation scrollAnim = new Animation(0, 9);
    private final Map<Module, Animation> hoverAnims = new HashMap<>();
    private final Map<Module, Animation> toggleAnims = new HashMap<>();
    private final Map<Module, Animation> expandAnims = new HashMap<>();
    private final Map<Module, Boolean> expanded = new HashMap<>();
    /** Плавные тумблеры настроек: анимация каждой строки-переключателя. */
    private final Map<Object, Animation> settingAnims = new HashMap<>();
    /** Наведение на слайдеры (отдельная карта — ключи те же объекты настроек). */
    private final Map<Object, Animation> sliderHoverAnims = new HashMap<>();
    /** Кэш переносов описаний модулей (пересчитывается только при смене ширины). */
    private final Map<Module, List<String>> descCache = new HashMap<>();
    private int descCacheKey = -1;
    /** Скользящий индикатор активной категории (позиция относительно верха сайдбара). */
    private final Animation sideIndicator = new Animation(0, 16);
    /** Штамп каскадного появления карточек: карточки раскрываются по очереди. */
    private long revealStamp = System.currentTimeMillis();
    /** Прошлое значение поиска — для перезапуска каскада при смене результатов. */
    private String lastSearchValue = "";

    private Category tab = Category.VISUAL;
    private boolean themeTab = false;
    private AuroraTextField search;
    private AuroraTextField nickField;
    private boolean profileOpen;
    private String profileError;
    private long profileErrorTime;
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
        final int idx;
        final int x, y, w, h;
        final int headerH;
        final List<Row> rows;
        final Runnable shapes;
        final Runnable texts;

        Card(Module module, int idx, int x, int y, int w, int h, int headerH, List<Row> rows,
             Runnable shapes, Runnable texts) {
            this.module = module;
            this.idx = idx;
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

    public AuroraClickGui() {
        this(null);
    }

    /** parent — экран, на который вернуться после закрытия (может быть null). */
    public AuroraClickGui(Screen parent) {
        super(Component.literal("Aurora"));
        this.theme = AuroraClient.theme();
        this.parent = parent;
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
        search = new AuroraTextField(this.font, 0, 0, SEARCH_W - 30, 16, Component.literal("Поиск"));
        search.setMaxLength(40);
        search.setBordered(false);
        // Ванильную отрисовку EditBox не вызываем: renderWidget() у
        // AuroraTextField — заглушка, текст/каретку/выделение рисует наш
        // движок из состояния виджета. visible оставляем true:
        // EditBox.charTyped требует isActive() = visible && active — при
        // setVisible(false) каждый символ молча отбрасывался (исчезал ввод)
        addWidget(search);

        nickField = new AuroraTextField(this.font, 0, 0, PROFILE_W - 48, 16, Component.literal("Никнейм"));
        nickField.setMaxLength(16);
        nickField.setBordered(false);
        addWidget(nickField);

        setFocused(search);
        // повышаем силу блюра на время, пока меню открыто
        try {
            if (Minecraft.getInstance().options != null) {
                Integer cur = (Integer) Minecraft.getInstance().options.menuBackgroundBlurriness().get();
                if (cur != null) {
                    originalBlur = cur;
                    if (theme.blur && cur < theme.blurStrength) {
                        Minecraft.getInstance().options.menuBackgroundBlurriness().set(theme.blurStrength);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void tick() {
        openAnim.update();
        tabAnim.update();
        dialogAnim.update();
        scrollAnim.update();
        for (Animation a : hoverAnims.values()) {
            a.update();
        }
        for (Animation a : toggleAnims.values()) {
            a.update();
        }
        for (Animation a : expandAnims.values()) {
            a.update();
        }
        for (Animation a : settingAnims.values()) {
            a.update();
        }
        scrollPos += (scrollTarget - scrollPos) * 0.35f;
        if (Math.abs(scrollTarget - scrollPos) < 0.5f) {
            scrollPos = scrollTarget;
        }
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
                ColorUtil.rgba(9, 10, 16, 130), ColorUtil.rgba(8, 9, 14, 165));
    }

    /* ------------------------------------------------------------------ */
    /*  Рендер                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        float t = openAnim.update();
        if (closing) {
            if (openAnim.isDone() && openAnim.getTarget() == 0) {
                finishClose();
                return;
            }
        }

        hits.clear();
        cards.clear();
        themeRows.clear();

        // перезапуск каскада при смене поискового запроса
        if (search != null) {
            String v = search.getValue();
            if (!v.equals(lastSearchValue)) {
                lastSearchValue = v;
                resetReveal();
            }
        }

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
        // мягкий «выезд» окна снизу вверх при открытии/закрытии
        int winYAnim = winY - (int) ((1f - Animation.easeOutQuint(t)) * 12);

        // ===== геометрия тела =====
        bodyX = winX + SIDEBAR_W + 10;
        bodyY = winYAnim + 8;
        bodyW = winX + WIN_W - 10 - bodyX;
        bodyH = WIN_H - 16;

        // строка поиска следует за окном при масштабе
        if (search != null) {
            search.setPosition(searchFieldX() + 24, bodyY + 10);
            search.setWidth(SEARCH_W - 30);
        }
        if (nickField != null) {
            nickField.setPosition(profileFieldX(winX) + 8, profileFieldY(winY) + 4);
            nickField.setWidth(PROFILE_W - 48);
        }

        // масштаб вписывания применяем всегда — даже при выключенных анимациях.
        // Раньше окно рисовалось в натуральную величину, а мышь считалась
        // по масштабу fit → на небольших экранах все клики «съезжали»
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(scale, scale);
        g.pose().translate(-cx, -cy);

        // ===== фаза 1: формы =====
        drawWindowShapes(g, winX, winYAnim, t);
        drawSidebarShapes(g, winX, winYAnim, t);
        drawHeaderShapes(g, t);
        layoutContent(g, winX, winYAnim);
        drawBodyShapes(g);
        drawProfileDialogShapes(g, winX, winYAnim);

        g.nextStratum();

        // ===== фаза 2: текст =====
        drawSidebarText(g, winX, winYAnim, t);
        drawHeaderText(g);
        drawBodyTexts(g);
        drawProfileDialogTexts(g, winX, winYAnim);

        g.pose().popMatrix();

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
        float k = alpha / 255f;
        GfxEngine.dropShadow(g, winX, winY, WIN_W, WIN_H, WIN_R, 5f, 9f,
                ColorUtil.rgba(0, 0, 0, (int) (150 * t)));
        // почти непрозрачная тёмная база (референс) + лёгкий стеклянный блик
        int baseA = (int) ((195 + 60 * theme.backgroundAlpha) * k);
        GfxEngine.rounded(g, winX, winY, WIN_W, WIN_H, WIN_R, ColorUtil.rgba(10, 11, 17, baseA));
        GfxEngine.roundedGradient(g, winX, winY, WIN_W, WIN_H, WIN_R,
                ColorUtil.rgba(255, 255, 255, (int) (10 * k)), ColorUtil.rgba(255, 255, 255, (int) (2 * k)));
        GfxEngine.outline(g, winX, winY, WIN_W, WIN_H, WIN_R, ColorUtil.rgba(255, 255, 255, (int) (26 * k)), 1f);
        // мягкий блик по верхней кромке
        GfxEngine.rounded(g, winX + WIN_R + 4, winY + 1, WIN_W - (WIN_R + 4) * 2, 1, 0.5f,
                ColorUtil.rgba(255, 255, 255, (int) (36 * k)));
    }

    /* ------------------------------------------------------------------ */
    /*  Боковая панель                                                    */
    /* ------------------------------------------------------------------ */

    private void drawSidebarShapes(GuiGraphics g, int winX, int winY, float t) {
        int alpha = (int) (255 * Animation.smooth(t));
        if (alpha < 4) {
            return;
        }
        float kf = alpha / 255f;
        int sx = winX;
        int sy = winY;
        int sw = SIDEBAR_W;
        int sh = WIN_H;

        // тёмная полоса сайдбара + вертикальный разделитель (как на референсе)
        GfxEngine.rounded(g, sx, sy, sw, sh, WIN_R, ColorUtil.rgba(0, 0, 0, (int) (85 * kf)));
        GfxEngine.rounded(g, sx + sw - 1, sy + 12, 1, sh - 24, 0.5f,
                ColorUtil.rgba(255, 255, 255, (int) (18 * kf)));

        // версии-чип у логотипа
        int logoW = RenderUtil.width(font, Theme.CLIENT_NAME, theme);
        int chipW = RenderUtil.width(font, Theme.CLIENT_VERSION, theme) + 10;
        GfxEngine.rounded(g, sx + 14 + logoW + 6, sy + 11, chipW, 12, 6,
                ColorUtil.alpha(theme.accentNow(), (int) (100 * kf)));

        // ===== скользящий индикатор активной категории =====
        // позиции: категории с 46-й строки с шагом 30; «Тема» — сразу после них
        int activeIdx = themeTab ? Category.values().length : tab.ordinal();
        int indicatorTarget = 46 + activeIdx * 30 + (themeTab ? 2 : 0);
        float indY = sy + sideIndicator.apply(indicatorTarget);
        GfxEngine.rounded(g, sx + 8, indY, sw - 18, 26, 8,
                ColorUtil.alpha(theme.accentNow(), (int) (34 * kf)));
        GfxEngine.outline(g, sx + 8, indY, sw - 18, 26, 8,
                ColorUtil.alpha(theme.accentNow(), (int) (55 * kf)), 1f);
        GfxEngine.rounded(g, sx + 12, indY + 6, 2, 14, 1,
                ColorUtil.alpha(theme.accentNow(), 220));

        // ===== категории =====
        int cy = sy + 46;
        for (Category cat : Category.values()) {
            boolean active = !themeTab && tab == cat;
            boolean hover = mouseX >= sx + 8 && mouseX < sx + sw - 10 && mouseY >= cy && mouseY < cy + 26;
            if (hover) {
                GfxEngine.rounded(g, sx + 8, cy, sw - 18, 26, 8, ColorUtil.rgba(255, 255, 255, (int) (10 * kf)));
            }
            drawCategoryIcon(g, cat, sx + 18, cy + 7, active);
            hits.add(new Hit(sx + 8, cy, sw - 18, 26, -1, () -> {
                playClick();
                themeTab = false;
                tab = cat;
                // сбрасываем поиск: иначе список модулей не сменится,
                // пока в поиске остаётся текст («вкладка не переключается»)
                if (search != null) {
                    search.setValue("");
                }
                scrollTarget = 0;
                tabAnim.snap(0);
                tabAnim.to(1);
                resetReveal();
            }));
            cy += 30;
        }

        // ===== «Тема» =====
        int themeY = cy + 2;
        boolean themeHover = mouseX >= sx + 8 && mouseX < sx + sw - 10 && mouseY >= themeY && mouseY < themeY + 26;
        if (themeHover) {
            GfxEngine.rounded(g, sx + 8, themeY, sw - 18, 26, 8, ColorUtil.rgba(255, 255, 255, (int) (10 * kf)));
        }
        // иконка «палитра» — три кружка
        GfxEngine.circle(g, sx + 22, themeY + 11, 10, theme.accentNow());
        GfxEngine.circle(g, sx + 27, themeY + 15, 8, theme.accent(1.0f));
        GfxEngine.circle(g, sx + 18, themeY + 16, 6, ColorUtil.rgba(255, 255, 255, 120));
        hits.add(new Hit(sx + 8, themeY, sw - 18, 26, -1, () -> {
            playClick();
            themeTab = true;
            if (search != null) {
                search.setValue("");
            }
            scrollTarget = 0;
            tabAnim.snap(0);
            tabAnim.to(1);
            resetReveal();
        }));

        // ===== профиль (клик — смена никнейма) =====
        int profY = sy + sh - 56;
        GfxEngine.rounded(g, sx + 8, profY, sw - 16, 42, 10, ColorUtil.rgba(255, 255, 255, (int) (8 * kf)));
        GfxEngine.outline(g, sx + 8, profY, sw - 16, 42, 10, ColorUtil.rgba(255, 255, 255, (int) (16 * kf)), 1f);
        // аватар: акцентный круг с инициалом
        int avX = sx + 24;
        int avY = profY + 21;
        GfxEngine.circle(g, avX, avY, 24, theme.accentNow());
        GfxEngine.circle(g, avX, avY, 19, ColorUtil.lerp(theme.accentNow(), ColorUtil.rgb(14, 15, 23), 0.45f));
        boolean profHover = mouseX >= sx + 8 && mouseX < sx + sw - 8 && mouseY >= profY && mouseY < profY + 42;
        if (profHover) {
            GfxEngine.rounded(g, sx + 8, profY, sw - 16, 42, 10, ColorUtil.rgba(255, 255, 255, (int) (12 * kf)));
        }
        // статусная точка справа (индикатор на референсе)
        GfxEngine.circle(g, sx + sw - 20, profY + 21, 6, theme.accentNow());
        hits.add(new Hit(sx + 8, profY, sw - 16, 42, 0, this::openProfileDialog));
    }

    private void drawSidebarText(GuiGraphics g, int winX, int winY, float t) {
        float kf = Animation.smooth(t);
        int sx = winX;
        int sy = winY;
        int sw = SIDEBAR_W;

        // логотип
        RenderUtil.drawGlowText(g, font, Theme.CLIENT_NAME, sx + 14, sy + 11,
                theme.textPrimary(), ColorUtil.alpha(theme.accentNow(), (int) (36 * kf)), theme);
        int logoW = RenderUtil.width(font, Theme.CLIENT_NAME, theme);
        int chipW = RenderUtil.width(font, Theme.CLIENT_VERSION, theme) + 10;
        RenderUtil.drawText(g, font, Theme.CLIENT_VERSION, sx + 14 + logoW + 11, sy + 14, ColorUtil.rgb(255, 255, 255), theme);
        RenderUtil.drawText(g, font, "клиент для MC " + Theme.MC_VERSION, sx + 14, sy + 26,
                ColorUtil.alpha(theme.textSecondary(), 150), theme);

        // категории
        int cy = sy + 46;
        for (Category cat : Category.values()) {
            boolean active = !themeTab && tab == cat;
            RenderUtil.drawText(g, font, cat.title, sx + 38, cy + 9,
                    active ? theme.accentNow() : theme.textSecondary(), theme);
            cy += 30;
        }

        // «Тема»
        RenderUtil.drawText(g, font, "Тема", sx + 38, cy + 2 + 9,
                themeTab ? theme.accentNow() : theme.textSecondary(), theme);

        // профиль
        int profY = sy + WIN_H - 56;
        String nick = ProfileManager.currentName();
        String initial = nick.isEmpty() ? "A" : nick.substring(0, 1).toUpperCase();
        RenderUtil.drawCenteredText(g, font, initial, sx + 24, profY + 16, ColorUtil.rgb(255, 255, 255), theme);
        RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, nick, sw - 64, theme), sx + 42, profY + 12, theme.textPrimary(), theme);
        // вторая строка короче и с гарантированной обрезкой — раньше
        // «Профиль · сменить» не влезала в чип и резалась правым краем
        RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, "Сменить профиль", sw - 64, theme),
                sx + 42, profY + 24, theme.textSecondary(), theme);
    }

    /** Минималистичные геометрические иконки категорий 12x12. */
    private void drawCategoryIcon(GuiGraphics g, Category cat, float x, float y, boolean active) {
        int color = active ? theme.accentNow() : theme.textSecondary();
        switch (cat) {
            case VISUAL -> {
                // «глаз»: линза + зрачок
                GfxEngine.outline(g, x, y + 2, 12, 8, 4, color, 1f);
                GfxEngine.circle(g, x + 6, y + 6, 4, color);
                GfxEngine.circle(g, x + 6, y + 6, 1.6f, ColorUtil.rgba(12, 13, 20, 200));
            }
            case HUD -> {
                // «панели»: три строки разной ширины
                GfxEngine.rounded(g, x, y + 1, 12, 2.4f, 1.2f, color);
                GfxEngine.rounded(g, x, y + 5, 8, 2.4f, 1.2f, color);
                GfxEngine.rounded(g, x, y + 9, 10, 2.4f, 1.2f, color);
            }
            case CHAT -> {
                // «пузырь»
                GfxEngine.rounded(g, x, y + 1, 12, 8, 3.5f, color);
                GfxEngine.circle(g, x + 4, y + 5, 1.6f, ColorUtil.rgba(12, 13, 20, 210));
                GfxEngine.circle(g, x + 6.5f, y + 5, 1.6f, ColorUtil.rgba(12, 13, 20, 210));
                GfxEngine.circle(g, x + 9, y + 5, 1.6f, ColorUtil.rgba(12, 13, 20, 210));
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Шапка                                                             */
    /* ------------------------------------------------------------------ */

    private int hudButtonW() {
        return RenderUtil.width(font, "HUD-редактор", theme) + 20;
    }

    private int searchFieldX() {
        return bodyX + bodyW - SEARCH_W;
    }

    private int hudButtonX() {
        return searchFieldX() - 8 - hudButtonW();
    }

    /** Заголовок шапки. */
    private String headerTitle() {
        if (themeTab) {
            return "Тема";
        }
        if (search != null && !search.getValue().trim().isEmpty()) {
            return "Поиск";
        }
        return tab.title;
    }

    /** Теги шапки: имена включённых модулей категории (как aura/antibot на референсе). */
    private List<String> headerTags() {
        List<String> out = new ArrayList<>();
        if (themeTab || (search != null && !search.getValue().trim().isEmpty())) {
            return out;
        }
        for (Module m : ModuleManager.byCategory(tab)) {
            if (m.isEnabled()) {
                out.add(m.name);
                if (out.size() == 4) {
                    break;
                }
            }
        }
        return out;
    }

    private void drawHeaderShapes(GuiGraphics g, float t) {
        int alpha = (int) (255 * Animation.smooth(t));
        if (alpha < 4) {
            return;
        }
        float k = alpha / 255f;

        // теги включённых модулей рядом с заголовком (каскадное появление)
        int tx = bodyX + 2 + RenderUtil.widthBold(font, headerTitle(), theme) + 10;
        int tagIdx = 0;
        for (String tag : headerTags()) {
            int tw = RenderUtil.width(font, tag, theme) + 14;
            if (tx + tw > hudButtonX() - 10) {
                break;
            }
            float rt = tagReveal(tagIdx++);
            int ta = (int) (rt * 255);
            if (ta > 4) {
                g.pose().pushMatrix();
                g.pose().translate(0, Math.round((1f - rt) * 4));
                GfxEngine.rounded(g, tx, bodyY + 7, tw, 16, 8, ColorUtil.rgba(255, 255, 255, (int) (8 * k * rt)));
                GfxEngine.outline(g, tx, bodyY + 7, tw, 16, 8, ColorUtil.rgba(255, 255, 255, (int) (16 * k * rt)), 1f);
                g.pose().popMatrix();
            }
            tx += tw + 6;
        }

        // кнопка HUD-редактора
        int hudW = hudButtonW();
        int hudX = hudButtonX();
        boolean hudHover = mouseX >= hudX && mouseX < hudX + hudW && mouseY >= bodyY + 4 && mouseY < bodyY + 26;
        RenderUtil.glassChip(g, hudX, bodyY + 4, hudW, 22, 11,
                hudHover ? ColorUtil.alpha(theme.accentNow(), 100) : ColorUtil.rgba(255, 255, 255, (int) (10 * k)),
                hudHover ? ColorUtil.alpha(theme.accentNow(), 180) : ColorUtil.rgba(255, 255, 255, (int) (20 * k)), theme);
        hits.add(new Hit(hudX, bodyY + 4, hudW, 22, 0, () -> {
            playClick();
            Minecraft.getInstance().setScreen(new HudEditorScreen(this));
        }));

        // строка поиска: клик по полю возвращает фокус ввода
        hits.add(new Hit(searchFieldX(), bodyY + 4, SEARCH_W, 22, 0, () -> setFocused(search)));

        // строка поиска (стекло)
        RenderUtil.glassChip(g, searchFieldX(), bodyY + 4, SEARCH_W, 22, 11,
                ColorUtil.rgba(255, 255, 255, (int) (8 * k)),
                search != null && search.isFocused()
                        ? ColorUtil.alpha(theme.accentNow(), 150)
                        : ColorUtil.rgba(255, 255, 255, (int) (18 * k)), theme);
    }

    /** Каскад тегов шапки (0..1) с задержкой между соседними. */
    private float tagReveal(int i) {
        float per = 0.04f;
        float elapsed = (System.currentTimeMillis() - revealStamp) / 1000f - i * per;
        return Animation.smooth(Math.max(0f, Math.min(1f, elapsed * 7f)));
    }

    private void drawHeaderText(GuiGraphics g) {
        RenderUtil.drawTextBold(g, font, headerTitle(), bodyX + 2, bodyY + 10, theme.textPrimary(), theme);

        // теги
        int tx = bodyX + 2 + RenderUtil.widthBold(font, headerTitle(), theme) + 10;
        int tagIdx = 0;
        for (String tag : headerTags()) {
            int tw = RenderUtil.width(font, tag, theme) + 14;
            if (tx + tw > hudButtonX() - 10) {
                break;
            }
            float rt = tagReveal(tagIdx++);
            if (rt > 0.02f) {
                g.pose().pushMatrix();
                g.pose().translate(0, Math.round((1f - rt) * 4));
                RenderUtil.drawText(g, font, tag, tx + 7, bodyY + 11,
                        ColorUtil.alpha(theme.textSecondary(), (int) (210 * rt)), theme);
                g.pose().popMatrix();
            }
            tx += tw + 6;
        }

        int hudW = hudButtonW();
        int hudX = hudButtonX();
        boolean hudHover = mouseX >= hudX && mouseX < hudX + hudW && mouseY >= bodyY + 4 && mouseY < bodyY + 26;
        RenderUtil.drawText(g, font, "HUD-редактор", hudX + 10, bodyY + 10,
                hudHover ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);

        // иконка-лупа поиска
        float ix = searchFieldX() + 13;
        float iy = bodyY + 15;
        GfxEngine.ring(g, ix, iy, 7, ColorUtil.rgba(235, 236, 244, 115), 1f);
        GfxEngine.circle(g, ix + 3.1f, iy + 3.1f, 1.8f, ColorUtil.rgba(235, 236, 244, 115));
        GfxEngine.circle(g, ix + 4.1f, iy + 4.1f, 1.4f, ColorUtil.rgba(235, 236, 244, 100));

        // строка поиска: значение, курсор и плейсхолдер — всё через движок
        if (search != null) {
            String value = search.getValue();
            boolean focused = search.isFocused();
            float textX = searchFieldX() + 24;
            float textY = bodyY + 10;
            if (value.isEmpty()) {
                // плейсхолдер виден и в фокусе — пустое поле больше не «исчезает»
                RenderUtil.drawText(g, font, "Поиск...", textX, textY, ColorUtil.rgba(235, 236, 244, 95), theme);
                if (focused && System.currentTimeMillis() % 1000 < 500) {
                    GfxEngine.rounded(g, textX, textY + 0.5f, 1, 8, 0.5f, ColorUtil.alpha(theme.accentNow(), 220));
                }
            } else {
                RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, value, SEARCH_W - 30, theme),
                        textX, textY, theme.textPrimary(), theme);
                if (focused) {
                    // каретка и выделение — из состояния виджета, не из своего
                    drawFieldCaret(g, textX, textY, value, search.getCursorPosition(),
                            search.getHighlighted(), SEARCH_W - 30);
                }
            }
        }
    }

    /**
     * Каретка и выделение текстового поля — строго из состояния виджета
     * (cursor + highlighted-строка, 1.21.11 не даёт геттер позиции начала
     * выделения), координаты в gui-пикселях движка.
     */
    private void drawFieldCaret(GuiGraphics g, float textX, float textY, String value,
                                int cursor, String highlighted, int maxWidth) {
        int from = cursor;
        int to = cursor;
        if (!highlighted.isEmpty()) {
            if (cursor + highlighted.length() <= value.length()
                    && value.startsWith(highlighted, cursor)) {
                from = cursor;
                to = cursor + highlighted.length();
            } else {
                to = cursor;
                from = cursor - highlighted.length();
            }
        }
        from = Math.max(0, Math.min(from, value.length()));
        to = Math.max(0, Math.min(to, value.length()));
        String before = value.substring(0, from);
        float caretX = textX + RenderUtil.width(font, RenderUtil.ellipsize(font, before, maxWidth, theme), theme) + 1;
        if (from != to) {
            // выделение: подсветка между началом и концом выделения
            String sel = value.substring(from, to);
            int selW = RenderUtil.width(font, RenderUtil.ellipsize(font, sel, maxWidth, theme), theme);
            GfxEngine.rounded(g, caretX - 1, textY - 1, Math.max(2, selW + 2), 10, 2,
                    ColorUtil.alpha(theme.accentNow(), 90));
        } else if (System.currentTimeMillis() % 1000 < 500) {
            GfxEngine.rounded(g, caretX, textY + 0.5f, 1, 8, 0.5f, ColorUtil.alpha(theme.accentNow(), 220));
        }
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
        int cardsTop = bodyY + HEADER_H + 4;
        int viewH = bodyH - HEADER_H - 8;
        if (themeTab) {
            themeRows.clear();
            int colW = Math.min(320, bodyW - 12);
            int rx = bodyX + (int) ((1f - Animation.smooth(tabAnim.get())) * 16) + (bodyW - colW) / 2;
            themeRowsH = buildThemeRows(g, themeRows, rx, cardsTop + 14 - (int) scrollPos, colW);
            maxScroll = Math.max(0, themeRowsH + 18 - viewH);
            scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
            scrollPos = Math.min(scrollPos, maxScroll);
            return;
        }

        List<Module> modules = currentModules();
        int colW = (bodyW - GAP) / 2;
        int[] colYs = new int[2];
        int contentH = 0;

        // лёгкий «выезд» контента при смене вкладки
        float tabT = Animation.smooth(tabAnim.get());
        int slideX = (int) ((1f - tabT) * 16);

        // кэш переносов описаний: пересчитываем только при смене ширины колонки
        // (ширина с запасом от тумблера и плюса справа)
        int descKey = colW - 76;
        if (descCacheKey != descKey) {
            descCache.clear();
            descCacheKey = descKey;
        }

        for (Module m : modules) {
            int col = colYs[0] <= colYs[1] ? 0 : 1;
            int x = bodyX + slideX + col * (colW + GAP);
            int y = cardsTop + colYs[col] - (int) scrollPos;
            float et = expandT(m);

            List<String> desc = descCache.computeIfAbsent(m,
                    key -> RenderUtil.wrapLimited(font, m.description, descKey, theme, 2));
            // имя (y+8) + статус (y+19) + строки описания (y+31..) + нижний паддинг
            int headerH = 38 + Math.max(1, desc.size()) * 10;
            int rowsH = settingsHeight(m);
            // +8 при раскрытии — нижний паддинг настроек, чтобы последняя строка
            // не прилипала к краю карточки (у свёрнутой карточки +6 достаточно)
            int h = headerH + 6 + Math.round((rowsH + 8) * et);

            // строки настроек рисуем с отступом 12px от краёв карточки и с
            // правым запасом 14px — раньше подписи рисовались от самой границы
            // и «вылезали» за плашку
            List<Row> rows = et > 0.01f ? buildModuleRows(g, m, x + 12, y + headerH + 8, colW - 26) : List.of();

            colYs[col] += h + GAP;
            contentH = Math.max(contentH, colYs[col]);

            cards.add(buildCard(g, m, cards.size(), x, y, colW, h, headerH, desc, rows, et));
        }

        maxScroll = Math.max(0, contentH - viewH);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scrollPos = Math.min(scrollPos, maxScroll);
    }

    private int settingsHeight(Module m) {
        int h = 0;
        for (Setting s : m.settings()) {
            if (s instanceof NumberSetting) {
                h += 25;
            } else if (s instanceof ColorSetting) {
                h += s.equals(openColorTarget) ? 17 + 96 : 17;
            } else {
                h += 17;
            }
        }
        return h;
    }

    /** Время каскадного появления карточки с индексом idx (0..1). */
    private float revealT(int idx) {
        float per = 0.03f; // задержка между карточками, сек
        float elapsed = (System.currentTimeMillis() - revealStamp) / 1000f - idx * per;
        return Animation.smooth(Math.max(0f, Math.min(1f, elapsed * 8f)));
    }

    /** Перезапуск каскада (смена вкладки/поиска/открытие меню). */
    private void resetReveal() {
        revealStamp = System.currentTimeMillis();
    }

    private Card buildCard(GuiGraphics g, Module m, int idx, int x, int y, int w, int h, int headerH, List<String> desc, List<Row> rows, float et) {
        boolean hasSettings = !m.settings().isEmpty();
        boolean open = Boolean.TRUE.equals(expanded.get(m));

        Runnable shapes = () -> {
            // каскад: карточка ещё «не наступила» — не рисуем и не даём кликать
            float rv = revealT(idx);
            if (rv <= 0.01f) {
                return;
            }
            Animation ha = hoverAnims.computeIfAbsent(m, k -> new Animation(0, 10));
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + h;
            ha.to(hovered ? 1 : 0);
            float hover = ha.update();
            // мягкий подъём карточки под курсором — тело «дышит»
            float lift = 1.5f * Animation.smooth(hover);
            float toggleT = toggleAnims.computeIfAbsent(m, k -> new Animation(m.isEnabled() ? 1 : 0, 14)).update();
            int a = (int) (255 * Animation.smooth(tabAnim.get()) * rv);
            if (a < 4) {
                return;
            }

            // лёгкий сдвиг вверх при появлении — карточка «доезжает» на место;
            // + подъём при наведении
            int slideY = Math.round((1f - rv) * 7 - lift);
            g.pose().pushMatrix();
            g.pose().translate(0, slideY);

            // фон карточки: ровная тёмная плитка (референс) с мягкой подсветкой
            float k = a / 255f;
            GfxEngine.rounded(g, x, y, w, h, CARD_R, ColorUtil.rgba(255, 255, 255, (int) (5 * k)));
            int fill = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 5), ColorUtil.rgba(255, 255, 255, 13), hover);
            fill = ColorUtil.lerp(fill, ColorUtil.alpha(theme.accentNow(), 22), toggleT);
            GfxEngine.rounded(g, x, y, w, h, CARD_R, ColorUtil.alpha(fill, (int) (ColorUtil.alphaI(fill) * a / 255f)));
            // едва заметный градиент-блик сверху карточки
            GfxEngine.roundedGradient(g, x, y, w, h, CARD_R,
                    ColorUtil.rgba(255, 255, 255, (int) (6 * k)), ColorUtil.rgba(255, 255, 255, (int) (1 * k)));
            int outline = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 16), ColorUtil.alpha(theme.accentNow(), 90), toggleT);
            if (hovered) {
                outline = ColorUtil.lerp(outline, ColorUtil.rgba(255, 255, 255, 55), hover * 0.6f);
            }
            GfxEngine.outline(g, x, y, w, h, CARD_R, ColorUtil.alpha(outline, (int) (ColorUtil.alphaI(outline) * a / 255f)), 1f);

            // плюс/минус раскрытия настроек — по вертикали шапки, левее тумблера
            if (hasSettings) {
                float indX = x + w - 56;
                float indY = y + headerH / 2f;
                int col = open ? theme.accentNow() : theme.textSecondary();
                float vis = 1f - et * 0.85f;
                GfxEngine.rounded(g, indX, indY - 0.7f, 7, 1.4f, 0.7f, ColorUtil.alpha(col, (int) (a * 0.9f)));
                if (vis > 0.05f) {
                    GfxEngine.rounded(g, indX + 2.8f, indY - 4.2f, 1.4f, 7, 0.7f, ColorUtil.alpha(col, (int) (a * vis)));
                }
            }

            // pill-тумблер справа по центру шапки
            drawPill(g, x + w - 34, y + headerH / 2f - 6.5f, 27, 13, toggleT);

            // настройки под шапкой
            if (!rows.isEmpty() && et > 0.01f) {
                int divY = y + headerH + 2;
                GfxEngine.rounded(g, x + 10, divY, w - 20, 1, 0.5f, ColorUtil.rgba(255, 255, 255, 18));
                RenderUtil.pushScissor(g, x + 1, divY + 1, x + w - 1, y + h - 1);
                for (Row row : rows) {
                    row.shapes().run();
                }
                RenderUtil.popScissor(g);
            }

            // зоны клика (после отрисовки: чем позже добавлена — тем выше приоритет)
            if (hasSettings) {
                hits.add(new Hit(x, y, w, headerH, 1, () -> toggleExpanded(m)));
                hits.add(new Hit(x, y, w, headerH, 0, () -> {
                    m.toggle();
                    playClick();
                    toggleAnims.get(m).to(m.isEnabled() ? 1 : 0);
                    AuroraConfig.markDirty();
                }));
                hits.add(new Hit(x + w - 60, y, 18, headerH, 0, () -> toggleExpanded(m)));
            } else {
                hits.add(new Hit(x, y, w, headerH, 0, () -> {
                    m.toggle();
                    playClick();
                    toggleAnims.get(m).to(m.isEnabled() ? 1 : 0);
                    AuroraConfig.markDirty();
                }));
            }
            g.pose().popMatrix();
        };

        Runnable texts = () -> {
            float rv = revealT(idx);
            int a = (int) (255 * Animation.smooth(tabAnim.get()) * rv);
            if (a < 4) {
                return;
            }
            float lift = 1.5f * Animation.smooth(hoverAnims.containsKey(m) ? hoverAnims.get(m).get() : 0f);
            g.pose().pushMatrix();
            g.pose().translate(0, Math.round((1f - rv) * 7 - lift));
            // имя всегда белым (как на референсе), статус и описание — цвет по состоянию
            RenderUtil.drawText(g, font, m.name, x + 12, y + 8, ColorUtil.alpha(theme.textPrimary(), a), theme);
            String status = m.isEnabled() ? "Включен" : "Выключен";
            int statusColor = m.isEnabled() ? theme.accentNow() : ColorUtil.rgba(235, 236, 244, 125);
            RenderUtil.drawText(g, font, status, x + 12, y + 19, ColorUtil.alpha(statusColor, a), theme);
            for (int i = 0; i < desc.size(); i++) {
                RenderUtil.drawText(g, font, desc.get(i), x + 12, y + 31 + i * 10,
                        ColorUtil.alpha(theme.textSecondary(), (int) (a * 0.85f)), theme);
            }
            if (!rows.isEmpty() && expandT(m) > 0.01f) {
                RenderUtil.pushScissor(g, x + 1, y + headerH + 3, x + w - 1, y + h - 1);
                for (Row row : rows) {
                    row.texts().run();
                }
                RenderUtil.popScissor(g);
            }
            g.pose().popMatrix();
        };

        return new Card(m, idx, x, y, w, h, headerH, rows, shapes, texts);
    }

    private void drawBodyShapes(GuiGraphics g) {
        RenderUtil.pushScissor(g, bodyX, bodyY + HEADER_H - 2, bodyX + bodyW, bodyY + bodyH);
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

        // полоса прокрутки: подсветка и утолщение при наведении на тело
        if (maxScroll > 0) {
            boolean bodyHover = mouseX >= bodyX && mouseX <= bodyX + bodyW
                    && mouseY >= bodyY && mouseY <= bodyY + bodyH;
            float st = theme.animations ? scrollAnim.apply(bodyHover ? 1f : 0f) : 0f;
            int trackH = bodyH - HEADER_H - 8;
            float ratio = (float) (bodyH - HEADER_H - 4) / (maxScroll + bodyH - HEADER_H - 4);
            int thumbH = Math.max(18, (int) (trackH * ratio));
            int thumbY = bodyY + HEADER_H + 2 + (int) ((trackH - thumbH) * (scrollPos / (float) maxScroll));
            int thumbW = 3 + Math.round(st);
            GfxEngine.rounded(g, bodyX + bodyW - 4, bodyY + HEADER_H + 2, thumbW, trackH, 1.5f,
                    ColorUtil.rgba(255, 255, 255, (int) (16 + 14 * st)));
            GfxEngine.rounded(g, bodyX + bodyW - 4, thumbY, thumbW, thumbH, 1.5f,
                    ColorUtil.alpha(theme.accentNow(), (int) (120 + 100 * st)));
        }
    }

    private void drawBodyTexts(GuiGraphics g) {
        RenderUtil.pushScissor(g, bodyX, bodyY + HEADER_H - 2, bodyX + bodyW, bodyY + bodyH);
        if (themeTab) {
            for (Row row : themeRows) {
                row.texts().run();
            }
        } else {
            if (cards.isEmpty()) {
                boolean searching = search != null && !search.getValue().isEmpty();
                RenderUtil.drawText(g, font, searching ? "Ничего не найдено" : "Здесь пока пусто",
                        bodyX + 12, bodyY + 48, theme.textSecondary(), theme);
                if (searching) {
                    RenderUtil.drawText(g, font, "Очистите поиск или смените вкладку слева",
                            bodyX + 12, bodyY + 60, ColorUtil.alpha(theme.textSecondary(), 120), theme);
                }
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
    }

    /* ------------------------------------------------------------------ */
    /*  Генераторы строк-контролов                                        */
    /* ------------------------------------------------------------------ */

    private List<Row> buildModuleRows(GuiGraphics g, Module m, int x, int y, int w) {
        List<Row> rows = new ArrayList<>();
        int cy = y;
        for (Setting s : m.settings()) {
            int h = s instanceof NumberSetting ? 25 : (s instanceof ColorSetting && s.equals(openColorTarget) ? 17 + 96 : 17);
            if (s instanceof BooleanSetting bs) {
                rows.add(boolRow(g, s.name, x, cy, w, bs::get, v -> {
                    bs.set(v);
                    AuroraConfig.markDirty();
                }, bs));
            } else if (s instanceof NumberSetting ns) {
                rows.add(sliderRow(g, s.name, x, cy, w, ns::progress, ns::display, p -> ns.setByProgress(p), ns));
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
        return rows;
    }

    /** Строка-переключатель (тумблер плавно анимируется). id — стабильный ключ анимации. */
    private Row boolRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Boolean> get, Consumer<Boolean> set, Object id) {
        return new Row(() -> {
            boolean on = get.get();
            Animation anim = settingAnims.computeIfAbsent(id,
                    key -> new Animation(on ? 1f : 0f, 14));
            anim.to(on ? 1f : 0f);
            float t = anim.update();
            drawPill(g, x + w - 24, y + 2, 22, 12, t);
            hits.add(new Hit(x, y - 1, w, 15, 0, () -> {
                set.accept(!get.get());
                playClick();
            }));
        }, () -> RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, label, Math.max(12, w - 32), theme), x, y + 4, theme.textSecondary(), theme));
    }

    /** Строка-слайдер. id — ключ анимации наведения (стабильный объект). */
    private Row sliderRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Float> progress,
                          Supplier<String> display, Consumer<Float> set, Object id) {
        return new Row(() -> {
            int trackY = y + 14;
            int trackX = x;
            int trackW = w;
            float p = Math.max(0f, Math.min(1f, progress.get()));
            // наведение: палец растёт и получает акцентное кольцо
            boolean hovered = mouseX >= trackX - 4 && mouseX <= trackX + trackW + 4
                    && mouseY >= trackY - 7 && mouseY <= trackY + 12;
            float hoverT = sliderHoverAnims.computeIfAbsent(id, k -> new Animation(0, 12))
                    .apply(hovered ? 1f : 0f);
            int fillW = Math.round(trackW * p);
            // дорожка: темная капсула + внутренняя подсветка сверху
            GfxEngine.rounded(g, trackX, trackY, trackW, 5, 2.5f, ColorUtil.rgba(255, 255, 255, 26));
            GfxEngine.rounded(g, trackX, trackY, trackW, 1.5f, 0.75f, ColorUtil.rgba(255, 255, 255, 18));
            if (fillW > 3) {
                // заполнение: вертикальный градиент акцента — объёмная «капля»
                GfxEngine.roundedGradient(g, trackX, trackY, fillW, 5, 2.5f,
                        ColorUtil.alpha(theme.accentNow(), 245), ColorUtil.alpha(theme.accent(1.0f), 205));
            }
            float kx = trackX + fillW;
            float ky = trackY + 2.5f;
            float d = 8 + 2.5f * hoverT;
            // мягкое свечение под пальцем при наведении/перетаскивании
            if (hoverT > 0.05f) {
                GfxEngine.glow(g, kx - d, ky - d, d * 2, d * 2, d, 5f,
                        ColorUtil.alpha(theme.accentNow(), (int) (110 * hoverT)));
            }
            GfxEngine.circle(g, kx, ky, d, ColorUtil.rgb(255, 255, 255));
            GfxEngine.ring(g, kx, ky, d + 2.5f,
                    ColorUtil.alpha(theme.accentNow(), (int) (90 + 120 * hoverT)), 1.5f);
            GfxEngine.circle(g, kx, ky, d * 0.35f, ColorUtil.alpha(theme.accentNow(), 230));
            hits.add(new Hit(trackX - 4, trackY - 6, trackW + 8, 16, 0, () -> {
                set.accept(clampP((mouseX - trackX) / (float) trackW));
                sliderX = trackX;
                sliderW = trackW;
                dragActiveSlider = set;
                playClick();
            }));
        }, () -> {
            String disp = display.get();
            // подпись не должна доеезжать до значения: обрезаем с запасом 8px
            String safe = RenderUtil.ellipsize(font, label, Math.max(12, w - RenderUtil.width(font, disp, theme) - 8), theme);
            RenderUtil.drawText(g, font, safe, x, y + 3, theme.textSecondary(), theme);
            RenderUtil.drawText(g, font, disp, x + w - RenderUtil.width(font, disp, theme), y + 3,
                    theme.textPrimary(), theme);
        });
    }

    /** Строка цвета (с раскрывающейся пипеткой). */
    private Row colorRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Integer> get, Consumer<Integer> set, Object targetId) {
        return new Row(() -> {
            int sw = 26;
            int sxp = x + w - sw;
            boolean openRow = targetId.equals(openColorTarget);
            GfxEngine.rounded(g, sxp, y + 2, sw, 12, 5, get.get());
            GfxEngine.outline(g, sxp, y + 2, sw, 12, 5, ColorUtil.rgba(255, 255, 255, 70), 1f);
            hits.add(new Hit(sxp - 4, y - 2, sw + 8, 18, 0, () -> {
                openColorTarget = openRow ? null : targetId;
                playClick();
            }));
            if (openRow) {
                drawColorPickerShapes(g, sxp - 64, y + 20, 90, 88, get.get(), set);
            }
        }, () -> RenderUtil.drawText(g, font, label, x, y + 4, theme.textSecondary(), theme));
    }

    /** Строка режима: чип со стрелками, подсветка зоны наведения. */
    private Row modeRow(GuiGraphics g, String label, int x, int y, int w, ModeSetting ms) {
        return new Row(() -> {
            int valW = RenderUtil.width(font, ms.display(), theme) + 34;
            int vx = x + w - valW;
            boolean hoverL = mouseX >= vx && mouseX < vx + 14 && mouseY >= y && mouseY < y + 15;
            boolean hoverR = mouseX >= vx + valW - 14 && mouseX <= vx + valW && mouseY >= y && mouseY < y + 15;
            GfxEngine.rounded(g, vx, y + 1, valW, 14, 7,
                    hoverL || hoverR ? ColorUtil.rgba(255, 255, 255, 34) : ColorUtil.rgba(255, 255, 255, 16));
            GfxEngine.outline(g, vx, y + 1, valW, 14, 7,
                    hoverL || hoverR ? ColorUtil.alpha(theme.accentNow(), 130) : ColorUtil.rgba(255, 255, 255, 14), 1f);
            hits.add(new Hit(vx, y, 14, 15, 0, () -> {
                ms.cycle(-1);
                playClick();
            }));
            hits.add(new Hit(vx + valW - 14, y, 14, 15, 0, () -> {
                ms.cycle(1);
                playClick();
            }));
        }, () -> {
            int valW = RenderUtil.width(font, ms.display(), theme) + 34;
            int vx = x + w - valW;
            boolean hoverL = mouseX >= vx && mouseX < vx + 14 && mouseY >= y && mouseY < y + 15;
            boolean hoverR = mouseX >= vx + valW - 14 && mouseX <= vx + valW && mouseY >= y && mouseY < y + 15;
            RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, label, Math.max(12, w - valW - 8), theme), x, y + 4, theme.textSecondary(), theme);
            // аккуратные уголки-стрелки вместо угловатых < и >
            RenderUtil.drawText(g, font, "‹", vx + 5, y + 4,
                    hoverL ? theme.accentNow() : theme.textSecondary(), theme);
            RenderUtil.drawText(g, font, ms.display(), vx + 17, y + 4, theme.textPrimary(), theme);
            RenderUtil.drawText(g, font, "›", vx + valW - 10, y + 4,
                    hoverR ? theme.accentNow() : theme.textSecondary(), theme);
        });
    }

    /** Строка бинда. id — уникальный идентификатор строки (объект настройки или метка меню). */
    private Row bindRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Integer> get, Consumer<Integer> set, Object id) {
        return new Row(() -> {
            boolean isListening = listeningBind == id;
            String keyText = isListening ? "..." : keyName(get.get());
            int bw = Math.max(42, RenderUtil.width(font, keyText, theme) + 16);
            int bx = x + w - bw;
            // пульсация захваченной кнопки — приглашение нажать клавишу
            int pulse = isListening
                    ? (int) (120 + 105 * (0.5f + 0.5f * Math.sin(System.currentTimeMillis() / 180.0)))
                    : 255;
            GfxEngine.rounded(g, bx, y + 1, bw, 14, 7,
                    isListening ? ColorUtil.alpha(theme.accentNow(), pulse) : ColorUtil.rgba(255, 255, 255, 18));
            if (isListening) {
                GfxEngine.outline(g, bx, y + 1, bw, 14, 7, ColorUtil.alpha(theme.accentNow(), pulse), 1f);
            }
            hits.add(new Hit(bx, y, bw, 15, 0, () -> {
                listeningBind = id;
                playClick();
            }));
        }, () -> {
            boolean isListening = listeningBind == id;
            String keyText = isListening ? "..." : keyName(get.get());
            int bw = Math.max(42, RenderUtil.width(font, keyText, theme) + 16);
            int bx = x + w - bw;
            RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, label, Math.max(12, w - bw - 8), theme), x, y + 4, theme.textSecondary(), theme);
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

        // пресеты
        final int presetY = cy;
        rows.add(new Row(() -> {
            int px = x + 4;
            for (String[] preset : Theme.PRESETS) {
                int c1 = (int) (0xFF000000L | Long.parseLong(preset[1], 16));
                int c2 = (int) (0xFF000000L | Long.parseLong(preset[2], 16));
                boolean isActive = theme.accent == c1 && theme.accent2 == c2 && !theme.rainbow;
                boolean hovered = mouseX >= px && mouseX < px + 30 && mouseY >= presetY && mouseY < presetY + 18;
                if (hovered) {
                    GfxEngine.rounded(g, px - 3, presetY - 3, 36, 24, 9, ColorUtil.rgba(255, 255, 255, 24));
                }
                GfxEngine.rounded(g, px, presetY, 13, 18, 6.5f, c1);
                GfxEngine.rounded(g, px + 15, presetY, 13, 18, 6.5f, c2);
                if (isActive) {
                    GfxEngine.outline(g, px - 2, presetY - 2, 32, 22, 8, ColorUtil.rgb(255, 255, 255), 1.2f);
                }
                final int f1 = c1;
                final int f2 = c2;
                hits.add(new Hit(px, presetY, 28, 18, 0, () -> {
                    theme.accent = f1;
                    theme.accent2 = f2;
                    theme.rainbow = false;
                    playClick();
                    AuroraConfig.save();
                }));
                px += 34;
            }
        }, () -> RenderUtil.drawText(g, font, "Цветовая схема", x, presetY - 14, theme.textPrimary(), theme)));
        cy += 26;

        rows.add(colorRow(g, "Акцент", x, cy, w, () -> theme.accent, c -> theme.accent = c, "accent"));
        cy += 17;
        rows.add(colorRow(g, "Второй цвет градиента", x, cy, w, () -> theme.accent2, c -> theme.accent2 = c, "accent2"));
        cy += 17;
        rows.add(boolRow(g, "Радужный акцент", x, cy, w, () -> theme.rainbow, v -> {
            theme.rainbow = v;
            AuroraConfig.save();
        }, "rainbow"));
        cy += 17;
        rows.add(sliderRow(g, "Прозрачность панелей", x, cy, w,
                () -> theme.backgroundAlpha, () -> String.format("%.2f", theme.backgroundAlpha),
                p -> theme.backgroundAlpha = Math.max(0.05f, Math.min(1.0f, p)), "bgAlpha"));
        cy += 25;
        rows.add(boolRow(g, "Блюр за меню", x, cy, w, () -> theme.blur, v -> {
            theme.blur = v;
            AuroraConfig.save();
        }, "blur"));
        cy += 17;
        rows.add(sliderRow(g, "Сила блюра", x, cy, w,
                () -> theme.blurStrength / 10.0f, () -> String.valueOf(theme.blurStrength),
                p -> theme.blurStrength = Math.round(p * 10), "blurStrength"));
        cy += 25;
        rows.add(boolRow(g, "Анимации", x, cy, w, () -> theme.animations, v -> {
            theme.animations = v;
            AuroraConfig.save();
        }, "animations"));
        cy += 17;
        rows.add(boolRow(g, "Шрифт Inter", x, cy, w, () -> theme.customFont, v -> {
            theme.customFont = v;
            AuroraConfig.save();
        }, "customFont"));
        cy += 17;
        rows.add(menuKeyRow(g, "Клавиша меню", x, cy, w));
        cy += 17;

        // кнопка сброса темы (переехала сюда из футера)
        final int resetY = cy + 6;
        rows.add(new Row(() -> {
            int rw = 150;
            int rx = x + (w - rw) / 2;
            boolean hov = mouseX >= rx && mouseX < rx + rw && mouseY >= resetY && mouseY < resetY + 20;
            RenderUtil.glassChip(g, rx, resetY, rw, 20, 10,
                    hov ? ColorUtil.rgba(248, 113, 113, 55) : ColorUtil.rgba(255, 255, 255, 12),
                    hov ? ColorUtil.rgba(248, 113, 113, 130) : ColorUtil.rgba(255, 255, 255, 26), theme);
            hits.add(new Hit(rx, resetY, rw, 20, 0, () -> {
                theme.resetToDefault();
                AuroraConfig.save();
                playClick();
            }));
        }, () -> {
            int rw = 150;
            int rx = x + (w - rw) / 2;
            RenderUtil.drawCenteredText(g, font, "Сбросить тему", rx + rw / 2f, resetY + 6,
                    theme.textPrimary(), theme);
        }));
        cy += 34;

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

    /* ------------------------------------------------------------------ */
    /*  Контролы                                                          */
    /* ------------------------------------------------------------------ */

    /** Pill-тумблер с гладким круглым ползунком и свечением включённого состояния. */
    private void drawPill(GuiGraphics g, float x, float y, float w, float h, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int track = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 34), theme.accentNow(), t);
        if (t > 0.35f) {
            // мягкое акцентное свечение вокруг включённого тумблера
            GfxEngine.glow(g, x - 3, y - 3, w + 6, h + 6, h / 2f, 4f,
                    ColorUtil.alpha(theme.accentNow(), (int) (70 * t)));
        }
        GfxEngine.rounded(g, x, y, w, h, h / 2f, track);
        float knob = h - 4;
        float kx = x + 2 + (w - knob - 4) * t;
        GfxEngine.circle(g, kx + knob / 2, y + h / 2, knob, ColorUtil.rgb(255, 255, 255));
    }

    private static float clampP(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /* ------------------------------------------------------------------ */
    /*  Пипетка цвета                                                     */
    /* ------------------------------------------------------------------ */

    private void drawColorPickerShapes(GuiGraphics g, int x, int y, int w, int h, int current, Consumer<Integer> setter) {
        // плотная всплывашка пипетки: раньше тут была theme.panelColor(),
        // которая при низкой прозрачности панелей просвечивала насквозь
        GfxEngine.rounded(g, x, y, w, h, 8, ColorUtil.rgba(14, 15, 23, 242));
        GfxEngine.roundedGradient(g, x, y, w, h, 8,
                ColorUtil.rgba(255, 255, 255, 26), ColorUtil.rgba(255, 255, 255, 4));
        GfxEngine.outline(g, x, y, w, h, 8, ColorUtil.rgba(255, 255, 255, 46), 1f);
        GfxEngine.rounded(g, x + 12, y + 1, w - 24, 1, 0.5f, ColorUtil.rgba(255, 255, 255, 64));

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
    /*  Диалог профиля (смена никнейма)                                    */
    /* ------------------------------------------------------------------ */

    private static final int PROFILE_W = 250;
    private static final int PROFILE_H = 112;

    private int profileFieldX(int winX) {
        return winX + (WIN_W - PROFILE_W) / 2;
    }

    private int profileFieldY(int winY) {
        return winY + 52;
    }

    private void openProfileDialog() {
        profileOpen = true;
        profileError = null;
        if (nickField != null) {
            nickField.setValue(ProfileManager.currentName());
            nickField.setCursorPosition(nickField.getValue().length());
            nickField.setHighlightPos(0);
        }
        dialogAnim.snap(0);
        dialogAnim.to(1);
        setFocused(nickField);
    }

    private void closeProfileDialog() {
        profileOpen = false;
        profileError = null;
        dialogAnim.to(0);
        setFocused(search);
    }

    private void applyNick() {
        if (nickField == null) {
            return;
        }
        String value = nickField.getValue().trim();
        if (!ProfileManager.isValid(value)) {
            profileError = "3–16 символов: латиница, цифры, _";
            profileErrorTime = System.currentTimeMillis();
            return;
        }
        AuroraConfig.setNickname(value);
        playClick();
        closeProfileDialog();
    }

    private void drawProfileDialogShapes(GuiGraphics g, int winX, int winY) {
        float dt = dialogAnim.get();
        if (dt < 0.01f) {
            return;
        }
        float k = Animation.smooth(dt);
        // pop-масштаб диалога: маленький «пружинный» выезд (лёгкий перелёт)
        float pop = theme.animations ? Animation.easeOutBack(dt) : 1f;
        int dx = profileFieldX(winX);
        int dy = profileFieldY(winY) - 34;

        // затемнение окна под диалогом + «стеклянная» карточка;
        // подложка почти непрозрачная — раньше сквозь неё просвечивал
        // текст карточек, и диалог выглядел сломанным
        GfxEngine.rounded(g, winX, winY, WIN_W, WIN_H, WIN_R, ColorUtil.rgba(5, 6, 10, (int) (232 * k)));
        float dcx = dx + PROFILE_W / 2f;
        float dcy = dy + PROFILE_H / 2f;
        float ps = 0.92f + 0.08f * pop;
        g.pose().pushMatrix();
        g.pose().translate(dcx, dcy);
        g.pose().scale(ps, ps);
        g.pose().translate(-dcx, -dcy);
        RenderUtil.softShadow(g, dx, dy, PROFILE_W, PROFILE_H, 14, (int) (150 * k));
        GfxEngine.rounded(g, dx, dy, PROFILE_W, PROFILE_H, 14,
                ColorUtil.rgba(14, 15, 23, (int) (242 * k)));
        GfxEngine.roundedGradient(g, dx, dy, PROFILE_W, PROFILE_H, 14,
                ColorUtil.rgba(255, 255, 255, (int) (26 * k)), ColorUtil.rgba(255, 255, 255, (int) (4 * k)));
        GfxEngine.outline(g, dx, dy, PROFILE_W, PROFILE_H, 14, ColorUtil.rgba(255, 255, 255, (int) (48 * k)), 1f);
        GfxEngine.rounded(g, dx + 20, dy + 1, PROFILE_W - 40, 1, 0.5f, ColorUtil.rgba(255, 255, 255, (int) (70 * k)));

        // поле ввода (стекло)
        boolean focused = nickField != null && nickField.isFocused();
        RenderUtil.glassChip(g, dx + 16, dy + 40, PROFILE_W - 32, 20, 9,
                ColorUtil.rgba(255, 255, 255, (int) (14 * k)),
                focused ? ColorUtil.alpha(theme.accentNow(), (int) (170 * k)) : ColorUtil.rgba(255, 255, 255, (int) (28 * k)), theme);

        // всплеск ошибки на границе поля
        if (profileError != null && System.currentTimeMillis() - profileErrorTime < 2500) {
            GfxEngine.outline(g, dx + 16, dy + 40, PROFILE_W - 32, 20, 9,
                    ColorUtil.rgba(248, 113, 113, (int) (200 * k)), 1.2f);
        }

        // кнопки
        int bw = (PROFILE_W - 32 - 8) / 2;
        int by = dy + PROFILE_H - 30;
        RenderUtil.glassChip(g, dx + 16, by, bw, 20, 9,
                ColorUtil.alpha(theme.accentNow(), (int) (120 * k)),
                ColorUtil.alpha(theme.accentNow(), (int) (180 * k)), theme);
        RenderUtil.glassChip(g, dx + 16 + bw + 8, by, bw, 20, 9,
                ColorUtil.rgba(255, 255, 255, (int) (14 * k)),
                ColorUtil.rgba(255, 255, 255, (int) (30 * k)), theme);

        // зоны клика: сначала «подложка» (клик мимо диалога закрывает),
        // затем поле и кнопки — они добавляются позже и приоритетнее
        hits.add(new Hit(winX, winY, WIN_W, WIN_H, -1, this::closeProfileDialog));
        hits.add(new Hit(dx + 16, dy + 40, PROFILE_W - 32, 20, 0, () -> setFocused(nickField)));
        hits.add(new Hit(dx + 16, by, bw, 20, 0, this::applyNick));
        hits.add(new Hit(dx + 16 + bw + 8, by, bw, 20, 0, this::closeProfileDialog));
        g.pose().popMatrix();
    }

    private void drawProfileDialogTexts(GuiGraphics g, int winX, int winY) {
        float dt = dialogAnim.get();
        if (dt < 0.01f) {
            return;
        }
        int k = (int) (255 * Animation.smooth(dt));
        int dx = profileFieldX(winX);
        int dy = profileFieldY(winY) - 34;
        // тот же pop-масштаб, что у форм — текст едет вместе с диалогом
        float pop = theme.animations ? Animation.easeOutBack(dt) : 1f;
        float ps = 0.92f + 0.08f * pop;
        float dcx = dx + PROFILE_W / 2f;
        float dcy = dy + PROFILE_H / 2f;
        g.pose().pushMatrix();
        g.pose().translate(dcx, dcy);
        g.pose().scale(ps, ps);
        g.pose().translate(-dcx, -dcy);

        RenderUtil.drawTextBold(g, font, "Смена никнейма", dx + 16, dy + 12,
                ColorUtil.alpha(theme.textPrimary(), k), theme);
        RenderUtil.drawText(g, font, "Виден в меню и на офлайн-серверах", dx + 16, dy + 25,
                ColorUtil.alpha(theme.textSecondary(), (int) (k * 0.85f)), theme);

        if (nickField != null) {
            String value = nickField.getValue();
            boolean focused = nickField.isFocused();
            float textX = dx + 24;
            float textY = dy + 46;
            if (value.isEmpty()) {
                RenderUtil.drawText(g, font, "Ваш никнейм", textX, textY,
                        ColorUtil.rgba(235, 236, 244, (int) (95 * dt)), theme);
                if (focused && System.currentTimeMillis() % 1000 < 500) {
                    GfxEngine.rounded(g, textX, textY + 0.5f, 1, 8, 0.5f, ColorUtil.alpha(theme.accentNow(), 220));
                }
            } else {
                RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, value, PROFILE_W - 52, theme), textX, textY,
                        ColorUtil.alpha(theme.textPrimary(), k), theme);
                if (focused) {
                    // каретка и выделение — из состояния виджета, не из своего
                    drawFieldCaret(g, textX, textY, value, nickField.getCursorPosition(),
                            nickField.getHighlighted(), PROFILE_W - 52);
                }
            }
        }

        // ошибка
        if (profileError != null && System.currentTimeMillis() - profileErrorTime < 2500) {
            RenderUtil.drawText(g, font, profileError, dx + 16, dy + 66,
                    ColorUtil.alpha(ColorUtil.rgb(248, 113, 113), k), theme);
        }

        // подписи кнопок
        int bw = (PROFILE_W - 32 - 8) / 2;
        int by = dy + PROFILE_H - 30;
        RenderUtil.drawCenteredText(g, font, "Сменить", dx + 16 + bw / 2f, by + 6,
                ColorUtil.alpha(ColorUtil.rgb(255, 255, 255), k), theme);
        RenderUtil.drawCenteredText(g, font, "Отмена", dx + 16 + bw + 8 + bw / 2f, by + 6,
                ColorUtil.alpha(theme.textSecondary(), k), theme);
        g.pose().popMatrix();
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
            // клик вне кнопки бинда отменяет захват — раньше клик просто
            // «проглатывался» и меню казалось замороженным
            listeningBind = null;
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
        if (profileOpen) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                closeProfileDialog();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                applyNick();
                return true;
            }
            if (key == theme.menuKey) {
                // не закрываем меню, пока пользователь вводит ник
                return true;
            }
            return super.keyPressed(e);
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
            if (key == theme.menuKey) {
                closeAnimated();
                return true;
            }
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
            finishClose();
            return;
        }
        closing = true;
        openAnim.to(0);
        // если значение уже у нуля — отступаем чуть назад, чтобы анимация
        // закрытия действительно поехала (snap перезаписывает target,
        // поэтому вызывать его здесь нельзя — иначе закрытие отменялось)
        if (openAnim.get() < 0.03f) {
            openAnim.snap(0.03f);
        }
    }

    /** Полное закрытие: возвращаемся на родительский экран, если он есть. */
    private void finishClose() {
        if (parent != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
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
        super.removed();
    }

    /** Смоук-тест вкладки «Тема» (AURORA_TEST_THEME=1) — для скриншотов. */
    public void debugOpenThemeTab() {
        themeTab = true;
        scrollTarget = 0;
        scrollPos = 0;
        tabAnim.snap(1);
        openAnim.snap(1);
    }

    @Override
    public void added() {
        openAnim.snap(theme.animations ? 0.0f : 1.0f);
        openAnim.to(1);
        tabAnim.snap(0.0f);
        tabAnim.to(1);
        // индикатор сразу на активной категории — без «прыжка» с нуля;
        // каскад карточек стартует вместе с открытием окна
        sideIndicator.snap(46 + (themeTab ? Category.values().length : tab.ordinal()) * 30
                + (themeTab ? 2 : 0));
        resetReveal();
    }
}
