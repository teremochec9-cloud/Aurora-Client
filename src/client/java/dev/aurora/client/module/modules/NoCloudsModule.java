package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;

/**
 * «Без облаков»: полностью отключает отрисовку облаков.
 *
 * <p>Облачный слой — большая полупрозрачная геометрия, которая
 * перестраивается и рисуется каждый кадр с сэмплированием цвета неба.
 * На слабых машинах и в облётах на элитрах отказ от облаков даёт
 * заметный прирост FPS; картинка в игре почти не меняется.</p>
 */
public class NoCloudsModule extends Module {

    public NoCloudsModule() {
        super("Без облаков", "Не отрисовывает слой облаков — больше FPS",
                Category.PROF);
    }
}
