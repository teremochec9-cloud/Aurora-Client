package dev.aurora.client.hud;

import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Счётчик FPS.
 */
public class FpsHud extends HudModule {

    public FpsHud() {
        super("FPS", "Количество кадров в секунду", 0.005f, 0.075f);
    }

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        // из кэша: при fps >= 1000 живое значение шире запасного "FPS: 000"
        // и текст вылезал за капсулу
        String fpsStr = cached("fps", 250, () -> String.valueOf(mc.getFps()));
        return RenderUtil.width(mc.font, "FPS: ", theme())
                + RenderUtil.width(mc.font, fpsStr, theme()) + 10;
    }

    @Override
    public int height() {
        return 15;
    }

    private Theme theme() {
        return dev.aurora.client.AuroraClient.theme();
    }

    @Override
    protected void renderContent(GuiGraphics g, Font font, DeltaTracker dt, Theme theme, Minecraft mc) {
        String fpsStr = cached("fps", 250, () -> String.valueOf(mc.getFps()));
        int fps = mc.getFps();
        int color = fps >= 120 ? ColorUtil.rgb(134, 239, 172) : fps >= 60 ? theme.textPrimary() : ColorUtil.rgb(252, 165, 165);
        RenderUtil.drawText(g, font, "FPS: ", 5, 3, theme.textSecondary(), theme);
        RenderUtil.drawText(g, font, fpsStr, 5 + RenderUtil.width(font, "FPS: ", theme), 3, color, theme);
    }
}
