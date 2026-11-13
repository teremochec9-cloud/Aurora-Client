package dev.aurora.client.util;

/**
 * Единое время кадра Aurora.
 *
 * <p>Системное время читается ОДИН раз за кадр — в начале отрисовки GUI
 * (миксин {@code GuiRendererMixin}). Все анимации, дым и эффекты клиента
 * берут время отсюда: это убирает десятки вызовов
 * {@code System.currentTimeMillis()} на кадр и гарантирует, что элементы,
 * анимируемые в одном кадре, движутся синхронно, без «рваных» фаз.</p>
 *
 * <p>Побочный бонус: время кадра можно подменить (например, в тестах или
 * при паузе отладчика) — и весь интерфейс замрёт согласованно.</p>
 */
public final class FrameClock {
    private static volatile long frame = System.currentTimeMillis();

    private FrameClock() {
    }

    /** Начало нового кадра. Вызывается один раз за кадр из миксина GUI-рендера. */
    public static void refresh() {
        frame = System.currentTimeMillis();
    }

    /** Время текущего кадра в миллисекундах. */
    public static long now() {
        return frame;
    }

    /** Время текущего кадра в секундах (для синусоид и дрейфа частиц). */
    public static float seconds() {
        return frame / 1000.0f;
    }
}
