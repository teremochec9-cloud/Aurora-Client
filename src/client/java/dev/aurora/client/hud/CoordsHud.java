package dev.aurora.client.hud;

import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Текущие координаты игрока.
 */
public class CoordsHud extends HudModule {

    public CoordsHud() {
        super("Координаты", "Позиция XYZ", 0.005f, 0.225f);
    }

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        // из кэша той же строки, что и рендер — капсула всегда вмещает координаты
        String pos = cached("coords", 100, () -> {
            if (mc.player != null) {
                return String.format("%d / %d / %d",
                        (int) Math.floor(mc.player.getX()),
                        (int) Math.floor(mc.player.getY()),
                        (int) Math.floor(mc.player.getZ()));
            }
            return "—";
        });
        return RenderUtil.width(mc.font, "XYZ: ", theme())
                + RenderUtil.width(mc.font, pos, theme()) + 10;
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
        String pos = cached("coords", 100, () -> {
            if (mc.player != null) {
                return String.format("%d / %d / %d",
                        (int) Math.floor(mc.player.getX()),
                        (int) Math.floor(mc.player.getY()),
                        (int) Math.floor(mc.player.getZ()));
            }
            return "—";
        });
        RenderUtil.drawText(g, font, "XYZ: ", 5, 3, theme.textSecondary(), theme);
        RenderUtil.drawText(g, font, pos, 5 + RenderUtil.width(font, "XYZ: ", theme), 3, theme.textPrimary(), theme);
    }
}
