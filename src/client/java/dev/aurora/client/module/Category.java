package dev.aurora.client.module;

/**
 * Категории меню. Названия отображаются в клиенте как есть.
 */
public enum Category {
    VISUAL("Визуал", "Зум, яркость и экранные эффекты"),
    HUD("HUD", "Информационные панели на экране"),
    CHAT("Чат", "Улучшения игрового чата"),
    PROF("Производительность", "Оптимизация игры и FPS");

    public final String title;
    public final String description;

    Category(String title, String description) {
        this.title = title;
        this.description = description;
    }
}
