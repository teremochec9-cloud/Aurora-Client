package dev.aurora.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aurora.client.config.AuroraConfig;
import dev.aurora.client.gui.AuroraClickGui;
import dev.aurora.client.gui.AuroraTitleScreen;
import dev.aurora.client.hud.HudEditorScreen;
import dev.aurora.client.hud.HudModule;
import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.modules.ChatModule;
import dev.aurora.client.module.modules.CleanHudModule;
import dev.aurora.client.module.modules.CustomCrosshairModule;
import dev.aurora.client.module.modules.TargetEspModule;
import dev.aurora.client.profile.ProfileManager;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.GfxEngine;
import dev.aurora.client.util.RenderUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Aurora Client — точка входа.
 */
public class AuroraClient implements ClientModInitializer {
    private static final Theme THEME = new Theme();
    private static boolean replacingTitle = false;
    /** Состояние клавиши меню в прошлом тике (антидребезг открытия). */
    private static boolean menuKeyWasHeld;
    /** Смоук-тест: автооткрытие ClickGUI (задаётся переменной окружения AURORA_TEST_GUI=1). */
    private static final boolean TEST_GUI = "1".equals(System.getenv("AURORA_TEST_GUI"));
    /** Смоук-тест: вместо ClickGUI открыть HUD-редактор (AURORA_TEST_HUD=1). */
    private static final boolean TEST_HUD = "1".equals(System.getenv("AURORA_TEST_HUD"));

    /** Доступ смоук-харнессу: логировать ли bbox-ассерты HUD. */
    public static boolean isTestHud() {
        return TEST_HUD;
    }
    /** Смоук-тест: сразу открыть вкладку «Тема» (AURORA_TEST_THEME=1). */
    private static final boolean TEST_THEME = "1".equals(System.getenv("AURORA_TEST_THEME"));
    /** Смоук-тест: проверить «прозрачный фон» чата (AURORA_TEST_CHAT=1) — лог значений опций. */
    private static final boolean TEST_CHAT = "1".equals(System.getenv("AURORA_TEST_CHAT"));
    /** Смоук-тест Target ESP: в мире включить ESP и призвать цель (AURORA_TEST_ESP=1). */
    private static final boolean TEST_ESP = "1".equals(System.getenv("AURORA_TEST_ESP"));
    private boolean chatTestDone;
    private boolean espTestDone;
    private boolean themeTabOpened;
    private int titleTicks;

    public static Theme theme() {
        return THEME;
    }

    @Override
    public void onInitializeClient() {
        ModuleManager.init();
        AuroraConfig.load();

        // ===== HUD-слой: рисуем все HUD-элементы после ванильных =====
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.SUBTITLES,
                Identifier.fromNamespaceAndPath("aurora", "hud_layer"),
                AuroraClient::renderHudLayer);

        // ===== Слой Target ESP: подсветка целей поверх HUD =====
        HudElementRegistry.attachElementAfter(
                Identifier.fromNamespaceAndPath("aurora", "hud_layer"),
                Identifier.fromNamespaceAndPath("aurora", "target_esp_layer"),
                AuroraClient::renderTargetEspLayer);

        // ===== Подмена ванильных элементов (чистый интерфейс, прицел) =====
        registerHiders();

        // ===== Тик: хоткей меню, модули, отложенное сохранение конфига =====
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // кламп HUD к границам экрана (один раз, когда окно готово);
            // здесь же логируется миграция конфига v1→v2
            AuroraConfig.clampAllHud();
            // отложенный никнейм из конфига: применяем, как только игра готова
            ProfileManager.applyPending();
            ModuleManager.tick();
            AuroraConfig.tick();
            // хоткей меню: срабатывает на переход «отпущена → нажата»,
            // поэтому удержание не открывает меню повторно после закрытия
            if (client.screen == null && client.player != null && client.level != null) {
                boolean held = InputConstants.isKeyDown(client.getWindow(), THEME.menuKey);
                if (held && !menuKeyWasHeld) {
                    client.setScreen(new AuroraClickGui());
                }
                menuKeyWasHeld = held;
            } else {
                menuKeyWasHeld = false;
            }
            // хоткеи модулей
            if (client.screen == null && client.player != null) {
                for (Module m : ModuleManager.all()) {
                    int key = m.bind.get();
                    if (key != 0 && InputConstants.isKeyDown(client.getWindow(), key)) {
                        if (!wasKeyHeld(m)) {
                            m.toggle();
                            AuroraClickGui.playClick();
                            AuroraConfig.markDirty();
                        }
                    }
                    markKeyHeld(m, key != 0 && InputConstants.isKeyDown(client.getWindow(), key));
                }
            }
            // служебный режим смоук-теста: открыть ClickGUI или HUD-редактор
            if (TEST_GUI && client.screen instanceof AuroraTitleScreen) {
                if (++titleTicks > 200) {
                    client.setScreen(TEST_HUD ? new HudEditorScreen() : new AuroraClickGui());
                    titleTicks = -1000000; // один раз
                }
            }
            // смоук-тест вкладки «Тема»: открыть её сразу после ClickGUI
            if (TEST_THEME && client.screen instanceof AuroraClickGui gui && !themeTabOpened) {
                gui.debugOpenThemeTab();
                themeTabOpened = true;
            }
            // смоук-тест чата: включаем «прозрачный фон» и пишем обе опции в лог
            if (TEST_CHAT && !chatTestDone && client.player == null && client.screen != null) {
                chatTestDone = true;
                ChatModule chat = ModuleManager.get(ChatModule.class);
                if (chat != null) {
                    chat.transparentBg.set(true);
                    chat.setEnabled(true); // вызовет onEnable → apply()
                    client.execute(() -> org.slf4j.LoggerFactory.getLogger("Aurora/Test")
                            .info("AURORA_CHAT_TEST: chatOpacity={} textBackgroundOpacity={} (ожидалось: текст {}, фон 0.0)",
                                    client.options.chatOpacity().get(),
                                    client.options.textBackgroundOpacity().get(),
                                    client.options.chatOpacity().get()));
                }
            }
            // смоук-тест Target ESP: в мире включаем модуль, разворачиваем камеру
            // чуть вниз и призываем паука (не горит на солнце) перед собой с NoAI
            if (TEST_ESP && !espTestDone && client.player != null && client.level != null) {
                espTestDone = true;
                TargetEspModule esp = ModuleManager.get(TargetEspModule.class);
                if (esp != null) {
                    esp.setEnabled(true);
                    esp.mode.setIndex(1);       // «Ближайшие»
                    esp.playersOnly.set(false); // паук тоже цель
                    esp.range.set(24f);
                    AuroraConfig.save();
                    client.player.connection.sendCommand("tp @s ~ ~ ~ 0 12");
                    client.execute(() -> {
                        client.player.connection.sendCommand("summon spider ~2 ~ ~5 {NoAI:1b}");
                        org.slf4j.LoggerFactory.getLogger("Aurora/Test").info("AURORA_ESP_TEST: цель призвана");
                    });
                }
            }
        });

        // ===== Красивое главное меню =====
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen.getClass() == TitleScreen.class && !replacingTitle) {
                replacingTitle = true;
                client.execute(() -> {
                    client.setScreen(new AuroraTitleScreen());
                    replacingTitle = false;
                });
            }
        });

        // ===== Сохранение при выходе (включая возврат ванильной опции блюра) =====
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            AuroraConfig.releaseVanillaBlurControl();
            AuroraConfig.save();
        });
    }

    /* Состояние «клавиша уже была нажата в прошлом тике» для антидребезга. */
    private static final java.util.Map<Module, Boolean> KEY_HELD = new java.util.HashMap<>();

    private static boolean wasKeyHeld(Module m) {
        return Boolean.TRUE.equals(KEY_HELD.get(m));
    }

    private static void markKeyHeld(Module m, boolean held) {
        KEY_HELD.put(m, held);
    }

    private static void registerHiders() {
        CleanHudModule clean = ModuleManager.get(CleanHudModule.class);
        CustomCrosshairModule crosshair = ModuleManager.get(CustomCrosshairModule.class);

        if (clean != null) {
            HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, old -> (g, t) -> {
                if (clean.isEnabled() && clean.hideHotbar.get()) {
                    return;
                }
                old.render(g, t);
            });
            HudElementRegistry.replaceElement(VanillaHudElements.PLAYER_LIST, old -> (g, t) -> {
                if (clean.isEnabled() && clean.hidePlayerList.get()) {
                    return;
                }
                old.render(g, t);
            });
            HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, old -> (g, t) -> {
                if (clean.isEnabled() && clean.hideScoreboard.get()) {
                    return;
                }
                old.render(g, t);
            });
            HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR, old -> (g, t) -> {
                if (clean.isEnabled() && clean.hideBossBar.get()) {
                    return;
                }
                old.render(g, t);
            });
            HudElementRegistry.replaceElement(VanillaHudElements.STATUS_EFFECTS, old -> (g, t) -> {
                if (clean.isEnabled() && clean.hideEffects.get()) {
                    return;
                }
                old.render(g, t);
            });
            HudElementRegistry.replaceElement(VanillaHudElements.HELD_ITEM_TOOLTIP, old -> (g, t) -> {
                if (clean.isEnabled() && clean.hideItemName.get()) {
                    return;
                }
                old.render(g, t);
            });
        }

        if (crosshair != null) {
            HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, old -> (g, t) -> {
                boolean hiddenByClean = clean != null && clean.isEnabled() && clean.hideCrosshair.get();
                if (hiddenByClean) {
                    return; // полностью скрыт
                }
                if (crosshair.isEnabled()) {
                    drawCustomCrosshair(g, crosshair);
                    return;
                }
                old.render(g, t);
            });
        }
    }

    /** Кастомный прицел: гладкие NanoVG-формы с анимацией включения. */
    private static void drawCustomCrosshair(GuiGraphics g, CustomCrosshairModule m) {
        // плавный выезд при включении/отключении
        float t = Animation.smooth(Math.max(0f, Math.min(1f, m.appear.apply(m.isEnabled() ? 1f : 0f))));
        if (t < 0.02f) {
            return;
        }
        float cx = g.guiWidth() / 2f;
        float cy = g.guiHeight() / 2f;
        int color = ColorUtil.alpha(m.color.get(), Math.round(255f * t));
        String style = m.style.get();
        boolean withGlow = m.glow.get();
        float s = 0.6f + 0.4f * Animation.easeOutBack(t); // масштаб при появлении

        boolean dot = style.equals("Точка") || style.equals("Точка + круг");
        boolean ring = style.equals("Круг") || style.equals("Точка + круг");
        boolean cross = style.equals("Крест");

        if (dot) {
            float d = 3.2f * s;
            if (withGlow) {
                GfxEngine.glow(g, cx - d, cy - d, d * 2, d * 2, d, 3f, ColorUtil.alpha(m.color.get(), (int) (120 * t)));
            }
            GfxEngine.circle(g, cx, cy, d, color);
        }
        if (ring) {
            float d = 11f * s;
            if (withGlow) {
                GfxEngine.glow(g, cx - d, cy - d, d * 2, d * 2, d, 4f, ColorUtil.alpha(m.color.get(), (int) (80 * t)));
            }
            GfxEngine.ring(g, cx, cy, d, color, 1.6f);
        }
        if (cross) {
            float gap = 2.8f * s;
            float len = 7f * s;
            float th = 1.7f;
            if (withGlow) {
                float ext = gap + len;
                GfxEngine.glow(g, cx - ext, cy - ext, ext * 2, ext * 2, 3f, 5f,
                        ColorUtil.alpha(m.color.get(), (int) (90 * t)));
            }
            // четыре скруглённых луча — никаких «лесенок» из пиксельных fill
            GfxEngine.rounded(g, cx - th / 2f, cy - gap - len, th, len, th / 2f, color); // вверх
            GfxEngine.rounded(g, cx - th / 2f, cy + gap, th, len, th / 2f, color);     // вниз
            GfxEngine.rounded(g, cx - gap - len, cy - th / 2f, len, th, th / 2f, color); // влево
            GfxEngine.rounded(g, cx + gap, cy - th / 2f, len, th, th / 2f, color);     // вправо
        }
    }

    /** Рендер всех HUD-элементов клиента. */
    private static void renderHudLayer(GuiGraphics g, DeltaTracker t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.options.hideGui) {
            return;
        }
        if (mc.screen instanceof HudEditorScreen) {
            return; // редактор рисует элементы сам
        }
        for (Module m : ModuleManager.all()) {
            if (m instanceof HudModule hud && hud.isEnabled()) {
                try {
                    hud.render(g, t, THEME, mc);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /** Рендер слоя Target ESP (проекция целей на экран). */
    private static void renderTargetEspLayer(GuiGraphics g, DeltaTracker t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.options.hideGui || mc.player == null || mc.level == null) {
            return;
        }
        TargetEspModule esp = ModuleManager.get(TargetEspModule.class);
        if (esp == null || !esp.isEnabled()) {
            return;
        }
        try {
            esp.renderTargets(g, t, THEME, mc);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
