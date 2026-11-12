package dev.aurora.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.aurora.client.AuroraClient;
import dev.aurora.client.hud.HudModule;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.settings.BindSetting;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.ColorSetting;
import dev.aurora.client.module.settings.ModeSetting;
import dev.aurora.client.module.settings.NumberSetting;
import dev.aurora.client.module.settings.Setting;
import dev.aurora.client.profile.ProfileManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Сохранение и загрузка настроек в config/aurora.json.
 */
public final class AuroraConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Есть несохранённые изменения. */
    private static volatile boolean dirty;
    /** Счётчик тиков для отложенной записи. */
    private static int dirtyTicks;

    private AuroraConfig() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aurora.json");
    }

    /** Помечает конфиг изменённым — запись произойдёт через 2 секунды или при flush(). */
    public static void markDirty() {
        dirty = true;
    }

    /** Немедленная запись, если есть изменения. */
    public static synchronized void flush() {
        if (dirty) {
            save();
        }
    }

    /** Вызывается каждый клиентский тик: раз в 2 секунды сбрасывает изменения на диск. */
    public static void tick() {
        if (dirty && ++dirtyTicks >= 40) {
            dirtyTicks = 0;
            save();
        }
    }

    /** Сохранённый в конфиге никнейм (null — использовать системный). */
    private static String nickname;

    /** Кламп HUD уже выполнен (окно готово). */
    private static boolean hudClamped;
    /** Конфиг был в формате v1 — после первого клампа логируем миграцию. */
    private static boolean pendingMigrationLog;

    /**
     * Юзерово значение menuBackgroundBlurriness, пока Aurora владеет опцией
     * в fallback-режиме (шейдеры/Fabulous): хранится в aurora.json, а не в
     * статике — иначе при краше юзерово значение навсегда уезжает в
     * options.txt. -1 — владения нет.
     */
    private static int vanillaBlurBackup = -1;

    public static String getNickname() {
        return nickname;
    }

    /** Запоминает и применяет никнейм. */
    public static void setNickname(String nick) {
        nickname = (nick == null || nick.isBlank()) ? null : nick.trim();
        if (nickname != null) {
            ProfileManager.apply(nickname);
        } else {
            // сброс невозможен без перезапуска — просто запоминаем
            ProfileManager.setPending(null);
        }
        save();
    }

    public static synchronized void save() {
        dirty = false;
        dirtyTicks = 0;
        try {
            JsonObject root = new JsonObject();
            // v2: доли экрана + якоря (v1 — только доли, без якорей)
            root.addProperty("ver", 2);
            root.add("theme", AuroraClient.theme().save());
            if (nickname != null) {
                root.addProperty("nickname", nickname);
            }
            if (vanillaBlurBackup != -1) {
                root.addProperty("blurVanillaBackup", vanillaBlurBackup);
            }

            JsonArray modules = new JsonArray();
            for (Module m : ModuleManager.all()) {
                JsonObject mo = new JsonObject();
                mo.addProperty("name", m.name);
                mo.addProperty("enabled", m.isEnabled());
                mo.addProperty("bind", m.bind.get());
                if (m instanceof HudModule hud) {
                    mo.addProperty("x", hud.x);
                    mo.addProperty("y", hud.y);
                    mo.addProperty("anchorX", hud.anchorX);
                    mo.addProperty("anchorY", hud.anchorY);
                    mo.addProperty("scale", hud.scale.get());
                    mo.addProperty("bg", hud.background.get());
                }
                JsonObject settings = new JsonObject();
                for (Setting s : m.settings()) {
                    if (s instanceof BooleanSetting bs) {
                        settings.addProperty(s.name, bs.get());
                    } else if (s instanceof NumberSetting ns) {
                        settings.addProperty(s.name, ns.get());
                    } else if (s instanceof ColorSetting cs) {
                        settings.addProperty(s.name, Integer.toHexString(cs.get()));
                    } else if (s instanceof ModeSetting ms) {
                        settings.addProperty(s.name, ms.get());
                    }
                }
                mo.add("settings", settings);
                modules.add(mo);
            }
            root.add("modules", modules);

            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) {
                firstRunDefaults();
                return;
            }
            JsonObject root = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) {
                firstRunDefaults();
                return;
            }
            pendingMigrationLog = !root.has("ver"); // конфиг формата 1.2.x — миграция v1→v2
            if (root.has("blurVanillaBackup")) {
                vanillaBlurBackup = Math.max(-1, root.get("blurVanillaBackup").getAsInt());
            }
            if (root.has("theme")) {
                AuroraClient.theme().load(root.getAsJsonObject("theme"));
            }
            if (root.has("nickname")) {
                String nick = root.get("nickname").getAsString();
                if (nick != null && !nick.isBlank()) {
                    nickname = nick.trim();
                    ProfileManager.setPending(nickname);
                }
            }
            if (root.has("modules")) {
                for (var el : root.getAsJsonArray("modules")) {
                    JsonObject mo = el.getAsJsonObject();
                    Module m = ModuleManager.byName(mo.get("name").getAsString());
                    if (m == null) {
                        continue;
                    }
                    if (mo.has("bind")) {
                        m.bind.set(mo.get("bind").getAsInt());
                    }
                    if (m instanceof HudModule hud) {
                        if (mo.has("x")) {
                            hud.x = mo.get("x").getAsFloat();
                        }
                        if (mo.has("y")) {
                            hud.y = mo.get("y").getAsFloat();
                        }
                        if (mo.has("anchorX")) {
                            hud.anchorX = Math.max(0, Math.min(2, mo.get("anchorX").getAsInt()));
                        }
                        if (mo.has("anchorY")) {
                            hud.anchorY = Math.max(0, Math.min(2, mo.get("anchorY").getAsInt()));
                        }
                        if (mo.has("scale")) {
                            hud.scale.set(mo.get("scale").getAsFloat());
                        }
                        if (mo.has("bg")) {
                            hud.background.set(mo.get("bg").getAsBoolean());
                        }
                    }
                    if (mo.has("settings")) {
                        JsonObject settings = mo.getAsJsonObject("settings");
                        for (Setting s : m.settings()) {
                            if (!settings.has(s.name)) {
                                continue;
                            }
                            try {
                                if (s instanceof BooleanSetting bs) {
                                    bs.set(settings.get(s.name).getAsBoolean());
                                } else if (s instanceof NumberSetting ns) {
                                    ns.set(settings.get(s.name).getAsFloat());
                                } else if (s instanceof ColorSetting cs) {
                                    cs.set((int) (0xFF000000L | Long.parseLong(settings.get(s.name).getAsString(), 16)));
                                } else if (s instanceof ModeSetting ms) {
                                    String v = settings.get(s.name).getAsString();
                                    for (int i = 0; i < ms.modes.length; i++) {
                                        if (ms.modes[i].equals(v)) {
                                            ms.setIndex(i);
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (mo.has("enabled") && mo.get("enabled").getAsBoolean()) {
                        m.setEnabled(true);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            firstRunDefaults();
        }
    }

    /**
     * Прижимает все HUD-элементы к границам экрана. Вызывается каждый тик,
     * но работает один раз — когда окно гарантированно готово. Заодно
     * логирует миграцию конфига v1→v2, если она была.
     */
    public static void clampAllHud() {
        if (hudClamped) {
            return;
        }
        try {
            var win = Minecraft.getInstance().getWindow();
            if (win == null || win.getGuiScaledWidth() <= 0) {
                return; // окно ещё не готово — попробуем на следующем тике
            }
            hudClamped = true;
            // если прошлый запуск завершился крашем в fallback-режиме —
            // возвращаем юзерово значение ванильной опции (бэкап был в aurora.json)
            if (vanillaBlurBackup != -1) {
                releaseVanillaBlurControl();
            }
            int gw = win.getGuiScaledWidth();
            int gh = win.getGuiScaledHeight();
            boolean changed = false;
            for (Module m : ModuleManager.all()) {
                if (m instanceof HudModule hud && hud.clampToScreen(gw, gh)) {
                    changed = true;
                }
            }
            if (changed) {
                save();
            }
            if (pendingMigrationLog) {
                pendingMigrationLog = false;
                org.slf4j.LoggerFactory.getLogger("Aurora/Config").info(
                        "Конфиг v1 перенесён в v2: позиции — доли экрана (guiScale на них не влияет), "
                                + "элементы прижаты к границам. Если что-то съехало — поправьте один раз в HUD-редакторе.");
            }
        } catch (Exception ignored) {
        }
    }

    /** Юзерово значение ванильной опции блюра, если сейчас владеем ею (-1 — нет). */
    public static int getVanillaBlurBackup() {
        return vanillaBlurBackup;
    }

    /**
     * Aurora забирает управление ванильной опцией блюра (fallback-режим):
     * юзерово значение запоминается в aurora.json один раз. Повторные вызовы
     * не перечитывают опцию — иначе поверх старого бэкапа записался бы
     * уже подменённый значение.
     */
    public static void takeVanillaBlurControl() {
        if (vanillaBlurBackup != -1) {
            return;
        }
        try {
            vanillaBlurBackup = Minecraft.getInstance().options.getMenuBackgroundBlurriness();
            save();
        } catch (Exception ignored) {
        }
    }

    /**
     * Маппинг слайдера темы на ванильную опцию. Правило порога: 0 =
     * выключено, иначе минимум 1 (порог ванили ровно 1.0) — тумблер не
     * может быть «включён и не работать».
     */
    public static void applyVanillaBlur(int strength) {
        try {
            var opt = Minecraft.getInstance().options.menuBackgroundBlurriness();
            int v = strength <= 0 ? 0 : Math.max(1, strength);
            if (opt.get() != v) {
                opt.set(v);
            }
        } catch (Exception ignored) {
        }
    }

    /** Возвращаем юзерово значение и прекращаем владение. */
    public static void releaseVanillaBlurControl() {
        if (vanillaBlurBackup == -1) {
            return;
        }
        try {
            Minecraft.getInstance().options.menuBackgroundBlurriness().set(vanillaBlurBackup);
        } catch (Exception ignored) {
        }
        vanillaBlurBackup = -1;
        save();
    }

    /** Приятные значения по умолчанию при первом запуске. */
    private static void firstRunDefaults() {
        Module watermark = ModuleManager.byName("Водяной знак");
        Module keystrokes = ModuleManager.byName("Клавиши");
        Module fps = ModuleManager.byName("FPS");
        if (watermark != null) {
            watermark.setEnabled(true);
        }
        if (keystrokes != null) {
            keystrokes.setEnabled(true);
        }
        if (fps != null) {
            fps.setEnabled(true);
        }
        save();
    }
}
