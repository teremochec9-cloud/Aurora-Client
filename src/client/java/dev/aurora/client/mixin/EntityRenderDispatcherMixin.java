package dev.aurora.client.mixin;

import dev.aurora.client.util.CullEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Окклюжн-куллинг Aurora (модуль «Оптимизация»): сущность, полностью
 * закрытая непрозрачными блоками, пропускается после того, как ванильные
 * проверки дистанции и фрустума уже прошли. Врезка в RETURN и только
 * true→false: мы можем лишь снять сущность с рендера, никогда не
 * показать лишнюю.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private <E extends Entity> void aurora$occlusionCull(E entity, Frustum frustum,
                                                         double camX, double camY, double camZ,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && CullEngine.isOccluded(entity, camX, camY, camZ)) {
            cir.setReturnValue(false);
        }
    }
}
