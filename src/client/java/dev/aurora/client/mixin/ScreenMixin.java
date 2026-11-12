package dev.aurora.client.mixin;

import dev.aurora.client.util.BlurGuard;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Правило владения блюром: пока Aurora рисует свой блюр за меню,
 * ванильный renderBlurredBackground гасится по тумблеру — а опция
 * menuBackgroundBlurriness остаётся нетронутой (она персистится ванилью
 * в options.txt; портить её статикой нельзя — при краше юзерово значение
 * навсегда уходит в ноль).
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "renderBlurredBackground(Lnet/minecraft/client/gui/GuiGraphics;)V",
            at = @At("HEAD"), cancellable = true)
    private void aurora$suppressVanillaBlur(GuiGraphics g, CallbackInfo ci) {
        if (BlurGuard.shouldSuppressVanilla()) {
            ci.cancel();
        }
    }
}
