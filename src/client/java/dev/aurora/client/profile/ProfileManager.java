package dev.aurora.client.profile;

import dev.aurora.client.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Смена никнейма прямо в игре.
 *
 * <p>Подменяет объект {@link User} у {@link Minecraft} через миксин-аксессор.
 * Для офлайн-имени UUID считается как у ванильного оффлайн-профиля
 * (OfflinePlayer:name), поэтому скины по нику продолжают работать там,
 * где скин запрашивается по имени.</p>
 *
 * <p>Никнейм применяется лениво (в первом клиентском тике): на момент
 * инициализации мода экземпляра Minecraft ещё нет. Имя видно в меню
 * клиента («С возвращением, …», карточка профиля) и используется при
 * подключении к серверам в офлайн-режиме. На онлайн-серверах имя
 * подтверждается авторизацией Mojang — там остаётся настоящее.</p>
 */
public final class ProfileManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Aurora/Profile");
    /** Офлайн-ник: 3–16 символов, латиница/цифры/подчёркивание. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /** Никнейм, применённый к сессии сейчас (может совпадать с системным). */
    private static String applied;
    /** Никнейм, который надо применить при первом тике (при старте игры). */
    private static String pending;

    private ProfileManager() {
    }

    /** Проверка имени на валидность. */
    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }

    /** Запланировать применение никнейма после запуска игры. */
    public static void setPending(String nickname) {
        pending = (nickname == null || nickname.isBlank()) ? null : nickname.trim();
    }

    /**
     * Применяет отложенный никнейм (вызывается из клиентского тика).
     *
     * @return true, если никнейм был применён
     */
    public static boolean applyPending() {
        if (pending == null) {
            return false;
        }
        String nick = pending;
        pending = null;
        return apply(nick);
    }

    /**
     * Меняет никнейм немедленно. Возвращает true при успехе.
     */
    public static boolean apply(String nickname) {
        if (!isValid(nickname)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            pending = nickname;
            return false;
        }
        try {
            User current = mc.getUser();
            if (current != null && nickname.equals(current.getName())) {
                applied = nickname;
                LOGGER.info("Aurora: никнейм «{}» уже активен", nickname);
                return true;
            }
            // оффлайн-профиль: UUID как у ванильного OfflinePlayer
            var uuid = java.util.UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + nickname).getBytes(StandardCharsets.UTF_8));
            User replacement = new User(nickname, uuid, "",
                    Optional.empty(), Optional.empty());
            ((MinecraftAccessor) mc).aurora$setUser(replacement);
            applied = nickname;
            LOGGER.info("Aurora: никнейм изменён на «{}»", nickname);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Aurora: не удалось сменить никнейм", t);
            return false;
        }
    }

    /** Текущий отображаемый никнейм. */
    public static String currentName() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                return mc.getUser().getName();
            }
        } catch (Exception ignored) {
        }
        return applied != null ? applied : "Player";
    }
}
