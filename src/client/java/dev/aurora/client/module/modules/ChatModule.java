package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import net.minecraft.client.Minecraft;

/**
 * Улучшения чата: сообщения не затухают, прозрачный фон, копирование.
 *
 * <p>«Прозрачный фон» управляет ванильной опцией textBackgroundOpacity —
 * она отвечает именно за подложку сообщений. Раньше мы зануляли
 * chatOpacity, а она влияет на сам ТЕКСТ: при открытом чате буквы
 * становились невидимыми. Теперь текст всегда виден, пропадает только фон.</p>
 */
public class ChatModule extends Module {
    public final BooleanSetting noFade = new BooleanSetting("Без затухания", true, "Сообщения не исчезают со временем");
    public final BooleanSetting transparentBg = new BooleanSetting("Прозрачный фон", false, "Убирает тёмную подложку чата, текст остаётся видимым");
    public final BooleanSetting ctrlCopy = new BooleanSetting("Ctrl+C — копировать", true, "В чате копирует последнее сообщение");

    private Double previousBackgroundOpacity;
    private boolean pending = false;

    public ChatModule() {
        super("Улучшения чата", "Без затухания, прозрачный фон, копирование", Category.CHAT);
        addSettings(noFade, transparentBg, ctrlCopy);
    }

    @Override
    protected void onEnable() {
        apply();
    }

    @Override
    protected void onDisable() {
        restore();
    }

    private void apply() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            pending = true;
            return;
        }
        if (transparentBg.get() && previousBackgroundOpacity == null) {
            try {
                previousBackgroundOpacity = (Double) mc.options.textBackgroundOpacity().get();
                mc.options.textBackgroundOpacity().set(0.0);
            } catch (Exception e) {
                previousBackgroundOpacity = null;
            }
        }
    }

    private void restore() {
        pending = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && previousBackgroundOpacity != null) {
            try {
                mc.options.textBackgroundOpacity().set(previousBackgroundOpacity);
            } catch (Exception ignored) {
            }
            previousBackgroundOpacity = null;
        }
    }

    @Override
    public void onTick() {
        if (pending) {
            pending = false;
            apply();
        } else if (isEnabled() && transparentBg.get() && previousBackgroundOpacity == null) {
            apply();
        }
    }

    /** Нужно ли отключить затухание сообщений (вызывается из миксина). */
    public static boolean shouldDisableFade() {
        ChatModule m = dev.aurora.client.module.ModuleManager.get(ChatModule.class);
        return m != null && m.isEnabled() && m.noFade.get();
    }
}
