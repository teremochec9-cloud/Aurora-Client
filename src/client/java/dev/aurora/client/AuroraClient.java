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
import dev.aurora.client.module.modules.CleanHudModule;
import dev.aurora.client.module.modules.CustomCrosshairModule;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.ColorUtil;
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

        // ===== Тик: хоткей меню, модули =====
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ModuleManager.tick();
            if (client.screen == null && client.player != null && client.level != null) {
                if (InputConstants.isKeyDown(client.getWindow(), THEME.menuKey)) {
                    client.setScreen(new AuroraClickGui());
                }
            }
            // хоткеи модулей
            if (client.screen == null && client.player != null) {
                for (Module m : ModuleManager.all()) {
                    int key = m.bind.get();
                    if (key != 0 && InputConstants.isKeyDown(client.getWindow(), key)) {
                        if (!wasKeyHeld(m)) {
                            m.toggle();
                            AuroraClickGui.playClick();
                            AuroraConfig.save();
                        }
                    }
                    markKeyHeld(m, key != 0 && InputConstants.isKeyDown(client.getWindow(), key));
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

    /** Кастомный прицел: точка/круг/крест. */
    private static void drawCustomCrosshair(GuiGraphics g, CustomCrosshairModule m) {
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        int color = m.color.get();
        String style = m.style.get();
        if (style.equals("Точка") || style.equals("Точка + круг")) {
            g.fill(cx - 1, cy - 1, cx + 1, cy + 1, color);
        }
        if (style.equals("Круг") || style.equals("Точка + круг")) {
            int r = 6;
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16;
                int px = cx + (int) Math.round(Math.cos(a) * r);
                int py = cy + (int) Math.round(Math.sin(a) * r);
                g.fill(px, py, px + 1, py + 1, color);
            }
        }
        if (style.equals("Крест")) {
            int arm = 4;
            int gap = 2;
            g.fill(cx - 1, cy - gap - arm, cx, cy - gap, color); // вверх
            g.fill(cx, cy + gap, cx + 1, cy + gap + arm, color); // вниз
            g.fill(cx - gap - arm, cy - 1, cx - gap, cy, color); // влево
            g.fill(cx + gap, cy, cx + gap + arm, cy + 1, color); // вправо
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
}
