package dev.aurora.client.module.modules;

import dev.aurora.client.hud.HudEditorScreen;
import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.ModeSetting;
import dev.aurora.client.module.settings.NumberSetting;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.GfxEngine;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Target ESP: подсветка целей в мире — рамка, уголки или свечение вокруг
 * сущности, на которую смотришь (или ближайших, по режиму).
 *
 * <p>Как это работает: сущности не рисуются в мире (там конфликтно с
 * пайплайном рендера 1.21.11), а проецируются на экран. Ванильный
 * {@link GameRenderer#projectPointToScreen(Vec3)} переводит точку мира в
 * координаты экрана с учётом FOV (включая зум Aurora) и поворота камеры.
 * 8 углов AABB цели превращаются в прямоугольник на экране, поверх которого
 * NanoVG-движок рисует гладкие скруглённые формы с мягким свечением.</p>
 *
 * <p>Появление/исчезновение подсветки плавное (по экспоненте), полоска
 * здоровья тоже анимированная — счётчик «доезжает» до нового значения.</p>
 */
public class TargetEspModule extends Module {
    /** Максимум целей в режиме «Ближайшие». */
    private static final int MAX_TARGETS = 6;
    /** Отладка подбора целей (AURORA_ESP_DEBUG=1). */
    private static final boolean DEBUG = System.getenv("AURORA_ESP_DEBUG") != null;
    private long lastDebugLog;

    public final ModeSetting mode = new ModeSetting("Режим",
            new String[]{"Под прицелом", "Ближайшие"}, 0, "Кого подсвечивать");
    public final NumberSetting range = new NumberSetting("Дальность", 8.0f, 3.0f, 32.0f, 1.0f, 0,
            "Радиус поиска целей в блоках");
    public final ModeSetting style = new ModeSetting("Стиль",
            new String[]{"Рамка", "Уголки", "Свечение"}, 0, "Как рисовать подсветку");
    public final BooleanSetting playersOnly = new BooleanSetting("Только игроки", true,
            "В режиме «Ближайшие» показывать лишь игроков");
    public final BooleanSetting health = new BooleanSetting("Здоровье", true,
            "Полоска здоровья под рамкой");

    public TargetEspModule() {
        super("Target ESP", "Подсветка цели: рамка, уголки или свечение", Category.VISUAL);
        addSettings(mode, range, style, playersOnly, health);
    }

    /** Состояние одной цели: альфа появления и сглаженное здоровье. */
    private static final class Target {
        final Animation alpha = new Animation(0f, 12);
        Animation hp;
    }

    private final Map<Integer, Target> states = new HashMap<>();

    /**
     * Отрисовка подсветки. Вызывается из HUD-слоя Aurora каждый кадр.
     */
    public void renderTargets(GuiGraphics g, DeltaTracker dt, Theme theme, Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.screen instanceof HudEditorScreen) {
            return;
        }
        float pt = dt.getGameTimeDeltaPartialTick(!mc.isPaused());
        List<Entity> targets = collectTargets(mc);

        if (DEBUG && System.currentTimeMillis() - lastDebugLog > 2000) {
            lastDebugLog = System.currentTimeMillis();
            org.slf4j.LoggerFactory.getLogger("Aurora/Test")
                    .info("AURORA_ESP_DEBUG: целей={}", targets.size());
        }

        // альфа: цели из текущего набора плавно появляются,
        // выпавшие — плавно гаснут, затем удаляются из карты
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Entity e : targets) {
            seen.add(e.getId());
            states.computeIfAbsent(e.getId(), k -> new Target()).alpha.to(1f);
        }
        states.entrySet().removeIf(entry -> {
            Target t = entry.getValue();
            if (!seen.contains(entry.getKey())) {
                t.alpha.to(0f);
                if (t.alpha.isDone()) {
                    return true;
                }
            }
            t.alpha.update();
            return false;
        });

        for (Entity e : targets) {
            try {
                drawTarget(g, e, pt, theme, mc);
            } catch (Exception ignored) {
                // проекция у края экрана/за камерой не должна ронять HUD
            }
        }
    }

    /** Сбор целей по режиму. */
    private List<Entity> collectTargets(Minecraft mc) {
        double r2 = range.get() * range.get();
        List<Entity> out = new ArrayList<>();
        if (mode.get().equals("Под прицелом")) {
            Entity e = mc.crosshairPickEntity;
            if (e instanceof LivingEntity le && le.isAlive() && !le.isSpectator()
                    && e != mc.player && e.position().distanceToSqr(mc.player.position()) <= r2) {
                out.add(e);
            }
            return out;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || e == mc.player) {
                continue;
            }
            if (playersOnly.get() && !(e instanceof Player)) {
                continue;
            }
            if (e.position().distanceToSqr(mc.player.position()) > r2) {
                continue;
            }
            out.add(e);
        }
        out.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(mc.player.position())));
        if (out.size() > MAX_TARGETS) {
            out = out.subList(0, MAX_TARGETS);
        }
        return out;
    }

    /** Проекция AABB цели на экран и отрисовка подсветки. */
    private void drawTarget(GuiGraphics g, Entity e, float pt, Theme theme, Minecraft mc) {
        // интерполированный bbox: текущий AABB, доезжающий к позиции в кадре
        Vec3 interp = e.getPosition(pt);
        AABB bb = e.getBoundingBox().move(interp.subtract(e.position()));

        GameRenderer gr = mc.gameRenderer;
        Vec3 camPos = gr.getMainCamera().position();
        // ориентация камеры: точка «впереди» — с отрицательным Z во вью-пространстве
        Quaternionf invRot = gr.getMainCamera().rotation().conjugate(new Quaternionf());

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int frontCorners = 0;
        double[] xs = {bb.minX, bb.maxX};
        double[] ys = {bb.minY, bb.maxY};
        double[] zs = {bb.minZ, bb.maxZ};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    Vector3f rel = new Vector3f((float) (x - camPos.x), (float) (y - camPos.y), (float) (z - camPos.z));
                    Vector3f view = invRot.transform(rel, new Vector3f());
                    if (view.z > 0) {
                        continue; // угол за камерой
                    }
                    frontCorners++;
                    Vec3 ndc = gr.projectPointToScreen(new Vec3(x, y, z));
                    float sx = (float) ((ndc.x + 1.0) * 0.5 * g.guiWidth());
                    float sy = (float) ((1.0 - ndc.y) * 0.5 * g.guiHeight());
                    minX = Math.min(minX, sx);
                    maxX = Math.max(maxX, sx);
                    minY = Math.min(minY, sy);
                    maxY = Math.max(maxY, sy);
                }
            }
        }
        // если ни один угол не впереди — цель за камерой
        if (frontCorners == 0) {
            return;
        }

        int pad = 3;
        int x0 = Math.round(minX) - pad;
        int y0 = Math.round(minY) - pad;
        int x1 = Math.round(maxX) + pad;
        int y1 = Math.round(maxY) + pad;
        int bw = x1 - x0;
        int bh = y1 - y0;
        if (bw < 4 || bh < 6) {
            return;
        }
        if (x1 < 0 || x0 > g.guiWidth() || y1 < 0 || y0 > g.guiHeight()) {
            return; // целиком вне экрана
        }

        Target st = states.get(e.getId());
        float alpha = st != null ? Animation.smooth(Math.max(0f, Math.min(1f, st.alpha.get()))) : 0f;
        if (alpha < 0.02f) {
            return;
        }
        int A = Math.round(alpha * 255f);
        int accent = theme.accentNow();
        int accent2 = theme.accent(1.0f);

        switch (style.get()) {
            case "Уголки" -> drawCorners(g, x0, y0, bw, bh, accent, A);
            case "Свечение" -> drawGlow(g, x0, y0, bw, bh, accent, accent2, A);
            default -> drawFrame(g, x0, y0, bw, bh, accent, A);
        }
        if (health.get() && e instanceof LivingEntity le) {
            drawHealth(g, st, le, x0, y0, bw, y1, accent, A);
        }
    }

    /** Стиль «Рамка»: скруглённый контур с заливкой и мягким свечением. */
    private void drawFrame(GuiGraphics g, int x, int y, int w, int h, int accent, int A) {
        GfxEngine.glow(g, x - 4, y - 4, w + 8, h + 8, 6, 8f, ColorUtil.alpha(accent, (int) (60 * A / 255f)));
        GfxEngine.rounded(g, x, y, w, h, 5, ColorUtil.alpha(accent, (int) (24 * A / 255f)));
        GfxEngine.outline(g, x, y, w, h, 5, ColorUtil.alpha(accent, (int) (235 * A / 255f)), 1.5f);
    }

    /** Стиль «Уголки»: четыре акцентных уголка + едва заметный контур. */
    private void drawCorners(GuiGraphics g, int x, int y, int w, int h, int accent, int A) {
        GfxEngine.outline(g, x, y, w, h, 5, ColorUtil.rgba(255, 255, 255, (int) (36 * A / 255f)), 1f);
        float len = Math.max(6f, Math.min(w, h) * 0.3f);
        float th = 2f;
        float a2 = 255f * A / 255f;
        // верх-лево, верх-право, низ-лево, низ-право
        GfxEngine.rounded(g, x - 1, y - 1, len, th, 1, ColorUtil.alpha(accent, (int) a2));
        GfxEngine.rounded(g, x - 1, y - 1, th, len, 1, ColorUtil.alpha(accent, (int) a2));
        GfxEngine.rounded(g, x + w + 1 - len, y - 1, len, th, 1, ColorUtil.alpha(accent, (int) a2));
        GfxEngine.rounded(g, x + w + 1 - th, y - 1, th, len, 1, ColorUtil.alpha(accent, (int) a2));
        GfxEngine.rounded(g, x - 1, y + h + 1 - th, len, th, 1, ColorUtil.alpha(accent, (int) a2));
        GfxEngine.rounded(g, x - 1, y + h + 1 - len, th, len, 1, ColorUtil.alpha(accent, (int) a2));
        GfxEngine.rounded(g, x + w + 1 - len, y + h + 1 - th, len, th, 1, ColorUtil.alpha(accent, (int) a2));
        GfxEngine.rounded(g, x + w + 1 - th, y + h + 1 - len, th, len, 1, ColorUtil.alpha(accent, (int) a2));
    }

    /** Стиль «Свечение»: мягкое акцентное облако вокруг цели. */
    private void drawGlow(GuiGraphics g, int x, int y, int w, int h, int accent, int accent2, int A) {
        float k = A / 255f;
        GfxEngine.glow(g, x - 8, y - 8, w + 16, h + 16, 10, 12f, ColorUtil.alpha(accent, (int) (110 * k)));
        GfxEngine.roundedGradient(g, x, y, w, h, 7,
                ColorUtil.alpha(accent, (int) (34 * k)), ColorUtil.alpha(accent2, (int) (18 * k)));
        GfxEngine.outline(g, x, y, w, h, 7, ColorUtil.rgba(255, 255, 255, (int) (70 * k)), 1f);
    }

    /** Полоска здоровья: плавно доезжает до нового значения. */
    private void drawHealth(GuiGraphics g, Target st, LivingEntity le, int x, int y, int w, int yBottom, int accent, int A) {
        float frac = Math.max(0f, Math.min(1f, le.getHealth() / Math.max(1f, le.getMaxHealth())));
        if (st.hp == null) {
            st.hp = new Animation(frac, 10);
        }
        float shown = st.hp.apply(frac);
        int barW = Math.min(54, Math.max(24, w / 2));
        int bx = x + (w - barW) / 2;
        int by = yBottom + 4;
        GfxEngine.rounded(g, bx, by, barW, 3, 1.5f, ColorUtil.rgba(5, 6, 10, (int) (150 * A / 255f)));
        int fillW = Math.max(3, Math.round(barW * shown));
        // цвет: зелёный при полном здоровье -> акцент -> красный на минимуме
        int col;
        if (shown > 0.5f) {
            col = ColorUtil.lerp(accent, ColorUtil.rgb(134, 239, 172), (shown - 0.5f) * 2f);
        } else {
            col = ColorUtil.lerp(ColorUtil.rgb(248, 113, 113), accent, shown * 2f);
        }
        GfxEngine.rounded(g, bx, by, fillW, 3, 1.5f, ColorUtil.alpha(col, Math.round(255f * A / 255f)));
    }
}
