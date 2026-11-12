package dev.aurora.client.util;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Определение активного шейдерпака через рефлексию стабильного Iris API v0,
 * без compile-зависимости от Iris. Fabulous ловится отдельно ванильным
 * useShaderTransparency().
 *
 * <p>Fail-closed: если Iris загружен, но API ответить не смог (неожиданная
 * версия, ошибка рефлексии) — считаем «пак активен»: свой блюр не рисуем,
 * render-target'ы не трогаем.</p>
 */
public final class IrisGuard {
    /** Кэш ответа, чтобы не дёргать рефлексию чаще раза в секунду. */
    private static final long CACHE_MS = 1000;

    private static Boolean irisLoaded;
    private static MethodHandle apiGetter;   // IrisApi.getInstance()
    private static MethodHandle packActive;  // IrisApi.isShaderPackInUse()
    private static long cachedAt;
    private static boolean cachedActive;

    private IrisGuard() {
    }

    /** true — свой блюр/FBO-копии рисовать нельзя (Fabulous или шейдерпак). */
    public static boolean shaderTargetConflict() {
        try {
            if (net.minecraft.client.Minecraft.useShaderTransparency()) {
                return true; // Fabulous: свои таргеты прозрачности
            }
        } catch (Exception ignored) {
        }
        return packActive();
    }

    /** true — активен шейдерпак Iris (опрос при открытии экрана, кэш 1 с). */
    public static synchronized boolean packActive() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MS) {
            return cachedActive;
        }
        if (irisLoaded == null) {
            irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
        }
        boolean active;
        if (!irisLoaded) {
            active = false;
        } else {
            active = true; // fail-closed по умолчанию
            try {
                if (apiGetter == null) {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    apiGetter = MethodHandles.publicLookup()
                            .findStatic(api, "getInstance", MethodType.methodType(api));
                    packActive = MethodHandles.publicLookup()
                            .findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class));
                }
                Object instance = apiGetter.invoke();
                active = (boolean) packActive.invoke(instance);
            } catch (Throwable t) {
                active = true; // Iris есть, а ответить не смог — худший случай
            }
        }
        cachedAt = now;
        cachedActive = active;
        return active;
    }
}
