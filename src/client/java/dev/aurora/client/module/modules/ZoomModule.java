package dev.aurora.client.module.modules;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.NumberSetting;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Зум: удерживай клавишу — камера плавно приближается.
 * Значение множителя сглаживается по времени, поэтому картинка не дёргается.
 */
public class ZoomModule extends Module {
    public final NumberSetting factor = new NumberSetting("Кратность", 4.0f, 2.0f, 8.0f, 0.5f, 1, "Во сколько раз приближать");
    public final NumberSetting smoothness = new NumberSetting("Плавность", 8.0f, 0.5f, 20.0f, 0.5f, 1, "Скорость сглаживания");
    public final BooleanSetting cinematic = new BooleanSetting("Кинематографично", true, "Мягкий разгон и торможение");

    private float current = 1.0f;
    private long lastFrame = System.currentTimeMillis();

    public ZoomModule() {
        super("Зум", "Плавное приближение камеры по клавише", Category.VISUAL);
        bind.set(GLFW.GLFW_KEY_C);
        addSettings(factor, smoothness, cinematic);
    }

    /** Удерживается ли сейчас клавиша зума. */
    public boolean isZooming() {
        Minecraft mc = Minecraft.getInstance();
        return isEnabled() && mc.player != null && mc.screen == null
                && InputConstants.isKeyDown(mc.getWindow(), bind.get());
    }

    /**
     * Текущий множитель FOV (обновляет анимацию). Вызывается из миксина каждый кадр.
     */
    public float currentMultiplier() {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000.0f);
        lastFrame = now;

        float target = isZooming() ? factor.get() : 1.0f;
        float speed = cinematic.get() ? Math.max(0.5f, smoothness.get() * 0.6f) : smoothness.get() * 4.0f;
        float k = 1.0f - (float) Math.exp(-speed * dt);
        current += (target - current) * k;
        if (Math.abs(target - current) < 0.01f) {
            current = target;
        }
        return Math.max(1.0f, current);
    }
}
