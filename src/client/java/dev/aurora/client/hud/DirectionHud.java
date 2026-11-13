package dev.aurora.client.hud;

import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.RenderUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Сторона света и угол взгляда.
 */
public class DirectionHud extends HudModule {
    private static final String[] DIRS = {"Юг", "ЮЗ", "Запад", "СЗ", "Север", "СВ", "Восток", "ЮВ"};

    public DirectionHud() {
        // 0.955, а не 0.99: «призрачная» капсула с подписью «(выкл)» в редакторе
        // шире самой панели — при 0.99 клэмп прижимал её к правому краю вплотную
        super("Сторона света", "Куда смотришь: Север / Юг / ...", 0.955f, 0.008f);
        anchorX = 2;
    }

    @Override
    public int width() {
        Minecraft mc = Minecraft.getInstance();
        // «Восток» — самое длинное направление: капсула не прыгает при повороте
        return RenderUtil.width(mc.font, "Восток (+000°)", theme()) + 12;
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
        String text = cached("dir", 100, () -> {
            if (mc.player != null) {
                float yaw = mc.player.getYRot();
                int idx = ((int) Math.round(yaw / 45.0f) & 7);
                return DIRS[idx] + String.format(" (%+d°)", Math.round(Math.floorMod((int) yaw + 180, 360) - 180));
            }
            return "—";
        });
        RenderUtil.drawText(g, font, text, 5, 3, theme.textPrimary(), theme);
    }
}
