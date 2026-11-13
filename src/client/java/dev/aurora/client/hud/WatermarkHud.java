package dev.aurora.client.hud;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.util.ClickTracker;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * Водяной знак клиента: название с градиентом, опционально — FPS и пинг.
 *
 * <p>Ширина считается из той же закэшированной строки, что и рисуется:
 * капсула всегда совпадает с текстом без переполнения и без «дыхания».</p>
 */
public class WatermarkHud extends HudModule {
    public final BooleanSetting showFps = new BooleanSetting("FPS", true, "Показывать счётчик FPS");
    public final BooleanSetting showPing = new BooleanSetting("Пинг", true, "Показывать задержку");

    public WatermarkHud() {
        super("Водяной знак", "Логотип клиента с FPS и пингом", 0.005f, 0.005f);
        addSettings(showFps, showPing);
    }

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        // +15: 6 слева + 6 после имени + 3 запаса — текст не упирается в край капсулы
        return RenderUtil.width(mc.font, Theme.CLIENT_NAME, theme())
                + RenderUtil.width(mc.font, rest(mc), theme()) + 15;
    }

    @Override
    public int height() {
        return 15;
    }

    private Theme theme() {
        return dev.aurora.client.AuroraClient.theme();
    }

    /** Хвост «  123 fps  45 ms» — та же закэшированная строка, что и в рендере. */
    private String rest(Minecraft mc) {
        return cached("wm_rest", 500, () -> {
            StringBuilder sb = new StringBuilder();
            if (showFps.get()) {
                sb.append("  ").append(mc.getFps()).append(" fps");
            }
            if (showPing.get()) {
                int ping = ping(mc);
                sb.append("  ").append(ping < 0 ? "—" : ping + " ms");
            }
            return sb.toString();
        });
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
        RenderUtil.drawText(g, font, rest(mc), x, 3, theme.textSecondary(), theme);
    }
}
