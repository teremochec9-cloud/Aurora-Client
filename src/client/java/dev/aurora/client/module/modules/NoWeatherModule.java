package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;

/**
 * «Без осадков»: отключает отрисовку дождя и снега.
 *
 * <p>Осадки — сотни полупрозрачных квадов между блоками, которые
 * пересобираются при каждом движении камеры и рисуются каждый кадр
 * в отдельный таргет с прозрачностью. В грозу и снегопад это одна
 * из самых дорогих частей кадра; отказ от неё даёт чистый прирост
 * FPS без изменения геймплея (звук и намокание остаются).</p>
 */
public class NoWeatherModule extends Module {

    public NoWeatherModule() {
        super("Без осадков", "Не отрисовывает дождь и снег — больше FPS",
                Category.PROF);
    }
}
