package dev.aurora.client.hud;

import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.ClickTracker;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Количество кликов в секунду (ЛКМ + ПКМ).
 */
public class CpsHud extends HudModule {

    public CpsHud() {
        super("CPS", "Клики в секунду", 0.005f, 0.300f);
    }

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        // 3 цифры CPS + запас справа
        return RenderUtil.width(mc.font, "CPS: 000", theme()) + 12;
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
        String cpsStr = cached("cps", 250, () -> String.valueOf(ClickTracker.leftCps() + ClickTracker.rightCps()));
        RenderUtil.drawText(g, font, "CPS: ", 5, 3, theme.textSecondary(), theme);
        RenderUtil.drawText(g, font, cpsStr, 5 + RenderUtil.width(font, "CPS: ", theme), 3, theme.accentNow(), theme);
    }
}
