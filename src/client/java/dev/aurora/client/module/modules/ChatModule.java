package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import net.minecraft.client.Minecraft;

/**
 * Улучшения чата: сообщения не затухают, прозрачный фон, копирование.
 */
public class ChatModule extends Module {
    public final BooleanSetting noFade = new BooleanSetting("Без затухания", true, "Сообщения не исчезают со временем");
    public final BooleanSetting transparentBg = new BooleanSetting("Прозрачный фон", false, "Убирает тёмный фон чата");
    public final BooleanSetting ctrlCopy = new BooleanSetting("Ctrl+C — копировать", true, "В чате копирует последнее сообщение");

    private Double previousOpacity;
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
        if (transparentBg.get() && previousOpacity == null) {
            // ВАЖНО: прозрачность ФОНА (textBackgroundOpacity), а не текста!
            // chatOpacity управляет прозрачностью самого текста — с 0.0
            // сообщения становились невидимыми (баг 1.0.5).
            previousOpacity = (Double) mc.options.textBackgroundOpacity().get();
            mc.options.textBackgroundOpacity().set(0.0);
        }
    }

    private void restore() {
        pending = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && previousOpacity != null) {
            mc.options.chatOpacity().set(previousOpacity);
            previousOpacity = null;
        }
    }

    @Override
    public void onTick() {
        if (pending) {
            pending = false;
            apply();
        } else if (isEnabled() && transparentBg.get() && previousOpacity == null) {
            apply();
        }
    }

    /** Нужно ли отключить затухание сообщений (вызывается из миксина). */
    public static boolean shouldDisableFade() {
        ChatModule m = dev.aurora.client.module.ModuleManager.get(ChatModule.class);
        return m != null && m.isEnabled() && m.noFade.get();
    }
}
