package dev.aurora.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.aurora.client.util.FrameClock;
import dev.aurora.client.util.GfxEngine;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Момент отрисовки Aurora: сразу после того, как ванильный GUI-рендерер
 * выполнил все слои интерфейса (мир, блюр, HUD, экраны, подсказки),
 * мы открываем свой render pass на главный фреймбуфер и рисуем всё
 * содержимое Aurora через NanoVG — поверх всего, без конфликтов сортировки.
 *
 * <p>Здесь же начинается «время кадра» {@link FrameClock}: одно чтение
 * системных часов на весь кадр — все анимации и эффекты клиента
 * (дым, наведение, переходы) движутся синхронно.</p>
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"))
    private void aurora$beginFrame(GpuBufferSlice slice, CallbackInfo ci) {
        FrameClock.refresh();
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("TAIL"))
    private void aurora$flushNanoVG(GpuBufferSlice slice, CallbackInfo ci) {
        GfxEngine.flushFrame();
    }
}
