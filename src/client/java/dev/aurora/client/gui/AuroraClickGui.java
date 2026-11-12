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
import dev.aurora.client.util.GfxEngine;
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
    private static final int WIN_W = 580;
    private static final int WIN_H = 340;
    private static final int WIN_R = 12;
    private static final int SIDEBAR_W = 138;
    private static final int GAP = 8;
    private static final int CARD_R = 8;
    private static final int SEARCH_W = 150;

    private final Theme theme;
    private final Animation openAnim = new Animation(0, 13);
    private final Animation tabAnim = new Animation(0, 12);
    private final Map<Module, Animation> hoverAnims = new HashMap<>();
    private final Map<Module, Animation> toggleAnims = new HashMap<>();
    private final Map<Module, Animation> expandAnims = new HashMap<>();
    private final Map<Module, Boolean> expanded = new HashMap<>();

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

    public AuroraClickGui() {
        super(Component.literal("Aurora"));
        this.theme = AuroraClient.theme();
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
        for (Animation a : hoverAnims.values()) {
            a.update();
        }
        for (Animation a : toggleAnims.values()) {
            a.update();
        }
        for (Animation a : expandAnims.values()) {
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
                super.onClose();
                return;
            }
        }

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

        // ===== геометрия тела =====
        bodyX = winX + SIDEBAR_W + 4;
        bodyY = winY + 8;
        bodyW = winX + WIN_W - 10 - bodyX;
        bodyH = WIN_H - 16;

        // строка поиска следует за окном при масштабе
        if (search != null) {
            search.setPosition(searchFieldX() + 10, bodyY + 8);
            search.setWidth(SEARCH_W - 20);
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
        GfxEngine.dropShadow(g, winX, winY, WIN_W, WIN_H, WIN_R, 5f, 9f,
                ColorUtil.rgba(0, 0, 0, (int) (120 * t)));
        GfxEngine.rounded(g, winX, winY, WIN_W, WIN_H, WIN_R,
                ColorUtil.alpha(theme.panelColor(), (int) (alpha * 0.92f + 20)));
        GfxEngine.outline(g, winX, winY, WIN_W, WIN_H, WIN_R, ColorUtil.rgba(255, 255, 255, (int) (26 * t)), 1f);
        // блик по верхней кромке
        GfxEngine.rounded(g, winX + WIN_R + 4, winY + 1, WIN_W - (WIN_R + 4) * 2, 1, 0.5f,
                ColorUtil.rgba(255, 255, 255, (int) (24 * t)));
    }

    /* ------------------------------------------------------------------ */
    /*  Боковая панель                                                    */
    /* ------------------------------------------------------------------ */

    private void drawSidebarShapes(GuiGraphics g, int winX, int winY, float t) {
        int sx = winX + 8;
        int sy = winY + 8;
        int sw = SIDEBAR_W - 20;
        int sh = WIN_H - 16;

        GfxEngine.rounded(g, sx, sy, sw, sh, 10, ColorUtil.rgba(255, 255, 255, (int) (7 * t)));
        GfxEngine.outline(g, sx, sy, sw, sh, 10, ColorUtil.rgba(255, 255, 255, (int) (14 * t)), 1f);

        // версии-чип у логотипа
        int logoW = RenderUtil.width(font, Theme.CLIENT_NAME, theme);
        int chipW = RenderUtil.width(font, Theme.CLIENT_VERSION, theme) + 10;
        GfxEngine.rounded(g, sx + 12 + logoW + 6, sy + 11, chipW, 12, 6,
                ColorUtil.alpha(theme.accentNow(), (int) (110 * t)));

        // ===== категории =====
        int cy = sy + 40;
        for (Category cat : Category.values()) {
            boolean active = !themeTab && tab == cat;
            boolean hover = mouseX >= sx + 4 && mouseX < sx + sw - 4 && mouseY >= cy && mouseY < cy + 22;
            if (active) {
                GfxEngine.rounded(g, sx + 4, cy, sw - 8, 22, 8, ColorUtil.alpha(theme.accentNow(), (int) (40 * t)));
                GfxEngine.rounded(g, sx + 4, cy + 4, 3, 14, 1.5f, theme.accentNow());
            } else if (hover) {
                GfxEngine.rounded(g, sx + 4, cy, sw - 8, 22, 8, ColorUtil.rgba(255, 255, 255, (int) (12 * t)));
            }
            drawCategoryIcon(g, cat, sx + 14, cy + 5, active);
            hits.add(new Hit(sx + 4, cy, sw - 8, 22, -1, () -> {
                playClick();
                themeTab = false;
                tab = cat;
                scrollTarget = 0;
                tabAnim.snap(0);
                tabAnim.to(1);
            }));
            cy += 26;
        }

        // ===== «Тема» =====
        int themeY = cy + 4;
        boolean themeActive = themeTab;
        boolean themeHover = mouseX >= sx + 4 && mouseX < sx + sw - 4 && mouseY >= themeY && mouseY < themeY + 22;
        if (themeActive) {
            GfxEngine.rounded(g, sx + 4, themeY, sw - 8, 22, 8, ColorUtil.alpha(theme.accentNow(), (int) (40 * t)));
            GfxEngine.rounded(g, sx + 4, themeY + 4, 3, 14, 1.5f, theme.accentNow());
        } else if (themeHover) {
            GfxEngine.rounded(g, sx + 4, themeY, sw - 8, 22, 8, ColorUtil.rgba(255, 255, 255, (int) (12 * t)));
        }
        // иконка «палитра» — три кружка
        GfxEngine.circle(g, sx + 18, themeY + 9, 5, theme.accentNow());
        GfxEngine.circle(g, sx + 24, themeY + 13, 4, theme.accent(1.0f));
        GfxEngine.circle(g, sx + 14, themeY + 14, 3, ColorUtil.rgba(255, 255, 255, 120));
        hits.add(new Hit(sx + 4, themeY, sw - 8, 22, -1, () -> {
            playClick();
            themeTab = true;
            scrollTarget = 0;
            tabAnim.snap(0);
            tabAnim.to(1);
        }));

        // ===== цветовая схема: аккуратные кружки с отступами =====
        int palY = themeY + 38;
        int px = sx + 14;
        for (String[] preset : Theme.PRESETS) {
            int c1 = (int) (0xFF000000L | Long.parseLong(preset[1], 16));
            boolean isActive = theme.accent == c1 && !theme.rainbow;
            boolean hoverP = mouseX >= px - 3 && mouseX < px + 13 && mouseY >= palY - 3 && mouseY < palY + 13;
            if (hoverP) {
                GfxEngine.circle(g, px + 5, palY + 5, 16, ColorUtil.rgba(255, 255, 255, 30));
            }
            GfxEngine.circle(g, px + 5, palY + 5, 10, c1);
            if (isActive) {
                GfxEngine.ring(g, px + 5, palY + 5, 15, ColorUtil.rgb(255, 255, 255), 1.2f);
            }
            final int f1 = c1;
            final int f2 = (int) (0xFF000000L | Long.parseLong(preset[2], 16));
            hits.add(new Hit(px - 3, palY - 3, 16, 16, 0, () -> {
                theme.accent = f1;
                theme.accent2 = f2;
                theme.rainbow = false;
                playClick();
                AuroraConfig.save();
            }));
            px += 16;
        }

        // ===== профиль =====
        int profY = sy + sh - 42;
        GfxEngine.rounded(g, sx + 4, profY, sw - 8, 34, 9, ColorUtil.rgba(255, 255, 255, (int) (10 * t)));
        GfxEngine.outline(g, sx + 4, profY, sw - 8, 34, 9, ColorUtil.rgba(255, 255, 255, (int) (16 * t)), 1f);
        String nick = Minecraft.getInstance().getUser().getName();
        int avD = 20;
        int avX = sx + 14;
        int avY = profY + 17;
        GfxEngine.circle(g, avX, avY, avD, theme.accentNow());
        GfxEngine.circle(g, avX, avY, avD - 6, ColorUtil.lerp(theme.accentNow(), ColorUtil.rgb(17, 18, 27), 0.35f));
    }

    private void drawSidebarText(GuiGraphics g, int winX, int winY, float t) {
        int sx = winX + 8;
        int sy = winY + 8;
        int sw = SIDEBAR_W - 20;

        // логотип
        RenderUtil.drawGlowText(g, font, Theme.CLIENT_NAME, sx + 12, sy + 11,
                theme.textPrimary(), ColorUtil.alpha(theme.accentNow(), (int) (36 * t)), theme);
        int logoW = RenderUtil.width(font, Theme.CLIENT_NAME, theme);
        int chipW = RenderUtil.width(font, Theme.CLIENT_VERSION, theme) + 10;
        RenderUtil.drawText(g, font, Theme.CLIENT_VERSION, sx + 12 + logoW + 11, sy + 14, ColorUtil.rgb(255, 255, 255), theme);
        RenderUtil.drawText(g, font, "клиент для MC " + Theme.MC_VERSION, sx + 12, sy + 26,
                ColorUtil.alpha(theme.textSecondary(), 150), theme);

        // категории
        int cy = sy + 40;
        for (Category cat : Category.values()) {
            boolean active = !themeTab && tab == cat;
            RenderUtil.drawText(g, font, cat.title, sx + 32, cy + 8,
                    active ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);
            cy += 26;
        }

        // «Тема»
        RenderUtil.drawText(g, font, "Тема", sx + 32, cy + 4 + 8,
                themeTab ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);

        // подпись схемы
        RenderUtil.drawText(g, font, "Цветовая схема", sx + 12, cy + 38 - 8, theme.textSecondary(), theme);

        // профиль
        int profY = sy + (WIN_H - 16) - 42;
        String nick = Minecraft.getInstance().getUser().getName();
        String initial = nick.isEmpty() ? "A" : nick.substring(0, 1).toUpperCase();
        RenderUtil.drawCenteredText(g, font, initial, sx + 14, profY + 13, ColorUtil.rgb(255, 255, 255), theme);
        RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, nick, sw - 52, theme), sx + 30, profY + 8, theme.textPrimary(), theme);
        RenderUtil.drawText(g, font, "Профиль", sx + 30, profY + 19, theme.textSecondary(), theme);
    }

    /** Минималистичные геометрические иконки категорий 12x12. */
    private void drawCategoryIcon(GuiGraphics g, Category cat, float x, float y, boolean active) {
        int color = active ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary();
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
        return bodyX + bodyW - hudButtonW() - 8 - SEARCH_W;
    }

    private void drawHeaderShapes(GuiGraphics g, float t) {
        // кнопка HUD-редактора
        int hudW = hudButtonW();
        int hudX = bodyX + bodyW - hudW;
        boolean hudHover = mouseX >= hudX && mouseX < hudX + hudW && mouseY >= bodyY + 2 && mouseY < bodyY + 22;
        GfxEngine.rounded(g, hudX, bodyY + 2, hudW, 20, 10,
                hudHover ? ColorUtil.alpha(theme.accentNow(), 130) : ColorUtil.rgba(255, 255, 255, (int) (16 * t)));
        GfxEngine.outline(g, hudX, bodyY + 2, hudW, 20, 10,
                hudHover ? ColorUtil.alpha(theme.accentNow(), 180) : ColorUtil.rgba(255, 255, 255, (int) (24 * t)), 1f);
        hits.add(new Hit(hudX, bodyY + 2, hudW, 20, 0, () -> {
            playClick();
            Minecraft.getInstance().setScreen(new HudEditorScreen());
        }));

        // строка поиска: клик по полю возвращает фокус ввода
        hits.add(new Hit(searchFieldX(), bodyY + 2, SEARCH_W, 20, 0, () -> setFocused(search)));

        // строка поиска
        GfxEngine.rounded(g, searchFieldX(), bodyY + 2, SEARCH_W, 20, 10, ColorUtil.rgba(255, 255, 255, (int) (12 * t)));
        GfxEngine.outline(g, searchFieldX(), bodyY + 2, SEARCH_W, 20, 10,
                search != null && search.isFocused()
                        ? ColorUtil.alpha(theme.accentNow(), 150)
                        : ColorUtil.rgba(255, 255, 255, (int) (18 * t)), 1f);
    }

    private void drawHeaderText(GuiGraphics g) {
        String title = themeTab ? "Тема" : tab.title;
        RenderUtil.drawText(g, font, title, bodyX + 2, bodyY + 9, theme.textPrimary(), theme);

        int hudW = hudButtonW();
        int hudX = bodyX + bodyW - hudW;
        boolean hudHover = mouseX >= hudX && mouseX < hudX + hudW && mouseY >= bodyY + 2 && mouseY < bodyY + 22;
        RenderUtil.drawText(g, font, "HUD-редактор", hudX + 10, bodyY + 8,
                hudHover ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);

        // строка поиска: значение, курсор и плейсхолдер — всё через движок
        if (search != null) {
            String value = search.getValue();
            boolean focused = search.isFocused();
            float textX = searchFieldX() + 10;
            float textY = bodyY + 8;
            if (value.isEmpty() && !focused) {
                RenderUtil.drawText(g, font, "Поиск...", textX, textY, ColorUtil.rgba(235, 236, 244, 95), theme);
            } else {
                RenderUtil.drawText(g, font, RenderUtil.ellipsize(font, value, SEARCH_W - 18, theme),
                        textX, textY, theme.textPrimary(), theme);
                if (focused && System.currentTimeMillis() % 1000 < 500) {
                    float caretX = textX + RenderUtil.width(font, value, theme) + 1;
                    GfxEngine.rounded(g, caretX, textY + 0.5f, 1, 8, 0.5f, ColorUtil.alpha(theme.accentNow(), 220));
                }
            }
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
        int cardsTop = bodyY + 28;
        int viewH = bodyH - 28 - 4;
        if (themeTab) {
            themeRows.clear();
            int colW = Math.min(320, bodyW - 12);
            int rx = bodyX + (bodyW - colW) / 2;
            themeRowsH = buildThemeRows(g, themeRows, rx, cardsTop + 18 - (int) scrollPos, colW);
            maxScroll = Math.max(0, themeRowsH + 18 - viewH);
            scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
            scrollPos = Math.min(scrollPos, maxScroll);
            return;
        }

        List<Module> modules = currentModules();
        int colW = (bodyW - GAP) / 2;
        int[] colYs = new int[2];
        int contentH = 0;

        for (Module m : modules) {
            int col = colYs[0] <= colYs[1] ? 0 : 1;
            int x = bodyX + col * (colW + GAP);
            int y = cardsTop + colYs[col] - (int) scrollPos;
            float et = expandT(m);

            List<String> desc = RenderUtil.wrapLimited(font, m.description, colW - 24, theme, 2);
            int headerH = 22 + Math.max(1, desc.size()) * 10;
            int rowsH = settingsHeight(m);
            int h = headerH + 6 + Math.round(rowsH * et);

            List<Row> rows = et > 0.01f ? buildModuleRows(g, m, x, y + headerH + 8, colW - 20) : List.of();

            colYs[col] += h + GAP;
            contentH = Math.max(contentH, colYs[col]);

            cards.add(buildCard(g, m, x, y, colW, h, headerH, desc, rows, et));
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

    private Card buildCard(GuiGraphics g, Module m, int x, int y, int w, int h, int headerH, List<String> desc, List<Row> rows, float et) {
        boolean hasSettings = !m.settings().isEmpty();
        boolean open = Boolean.TRUE.equals(expanded.get(m));

        Runnable shapes = () -> {
            Animation ha = hoverAnims.computeIfAbsent(m, k -> new Animation(0, 10));
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + h;
            ha.to(hovered ? 1 : 0);
            float hover = ha.update();
            float toggleT = toggleAnims.computeIfAbsent(m, k -> new Animation(m.isEnabled() ? 1 : 0, 14)).update();
            int a = (int) (255 * Animation.smooth(tabAnim.get()));
            if (a < 4) {
                return;
            }

            // фон карточки: стекло + подсветка при наведении/включении
            int base = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 8), ColorUtil.rgba(255, 255, 255, 18), hover);
            int fill = ColorUtil.lerp(base, ColorUtil.alpha(theme.accentNow(), 26), toggleT * 0.8f);
            GfxEngine.rounded(g, x, y, w, h, CARD_R, ColorUtil.alpha(fill, (int) (ColorUtil.alphaI(fill) * a / 255f)));
            int outline = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 16), ColorUtil.alpha(theme.accentNow(), 120), toggleT);
            if (hovered) {
                outline = ColorUtil.lerp(outline, ColorUtil.rgba(255, 255, 255, 60), hover * 0.6f);
            }
            GfxEngine.outline(g, x, y, w, h, CARD_R, ColorUtil.alpha(outline, (int) (ColorUtil.alphaI(outline) * a / 255f)), 1f);

            // плюс/минус раскрытия настроек
            if (hasSettings) {
                float indX = x + w - 54;
                float indY = y + 14;
                int col = open ? theme.accentNow() : theme.textSecondary();
                float vis = 1f - et * 0.85f;
                GfxEngine.rounded(g, indX, indY, 7, 1.4f, 0.7f, ColorUtil.alpha(col, (int) (a * 0.9f)));
                if (vis > 0.05f) {
                    GfxEngine.rounded(g, indX + 3, indY - 2.8f, 1.4f, 7, 0.7f, ColorUtil.alpha(col, (int) (a * vis)));
                }
            }

            // pill-тумблер
            drawPill(g, x + w - 36, y + 9, 26, 13, toggleT);

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
        };

        Runnable texts = () -> {
            int a = (int) (255 * Animation.smooth(tabAnim.get()));
            if (a < 4) {
                return;
            }
            int nameColor = m.isEnabled() ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary();
            RenderUtil.drawText(g, font, m.name, x + 11, y + 7, ColorUtil.alpha(nameColor, a), theme);
            for (int i = 0; i < desc.size(); i++) {
                RenderUtil.drawText(g, font, desc.get(i), x + 11, y + 18 + i * 10,
                        ColorUtil.alpha(theme.textSecondary(), (int) (a * 0.92f)), theme);
            }
            if (!rows.isEmpty() && expandT(m) > 0.01f) {
                RenderUtil.pushScissor(g, x + 1, y + headerH + 3, x + w - 1, y + h - 1);
                for (Row row : rows) {
                    row.texts().run();
                }
                RenderUtil.popScissor(g);
            }
        };

        return new Card(m, x, y, w, h, headerH, rows, shapes, texts);
    }

    private void drawBodyShapes(GuiGraphics g) {
        RenderUtil.pushScissor(g, bodyX, bodyY + 26, bodyX + bodyW, bodyY + bodyH);
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

        // полоса прокрутки
        if (maxScroll > 0) {
            int trackH = bodyH - 34;
            float ratio = (float) (bodyH - 28) / (maxScroll + bodyH - 28);
            int thumbH = Math.max(18, (int) (trackH * ratio));
            int thumbY = bodyY + 30 + (int) ((trackH - thumbH) * (scrollPos / (float) maxScroll));
            GfxEngine.rounded(g, bodyX + bodyW - 4, thumbY, 3, thumbH, 1.5f,
                    ColorUtil.alpha(theme.accentNow(), 130));
        }
    }

    private void drawBodyTexts(GuiGraphics g) {
        RenderUtil.pushScissor(g, bodyX, bodyY + 26, bodyX + bodyW, bodyY + bodyH);
        if (themeTab) {
            for (Row row : themeRows) {
                row.texts().run();
            }
        } else {
            if (cards.isEmpty()) {
                RenderUtil.drawText(g, font, "Ничего не найдено", bodyX + 12, bodyY + 40, theme.textSecondary(), theme);
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
        return rows;
    }

    /** Строка-переключатель. */
    private Row boolRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Boolean> get, Consumer<Boolean> set) {
        return new Row(() -> {
            float t = get.get() ? 1f : 0f;
            drawPill(g, x + w - 24, y + 2, 22, 12, t);
            hits.add(new Hit(x, y - 1, w, 15, 0, () -> {
                set.accept(!get.get());
                playClick();
            }));
        }, () -> RenderUtil.drawText(g, font, label, x, y + 4, theme.textSecondary(), theme));
    }

    /** Строка-слайдер. */
    private Row sliderRow(GuiGraphics g, String label, int x, int y, int w, Supplier<Float> progress,
                          Supplier<String> display, Consumer<Float> set) {
        return new Row(() -> {
            int trackY = y + 14;
            int trackX = x + 2;
            int trackW = w - 4;
            float p = Math.max(0f, Math.min(1f, progress.get()));
            GfxEngine.rounded(g, trackX, trackY, trackW, 4, 2, ColorUtil.rgba(255, 255, 255, 30));
            int fillW = (int) (trackW * p);
            if (fillW > 3) {
                GfxEngine.rounded(g, trackX, trackY, fillW, 4, 2, theme.accentNow());
            }
            GfxEngine.circle(g, trackX + fillW, trackY + 2, 9, ColorUtil.rgb(255, 255, 255));
            GfxEngine.ring(g, trackX + fillW, trackY + 2, 11, ColorUtil.rgba(0, 0, 0, 70), 1f);
            hits.add(new Hit(trackX - 4, trackY - 6, trackW + 8, 16, 0, () -> {
                set.accept(clampP((mouseX - trackX) / (float) trackW));
                sliderX = trackX;
                sliderW = trackW;
                dragActiveSlider = set;
                playClick();
            }));
        }, () -> {
            RenderUtil.drawText(g, font, label, x, y + 2, theme.textSecondary(), theme);
            String disp = display.get();
            RenderUtil.drawText(g, font, disp, x + w - RenderUtil.width(font, disp, theme), y + 2,
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
            int vx = x + w - valW;
            RenderUtil.drawText(g, font, label, x, y + 4, theme.textSecondary(), theme);
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
            RenderUtil.drawText(g, font, label, x, y + 4, theme.textSecondary(), theme);
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

    /** Pill-тумблер с гладким круглым ползунком. */
    private void drawPill(GuiGraphics g, float x, float y, float w, float h, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int track = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 34), theme.accentNow(), t);
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
        GfxEngine.rounded(g, x, y, w, h, 8, ColorUtil.rgba(12, 13, 20, 240));
        GfxEngine.outline(g, x, y, w, h, 8, ColorUtil.rgba(255, 255, 255, 36), 1f);

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
    /*  Футер                                                             */
    /* ------------------------------------------------------------------ */

    private void drawFooterShapes(GuiGraphics g, int winX, int winY) {
        int footY = winY + WIN_H - FOOTER_H + 3;
        int btnX = winX + WIN_W - 14;
        btnX = footerButton(g, btnX, footY, "Сохранить", true, () -> {
            AuroraConfig.save();
            playClick();
        });
        footerButton(g, btnX, footY, "Сброс", false, () -> {
            theme.resetToDefault();
            playClick();
        });
    }

    private void drawFooterTexts(GuiGraphics g, int winX, int winY) {
        int footY = winY + WIN_H - FOOTER_H + 3;
        RenderUtil.drawText(g, font, "ЛКМ — вкл/выкл · ПКМ — настройки · Колесо — прокрутка",
                winX + 16, footY + 6, theme.textSecondary(), theme);
        int btnX = winX + WIN_W - 14;
        String save = "Сохранить";
        int saveW = RenderUtil.width(font, save, theme) + 18;
        RenderUtil.drawText(g, font, save, btnX - saveW + 9, footY + 6, theme.textPrimary(), theme);
        String reset = "Сброс";
        int resetW = RenderUtil.width(font, reset, theme) + 18;
        int resetX = btnX - saveW - 6 - resetW;
        RenderUtil.drawText(g, font, reset, resetX + 9, footY + 6, theme.textPrimary(), theme);
    }

    private static final int FOOTER_H = 24;

    private int footerButton(GuiGraphics g, int rightX, int y, String label, boolean primary, Runnable action) {
        int tw = RenderUtil.width(font, label, theme) + 18;
        int bx = rightX - tw;
        boolean hover = mouseX >= bx && mouseX < rightX && mouseY >= y && mouseY < y + 18;
        int bg = primary
                ? (hover ? ColorUtil.alpha(theme.accentNow(), 165) : ColorUtil.alpha(theme.accentNow(), 105))
                : (hover ? ColorUtil.alpha(theme.accentNow(), 80) : ColorUtil.rgba(255, 255, 255, 16));
        GfxEngine.rounded(g, bx, y, tw, 18, 9, bg);
        hits.add(new Hit(bx, y, tw, 18, 0, action));
        return bx - 6;
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
        super.removed();
    }

    @Override
    public void added() {
        openAnim.snap(theme.animations ? 0.0f : 1.0f);
        openAnim.to(1);
        tabAnim.snap(0.0f);
        tabAnim.to(1);
    }
}
