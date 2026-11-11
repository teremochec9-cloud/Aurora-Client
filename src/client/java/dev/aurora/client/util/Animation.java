package dev.aurora.client.util;

/**
 * Плавно анимируемое значение. Обновляется по дельте времени между кадрами.
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
        this.lastTime = System.currentTimeMillis();
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
        return this;
    }

    /** Пересчитывает текущее значение. Вызывать каждый кадр. */
    public float update() {
        long now = System.currentTimeMillis();
        float dt = (now - lastTime) / 1000.0f;
        lastTime = now;
        if (dt <= 0) {
            return value;
        }
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

    /** easing: быстрый старт, мягкое завершение. */
    public static float easeOutQuint(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        return 1.0f - (float) Math.pow(1.0f - t, 5);
    }

    /** easing: с лёгким «пружинным» перелётом. */
    public static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        t = Math.max(0.0f, Math.min(1.0f, t));
        return 1.0f + c3 * (float) Math.pow(t - 1.0f, 3) + c1 * (float) Math.pow(t - 1.0f, 2);
    }
}
