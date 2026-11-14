package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.ModeSetting;
import dev.aurora.client.module.settings.NumberSetting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * «Меньше частиц»: сокращает количество ванильных частиц — дым, искры,
 * капли и прочий «шум» создаётся частично. Взрывы и вспышки не режутся
 * никогда: они должны читаться целиком (геймплей).
 *
 * <p>Режимы: «Меньше» — остаётся половина частиц, «Минимум» — остаётся
 * каждая пятая. Дополнительно «Далёкие частицы» вообще не создаёт
 * частицы дальше выбранного радиуса от камеры: порталы, фонтаны и фермы
 * вдали спавнят тысячи частиц, которых игрок всё равно не видит —
 * их симуляция и рендер просто исчезают из бюджета кадра.</p>
 */
public class ParticleModule extends Module {
    public final ModeSetting mode = new ModeSetting("Режим",
            new String[]{"Меньше", "Минимум"}, 0, "Как сильно сокращать частицы");
    public final BooleanSetting farCull = new BooleanSetting("Далёкие частицы", true,
            "Частицы дальше радиуса от камеры не создаются вовсе");
    public final NumberSetting farRadius = new NumberSetting("Радиус", 32.0f, 8.0f, 64.0f, 1.0f, 0,
            "Расстояние до камеры, дальше которого частицы не создаются (блоки)");

    private static ParticleModule module;

    public ParticleModule() {
        super("Меньше частиц", "Сокращает ванильные частицы — больше FPS",
                Category.PROF);
        addSettings(mode, farCull, farRadius);
    }

    /** Доля частиц, которые остаются (1 — все, 0.5 — половина, 0.2 — каждая пятая). */
    public float keepFraction() {
        return mode.get().equals("Минимум") ? 0.2f : 0.5f;
    }

    /**
     * Не создавать ли частицу в точке (x, y, z): включён «Далёкие частицы»
     * и точка дальше выбранного радиуса от камеры. Вызывается из миксина
     * на рендер-потоке; быстрый путь — модуль + флаг + квадрат дистанции.
     */
    public static boolean shouldCullFar(double x, double y, double z) {
        if (module == null) {
            module = ModuleManager.get(ParticleModule.class);
        }
        if (module == null || !module.isEnabled() || !module.farCull.get()) {
            return false;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
            return false;
        }
        var pos = mc.gameRenderer.getMainCamera().position();
        double r = module.farRadius.get();
        double dx = x - pos.x, dy = y - pos.y, dz = z - pos.z;
        return dx * dx + dy * dy + dz * dz > r * r;
    }

    /** Критичные частицы (взрывы, вспышки) не сокращаются никогда. */
    public static boolean isCritical(ParticleOptions options) {
        var key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        return path.equals("explosion") || path.equals("explosion_emitter")
                || path.equals("flash") || path.equals("poof");
    }
}
