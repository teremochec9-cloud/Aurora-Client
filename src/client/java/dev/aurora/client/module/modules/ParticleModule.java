package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.ModeSetting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * «Меньше частиц»: сокращает количество ванильных частиц — дым, искры,
 * капли и прочий «шум» создаётся частично. Взрывы и вспышки не режутся
 * никогда: они должны читаться целиком (геймплей).
 *
 * <p>Режимы: «Меньше» — остаётся половина частиц, «Минимум» — остаётся
 * каждая пятая. На стримах с зумом и в пещерных файтах даёт прирост FPS.</p>
 */
public class ParticleModule extends Module {
    public final ModeSetting mode = new ModeSetting("Режим",
            new String[]{"Меньше", "Минимум"}, 0, "Как сильно сокращать частицы");

    public ParticleModule() {
        super("Меньше частиц", "Сокращает ванильные частицы — больше FPS",
                Category.PROF);
        addSettings(mode);
    }

    /** Доля частиц, которые остаются (1 — все, 0.5 — половина, 0.2 — каждая пятая). */
    public float keepFraction() {
        return mode.get().equals("Минимум") ? 0.2f : 0.5f;
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
