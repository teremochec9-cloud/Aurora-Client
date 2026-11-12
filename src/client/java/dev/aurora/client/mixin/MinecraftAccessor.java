package dev.aurora.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Доступ к полю user у Minecraft: нужен для смены никнейма в игре.
 * Поле final — поэтому @Mutable. Миксин сам корректно ремапится
 * между dev- и production-именами.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Accessor("user")
    User aurora$getUser();

    @Mutable
    @Accessor("user")
    void aurora$setUser(User user);
}
