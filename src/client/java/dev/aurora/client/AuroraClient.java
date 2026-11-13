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
import dev.aurora.client.theme.Theme;
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
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Aurora Client — точка входа.
 */
public class AuroraClient implements ClientModInitializer {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("Aurora/Main");
    private static final Theme THEME = new Theme();
    private static boolean replacingTitle = false;
    /** Никнейм применяется один раз — в первом клиентском тике. */
    private static boolean profileApplied;
    /** Состояние клавиши меню в прошлом тике (антидребезг открытия). */
    private static boolean menuKeyWasHeld;
    /** Смоук-тест: автооткрытие ClickGUI (задаётся переменной окружения AURORA_TEST_GUI=1). */
    private static final boolean TEST_GUI = "1".equals(System.getenv("AURORA_TEST_GUI"));
    /** Смоук-тест: вместо ClickGUI открыть HUD-редактор (AURORA_TEST_HUD=1). */
    private static final boolean TEST_HUD = "1".equals(System.getenv("AURORA_TEST_HUD"));
    /** Смоук-тест: автосоздание мира с мобами (AURORA_TEST_WORLD=1). */
    private static final boolean TEST_WORLD = "1".equals(System.getenv("AURORA_TEST_WORLD"));
    /** Смоук-тест: после загрузки мира открыть чат с «/» (AURORA_TEST_CHAT=1). */
    private static final boolean TEST_CHAT = "1".equals(System.getenv("AURORA_TEST_CHAT"));
    /** Смоук-харнесс: автоскриншоты в папку (AURORA_SHOT_DIR=/путь). */
    private static final String SHOT_DIR = System.getenv("AURORA_SHOT_DIR");
    /** Смоук-харнесс: макс. число скриншотов (AURORA_SHOT_MAX, по умолчанию 40). */
    private static final int SHOT_MAX = Integer.parseInt(System.getenv().getOrDefault("AURORA_SHOT_MAX", "40"));
    /** Смоук-харнесс: интервал скриншотов в тиках (AURORA_SHOT_EVERY, по умолчанию 40). */
    private static final int SHOT_EVERY = Integer.parseInt(System.getenv().getOrDefault("AURORA_SHOT_EVERY", "40"));
    private int titleTicks;
    private int shotCooldown = 60; // первый кадр — через 3 с после старта
    private int shotIndex;
    /** Смоук: мир запрошен (один раз). */
    private static boolean worldRequested;
    /** Смоук: тики после захода в мир (−1 — ещё не в мире). */
    private static int worldJoinTicks = -1;
    /** Смоук: мобы призваны. */
    private static boolean mobsSummoned;
    /** Смоук: чат открыт. */
    private static boolean chatOpened;
    /** Контроль мерцания: предупреждение логируется один раз. */
    private static boolean flickerLogged;

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

        // ===== Подмена ванильных элементов (чистый интерфейс, прицел) =====
        registerHiders();

        // ===== Тик: хоткей меню, модули, отложенное сохранение конфига =====
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // ленивое применение никнейма (первый тик: Minecraft уже создан)
            if (!profileApplied) {
                profileApplied = true;
                dev.aurora.client.profile.ProfileManager.applyPending();
                // чиним прозрачность чата, испорченную багом 1.0.5,
                // ДО тика модулей (модуль чата мог уже «одолжить» опцию)
                ChatModule.repairOnce(client);
            }
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
            // контроль мерцания: ванильный TitleScreen не должен быть текущим
            // ни на один тик (синхронная подмена). Если строчка появляется —
            // ванильная заставка мелькает перед меню Aurora (логируем один раз)
            if (client.screen instanceof TitleScreen && !flickerLogged) {
                flickerLogged = true;
                LOG.warn("СМОУК: ванильный TitleScreen стал текущим экраном — возможно мерцание!");
            }
            // служебный режим смоук-теста: открыть ClickGUI или HUD-редактор
            if (TEST_GUI && client.screen instanceof AuroraTitleScreen) {
                if (++titleTicks > 200) {
                    client.setScreen(TEST_HUD ? new HudEditorScreen() : new AuroraClickGui());
                    titleTicks = -1000000; // один раз
                }
            }
            // смоук-тест: автосоздание мира с мобами (AURORA_TEST_WORLD=1)
            if (TEST_WORLD && !worldRequested
                    && client.screen instanceof AuroraTitleScreen && ++titleTicks > 100) {
                worldRequested = true;
                titleTicks = -1000000; // не открывать меню после создания
                try {
                    client.createWorldOpenFlows().createFreshLevel("aurora_test",
                            new LevelSettings("aurora_test", GameType.SURVIVAL, false, Difficulty.NORMAL,
                                    true, new GameRules(FeatureFlags.VANILLA_SET), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(3250963L, true, false),
                            WorldPresets::createNormalWorldDimensions, null);
                    LOG.info("Смоук: создаём тестовый мир aurora_test (seed 3250963, чит-коды включены)");
                } catch (Exception ex) {
                    LOG.error("Смоук: не удалось создать тестовый мир", ex);
                }
            }
            // смоук-тест: после загрузки мира — призвать мобов перед камерой,
            // при AURORA_TEST_CHAT=1 открыть чат с «/» (проверка подсказок команд)
            if (TEST_WORLD && client.level != null && client.player != null) {
                if (worldJoinTicks < 0) {
                    worldJoinTicks = 0;
                    LOG.info("Смоук: мир загружен, игрок в мире");
                }
                worldJoinTicks++;
                if (!mobsSummoned && worldJoinTicks == 40) {
                    mobsSummoned = true;
                    try {
                        client.getConnection().sendCommand("summon minecraft:sheep ~ ~ ~5");
                        client.getConnection().sendCommand("summon minecraft:pig ~2 ~ ~4");
                        client.getConnection().sendCommand("summon minecraft:cow ~-2 ~ ~5");
                        client.getConnection().sendCommand("time set day");
                        LOG.info("Смоук: призваны овца, свинья и корова перед камерой");
                    } catch (Exception ex) {
                        LOG.error("Смоук: summon не удался", ex);
                    }
                }
                if (TEST_CHAT && !chatOpened && worldJoinTicks == 160) {
                    chatOpened = true;
                    try {
                        // пустой ввод: «печатание» ниже пройдёт полный путь ванильного
                        // редактирования (респондент включает автоподсказки)
                        client.setScreen(new ChatScreen("", false));
                        LOG.info("Смоук: открыт чат — проверяем ввод и подсказки команд");
                    } catch (Exception ex) {
                        LOG.error("Смоук: чат не открылся", ex);
                    }
                }
                // через секунду «печатаем» /gam: частичная команда даёт непустой
                // список подсказок (для полностью введённого «/gamemode» бригадьер
                // возвращает пустые подсказки — и дропдаун скрыт по семантике)
                if (TEST_CHAT && chatOpened && worldJoinTicks == 180
                        && client.screen instanceof ChatScreen chatScreen) {
                    try {
                        var field = ChatScreen.class.getDeclaredField("input");
                        field.setAccessible(true);
                        var box = (net.minecraft.client.gui.components.EditBox) field.get(chatScreen);
                        box.insertText("/gam");
                        LOG.info("Смоук: в чат напечатано «/gam» — ждём список подсказок");
                    } catch (Exception ex) {
                        LOG.error("Смоук: не удалось ввести текст в чат", ex);
                    }
                }
            }
            // смоук-харнесс: периодические скриншоты для визуальной приёмки
            if (SHOT_DIR != null) {
                if (++shotCooldown >= SHOT_EVERY && shotIndex < SHOT_MAX) {
                    shotCooldown = 0;
                    final int idx = shotIndex++;
                    final String screenName = client.screen == null ? "game"
                            : client.screen.getClass().getSimpleName();
                    Screenshot.takeScreenshot(client.getMainRenderTarget(), img -> {
                        try {
                            java.nio.file.Path p = java.nio.file.Path.of(SHOT_DIR,
                                    String.format("shot_%02d.png", idx));
                            img.writeToFile(p);
                            LOG.info("AURORA_SHOT #{} экран={} -> {}", idx, screenName, p);
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            img.close();
                        }
                    });
                }
            }
        });

        // ===== Красивое главное меню =====
        // ВАЖНО: экран подменяется СИНХРОННО, прямо внутри BEFORE_INIT.
        // Раньше подмена откладывалась через client.execute — ванильный
        // TitleScreen с панорамой успевал отрисоваться кадр-другой, и при
        // каждом входе в меню неприятно «мелькала» ванильная заставка
        // (баг 1.0.5). Вложенный setScreen безопасен: после init() внешний
        // setScreen уже не перезаписывает this.screen.
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen.getClass() == TitleScreen.class && !replacingTitle) {
                replacingTitle = true;
                try {
                    client.setScreen(new AuroraTitleScreen());
                } finally {
                    replacingTitle = false;
                }
            }
        });

        // ===== Сохранение при выходе =====
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AuroraConfig.save());
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

    /**
     * Кастомный прицел: векторная отрисовка (NanoVG) — гладкие точки,
     * круги и линии любой толщины, с тёмной подложкой для читаемости
     * на светлом фоне. «Динамика» мягко разводит крест при движении.
     */
    private static void drawCustomCrosshair(GuiGraphics g, CustomCrosshairModule m) {
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        int color = m.color.get();
        String style = m.style.get();
        float size = Math.max(1, m.size.get());
        float th = Math.max(1f, m.thickness.get());
        float spread = Math.max(0f, m.gap.get());
        // динамика: крест плавно расходится от скорости движения
        if (m.dynamic.get() && Minecraft.getInstance().player != null) {
            double v = Minecraft.getInstance().player.getDeltaMovement().horizontalDistance();
            spread += (float) Math.min(4.0, v * 2.4);
        }
        // тёмная подложка — прицел читается и на снегу, и в облаках
        int shade = ColorUtil.rgba(5, 6, 10, 170);

        if (style.equals("Точка") || style.equals("Точка + круг")) {
            GfxEngine.circle(g, cx, cy, size + 2.5f, shade);
            GfxEngine.circle(g, cx, cy, size, color);
        }
        if (style.equals("Круг") || style.equals("Точка + круг")) {
            float r = 4 + size;
            GfxEngine.ring(g, cx, cy, (r + th * 0.5f) * 2, shade, th + 1.6f);
            GfxEngine.ring(g, cx, cy, r * 2, color, th);
        }
        if (style.equals("Крест")) {
            float arm = 2 + size;
            float half = th / 2f;
            GfxEngine.rounded(g, cx - half - 0.8f, cy - spread - arm - 0.8f, th + 1.6f, arm + 1.6f, half, shade);
            GfxEngine.rounded(g, cx - half - 0.8f, cy + spread - 0.8f, th + 1.6f, arm + 1.6f, half, shade);
            GfxEngine.rounded(g, cx - spread - arm - 0.8f, cy - half - 0.8f, arm + 1.6f, th + 1.6f, half, shade);
            GfxEngine.rounded(g, cx + spread - 0.8f, cy - half - 0.8f, arm + 1.6f, th + 1.6f, half, shade);
            GfxEngine.rounded(g, cx - half, cy - spread - arm, th, arm, half, color);
            GfxEngine.rounded(g, cx - half, cy + spread, th, arm, half, color);
            GfxEngine.rounded(g, cx - spread - arm, cy - half, arm, th, half, color);
            GfxEngine.rounded(g, cx + spread, cy - half, arm, th, half, color);
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
        // Target ESP: подсветка целей рисуется ПОД панелями HUD
        TargetEspModule esp = ModuleManager.get(TargetEspModule.class);
        if (esp != null && esp.isEnabled()) {
            try {
                esp.renderTargets(g, t, THEME, mc);
            } catch (Exception e) {
                e.printStackTrace();
            }
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
}
