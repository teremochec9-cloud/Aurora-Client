package dev.aurora.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.modules.LowFireModule;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Низкий огонь: сжимает и опускает пламя на экране.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {

    @Inject(method = "renderFire", at = @At("HEAD"))
    private static void aurora$lowFire(PoseStack poseStack, MultiBufferSource bufferSource,
                                       TextureAtlasSprite sprite, CallbackInfo ci) {
        LowFireModule lowFire = ModuleManager.get(LowFireModule.class);
        if (lowFire != null && lowFire.isEnabled()) {
            float scale = lowFire.height.get();
            float bottom = -0.55f; // приблизительный низ пламени в координатах камеры
            float dy = bottom * (1.0f - scale) + lowFire.offset.get();
            poseStack.translate(0.0f, dy, 0.0f);
            poseStack.scale(1.0f, scale, 1.0f);
        }
    }
}
