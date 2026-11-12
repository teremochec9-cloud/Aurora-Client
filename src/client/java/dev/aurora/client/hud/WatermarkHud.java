package dev.aurora.client.hud;

import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.ClickTracker;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * Водяной знак клиента: название с градиентом, FPS и пинг.
 */
public class WatermarkHud extends HudModule {

    public WatermarkHud() {
        super("Водяной знак", "Логотип клиента с FPS и пингом", 0.005f, 0.005f);
    }

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        String rest = "  " + mc.getFps() + " fps  " + pingText(mc);
        return RenderUtil.width(mc.font, Theme.CLIENT_NAME, theme()) + RenderUtil.width(mc.font, rest, theme()) + 10;
    }

    @Override
    public int height() {
        return 15;
    }

    private Theme theme() {
        return dev.aurora.client.AuroraClient.theme();
    }

    private String pingText(Minecraft mc) {
        int ping = ping(mc);
        return ping < 0 ? "—" : ping + " ms";
    }

    static int ping(Minecraft mc) {
        try {
            if (mc.getConnection() != null && mc.player != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (info != null) {
                    return info.getLatency();
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    @Override
    protected void renderContent(GuiGraphics g, Font font, DeltaTracker dt, Theme theme, Minecraft mc) {
        int accentA = theme.accentNow();
        int accentB = theme.accent(1.0f);
        int x = 6;
        x += RenderUtil.drawGradientText(g, font, Theme.CLIENT_NAME, x, 3, accentA, accentB, theme) + 6;
        String rest = cached("wm_rest", 500, () -> mc.getFps() + " fps  " + pingText(mc));
        RenderUtil.drawText(g, font, rest, x, 3, theme.textSecondary(), theme);
    }
}
