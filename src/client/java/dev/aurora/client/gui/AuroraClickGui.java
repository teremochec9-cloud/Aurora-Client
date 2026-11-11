package dev.aurora.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
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
 * Главное меню клиента: стеклянные панели, блюр, плавные анимации.
 * ЛКМ по модулю — включить, ПКМ — настройки.
 */
public class AuroraClickGui extends Screen {
    private static final int WIN_W = 520;
    private static final int WIN_H = 330;
    private static final int HEADER_H = 42;
    private static final int TABS_H = 26;
    private static final int FOOTER_H = 28;
    private static final int LIST_W = 300;
    private static final int ROW_H = 26;

    private final Theme theme;
    private final Animation openAnim = new Animation(0, 13);
    private final Animation tabAnim = new Animation(0, 12);
    private final Map<Module, Animation> hoverAnims = new HashMap<>();
    private final Map<Module, Animation> toggleAnims = new HashMap<>();
    private final Map<Module, Boolean> expanded = new HashMap<>();

    private Category tab = Category.VISUAL;
    private boolean themeTab = false;
    private EditBox search;
    private float scrollTarget = 0;
    private float scrollPos = 0;
    private boolean closing = false;
    private int mouseX;
    private int mouseY;
    private boolean mouseDown;

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

    @Override
    protected void init() {
        int winX = (this.width - WIN_W) / 2;
        int winY = (this.height - WIN_H) / 2;
        search = new EditBox(this.font, winX + WIN_W - 172, winY + 14, 130, 14, Component.literal("Поиск"));
        search.setMaxLength(40);
        search.setHint(Component.literal("Поиск..."));
        addRenderableWidget(search);
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
        float scrollPrev = scrollPos;
        scrollPos += (scrollTarget - scrollPos) * 0.35f;
        if (Math.abs(scrollTarget - scrollPos) < 0.5f) {
            scrollPos = scrollTarget;
        }
        if (scrollPrev != scrollPos) {
            // плавная прокрутка обновляется в tick
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
        renderSafeBackground(g, mx, my, delta);

        hits.clear();

        // масштаб: вписываем окно в экран + анимация открытия
        float fit = fitScale();
        float scale = fit * (theme.animations ? 0.95f + 0.05f * Animation.easeOutQuint(t) : 1.0f);
        this.uiScale = scale;
        float cx = this.width / 2f;
        float cy = this.height / 2f;
        // мышь в «виртуальных» координатах окна
        this.mouseX = Math.round((mx - cx) / scale + cx);
        this.mouseY = Math.round((my - cy) / scale + cy);

        int alpha = (int) (255 * Animation.smooth(t));
        int winX = (this.width - WIN_W) / 2;
        int winY = (this.height - WIN_H) / 2;
        int w = WIN_W;
        int h = WIN_H;

        if (alpha < 4) {
            super.render(g, mx, my, delta);
            return;
        }

        // обновляем позицию строки поиска под текущий масштаб
        if (search != null) {
            int sbx = Math.round(cx + (winX + WIN_W - 168 - cx) * scale);
            int sby = Math.round(cy + (winY + 14 - cy) * scale);
            search.setPosition(sbx, sby);
        }

        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(scale, scale);
        g.pose().translate(-cx, -cy);

        // ===== ОКНО =====
        RenderUtil.glassPanel(g, winX, winY, w, h, 14, theme);
        RenderUtil.rounded(g, winX + 2, winY + 2, w - 4, HEADER_H, 12, ColorUtil.alpha(theme.accentNow(), (int) (alpha * 0.06f)));

        // ===== ШАПКА =====
        int logoX = winX + 18;
        int logoY = winY + 14;
        RenderUtil.drawGlowText(g, font, Theme.CLIENT_NAME, logoX, logoY,
                theme.textPrimary(), ColorUtil.alpha(theme.accentNow(), 30), theme);
        int logoW = RenderUtil.width(font, Theme.CLIENT_NAME, theme);
        // чип с версией
        int chipX = logoX + logoW + 8;
        int chipY = logoY - 1;
        int chipW = RenderUtil.width(font, "1.0", theme) + 10;
        RenderUtil.rounded(g, chipX, chipY, chipW, 11, 5, ColorUtil.alpha(theme.accentNow(), 95));
        RenderUtil.drawText(g, font, "1.0", chipX + 5, chipY + 1, ColorUtil.rgb(255, 255, 255), theme);

        // ===== ВКЛАДКИ =====
        int tabsY = winY + HEADER_H + 6;
        String[] tabs = {Category.VISUAL.title, Category.HUD.title, Category.CHAT.title, "Тема"};
        int tabX = winX + 16;
        for (int i = 0; i < tabs.length; i++) {
            boolean active = (i == 3) == themeTab && (i < 3 ? tab.ordinal() == i : themeTab);
            int tw = RenderUtil.width(font, tabs[i], theme) + 22;
            boolean hover = mx >= tabX && mx < tabX + tw && my >= tabsY && my < tabsY + 20;
            if (active) {
                RenderUtil.rounded(g, tabX, tabsY, tw, 20, 10, ColorUtil.alpha(theme.accentNow(), 70));
            } else if (hover) {
                RenderUtil.rounded(g, tabX, tabsY, tw, 20, 10, ColorUtil.rgba(255, 255, 255, 18));
            }
            RenderUtil.drawText(g, font, tabs[i], tabX + 11, tabsY + 6,
                    active ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary(), theme);
            final int idx = i;
            hits.add(new Hit(tabX, tabsY, tw, 20, -1, () -> {
                playClick();
                if (idx == 3) {
                    themeTab = true;
                } else {
                    themeTab = false;
                    tab = Category.values()[idx];
                }
                scrollTarget = 0;
                tabAnim.snap(0);
                tabAnim.to(1);
            }));
            tabX += tw + 4;
        }

        // ===== ТЕЛО =====
        int bodyY = tabsY + 26;
        int bodyH = winY + h - FOOTER_H - bodyY;
        int listX = winX + 12;
        int listW = themeTab ? w - 24 : LIST_W;

        if (themeTab) {
            drawThemeTab(g, listX, bodyY, listW, bodyH);
        } else {
            drawModuleList(g, listX, bodyY, listW, bodyH);
            // ===== ПРАВАЯ ПАНЕЛЬ =====
            int rightX = listX + listW + 8;
            int rightW = winX + w - 12 - rightX;
            drawActivePanel(g, rightX, bodyY, rightW, bodyH);
        }

        // ===== ФУТЕР =====
        int footY = winY + h - FOOTER_H + 4;
        RenderUtil.drawText(g, font, "Профиль: стандартный", winX + 16, footY + 2, theme.textSecondary(), theme);

        int btnX = winX + w - 16;
        btnX = footerButton(g, btnX, footY, "Сохранить", true, () -> {
            AuroraConfig.save();
            playClick();
        });
        btnX = footerButton(g, btnX, footY, "Сброс", false, () -> {
            theme.resetToDefault();
            playClick();
        });
        footerButton(g, btnX, footY, "HUD-редактор", false, () -> {
            playClick();
            Minecraft.getInstance().setScreen(new HudEditorScreen());
        });

        g.pose().popMatrix();

        super.render(g, mx, my, delta);
    }

    /** Масштаб вписывания окна в экран. */
    private float fitScale() {
        float fw = (this.width - 12f) / WIN_W;
        float fh = (this.height - 12f) / WIN_H;
        return Math.max(0.55f, Math.min(1f, Math.min(fw, fh)));
    }

    /**
     * Фон за меню с блюром. Если блюр в этом кадре уже занят (например,
     * поверх рендерится loading-overlay), просто затемняем — иначе краш
     * «Can only blur once per frame».
     */
    private void renderSafeBackground(GuiGraphics g, int mx, int my, float delta) {
        try {
            renderBackground(g, mx, my, delta);
        } catch (IllegalStateException e) {
            g.fill(0, 0, this.width, this.height, 0x8C10101A);
        }
    }

    /** Текущий масштаб интерфейса (для преобразования координат мыши). */
    private float uiScale = 1f;

    private int footerButton(GuiGraphics g, int rightX, int y, String label, boolean primary, Runnable action) {
        int tw = RenderUtil.width(font, label, theme) + 16;
        int bx = rightX - tw;
        boolean hover = mouseX >= bx && mouseX < rightX && mouseY >= y && mouseY < y + 18;
        int bg = primary
                ? (hover ? ColorUtil.alpha(theme.accentNow(), 150) : ColorUtil.alpha(theme.accentNow(), 95))
                : (hover ? ColorUtil.alpha(theme.accentNow(), 80) : ColorUtil.rgba(255, 255, 255, 16));
        RenderUtil.rounded(g, bx, y, tw, 18, 9, bg);
        RenderUtil.drawText(g, font, label, bx + 8, y + 5, hover ? ColorUtil.rgb(255, 255, 255) : theme.textPrimary(), theme);
        hits.add(new Hit(bx, y, tw, 18, 0, action));
        return bx - 6;
    }

    /* ------------------------------------------------------------------ */
    /*  Список модулей                                                    */
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

    private void drawModuleList(GuiGraphics g, int x, int y, int w, int h) {
        List<Module> modules = currentModules();
        if (modules.isEmpty()) {
            RenderUtil.drawText(g, font, "Ничего не найдено", x + 12, y + 10, theme.textSecondary(), theme);
            return;
        }

        // общая высота контента
        int contentH = 0;
        for (Module m : modules) {
            contentH += ROW_H;
            if (Boolean.TRUE.equals(expanded.get(m))) {
                contentH += settingsHeight(m) + 4;
            }
        }
        int maxScroll = Math.max(0, contentH - h);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scrollPos = Math.min(scrollPos, maxScroll);

        g.enableScissor(x - 2, y - 2, x + w + 2, y + h + 2);
        float tabT = Animation.smooth(tabAnim.update());
        int cy = y + 2 - (int) scrollPos;
        cy += (int) ((1 - tabT) * 8);

        for (Module m : modules) {
            if (cy > y + h + 40 || cy + ROW_H + 60 < y) {
                // всё ещё учитываем высоту настроек при пропуске
                if (Boolean.TRUE.equals(expanded.get(m))) {
                    cy += ROW_H + settingsHeight(m) + 4;
                } else {
                    cy += ROW_H;
                }
                continue;
            }
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= cy && mouseY < cy + ROW_H;
            Animation ha = hoverAnims.computeIfAbsent(m, k -> new Animation(0, 10));
            ha.to(hovered ? 1 : 0);
            float hover = ha.update();

            // фон строки
            RenderUtil.rounded(g, x, cy, w, ROW_H, 8,
                    ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 0), ColorUtil.rgba(255, 255, 255, 18), hover));

            int alpha = (int) (255 * tabT);
            int nameColor = m.isEnabled() ? ColorUtil.rgb(255, 255, 255) : theme.textSecondary();
            RenderUtil.drawText(g, font, m.name, x + 12, cy + 9, ColorUtil.alpha(nameColor, alpha), theme);

            // стрелка настроек (геометрическая, без юникода)
            if (!m.settings().isEmpty()) {
                boolean open = Boolean.TRUE.equals(expanded.get(m));
                int chX = x + w - 80;
                int chY = cy + ROW_H / 2 - 5;
                int col = open ? theme.accentNow() : theme.textSecondary();
                if (open) {
                    // треугольник вниз: строки сужаются к низу 7→1
                    g.fill(chX, chY, chX + 7, chY + 1, col);
                    g.fill(chX + 1, chY + 1, chX + 6, chY + 2, col);
                    g.fill(chX + 2, chY + 2, chX + 5, chY + 3, col);
                    g.fill(chX + 3, chY + 3, chX + 4, chY + 4, col);
                } else {
                    // треугольник вправо: колонки сужаются вправо 7→1
                    g.fill(chX, chY, chX + 1, chY + 7, col);
                    g.fill(chX + 1, chY + 1, chX + 2, chY + 6, col);
                    g.fill(chX + 2, chY + 2, chX + 3, chY + 5, col);
                    g.fill(chX + 3, chY + 3, chX + 4, chY + 4, col);
                }
                hits.add(new Hit(chX - 4, cy, 18, ROW_H, 0, () -> toggleExpanded(m)));
            }

            // переключатель
            drawToggle(g, x + w - 40, cy + ROW_H / 2 - 6, 26, 12, toggleAnims.computeIfAbsent(m, k -> new Animation(m.isEnabled() ? 1 : 0, 14)), theme);

            hits.add(new Hit(x, cy, w - 46, ROW_H, 0, () -> {
                m.toggle();
                playClick();
                toggleAnims.get(m).to(m.isEnabled() ? 1 : 0);
                AuroraConfig.save();
            }));
            hits.add(new Hit(x, cy, w, ROW_H, 1, () -> toggleExpanded(m)));

            cy += ROW_H;

            if (Boolean.TRUE.equals(expanded.get(m))) {
                cy = drawModuleSettings(g, m, x + 6, cy, w - 6) + 4;
            }
        }
        g.disableScissor();
    }

    private void toggleExpanded(Module m) {
        playClick();
        expanded.merge(m, true, (a, b) -> Boolean.TRUE.equals(a) ? Boolean.FALSE : Boolean.TRUE);
    }

    private int settingsHeight(Module m) {
        int h = 6;
        for (Setting s : m.settings()) {
            if (s instanceof BooleanSetting) {
                h += 18;
            } else if (s instanceof NumberSetting) {
                h += 26;
            } else if (s instanceof ColorSetting) {
                h += (s.equals(openColorTarget)) ? 108 : 18;
            } else if (s instanceof ModeSetting) {
                h += 18;
            } else if (s instanceof BindSetting) {
                h += 20;
            }
        }
        return h + 2;
    }

    private int drawModuleSettings(GuiGraphics g, Module m, int x, int y, int w) {
        int cy = y + 6;
        RenderUtil.rounded(g, x, y, w, settingsHeight(m), 8, ColorUtil.rgba(0, 0, 0, 60));
        for (Setting s : m.settings()) {
            if (s instanceof BooleanSetting bs) {
                cy = drawBoolRow(g, s.name, x + 10, cy, w - 20, bs.get(), v -> bs.set(v));
            } else if (s instanceof NumberSetting ns) {
                cy = drawSliderRow(g, s.name, x + 10, cy, w - 20, ns.progress(), ns.display(), p -> ns.setByProgress(p));
            } else if (s instanceof ColorSetting cs) {
                cy = drawColorRow(g, s.name, x + 10, cy, w - 20, cs, cs::set);
            } else if (s instanceof ModeSetting ms) {
                cy = drawModeRow(g, s.name, x + 10, cy, w - 20, ms);
            } else if (s instanceof BindSetting bind) {
                cy = drawBindRow(g, s.name, x + 10, cy, w - 20, bind);
            }
        }
        return y + settingsHeight(m);
    }

    /* ------------------------------------------------------------------ */
    /*  Вкладка «Тема»                                                    */
    /* ------------------------------------------------------------------ */

    private void drawThemeTab(GuiGraphics g, int x, int y, int w, int h) {
        int cy = y + 2;
        g.enableScissor(x - 2, y - 2, x + w + 2, y + h + 2);

        // Заголовок
        RenderUtil.drawText(g, font, "Цветовая схема", x + 6, cy + 2, theme.textPrimary(), theme);
        cy += 16;
        // Пресеты
        int px = x + 10;
        for (String[] preset : Theme.PRESETS) {
            int c1 = (int) (0xFF000000L | Long.parseLong(preset[1], 16));
            int c2 = (int) (0xFF000000L | Long.parseLong(preset[2], 16));
            boolean hovered = mouseX >= px && mouseX < px + 30 && mouseY >= cy && mouseY < cy + 16;
            if (hovered) {
                RenderUtil.rounded(g, px - 2, cy - 2, 34, 20, 8, ColorUtil.rgba(255, 255, 255, 25));
            }
            RenderUtil.rounded(g, px, cy, 14, 16, 5, c1);
            RenderUtil.rounded(g, px + 14, cy, 14, 16, 5, c2);
            final int f1 = c1;
            final int f2 = c2;
            hits.add(new Hit(px, cy, 28, 16, 0, () -> {
                theme.accent = f1;
                theme.accent2 = f2;
                theme.rainbow = false;
                playClick();
                AuroraConfig.save();
            }));
            px += 36;
        }
        cy += 24;

        cy = drawColorRow(g, "Акцент", x + 10, cy, w - 20, null, c -> theme.accent = c);
        cy = drawColorRow(g, "Второй цвет градиента", x + 10, cy, w - 20, null, c -> theme.accent2 = c);
        cy = drawBoolRow(g, "Радужный акцент", x + 10, cy, w - 20, theme.rainbow, v -> theme.rainbow = v);
        cy = drawSliderRow(g, "Прозрачность панелей", x + 10, cy, w - 20, theme.backgroundAlpha, String.format("%.2f", theme.backgroundAlpha), p -> theme.backgroundAlpha = Math.max(0.05f, Math.min(1.0f, p)));
        cy = drawBoolRow(g, "Блюр за меню", x + 10, cy, w - 20, theme.blur, v -> theme.blur = v);
        cy = drawThemeSlider(g, "Сила блюра", x + 10, cy, w - 20, theme.blurStrength / 10.0f, String.valueOf(theme.blurStrength), p -> theme.blurStrength = Math.round(p * 10));
        cy = drawBoolRow(g, "Анимации", x + 10, cy, w - 20, theme.animations, v -> theme.animations = v);
        cy = drawBoolRow(g, "Шрифт Golos", x + 10, cy, w - 20, theme.customFont, v -> theme.customFont = v);
        cy = drawBindRow(g, "Клавиша меню", x + 10, cy, w - 20, null);
        g.disableScissor();
    }

    /* ------------------------------------------------------------------ */
    /*  Строки-контролы                                                   */
    /* ------------------------------------------------------------------ */

    private int drawBoolRow(GuiGraphics g, String label, int x, int y, int w, boolean value, Consumer<Boolean> setter) {
        RenderUtil.drawText(g, font, label, x, y + 4, theme.textSecondary(), theme);
        int tw = 24;
        int tx = x + w - tw;
        // фон
        RenderUtil.rounded(g, tx, y + 2, tw, 12, 6, value ? theme.accentNow() : ColorUtil.rgba(255, 255, 255, 30));
        int knobX = value ? tx + tw - 11 : tx + 1;
        g.fill(knobX, y + 3, knobX + 10, y + 13, ColorUtil.rgb(255, 255, 255));
        hits.add(new Hit(x, y, w, 16, 0, () -> {
            setter.accept(!value);
            playClick();
            AuroraConfig.save();
        }));
        return y + 18;
    }

    private int drawSliderRow(GuiGraphics g, String label, int x, int y, int w, float progress, String display, Consumer<Float> setter) {
        RenderUtil.drawText(g, font, label, x, y + 2, theme.textSecondary(), theme);
        String val = display;
        RenderUtil.drawText(g, font, val, x + w - RenderUtil.width(font, val, theme), y + 2, theme.textPrimary(), theme);
        int trackY = y + 14;
        int trackX = x + 2;
        int trackW = w - 4;
        RenderUtil.rounded(g, trackX, trackY, trackW, 5, 2, ColorUtil.rgba(255, 255, 255, 26));
        int fillW = (int) (trackW * Math.max(0, Math.min(1, progress)));
        RenderUtil.rounded(g, trackX, trackY, Math.max(2, fillW), 5, 2, theme.accentNow());
        int knobX = trackX + fillW - 2;
        g.fill(knobX, trackY - 2, knobX + 5, trackY + 7, ColorUtil.rgb(255, 255, 255));
        hits.add(new Hit(trackX - 2, trackY - 5, trackW + 4, 14, 0, () -> {
            setter.accept(clampP((mouseX - trackX) / (float) trackW));
            sliderX = trackX;
            sliderW = trackW;
            dragActiveSlider = setter;
            playClick();
        }));
        return y + 24;
    }

    private int drawThemeSlider(GuiGraphics g, String label, int x, int y, int w, float progress, String display, Consumer<Float> setter) {
        return drawSliderRow(g, label, x, y, w, progress, display, setter);
    }
    private int drawColorRow(GuiGraphics g, String label, int x, int y, int w, ColorSetting setting, Consumer<Integer> setter) {
        int color = setting != null ? setting.get() : (label.startsWith("Второй") ? theme.accent2 : theme.accent);
        RenderUtil.drawText(g, font, label, x, y + 4, theme.textSecondary(), theme);
        int sw = 30;
        int sx = x + w - sw;
        Object id = setting != null ? (Object) setting : (Object) label;
        boolean isThisOpen = id.equals(openColorTarget);
        RenderUtil.rounded(g, sx, y + 2, sw, 12, 5, color);
        RenderUtil.roundedOutline(g, sx, y + 2, sw, 12, 5, ColorUtil.rgba(255, 255, 255, 60));
        hits.add(new Hit(sx - 4, y - 2, sw + 8, 18, 0, () -> {
            openColorTarget = isThisOpen ? null : id;
            playClick();
        }));
        if (!isThisOpen) {
            return y + 18;
        }
        // пипетка
        int pickerY = y + 20;
        drawColorPicker(g, sx - 60, pickerY, 90, 88, color, c -> {
            if (setting != null) {
                setting.set(c);
            } else {
                setter.accept(c);
            }
        });
        return y + 112;
    }

    private int drawModeRow(GuiGraphics g, String label, int x, int y, int w, ModeSetting ms) {
        RenderUtil.drawText(g, font, label, x, y + 4, theme.textSecondary(), theme);
        int valW = RenderUtil.width(font, ms.display(), theme) + 26;
        int vx = x + w - valW;
        RenderUtil.rounded(g, vx, y + 1, valW, 14, 7, ColorUtil.rgba(255, 255, 255, 18));
        RenderUtil.drawText(g, font, "<", vx + 5, y + 4, theme.textSecondary(), theme);
        RenderUtil.drawText(g, font, ms.display(), vx + 14, y + 4, theme.textPrimary(), theme);
        RenderUtil.drawText(g, font, ">", vx + valW - 9, y + 4, theme.textSecondary(), theme);
        hits.add(new Hit(vx, y, 14, 16, 0, () -> {
            ms.cycle(-1);
            playClick();
        }));
        hits.add(new Hit(vx + valW - 14, y, 14, 16, 0, () -> {
            ms.cycle(1);
            playClick();
        }));
        return y + 18;
    }

    private int drawBindRow(GuiGraphics g, String label, int x, int y, int w, BindSetting setting) {
        boolean isMenuKey = setting == null;
        int key = isMenuKey ? theme.menuKey : setting.get();
        boolean listening = listeningBind == (isMenuKey ? (Object) "menu" : (Object) setting);
        String keyText = listening ? "…" : keyName(key);
        RenderUtil.drawText(g, font, label, x, y + 4, theme.textSecondary(), theme);
        int bw = Math.max(46, RenderUtil.width(font, keyText, theme) + 18);
        int bx = x + w - bw;
        RenderUtil.rounded(g, bx, y + 1, bw, 14, 7, listening ? theme.accentNow() : ColorUtil.rgba(255, 255, 255, 18));
        RenderUtil.drawText(g, font, keyText, bx + bw / 2 - RenderUtil.width(font, keyText, theme) / 2, y + 4,
                listening ? ColorUtil.rgb(255, 255, 255) : theme.textPrimary(), theme);
        hits.add(new Hit(bx, y, bw, 16, 0, () -> {
            listeningBind = isMenuKey ? (Object) "menu" : (Object) setting;
            playClick();
        }));
        return y + 18;
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
        String n = GLFW.glfwGetKeyName(key, 0);
        return n != null ? n.toUpperCase() : ("Код " + key);
    }

    /* ------------------------------------------------------------------ */
    /*  Пипетка цвета                                                     */
    /* ------------------------------------------------------------------ */

    private void drawColorPicker(GuiGraphics g, int x, int y, int w, int h, int current, Consumer<Integer> setter) {
        RenderUtil.rounded(g, x, y, w, h, 8, ColorUtil.rgba(12, 13, 20, 235));
        RenderUtil.roundedOutline(g, x, y, w, h, 8, ColorUtil.rgba(255, 255, 255, 30));

        float[] hsb = ColorUtil.hsb(current);
        int fx = x + 8;
        int fy = y + 8;
        int fw = w - 16;
        int fh = h - 42;
        // SV-поле: сетка
        int cols = 14;
        int rows = 10;
        for (int r = 0; r < rows; r++) {
            float v = 1.0f - (float) r / (rows - 1);
            for (int c = 0; c < cols; c++) {
                float s = (float) c / (cols - 1);
                int col = ColorUtil.fromHsb(hsb[0], s, Math.max(0.04f, v));
                g.fill(fx + c * fw / cols, fy + r * fh / rows, fx + (c + 1) * fw / cols, fy + (r + 1) * fh / rows, col);
            }
        }
        RenderUtil.roundedOutline(g, fx, fy, fw, fh, 3, ColorUtil.rgba(255, 255, 255, 70));
        // курсор
        int cx = fx + (int) (hsb[1] * (fw - 4));
        int cyv = fy + (int) ((1 - hsb[2]) * (fh - 4));
        g.fill(cx, cyv, cx + 4, cyv + 4, ColorUtil.rgb(255, 255, 255));
        g.fill(cx + 1, cyv + 1, cx + 3, cyv + 3, ColorUtil.rgb(0, 0, 0));

        int hueY = fy + fh + 6;
        int hueSegs = 12;
        for (int i = 0; i < hueSegs; i++) {
            int c1 = ColorUtil.fromHsb((float) i / hueSegs, 1.0f, 1.0f);
            g.fill(fx + i * fw / hueSegs, hueY, fx + (i + 1) * fw / hueSegs, hueY + 8, c1);
        }
        RenderUtil.roundedOutline(g, fx, hueY, fw, 8, 3, ColorUtil.rgba(0, 0, 0, 90));
        int hx = fx + (int) (hsb[0] * (fw - 4));
        g.fill(hx, hueY - 2, hx + 4, hueY + 10, ColorUtil.rgb(255, 255, 255));

        hits.add(new Hit(fx, fy, fw, fh, 0, () -> {
            dragColorField = true;
            applyColorFromField(fx, fy, fw, fh, hsb[0], setter);
        }));
        hits.add(new Hit(fx, hueY, fw, 10, 0, () -> {
            dragColorHue = true;
            applyHueFromBar(fx, hueY, fw, current, setter);
        }));

        // геометрия для перетаскивания
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

    private void applyHueFromBar(int fx, int hueY, int fw, int current, Consumer<Integer> setter) {
        float h = clampP((mouseX - fx) / (float) fw);
        float[] hsb = ColorUtil.hsb(current);
        setter.accept(ColorUtil.fromHsb(h, hsb[1] == 0 ? 1 : hsb[1], hsb[2] == 0 ? 1 : hsb[2]));
    }

    private static float clampP(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /* ------------------------------------------------------------------ */
    /*  Правая панель: активные модули                                    */
    /* ------------------------------------------------------------------ */

    private void drawActivePanel(GuiGraphics g, int x, int y, int w, int h) {
        RenderUtil.glassPanel(g, x, y, w, h, 12, theme);
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        RenderUtil.drawText(g, font, "Активные", x + 12, y + 10, theme.textSecondary(), theme);
        int timeW = RenderUtil.width(font, time, theme);
        RenderUtil.drawText(g, font, time, x + w - 12 - timeW - 16, y + 10, theme.textSecondary(), theme);
        List<Module> enabled = ModuleManager.enabled();
        String count = String.valueOf(enabled.size());
        RenderUtil.drawText(g, font, count, x + w - 12 - RenderUtil.width(font, count, theme), y + 10, theme.accentNow(), theme);

        int cy = y + 26;
        int idx = 0;
        for (Module m : enabled) {
            if (cy > y + h - 12) {
                break;
            }
            float t = enabled.size() <= 1 ? 0 : (float) idx / (enabled.size() - 1);
            int barColor = ColorUtil.lerp(theme.accentNow(), theme.accent(1.0f), t);
            g.fill(x + 8, cy + 2, x + 10, cy + 10, barColor);
            RenderUtil.drawText(g, font, m.name, x + 15, cy + 1, theme.textPrimary(), theme);
            cy += 12;
            idx++;
        }
        if (enabled.isEmpty()) {
            RenderUtil.drawText(g, font, "Все модули выключены", x + 12, cy + 4, theme.textSecondary(), theme);
        }

        // быстрые пресеты темы
        int presetY = y + h - 86;
        RenderUtil.drawText(g, font, "Цветовая схема", x + 12, presetY, theme.textSecondary(), theme);
        int pX = x + 12;
        int pY = presetY + 12;
        for (String[] preset : Theme.PRESETS) {
            int c1 = (int) (0xFF000000L | Long.parseLong(preset[1], 16));
            boolean hoverPreset = mouseX >= pX && mouseX < pX + 13 && mouseY >= pY && mouseY < pY + 13;
            if (hoverPreset) {
                RenderUtil.rounded(g, pX - 2, pY - 2, 17, 17, 6, ColorUtil.rgba(255, 255, 255, 30));
            }
            RenderUtil.rounded(g, pX, pY, 13, 13, 4, c1);
            final int f1 = c1;
            final String f2 = preset[2];
            hits.add(new Hit(pX - 2, pY - 2, 17, 17, 0, () -> {
                theme.accent = f1;
                theme.accent2 = (int) (0xFF000000L | Long.parseLong(f2, 16));
                theme.rainbow = false;
                playClick();
                AuroraConfig.save();
            }));
            pX += 17;
        }

        // подсказки внизу панели
        int hintY = y + h - 46;
        g.fill(x + 10, hintY, x + w - 10, hintY + 1, ColorUtil.rgba(255, 255, 255, 20));
        RenderUtil.drawText(g, font, "ЛКМ — вкл/выкл", x + 12, hintY + 6, theme.textSecondary(), theme);
        RenderUtil.drawText(g, font, "ПКМ — настройки", x + 12, hintY + 18, theme.textSecondary(), theme);
        RenderUtil.drawText(g, font, "Колесо — прокрутка", x + 12, hintY + 30, theme.textSecondary(), theme);
    }

    /** Переключатель-пилюля с анимацией. */
    private void drawToggle(GuiGraphics g, int x, int y, int w, int h, Animation a, Theme theme) {
        float t = a.update();
        int bg = ColorUtil.lerp(ColorUtil.rgba(255, 255, 255, 30), theme.accentNow(), t);
        RenderUtil.rounded(g, x, y, w, h, h / 2, bg);
        int knob = h - 4;
        int kx = x + 2 + (int) ((w - knob - 4) * t);
        g.fill(kx, y + 2, kx + knob, y + h - 2, ColorUtil.rgb(255, 255, 255));
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
        // свои зоны (сверху вниз, обратный порядок)
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (mouseX >= hit.x && mouseX <= hit.x + hit.w && mouseY >= hit.y && mouseY <= hit.y + hit.h
                    && (hit.button == -1 || hit.button == btn)) {
                mouseDown = true;
                hit.action.run();
                return true;
            }
        }
        mouseDown = true;
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        mouseDown = false;
        dragActiveSlider = null;
        dragColorField = false;
        dragColorHue = false;
        AuroraConfig.save();
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
            applyHueFromBar(gm[0], gm[4], gm[2], colorCurrent, colorSetter);
            return true;
        }
        return super.mouseDragged(e, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!themeTab) {
            scrollTarget -= scrollY * 26;
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
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
        AuroraConfig.save();
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
