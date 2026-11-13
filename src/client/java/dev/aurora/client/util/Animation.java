package dev.aurora.client.util;

/**
 * Плавно анимируемое значение. Обновляется по дельте времени между кадрами.
 *
 * <p>Все экземпляры берут время из {@link FrameClock} — одного чтения на кадр,
 * поэтому несколько анимаций, обновлённых в одном кадре, всегда движутся
 * согласованно. Сглаживание — экспоненциальное (кадронезависимое): при любой
 * частоте кадров скорость перехода одинакова.</p>
 */
public class Animation {
    private float value;
    private float target;
    private float speed;
    private long lastTime;

    public Animation(float initial, float speed) {
        this.value = initial;
        this.target = initial;
        this.speed = speed;
        this.lastTime = FrameClock.now();
    }

    /** Устанавливает целевое значение. */
    public Animation to(float target) {
        this.target = target;
        return this;
    }

    /** Мгновенно принимает целевое значение (без анимации). */
    public Animation snap(float value) {
        this.value = value;
        this.target = value;
        this.lastTime = FrameClock.now();
        return this;
    }

    /**
     * Пересчитывает текущее значение. Вызывать один раз за кадр.
     * После лага (пропущенные кадры) дельта ограничивается — значение
     * плавно догоняет цель, без резкого прыжка.
     */
    public float update() {
        long now = FrameClock.now();
        float dt = (now - lastTime) / 1000.0f;
        if (dt <= 0) {
            return value;
        }
        if (dt > 0.1f) {
            dt = 0.1f;
        }
        lastTime = now;
        float k = 1.0f - (float) Math.exp(-speed * dt);
        value += (target - value) * k;
        if (Math.abs(target - value) < 0.001f) {
            value = target;
        }
        return value;
    }

    public float get() {
        return value;
    }

    /**
     * Разовый вызов «приблизиться к цели и вернуть значение» — удобно для
     * состояний, которые обновляются ровно в одном месте за кадр (например,
     * плавная полоска здоровья Target ESP). Эквивалент to(v) + update() + get().
     */
    public float apply(float target) {
        to(target);
        return update();
    }

    public float getTarget() {
        return target;
    }

    public boolean isDone() {
        return Math.abs(target - value) < 0.001f;
    }

    /** easing: плавное начало и конец (smoothstep). */
    public static float smooth(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }

    /** easing: быстрый старт, очень мягкое завершение. */
    public static float easeOutQuint(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        return 1.0f - (float) Math.pow(1.0f - t, 5);
    }

    /** easing: мягкий старт, мягкий конец, чуть живее smoothstep. */
    public static float easeOutCubic(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        float u = 1.0f - t;
        return 1.0f - u * u * u;
    }

    /** easing: с лёгким «пружинным» перелётом. */
    public static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        t = Math.max(0.0f, Math.min(1.0f, t));
        return 1.0f + c3 * (float) Math.pow(t - 1.0f, 3) + c1 * (float) Math.pow(t - 1.0f, 2);
    }
}
