package dev.aurora.client.theme;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.aurora.client.util.ColorUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Тема оформления клиента: цвета, прозрачность, блюр, анимации.
 * Сохраняется и загружается вместе с конфигом модулей.
 */
public class Theme {
    public static final String CLIENT_NAME = "Aurora";
    public static final String CLIENT_VERSION = "1.2.1";
    public static final String MC_VERSION = "1.21.11";

    /** Основной акцентный цвет (по умолчанию — фиолетовый, как на референсе). */
    public int accent = ColorUtil.rgb(139, 92, 246);
    /** Второй цвет градиента. */
    public int accent2 = ColorUtil.rgb(99, 102, 241);
    /** Радужный режим акцента. */
    public boolean rainbow = false;
    /** Прозрачность панелей (0..1). */
    public float backgroundAlpha = 0.55f;
    /** Сила блюра за меню (0..10). */
    public int blurStrength = 10;
    /** Включить блюр за меню. */
    public boolean blur = true;
    /** Плавные анимации интерфейса. */
    public boolean animations = true;
    /** Использовать кастомный шрифт Inter. */
    public boolean customFont = true;
    /** Клавиша открытия меню (GLFW-код, по умолчанию — левый Ctrl). */
    public int menuKey = 341;

    /** Пресеты акцента: name -> [accent, accent2]. */
    public static final String[][] PRESETS = {
            {"Фиалка", "8B5CF6", "6366F1"},
            {"Закат", "F97316", "EC4899"},
            {"Океан", "06B6D4", "3B82F6"},
            {"Изумруд", "10B981", "84CC16"},
            {"Рубин", "EF4444", "F43F5E"},
            {"Золото", "F59E0B", "FDE047"},
            {"Роза", "EC4899", "F472B6"},
            {"Аметист", "A78BFA", "C084FC"},
    };

    public int accent(float t) {
        if (rainbow) {
            return ColorUtil.rainbow(3.5f, 0.72f, 1.0f, (long) (t * 3000));
        }
        return ColorUtil.lerp(accent, accent2, t);
    }

    /** Акцентный цвет с учётом времени (для анимаций). */
    public int accentNow() {
        return accent((float) (Math.sin(System.currentTimeMillis() / 2000.0) * 0.5 + 0.5));
    }

    public int panelColor() {
        return ColorUtil.alpha(ColorUtil.rgb(17, 18, 27), (int) (backgroundAlpha * 255));
    }

    public int borderColor() {
        return ColorUtil.rgba(255, 255, 255, 26);
    }

    public int textPrimary() {
        return ColorUtil.rgb(242, 243, 247);
    }

    public int textSecondary() {
        return ColorUtil.rgba(235, 236, 244, 145);
    }

    /* ------------------------------------------------------------------ */
    /*  Сохранение / загрузка                                             */
    /* ------------------------------------------------------------------ */

    /** Сброс темы к значениям по умолчанию. */
    public void resetToDefault() {
        accent = ColorUtil.rgb(139, 92, 246);
        accent2 = ColorUtil.rgb(99, 102, 241);
        rainbow = false;
        backgroundAlpha = 0.55f;
        blurStrength = 10;
        blur = true;
        animations = true;
        customFont = true;
        menuKey = 341;
    }

    public JsonObject save() {
        JsonObject o = new JsonObject();
        o.addProperty("accent", Integer.toHexString(accent));
        o.addProperty("accent2", Integer.toHexString(accent2));
        o.addProperty("rainbow", rainbow);
        o.addProperty("backgroundAlpha", backgroundAlpha);
        o.addProperty("blurStrength", blurStrength);
        o.addProperty("blur", blur);
        o.addProperty("animations", animations);
        o.addProperty("customFont", customFont);
        o.addProperty("menuKey", menuKey);
        return o;
    }

    public void load(JsonObject o) {
        if (o == null) {
            return;
        }
        try {
            if (o.has("accent")) {
                accent = (int) (0xFF000000L | Long.parseLong(o.get("accent").getAsString(), 16));
            }
            if (o.has("accent2")) {
                accent2 = (int) (0xFF000000L | Long.parseLong(o.get("accent2").getAsString(), 16));
            }
            rainbow = o.has("rainbow") && o.get("rainbow").getAsBoolean();
            if (o.has("backgroundAlpha")) {
                backgroundAlpha = o.get("backgroundAlpha").getAsFloat();
            }
            if (o.has("blurStrength")) {
                blurStrength = o.get("blurStrength").getAsInt();
            }
            blur = !o.has("blur") || o.get("blur").getAsBoolean();
            animations = !o.has("animations") || o.get("animations").getAsBoolean();
            customFont = !o.has("customFont") || o.get("customFont").getAsBoolean();
            if (o.has("menuKey")) {
                menuKey = o.get("menuKey").getAsInt();
            }
        } catch (Exception ignored) {
        }
    }
}
