package dev.aurora.client.mixin;

import dev.aurora.client.module.modules.ItemOptimizeModule;
import dev.aurora.client.module.modules.OptimizeModule;
import dev.aurora.client.util.CullEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Две клиентские оптимизации рендера сущностей (обе — срез true→false,
 * лишнюю сущность показать нельзя):
 * 1) окклюжн-куллинг Aurora («Оптимизация»): сущность, полностью закрытая
 *    непрозрачными блоками, пропускается после ванильных проверок;
 * 2) оптимизация дропов: выпавшие предметы дальше выбранной дистанции
 *    не рендерятся (светящиеся не трогаем — игрок ищет их взглядом).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private <E extends Entity> void aurora$occlusionCull(E entity, Frustum frustum,
                                                         double camX, double camY, double camZ,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (CullEngine.isOccluded(entity, camX, camY, camZ)) {
            cir.setReturnValue(false);
            return;
        }
        // «Дальность сущностей»: далеко от камеры — не рисуем
        // (игроки и светящиеся не скрываются — см. OptimizeModule)
        if (OptimizeModule.shouldCullFar(entity, entity.distanceToSqr(camX, camY, camZ))) {
            cir.setReturnValue(false);
            return;
        }
        // «Оптимизация дропов»: предмет далеко от камеры — не рисуем
        if (entity instanceof ItemEntity
                && ItemOptimizeModule.shouldCull(entity.distanceToSqr(camX, camY, camZ),
                entity.isCurrentlyGlowing())) {
            cir.setReturnValue(false);
        }
    }
}
