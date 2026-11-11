package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import net.minecraft.client.Minecraft;

/**
 * Fullbright: максимальная яркость без зелий ночного зрения.
 * Меняет гамму через настройки, при выключении возвращает как было.
 */
public class FullbrightModule extends Module {
    private Double previousGamma;
    private boolean pending = false;

    public FullbrightModule() {
        super("Fullbright", "Максимальная яркость в пещерах и ночью", Category.VISUAL);
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            pending = true; // настройки ещё не готовы — применим в первом тике
            return;
        }
        if (previousGamma == null) {
            previousGamma = (Double) mc.options.gamma().get();
        }
        mc.options.gamma().set(15.0);
    }

    @Override
    protected void onDisable() {
        pending = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && previousGamma != null) {
            mc.options.gamma().set(previousGamma);
            previousGamma = null;
        }
    }

    @Override
    public void onTick() {
        if (pending) {
            pending = false;
            previousGamma = null;
            onEnable();
        }
    }
}
