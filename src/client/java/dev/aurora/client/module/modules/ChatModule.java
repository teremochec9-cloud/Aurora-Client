package dev.aurora.client.module.modules;

import dev.aurora.client.module.Category;
import dev.aurora.client.module.Module;
import dev.aurora.client.module.settings.BooleanSetting;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Улучшения чата: сообщения не затухают, прозрачный фон, копирование.
 *
 * <p><b>Важно (исправлено в 1.0.6):</b> раньше «Прозрачный фон» одалживал
 * ванильную опцию {@code textBackgroundOpacity}, а при выключении модуля
 * возвращал её по ошибке в {@code chatOpacity} — прозрачность ТЕКСТА.
 * Из-за этого буквы в чате почти исчезали («буквы пропадают»), а фон
 * сообщений и подсказок команд навсегда оставался прозрачным («команды
 * не подсвечиваются»). Теперь одалживается и возвращается ровно одна
 * и та же опция, а при первом тике клиента чинятся настройки,
 * испорченные версией 1.0.5 ({@link #repairOnce}).</p>
 */
public class ChatModule extends Module {
    private static final Logger LOG = LoggerFactory.getLogger("Aurora/Chat");

    public final BooleanSetting noFade = new BooleanSetting("Без затухания", true, "Сообщения не исчезают со временем");
    public final BooleanSetting transparentBg = new BooleanSetting("Прозрачный фон", false, "Убирает тёмный фон чата");
    public final BooleanSetting ctrlCopy = new BooleanSetting("Ctrl+C — копировать", true, "В чате копирует последнее сообщение");

    /** Значение textBackgroundOpacity до включения «Прозрачного фона». */
    private Double previousOpacity;
    private boolean pending = false;
    /** Авторемонт сломанных настроек выполняется один раз за жизнь клиента. */
    private static boolean repaired;

    public ChatModule() {
        super("Улучшения чата", "Без затухания, прозрачный фон, копирование", Category.CHAT);
        addSettings(noFade, transparentBg, ctrlCopy);
    }

    @Override
    protected void onEnable() {
        apply();
    }

    @Override
    protected void onDisable() {
        restore();
    }

    /** ВАЖНО: прозрачность ФОНА (textBackgroundOpacity), а не текста!
     *  chatOpacity управляет прозрачностью самих букв — её трогать нельзя
     *  (с 0.0 сообщения становятся невидимыми, баг 1.0.5). */
    private void apply() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            pending = true;
            return;
        }
        if (transparentBg.get() && previousOpacity == null) {
            previousOpacity = (Double) mc.options.textBackgroundOpacity().get();
            mc.options.textBackgroundOpacity().set(0.0);
        }
    }

    /** Возвращает РОВНО ту опцию, которую одалживали (баг 1.0.5: возвращали
     *  в chatOpacity — прозрачность текста, и буквы в чате пропадали). */
    private void restore() {
        pending = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && previousOpacity != null) {
            mc.options.textBackgroundOpacity().set(previousOpacity);
            previousOpacity = null;
        }
    }

    @Override
    public void onTick() {
        if (pending) {
            pending = false;
            apply();
            return;
        }
        // пользователь выключил «Прозрачный фон» при включённом модуле —
        // сразу возвращаем фон, не дожидаясь отключения модуля
        if (isEnabled() && !transparentBg.get() && previousOpacity != null) {
            restore();
        } else if (isEnabled() && transparentBg.get() && previousOpacity == null) {
            apply();
        }
    }

    /**
     * Лечит настройки, испорченные багом 1.0.5 (вызывается один раз из
     * первого тика клиента, до тика модулей). Тогда при выключении модуля
     * значение фоновой прозрачности попадало в chatOpacity (прозрачность
     * букв), а textBackgroundOpacity навсегда оставалась нулевой. Сочетание
     * «буквы почти невидимы + фон полностью прозрачен» само по себе не
     * возникает — чиним и пишем об этом в лог.
     */
    public static void repairOnce(Minecraft mc) {
        if (repaired || mc == null || mc.options == null) {
            return;
        }
        repaired = true;
        try {
            double textOpacity = mc.options.chatOpacity().get();
            double bgOpacity = mc.options.textBackgroundOpacity().get();
            if (textOpacity < 0.3 && bgOpacity <= 0.01) {
                mc.options.chatOpacity().set(1.0);
                mc.options.textBackgroundOpacity().set(0.5);
                ChatModule m = dev.aurora.client.module.ModuleManager.get(ChatModule.class);
                // если модуль прямо сейчас одалживает опцию, запомненное им
                // «исходное» тоже было испорчено нулём — считаем исходником
                // отремонтированное значение
                if (m != null && m.previousOpacity != null && m.previousOpacity <= 0.01) {
                    m.previousOpacity = 0.5;
                }
                LOG.info("Починили прозрачность чата, испорченную версией 1.0.5: буквы "
                                + "были почти невидимы ({}), фон — прозрачен ({}). Вернули 1.0 / 0.5",
                        textOpacity, bgOpacity);
            }
        } catch (Exception ignored) {
            // редкие сборки без этих опций не должны ронять клиент
        }
    }

    /** Нужно ли отключить затухание сообщений (вызывается из миксина). */
    public static boolean shouldDisableFade() {
        ChatModule m = dev.aurora.client.module.ModuleManager.get(ChatModule.class);
        return m != null && m.isEnabled() && m.noFade.get();
    }
}
