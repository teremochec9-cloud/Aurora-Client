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
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Сохранение и загрузка настроек в config/aurora.json.
 */
public final class AuroraConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AuroraConfig() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aurora.json");
    }

    public static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.add("theme", AuroraClient.theme().save());

            JsonArray modules = new JsonArray();
            for (Module m : ModuleManager.all()) {
                JsonObject mo = new JsonObject();
                mo.addProperty("name", m.name);
                mo.addProperty("enabled", m.isEnabled());
                mo.addProperty("bind", m.bind.get());
                if (m instanceof HudModule hud) {
                    mo.addProperty("x", hud.x);
                    mo.addProperty("y", hud.y);
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
            if (root.has("theme")) {
                AuroraClient.theme().load(root.getAsJsonObject("theme"));
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
