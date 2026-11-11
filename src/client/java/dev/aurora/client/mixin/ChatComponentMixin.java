package dev.aurora.client.mixin;

import dev.aurora.client.module.modules.ChatModule;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * «Без затухания»: сообщения в чате не бледнеют со временем.
 *
 * <p>Vanilla считает альфу как timeBased(текущийТик): лямбда внутри берёт
 * разницу (текущийТик - времяСообщения). Если передать 0, разница всегда
 * отрицательна и альфа остаётся 1.0 — сообщение никогда не затухает.</p>
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @ModifyArg(
            method = "render(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ChatComponent$AlphaCalculator;timeBased(I)Lnet/minecraft/client/gui/components/ChatComponent$AlphaCalculator;"
            ),
            require = 0
    )
    private int aurora$noFade(int tick) {
        return ChatModule.shouldDisableFade() ? 0 : tick;
    }
}
