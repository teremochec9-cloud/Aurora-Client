package dev.aurora.client.module;

import dev.aurora.client.module.modules.ChatModule;
import dev.aurora.client.module.modules.CleanHudModule;
import dev.aurora.client.module.modules.CustomCrosshairModule;
import dev.aurora.client.module.modules.EntityShadowsModule;
import dev.aurora.client.module.modules.FullbrightModule;
import dev.aurora.client.module.modules.LowFireModule;
import dev.aurora.client.module.modules.NoHurtCamModule;
import dev.aurora.client.module.modules.OptimizeModule;
import dev.aurora.client.module.modules.ParticleModule;
import dev.aurora.client.module.modules.TargetEspModule;
import dev.aurora.client.module.modules.ZoomModule;
import dev.aurora.client.hud.ClockHud;
import dev.aurora.client.hud.CoordsHud;
import dev.aurora.client.hud.CpsHud;
import dev.aurora.client.hud.DirectionHud;
import dev.aurora.client.hud.FpsHud;
import dev.aurora.client.hud.KeystrokesHud;
import dev.aurora.client.hud.PingHud;
import dev.aurora.client.hud.WatermarkHud;

import java.util.ArrayList;
import java.util.List;

/**
 * Реестр всех модулей клиента.
 */
public class ModuleManager {
    private static final List<Module> MODULES = new ArrayList<>();

    public static void init() {
        // Визуал
        register(new ZoomModule());
        register(new FullbrightModule());
        register(new NoHurtCamModule());
        register(new LowFireModule());
        register(new CleanHudModule());
        register(new CustomCrosshairModule());
        register(new TargetEspModule());
        // Производительность
        register(new OptimizeModule());
        register(new EntityShadowsModule());
        register(new ParticleModule());
        // HUD
        register(new WatermarkHud());
        register(new FpsHud());
        register(new PingHud());
        register(new CoordsHud());
        register(new CpsHud());
        register(new KeystrokesHud());
        register(new DirectionHud());
        register(new ClockHud());
        // Чат
        register(new ChatModule());
    }

    private static void register(Module m) {
        MODULES.add(m);
    }

    public static List<Module> all() {
        return MODULES;
    }

    public static List<Module> byCategory(Category c) {
        List<Module> out = new ArrayList<>();
        for (Module m : MODULES) {
            if (m.category == c) {
                out.add(m);
            }
        }
        return out;
    }

    public static List<Module> enabled() {
        List<Module> out = new ArrayList<>();
        for (Module m : MODULES) {
            if (m.isEnabled()) {
                out.add(m);
            }
        }
        return out;
    }

    public static Module byName(String name) {
        for (Module m : MODULES) {
            if (m.name.equalsIgnoreCase(name)) {
                return m;
            }
        }
        return null;
    }

    /** Возвращает модуль по классу (удобно для миксинов). */
    public static <T extends Module> T get(Class<T> type) {
        for (Module m : MODULES) {
            if (type.isInstance(m)) {
                return type.cast(m);
            }
        }
        return null;
    }

    /** Тик всех включённых модулей. */
    public static void tick() {
        for (Module m : MODULES) {
            if (m.isEnabled()) {
                try {
                    m.onTick();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
