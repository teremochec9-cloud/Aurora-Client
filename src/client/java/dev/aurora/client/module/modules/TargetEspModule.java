package dev.aurora.client.module.modules;

import dev.aurora.client.hud.HudEditorScreen;
import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import dev.aurora.client.module.settings.ColorSetting;
import dev.aurora.client.module.settings.ModeSetting;
import dev.aurora.client.module.settings.NumberSetting;
import dev.aurora.client.theme.Theme;
import dev.aurora.client.util.Animation;
import dev.aurora.client.util.ColorUtil;
import dev.aurora.client.util.FrameClock;
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
 * Target ESP: подсветка целей в мире. Сущности проецируются на экран
 * (ванильный {@link GameRenderer#projectPointToScreen(Vec3)} с учётом FOV
 * и зума Aurora), поверх прямоугольника цели NanoVG рисует стиль.
 *
 * <p>Стиль «Круг» (по умолчанию) — фирменная подсветка: моб находится
 * внутри светящегося круга, круг плавно «дышит» вверх-вниз по синусоиде
 * (амплитуда подобрана так, что цель всегда остаётся внутри), свечение
 * размыто несколькими слоями колец и радиальным пятном. Внутри летают
 * две дуги-«кометы» навстречу друг другу, у ног цели — светящееся
 * кольцо-якорь в мировой проекции.</p>
 *
 * <p>Стиль «Призрак» — квадрат-подложка, два вращающихся ромба навстречу
 * друг другу, кольцо вдоль тела цели, светящийся диск под ногами и три
 * «призрака», кружащих вокруг противника. Все углы — от абсолютного
 * времени {@link FrameClock}: движение идеально плавное и не зависит
 * от тиков.</p>
 */
public class TargetEspModule extends Module {
    /** Максимум целей в режиме «Ближайшие». */
    private static final int MAX_TARGETS = 6;
    /** Отладка подбора целей (AURORA_ESP_DEBUG=1). */
    private static final boolean DEBUG = System.getenv("AURORA_ESP_DEBUG") != null;
    private long lastDebugLog;
    private long lastBboxLog;

    public final ModeSetting mode = new ModeSetting("Режим",
            new String[]{"Под прицелом", "Ближайшие"}, 0, "Кого подсвечивать");
    public final NumberSetting range = new NumberSetting("Дальность", 8.0f, 3.0f, 32.0f, 1.0f, 0,
            "Радиус поиска целей в блоках");
    public final ModeSetting style = new ModeSetting("Стиль",
            new String[]{"Круг", "Призрак", "Рамка", "Уголки", "Свечение"}, 0, "Как рисовать подсветку");
    public final NumberSetting circleRadius = new NumberSetting("Радиус круга", 1.05f, 0.9f, 1.8f, 0.05f, 2,
            "Насколько круг больше цели");
    public final NumberSetting speed = new NumberSetting("Скорость", 1.0f, 0.2f, 3.0f, 0.1f, 1,
            "Скорость полёта круга и дуг");
    public final BooleanSetting glow = new BooleanSetting("Свечение", true,
            "Мягкое размытое свечение вокруг круга");
    public final BooleanSetting customColor = new BooleanSetting("Свой цвет", false,
            "Использовать свой цвет вместо акцента темы");
    public final ColorSetting color = new ColorSetting("Цвет", 0xFF8B5CF6, "Цвет подсветки в стиле «Круг»");
    public final BooleanSetting playersOnly = new BooleanSetting("Только игроки", true,
            "В режиме «Ближайшие» показывать лишь игроков");
    public final BooleanSetting health = new BooleanSetting("Здоровье", true,
            "Полоска здоровья под рамкой");

    public TargetEspModule() {
        super("Target ESP", "Подсветка цели: круг с дугами, призраки, рамка, уголки", Category.VISUAL);
        addSettings(mode, range, style, circleRadius, speed, glow, customColor, color, playersOnly, health);
    }

    /** Состояние одной цели: альфа появления и сглаженное здоровье. */
    private static final class Target {
        final Animation alpha = new Animation(0f, 12);
        Animation hp;
    }

    private final Map<Integer, Target> states = new HashMap<>();
    /** Список целей переиспользуется между кадрами (без аллокаций в кадре). */
    private final List<Entity> targets = new ArrayList<>();
    private static final Comparator<Entity> DIST_ORDER =
            Comparator.comparingDouble(e -> e.position().distanceToSqr(Minecraft.getInstance().player.position()));

    /* Пул векторной математики: ноль аллокаций на цель за кадр. */
    private final Vector3f relV = new Vector3f();
    private final Vector3f viewV = new Vector3f();
    private final Quaternionf invRotQ = new Quaternionf();
    private final float[] diamond = new float[8];
    private final float[] diamond2 = new float[8];
    private final float[] ghostPts = new float[GHOST.length];
    /** Буфер дуг стиля «Круг» (до 34 точек). */
    private final float[] arcPts = new float[68];
    private float projX, projY;
    private Vec3 camPos;
    private float guiW, guiH;

    /** Силуэт призрака: купол + волнистый «хвост» (нормированные координаты). */
    private static final float[] GHOST = {
            -0.50f, 0.45f, -0.50f, -0.10f, -0.42f, -0.35f, -0.25f, -0.52f,
            0.00f, -0.58f, 0.25f, -0.52f, 0.42f, -0.35f, 0.50f, -0.10f,
            0.50f, 0.45f, 0.375f, 0.36f, 0.25f, 0.50f, 0.125f, 0.36f,
            0.00f, 0.50f, -0.125f, 0.36f, -0.25f, 0.50f, -0.375f, 0.36f
    };

    /**
     * Отрисовка подсветки. Вызывается из HUD-слоя Aurora каждый кадр.
     */
    public void renderTargets(GuiGraphics g, DeltaTracker dt, Theme theme, Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.screen instanceof HudEditorScreen) {
            return;
        }
        float pt = dt.getGameTimeDeltaPartialTick(!mc.isPaused());
        // ориентация камеры одна на кадр: инверсия в пуленепробиваемый вектор
        GameRenderer gr = mc.gameRenderer;
        camPos = gr.getMainCamera().position();
        gr.getMainCamera().rotation().conjugate(invRotQ);
        guiW = g.guiWidth();
        guiH = g.guiHeight();

        List<Entity> list = collectTargets(mc, pt);

        // альфа: цели из текущего набора плавно появляются,
        // выпавшие — плавно гаснут, затем удаляются из карты
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Entity e : list) {
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

        float tSec = FrameClock.now() / 1000.0f;
        for (Entity e : list) {
            try {
                drawTarget(g, e, pt, tSec, theme, mc);
            } catch (Exception ignored) {
                // проекция у края экрана/за камерой не должна ронять HUD
            }
        }
        if (DEBUG && FrameClock.now() - lastDebugLog > 2000) {
            lastDebugLog = FrameClock.now();
            org.slf4j.LoggerFactory.getLogger("Aurora/Test")
                    .info("AURORA_ESP_DEBUG: целей={}", list.size());
            if (!list.isEmpty()) {
                Entity e0 = list.get(0);
                org.slf4j.LoggerFactory.getLogger("Aurora/Test").info(
                        "AURORA_ESP_DEBUG: цель#0={} pos=({},{},{}) cam=({},{},{}) rot=({},{},{},{}) gui={}x{}",
                        e0.getName().getString(),
                        String.format("%.1f", e0.getX()), String.format("%.1f", e0.getY()),
                        String.format("%.1f", e0.getZ()),
                        String.format("%.1f", camPos.x), String.format("%.1f", camPos.y),
                        String.format("%.1f", camPos.z),
                        String.format("%.2f", mc.gameRenderer.getMainCamera().rotation().x),
                        String.format("%.2f", mc.gameRenderer.getMainCamera().rotation().y),
                        String.format("%.2f", mc.gameRenderer.getMainCamera().rotation().z),
                        String.format("%.2f", mc.gameRenderer.getMainCamera().rotation().w),
                        (int) guiW, (int) guiH);
            }
        }
    }

    /** Сбор целей по режиму (список переиспользуется). */
    private List<Entity> collectTargets(Minecraft mc, float pt) {
        double r2 = range.get() * range.get();
        targets.clear();
        if (mode.get().equals("Под прицелом")) {
            Entity e = mc.crosshairPickEntity;
            if (e instanceof LivingEntity le && le.isAlive() && !le.isSpectator()
                    && e != mc.player && e.position().distanceToSqr(mc.player.position()) <= r2) {
                targets.add(e);
            }
            return targets;
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
            targets.add(e);
        }
        targets.sort(DIST_ORDER);
        while (targets.size() > MAX_TARGETS) {
            targets.remove(targets.size() - 1);
        }
        return targets;
    }

    /**
     * Проекция мировой точки на экран. Пишет в projX/projY, возвращает
     * видимость (перед камерой и в пределах экрана с запасом).
     */
    private boolean project(GameRenderer gr, double wx, double wy, double wz) {
        relV.set((float) (wx - camPos.x), (float) (wy - camPos.y), (float) (wz - camPos.z));
        invRotQ.transform(relV, viewV);
        if (viewV.z > 0) {
            return false; // точка за камерой
        }
        Vec3 ndc = gr.projectPointToScreen(new Vec3(wx, wy, wz));
        projX = (float) ((ndc.x + 1.0) * 0.5 * guiW);
        projY = (float) ((1.0 - ndc.y) * 0.5 * guiH);
        return projX > -80 && projX < guiW + 80 && projY > -80 && projY < guiH + 80;
    }

    /** Проекция AABB цели на экран и отрисовка подсветки. */
    private void drawTarget(GuiGraphics g, Entity e, float pt, float tSec, Theme theme, Minecraft mc) {
        // интерполированный bbox: текущий AABB, доезжающий к позиции в кадре
        Vec3 interp = e.getPosition(pt);
        AABB bb = e.getBoundingBox().move(interp.subtract(e.position()));

        GameRenderer gr = mc.gameRenderer;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int frontCorners = 0;
        for (double cx = bb.minX; ; cx = bb.maxX) {
            for (double cy = bb.minY; ; cy = bb.maxY) {
                for (double cz = bb.minZ; ; cz = bb.maxZ) {
                    relV.set((float) (cx - camPos.x), (float) (cy - camPos.y), (float) (cz - camPos.z));
                    invRotQ.transform(relV, viewV);
                    if (viewV.z <= 0) {
                        frontCorners++;
                        Vec3 ndc = gr.projectPointToScreen(new Vec3(cx, cy, cz));
                        float sx = (float) ((ndc.x + 1.0) * 0.5 * guiW);
                        float sy = (float) ((1.0 - ndc.y) * 0.5 * guiH);
                        minX = Math.min(minX, sx);
                        maxX = Math.max(maxX, sx);
                        minY = Math.min(minY, sy);
                        maxY = Math.max(maxY, sy);
                    }
                    if (cz == bb.maxZ) {
                        break;
                    }
                }
                if (cy == bb.maxY) {
                    break;
                }
            }
            if (cx == bb.maxX) {
                break;
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
        if (DEBUG && FrameClock.now() - lastBboxLog > 2000) {
            lastBboxLog = FrameClock.now();
            org.slf4j.LoggerFactory.getLogger("Aurora/Test").info(
                    "AURORA_ESP_BBOX: {} corners={} bbox=({},{})-({},{})",
                    e.getName().getString(), frontCorners, x0, y0, x1, y1);
        }
        int bw = x1 - x0;
        int bh = y1 - y0;
        if (bw < 4 || bh < 6) {
            return;
        }
        if (x1 < 0 || x0 > guiW || y1 < 0 || y0 > guiH) {
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
            case "Круг" -> drawCircleStyle(g, e, bb, x0, y0, bw, bh, accent, accent2, A, tSec, gr);
            case "Уголки" -> drawCorners(g, x0, y0, bw, bh, accent, A);
            case "Свечение" -> drawGlow(g, x0, y0, bw, bh, accent, accent2, A);
            case "Рамка" -> drawFrame(g, x0, y0, bw, bh, accent, A);
            default -> drawGhostStyle(g, e, bb, x0, y0, bw, bh, accent, accent2, A, tSec, gr);
        }
        if (health.get() && e instanceof LivingEntity le) {
            drawHealth(g, st, le, x0, y0, bw, y1, accent, A);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Стиль «Круг»: светящийся круг вокруг цели, плавно летящий          */
    /*  вверх-вниз, с размытым свечением и дугами-«кометами»              */
    /* ------------------------------------------------------------------ */

    private void drawCircleStyle(GuiGraphics g, Entity e, AABB bb, int x, int y, int w, int h,
                                 int accent, int accent2, int A, float t, GameRenderer gr) {
        float k = A / 255f;
        int col = customColor.get() ? color.get() : accent;
        int col2 = customColor.get()
                ? ColorUtil.lerp(color.get(), ColorUtil.rgb(255, 255, 255), 0.30f)
                : accent2;
        float spd = Math.max(0.1f, speed.get());

        float cx = x + w / 2f;
        float cy = y + h / 2f;
        float halfDiag = (float) Math.sqrt(w * (float) w + h * (float) h) * 0.5f;

        // амплитуда полёта круга: заметная, но цель всегда внутри круга
        float amp = Math.max(2f, h * 0.15f);
        float R = Math.max(halfDiag * circleRadius.get(), h * 0.5f + amp + 3f);
        // кэп: у самой близкой цели круг не должен разрастаться на пол-экрана
        // и перекрывать HUD-панели (замечено на скриншоте смоук-теста)
        R = Math.min(R, guiH * 0.42f);
        // плавный полёт вверх-вниз (чистый синус от абсолютного времени)
        float bob = (float) Math.sin(t * 1.7f * spd) * amp;
        float rcy = cy + bob;
        // лёгкое «дыхание» радиуса — круг живой
        float breathe = 1f + 0.03f * (float) Math.sin(t * 2.3f * spd);
        R *= breathe;

        // ===== размытое свечение: радиальное пятно + широкие мягкие кольца =====
        if (glow.get()) {
            GfxEngine.radial(g, cx, rcy, R * 2.8f, ColorUtil.alpha(col, (int) (44 * k)), ColorUtil.alpha(col, 0));
            GfxEngine.ring(g, cx, rcy, (R + 6.5f) * 2, ColorUtil.alpha(col, (int) (32 * k)), 3.4f);
            GfxEngine.ring(g, cx, rcy, (R + 3.2f) * 2, ColorUtil.alpha(col, (int) (58 * k)), 2.2f);
        }

        // ===== основной круг: «размытый» гауссов ореол — несколько всё более
        // широких и всё более тусклых штрихов вместо одного резкого контура =====
        GfxEngine.ring(g, cx, rcy, R * 2, ColorUtil.alpha(col, (int) (48 * k)), 7.5f);
        GfxEngine.ring(g, cx, rcy, R * 2, ColorUtil.alpha(col, (int) (95 * k)), 4.4f);
        GfxEngine.ring(g, cx, rcy, R * 2, ColorUtil.alpha(col, (int) (205 * k)), 2.4f);
        // тонкое внутреннее колечко вторым цветом — глубина
        GfxEngine.ring(g, cx, rcy, (R - 3.2f) * 2, ColorUtil.alpha(col2, (int) (100 * k)), 0.9f);

        // ===== две дуги-«кометы», летящие по кругу навстречу друг другу =====
        float arcR = Math.max(6f, R - 4.5f);
        float headA = t * 1.9f * spd;
        float tailA = headA - 2.2f; // ~126°
        drawArc(g, cx, rcy, arcR, headA, tailA, ColorUtil.alpha(col, (int) (205 * k)), 1.9f);
        float headB = -t * 1.35f * spd + 2.4f;
        float tailB = headB - 1.45f;
        drawArc(g, cx, rcy, arcR * 0.99f, headB, tailB, ColorUtil.alpha(col2, (int) (150 * k)), 1.2f);
        // светящиеся «головки» комет
        GfxEngine.circle(g, cx + (float) Math.cos(headA) * arcR, rcy + (float) Math.sin(headA) * arcR,
                3.6f, ColorUtil.alpha(col, (int) (220 * k)));
        GfxEngine.circle(g, cx + (float) Math.cos(headA) * arcR, rcy + (float) Math.sin(headA) * arcR,
                1.7f, ColorUtil.rgba(255, 255, 255, (int) (215 * k)));
        GfxEngine.circle(g, cx + (float) Math.cos(headB) * arcR, rcy + (float) Math.sin(headB) * arcR,
                2.4f, ColorUtil.alpha(col2, (int) (185 * k)));

        // ===== кольцо-якорь у ног цели (мировая проекция) =====
        if (project(gr, e.getX(), bb.minY + 0.03, e.getZ())) {
            float rx = Math.max(7f, w * 0.40f);
            float ry = rx * 0.30f;
            GfxEngine.ellipse(g, projX, projY, rx * 1.4f, ry * 1.4f, ColorUtil.alpha(col, (int) (40 * k)), 2.6f);
            GfxEngine.ellipse(g, projX, projY, rx, ry, ColorUtil.alpha(col, (int) (150 * k)), 1.1f);
        }
    }

    /**
     * Дуга по окружности (по часовой при sweep &lt; 0). Точки пишутся в буфер
     * {@code arcPts} — без аллокаций.
     */
    private void drawArc(GuiGraphics g, float cx, float cy, float r, float headA, float tailA,
                         int argb, float thickness) {
        float sweep = tailA - headA;
        int n = Math.min(arcPts.length / 2, Math.max(6, (int) (Math.abs(sweep) / 0.22f) + 2));
        for (int i = 0; i < n; i++) {
            float f = i / (float) (n - 1);
            float a = headA + sweep * f;
            arcPts[i * 2] = cx + (float) Math.cos(a) * r;
            arcPts[i * 2 + 1] = cy + (float) Math.sin(a) * r;
        }
        GfxEngine.pathStroke(g, arcPts, n, argb, thickness, false);
    }

    /* ------------------------------------------------------------------ */
    /*  Стиль «Призрак»: ромбы + кольцо + орбитальные призраки            */
    /* ------------------------------------------------------------------ */

    private void drawGhostStyle(GuiGraphics g, Entity e, AABB bb, int x, int y, int w, int h,
                                int accent, int accent2, int A, float t, GameRenderer gr) {
        float k = A / 255f;
        int cx = x + w / 2;
        int cy = y + h / 2;

        // амбиентная аура вокруг цели
        GfxEngine.glow(g, x - 3, y - 3, w + 6, h + 6, 8, 10f, ColorUtil.alpha(accent, (int) (70 * k)));

        // квадрат-подложка (как на референсе) — тихий белый контур и заливка
        GfxEngine.rounded(g, x, y, w, h, 4, ColorUtil.alpha(accent, (int) (16 * k)));
        GfxEngine.outline(g, x, y, w, h, 4, ColorUtil.rgba(255, 255, 255, (int) (46 * k)), 1.2f);

        // два вращающихся ромба: внешний по часовой, внутренний против
        float diag = (float) Math.sqrt(w * (float) w + h * (float) h) * 0.5f;
        float pulse = (float) Math.sin(t * 2.6) * 2.2f;
        fillDiamond(diamond, cx, cy, diag + 6 + pulse, t * 0.9f);
        fillDiamond(diamond2, cx, cy, (diag + 6) * 0.62f + pulse * 0.6f, -t * 1.4f + 0.7f);
        GfxEngine.pathStroke(g, diamond, 4, ColorUtil.alpha(accent, (int) (215 * k)), 1.6f, true);
        GfxEngine.pathStroke(g, diamond2, 4, ColorUtil.alpha(accent2, (int) (135 * k)), 1.1f, true);
        // вершины ромбов — светящиеся точки
        GfxEngine.circle(g, diamond[0], diamond[1], 3.4f, ColorUtil.alpha(accent, (int) (200 * k)));
        GfxEngine.circle(g, diamond2[0], diamond2[1], 2.6f, ColorUtil.alpha(accent2, (int) (170 * k)));

        // кольцо, летящее вверх-вниз вдоль тела цели (мировая проекция)
        float bodyH = (float) (bb.maxY - bb.minY);
        float cycle = 0.5f + 0.5f * (float) Math.sin(t * 1.9);
        float worldY = (float) bb.minY + 0.08f * bodyH + cycle * 0.84f * bodyH;
        if (project(gr, e.getX(), worldY, e.getZ())) {
            float rx = w * 0.46f;
            float ry = rx * 0.30f;
            GfxEngine.ellipse(g, projX, projY, rx * 1.3f, ry * 1.3f, ColorUtil.alpha(accent, (int) (60 * k)), 1f);
            GfxEngine.ellipse(g, projX, projY, rx, ry, ColorUtil.alpha(accent, (int) (225 * k)), 1.8f);
        }
        // светящийся диск под ногами (референс: кольцо у стоп)
        if (project(gr, e.getX(), bb.minY + 0.03, e.getZ())) {
            float rx = w * 0.36f;
            float ry = rx * 0.30f;
            GfxEngine.ellipse(g, projX, projY, rx * 1.35f, ry * 1.35f, ColorUtil.alpha(accent, (int) (55 * k)), 2.6f);
            GfxEngine.ellipse(g, projX, projY, rx, ry, ColorUtil.alpha(accent, (int) (150 * k)), 1.2f);
        }

        // три призрака кружат вокруг противника
        float rWorld = (float) Math.max(0.8, e.getBbWidth() * 0.55 + 0.45);
        float midY = (float) ((bb.minY + bb.maxY) / 2.0);
        for (int i = 0; i < 3; i++) {
            float a = t * 1.35f + i * 2.094f;
            float bob = (float) Math.sin(t * 2.4 + i * 1.7) * 0.16f;
            double wx = e.getX() + Math.cos(a) * rWorld;
            double wz = e.getZ() + Math.sin(a) * rWorld;
            double wy = midY + bob;
            if (!project(gr, wx, wy, wz)) {
                continue;
            }
            float pulseG = 0.55f + 0.45f * (float) Math.sin(t * 3.1 + i * 2.1);
            drawGhost(g, projX, projY - w * 0.09f, w, accent, accent2, k * pulseG);
        }
    }

    /** Один призрак: аура, силуэт и глазки. s — масштаб в gui-пикселях. */
    private void drawGhost(GuiGraphics g, float gx, float gy, float rectW, int accent, int accent2, float k) {
        float s = Math.max(4.5f, Math.min(10f, rectW * 0.17f));
        // мягкая аура
        GfxEngine.circle(g, gx, gy, s * 2.2f, ColorUtil.alpha(accent, (int) (48 * k)));
        // силуэт
        for (int i = 0; i < GHOST.length; i += 2) {
            ghostPts[i] = gx + GHOST[i] * s;
            ghostPts[i + 1] = gy + GHOST[i + 1] * s;
        }
        int body = ColorUtil.lerp(ColorUtil.rgb(255, 255, 255), accent, 0.30f);
        GfxEngine.pathFill(g, ghostPts, GHOST.length / 2, ColorUtil.alpha(body, (int) (215 * k)));
        // глазки
        int eye = ColorUtil.rgba(14, 15, 24, (int) (235 * k));
        GfxEngine.circle(g, gx - s * 0.17f, gy - s * 0.12f, Math.max(1.1f, s * 0.15f), eye);
        GfxEngine.circle(g, gx + s * 0.17f, gy - s * 0.12f, Math.max(1.1f, s * 0.15f), eye);
    }

    /** Заполняет 4 вершины ромба с центром (cx,cy), радиусом r и поворотом rot. */
    private static void fillDiamond(float[] out, float cx, float cy, float r, float rot) {
        for (int i = 0; i < 4; i++) {
            float a = rot + i * 1.5707964f; // π/2
            out[i * 2] = cx + (float) Math.cos(a) * r;
            out[i * 2 + 1] = cy + (float) Math.sin(a) * r;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Статические стили                                                 */
    /* ------------------------------------------------------------------ */

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
