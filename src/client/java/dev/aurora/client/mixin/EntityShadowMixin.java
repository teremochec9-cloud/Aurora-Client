package dev.aurora.client.mixin;

import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.modules.EntityShadowsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * «Тени сущностей»: модуль отменяет извлечение теней на этапе экстракции
 * рендер-состояния. Тень рисуется из {@code EntityRenderState.shadowPieces},
 * которые заполняет {@code EntityRenderer.extractShadow} — отмена HEAD
 * убирает и затраты на сэмплирование блоков, и саму отрисовку.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityShadowMixin {

    @Inject(method = "extractShadow", at = @At("HEAD"), cancellable = true)
    private void aurora$noShadows(EntityRenderState state, Minecraft minecraft, Level level, CallbackInfo ci) {
        EntityShadowsModule m = ModuleManager.get(EntityShadowsModule.class);
        if (m != null && m.isEnabled()) {
            ci.cancel();
        }
    }
}
