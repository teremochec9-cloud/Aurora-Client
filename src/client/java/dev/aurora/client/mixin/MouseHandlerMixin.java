package dev.aurora.client.mixin;

import dev.aurora.client.util.ClickTracker;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Трекер кликов для CPS и Keystrokes.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onButton", at = @At("HEAD"))
    private void aurora$onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        int button = info.button();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (action == GLFW.GLFW_PRESS) {
                ClickTracker.onPress(button);
            } else if (action == GLFW.GLFW_RELEASE) {
                ClickTracker.onRelease(button);
            }
        }
    }
}
