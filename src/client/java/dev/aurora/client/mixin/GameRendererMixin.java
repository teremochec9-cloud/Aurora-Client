package dev.aurora.client.mixin;

import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.modules.NoHurtCamModule;
import dev.aurora.client.module.modules.ZoomModule;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Зум (изменение FOV) и отключение тряски камеры при уроне.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void aurora$zoomFov(Camera camera, float partialTick, boolean useFovSetting,
                                CallbackInfoReturnable<Float> cir) {
        ZoomModule zoom = ModuleManager.get(ZoomModule.class);
        if (zoom != null && zoom.isEnabled()) {
            cir.setReturnValue(cir.getReturnValueF() / zoom.currentMultiplier());
        }
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void aurora$noHurtCam(CallbackInfo ci) {
        NoHurtCamModule noHurtCam = ModuleManager.get(NoHurtCamModule.class);
        if (noHurtCam != null && noHurtCam.isEnabled()) {
            ci.cancel();
        }
    }
}
