package dev.aurora.client.util;

import java.awt.Color;

/**
 * Утилиты для работы с цветами в формате ARGB (int).
 */
public final class ColorUtil {
    private ColorUtil() {
    }

    public static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int rgba(int r, int g, int b, int a) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** Альфа-канал цвета (0..255). */
    public static int alphaI(int color) {
        return (color >>> 24) & 0xFF;
    }

    /** Смешивает два ARGB-цвета (t=0 — colorA, t=1 — colorB). */
    public static int lerp(int colorA, int colorB, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        int aA = (colorA >> 24) & 0xFF, rA = (colorA >> 16) & 0xFF, gA = (colorA >> 8) & 0xFF, bA = colorA & 0xFF;
        int aB = (colorB >> 24) & 0xFF, rB = (colorB >> 16) & 0xFF, gB = (colorB >> 8) & 0xFF, bB = colorB & 0xFF;
        int a = (int) (aA + (aB - aA) * t);
        int r = (int) (rA + (rB - rA) * t);
        int g = (int) (gA + (gB - gA) * t);
        int b = (int) (bA + (bB - bA) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static float[] hsb(int argb) {
        return Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, null);
    }

    public static int fromHsb(float h, float s, float b) {
        return Color.HSBtoRGB(h, s, b) | 0xFF000000;
    }

    /** Радужный цвет, смещённый по времени. */
    public static int rainbow(float speed, float saturation, float brightness, long offset) {
        float h = ((System.currentTimeMillis() + offset) % (int) (1000 * speed)) / (1000.0f * speed);
        return fromHsb(h, saturation, brightness);
    }
}
