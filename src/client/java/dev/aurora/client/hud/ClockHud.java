package dev.aurora.client.hud;

import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Реальное время на часах.
 */
public class ClockHud extends HudModule {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");

    public ClockHud() {
        super("Часы", "Текущее реальное время", 0.5f, 0.005f);
        anchorX = 1;
    }

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        return RenderUtil.width(mc.font, "00:00", theme()) + 10;
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
        String time = cached("clock", 1000, () -> LocalTime.now().format(FMT));
        RenderUtil.drawText(g, font, time, 5, 3, theme.textPrimary(), theme);
    }
}
