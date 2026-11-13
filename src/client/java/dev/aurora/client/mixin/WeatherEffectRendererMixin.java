package dev.aurora.client.mixin;

import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.modules.NoWeatherModule;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * «Без осадков»: отменяет отрисовку дождя и снега.
 *
 * <p>Миксин стоит в {@link WeatherEffectRenderer#render} — проход осадков
 * выполняется, но не рисует ни одного квада. Извлечение рендер-состояния
 * и звуки остаются нетронутыми, поэтому при выключении модуля всё
 * возвращается без артефактов.</p>
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aurora$noWeather(MultiBufferSource bufferSource, Vec3 cameraPos,
                                  WeatherRenderState state, CallbackInfo ci) {
        NoWeatherModule m = ModuleManager.get(NoWeatherModule.class);
        if (m != null && m.isEnabled()) {
            ci.cancel();
        }
    }
}
