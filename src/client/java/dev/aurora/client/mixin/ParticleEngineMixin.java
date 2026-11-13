package dev.aurora.client.mixin;

import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.modules.ParticleModule;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * «Меньше частиц»: часть некритичных частиц не создаётся вовсе
 * (взрывы, вспышки и «пооф» никогда не пропускаются — они должны
 * читаться полностью). Возвращает null вместо частицы — ванильный
 * обработчик спокойно игнорирует пустой результат.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    @Inject(
            method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void aurora$reduceParticles(ParticleOptions options, double x, double y, double z,
                                        double xd, double yd, double zd,
                                        CallbackInfoReturnable<Particle> cir) {
        ParticleModule m = ModuleManager.get(ParticleModule.class);
        if (m == null || !m.isEnabled()) {
            return;
        }
        if (ParticleModule.isCritical(options)) {
            return; // взрывы и вспышки не режем никогда
        }
        float keep = m.keepFraction();
        if (keep >= 0.999f) {
            return;
        }
        if (ThreadLocalRandom.current().nextFloat() > keep) {
            cir.setReturnValue(null);
        }
    }
}
