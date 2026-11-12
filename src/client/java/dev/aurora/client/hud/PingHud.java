package dev.aurora.client.hud;

import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Пинг до сервера.
 */
public class PingHud extends HudModule {

    public PingHud() {
        super("Пинг", "Задержка до сервера в миллисекундах", 0.005f, 0.150f);
    }

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        return RenderUtil.width(mc.font, "Пинг: 000 ms", theme()) + 10;
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
        String value = cached("ping", 500, () -> {
            int p = WatermarkHud.ping(mc);
            return p < 0 ? "—" : p + " ms";
        });
        int ping = WatermarkHud.ping(mc);
        int color = ping < 0 ? theme.textSecondary() : ping < 80 ? ColorUtil.rgb(134, 239, 172)
                : ping < 160 ? ColorUtil.rgb(253, 224, 71) : ColorUtil.rgb(252, 165, 165);
        RenderUtil.drawText(g, font, "Пинг: ", 5, 3, theme.textSecondary(), theme);
        RenderUtil.drawText(g, font, value, 5 + RenderUtil.width(font, "Пинг: ", theme), 3, color, theme);
    }
}
