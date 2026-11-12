package dev.aurora.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * EditBox без ванильной отрисовки: renderWidget() — заглушка.
 *
 * Фокус, клики, charTyped/keyPressed, Ctrl+A/C/V/X, выделение и позиция
 * каретки живут в ванильном состоянии виджета; текст, курсор и выделение
 * рисует наш движок из getValue()/getCursorPosition()/getHighlightPos().
 *
 * visible=true обязателен: EditBox.charTyped требует isActive() =
 * visible && active — при setVisible(false) каждый символ молча
 * отбрасывался (поле «съедало» ввод).
 */
public class AuroraTextField extends EditBox {
    public AuroraTextField(Font font, int x, int y, int width, int height, Component hint) {
        super(font, x, y, width, height, hint);
    }

    /** Никакой ванильной графики — иначе она дублирует наш рендер.
     *  В 1.21.11 renderWidget — public. */
    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }
}
