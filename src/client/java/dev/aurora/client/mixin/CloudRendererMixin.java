package dev.aurora.client.mixin;

import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.modules.NoCloudsModule;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * «Без облаков»: отменяет отрисовку облачного слоя целиком.
 *
 * <p>Миксин стоит в {@link CloudRenderer#render} — самом начале пайплайна
 * облаков: не строится меш, не сэмплируется небо, не рисуется геометрия.
 * Остальная логика рендерера (перестройка по флагам) не страдает.</p>
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aurora$noClouds(int ticks, CloudStatus status, float partialTick,
                                 Vec3 cameraPos, long cameraMicros, float cloudHeight,
                                 CallbackInfo ci) {
        NoCloudsModule m = ModuleManager.get(NoCloudsModule.class);
        if (m != null && m.isEnabled()) {
            ci.cancel();
        }
    }
}
