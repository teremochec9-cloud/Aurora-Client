package dev.aurora.client.util;

import dev.aurora.client.AuroraClient;
import dev.aurora.client.theme.Theme;

/**
 * Правило владения блюром за меню.
 *
 * <p>Факт байткода 1.21.11: ванильный блюр работает во ВСЕХ графических
 * режимах при menuBackgroundBlurriness &gt;= 1 (Screen.renderBlurredBackground
 * проверяет только порог 1.0), и он персистится ванилью в options.txt.</p>
 *
 * <p>Поэтому:</p>
 * <ul>
 *   <li><b>свой блюр</b> (Iris не активен): ваниль глушится mixin-cancel'ом
 *       renderBlurredBackground по тумблеру — опция menuBackgroundBlurriness
 *       НЕ трогается вообще (иначе при краше юзерово значение навсегда
 *       уедет в options.txt нулём);</li>
 *   <li><b>fallback</b> (шейдеры/Fabulous): свой блюр выключен, слайдер темы
 *       маппится на ванильную опцию, юзерово значение бэкапится в
 *       aurora.json (см. AuroraConfig.takeVanillaBlurControl/apply/release);
 *       правило порога: 0 = выключено, иначе минимум 1 (ceil) — тумблер
 *       не может быть «включён и не работает»;</li>
 *   <li>свой путь НЕ вызывает GuiGraphics.blurBeforeThisStratum() —
 *       там IllegalStateException("Can only blur once per frame"), чужой
 *       вызов в том же кадре уронит игру; свой блюр рисует собственный quad
 *       на своём FBO.</li>
 * </ul>
 */
public final class BlurGuard {
    /** true только на кадрах, где Aurora реально рисует свой блюр за меню. */
    public static boolean ownBlurThisFrame;

    private BlurGuard() {
    }

    /** Aurora рисует свой блюр в этом кадре и имеет право глушить ваниль. */
    public static boolean shouldSuppressVanilla() {
        if (!ownBlurThisFrame) {
            return false;
        }
        Theme t = AuroraClient.theme();
        if (!t.blur || t.blurStrength <= 0) {
            return false; // сила 0 = выключено — тумблер не должен лгать
        }
        return !IrisGuard.shaderTargetConflict();
    }
}
