package dev.aurora.client.module.settings;

/**
 * Базовый класс настройки модуля. Настройки рисуются в меню автоматически.
 */
public abstract class Setting {
    public final String name;
    public final String description;

    protected Setting(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /** Текстовое представление текущего значения (для списка настроек). */
    public abstract String display();
}
