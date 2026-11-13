package dev.aurora.client.util;

import dev.aurora.client.module.ModuleManager;
import dev.aurora.client.module.modules.OptimizeModule;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

/**
 * Окклюжн-куллинг сущностей. Вызывается из
 * {@code EntityRenderDispatcherMixin} после того, как ванильные проверки
 * (дистанция, фрустум) уже пропустили сущность: мы лишь дополнительно
 * снимаем те, что полностью закрыты блоками.
 *
 * <p>Метод: лучевая развёртка от камеры к ключевым точкам AABB (центр,
 * низ, верх, а на дистанции — боковые углы). Препятствие — только
 * непрозрачный полный куб ({@code isSolidRender}): стекло и листва
 * прозрачны для проверки, ровно как и для взгляда. Шаг семпла 0.45 блока.</p>
 *
 * <p>Результат кэшируется по id сущности на ~0.5 с (со сдвигом фаз, чтобы
 * проверки всех сущностей не совпадали в один кадр); при движении
 * сущности или камеры кэш перестраивается. Сущности с glow/невидимые/
 * зрители/цель внутри нашего AABB не куллятся никогда — это же правило
 * защищает сквозь-стенные хайлайты и ездовые животные.</p>
 */
public final class CullEngine {
    private static final class Entry {
        boolean occluded;
        long nextCheckMs;
        double lx, ly, lz;
        double cx, cy, cz;
    }

    private static final Map<Integer, Entry> CACHE = new HashMap<>();
    /** Один переиспользуемый курсор: ноль аллокаций BlockPos на семпл. */
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static OptimizeModule module;

    /** Камера стоит внутри непрозрачного блока — в такой кадр не вмешиваемся. */
    private static long camCheckedAt;
    private static boolean camInOpaque;

    private CullEngine() {
    }

    /**
     * Скрыта ли сущность блоками. Вызывается на рендер-потоке для каждой
     * сущности, прошедшей ванильный отбор; быстрый путь — одна проверка кэша.
     */
    public static boolean isOccluded(Entity e, double camX, double camY, double camZ) {
        if (module == null) {
            module = ModuleManager.get(OptimizeModule.class);
        }
        if (module == null || !module.isEnabled() || !module.entityCulling.get()) {
            return false;
        }
        // всё, что видно «сквозь стены» или рендерится особым образом, не трогаем
        if (e.isCurrentlyGlowing() || e.isSpectator() || e.isInvisible()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        // камера внутри AABB (оседлали/внутри) — сущность вокруг нас, не куллим
        if (e.getBoundingBox().contains(camX, camY, camZ)) {
            return false;
        }
        double dx = camX - e.getX();
        double dy = camY - e.getY();
        double dz = camZ - e.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;
        double range = module.cullRange.get();
        if (distSqr > range * range) {
            return false;
        }

        long now = FrameClock.now();
        if (now != camCheckedAt) {
            camCheckedAt = now;
            camInOpaque = mc.level.getBlockState(CURSOR.set(camX, camY, camZ)).isSolidRender();
        }
        if (camInOpaque) {
            return false; // ваниль и так рисует всё «сквозь стены» в таком кадре
        }

        Entry en = CACHE.get(e.getId());
        if (en != null && now < en.nextCheckMs
                && Math.abs(en.lx - e.getX()) + Math.abs(en.ly - e.getY()) + Math.abs(en.lz - e.getZ()) < 1.0
                && Math.abs(en.cx - camX) + Math.abs(en.cy - camY) + Math.abs(en.cz - camZ) < 1.5) {
            return en.occluded;
        }
        if (en == null) {
            en = new Entry();
            CACHE.put(e.getId(), en);
        }
        if (CACHE.size() > 1024) {
            CACHE.clear();
        }

        boolean occ = evaluate(e, mc, camX, camY, camZ, distSqr);
        en.occluded = occ;
        // сдвиг фаз по id: проверки не совпадают в один кадр
        en.nextCheckMs = now + 450 + (e.getId() % 6) * 45L;
        en.lx = e.getX();
        en.ly = e.getY();
        en.lz = e.getZ();
        en.cx = camX;
        en.cy = camY;
        en.cz = camZ;
        return occ;
    }

    /** Лучевой тест AABB: все ключевые точки закрыты — сущность скрыта. */
    private static boolean evaluate(Entity e, Minecraft mc, double camX, double camY, double camZ,
                                    double distSqr) {
        AABB bb = e.getBoundingBox().inflate(0.12);
        double mx = (bb.minX + bb.maxX) / 2.0;
        double my = (bb.minY + bb.maxY) / 2.0;
        double mz = (bb.minZ + bb.maxZ) / 2.0;

        // центральная точка — самый частый «виден» случай, одной проверкой
        if (rayVisible(mc, camX, camY, camZ, mx, my, mz)) {
            return false;
        }
        if (rayVisible(mc, camX, camY, camZ, mx, bb.minY, mz)) {
            return false;
        }
        if (rayVisible(mc, camX, camY, camZ, mx, bb.maxY, mz)) {
            return false;
        }
        if (distSqr > 196.0) { // дальше 14 блоков — проверяем и боковые углы
            if (rayVisible(mc, camX, camY, camZ, bb.minX, my, bb.minZ)) {
                return false;
            }
            if (rayVisible(mc, camX, camY, camZ, bb.maxX, my, bb.minZ)) {
                return false;
            }
            if (rayVisible(mc, camX, camY, camZ, bb.minX, my, bb.maxZ)) {
                return false;
            }
            if (rayVisible(mc, camX, camY, camZ, bb.maxX, my, bb.maxZ)) {
                return false;
            }
        }
        return true; // все лучи упёрлись — сущность полностью скрыта
    }

    /** Есть ли от камеры до точки свободный путь сквозь прозрачные/пустые блоки. */
    private static boolean rayVisible(Minecraft mc, double x0, double y0, double z0,
                                      double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0) {
            return true;
        }
        int steps = Math.max(1, (int) Math.ceil(len / 0.45));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            if (mc.level.getBlockState(CURSOR.set(x0 + dx * t, y0 + dy * t, z0 + dz * t)).isSolidRender()) {
                return false; // упёрлись в непрозрачный куб
            }
        }
        return true;
    }
}
