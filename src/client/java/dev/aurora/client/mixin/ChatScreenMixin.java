package dev.aurora.client.mixin;

import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.modules.ChatModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ctrl+C в чате с пустой строкой ввода копирует последнее сообщение.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void aurora$copyLast(KeyEvent e, CallbackInfoReturnable<Boolean> cir) {
        ChatModule chat = ModuleManager.get(ChatModule.class);
        if (chat == null || !chat.isEnabled() || !chat.ctrlCopy.get()) {
            return;
        }
        if (e.key() != GLFW.GLFW_KEY_C || (e.modifiers() & GLFW.GLFW_MOD_CONTROL) == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ChatComponent chatHud = mc.gui.getChat();
        var messages = ((ChatComponentAccessor) chatHud).aurora$getAllMessages();
        if (messages.isEmpty()) {
            return;
        }
        String text = messages.get(messages.size() - 1).content().getString();
        mc.keyboardHandler.setClipboard(text);
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§a[Aurora] §fСкопировано: §7" + text), false);
        }
        cir.setReturnValue(true);
    }
}
