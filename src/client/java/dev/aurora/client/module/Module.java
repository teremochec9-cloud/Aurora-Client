package dev.aurora.client.module;

import dev.aurora.client.module.settings.BindSetting;
import dev.aurora.client.module.settings.Setting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Базовый класс модуля. Наследники добавляют логику в onEnable/onDisable/onTick.
 */
public abstract class Module {
    public final String name;
    public final String description;
    public final Category category;
    private boolean enabled;
    private final List<Setting> settings = new ArrayList<>();
    /** Бинд вкл/выкл (0 — нет). */
    public final BindSetting bind = new BindSetting("Клавиша");

    protected Module(String name, String description, Category category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    protected void addSettings(Setting... list) {
        settings.addAll(Arrays.asList(list));
    }

    public List<Setting> settings() {
        return settings;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        try {
            if (enabled) {
                onEnable();
            } else {
                onDisable();
            }
        } catch (Exception e) {
            // никогда не роняем игру из-за модуля
            e.printStackTrace();
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /** Вызывается при включении. */
    protected void onEnable() {
    }

    /** Вызывается при выключении. */
    protected void onDisable() {
    }

    /** Вызывается каждый клиентский тик, только когда модуль включён. */
    public void onTick() {
    }
}
