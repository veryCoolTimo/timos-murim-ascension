package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Кулдаун техник на клиенте (автор 03.10: «нужно понимать кулдаун, нажимаю — не нажимается»).
 * Сервер считает перезарядку от начала последней техники и кулдауна той, что запускают
 * (TechniceService.offCooldown); клиент повторяет это по своему событию STARTED.
 */
public final class ClientCooldowns {

    private static long lastStart = Long.MIN_VALUE / 2;

    static void started() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            lastStart = mc.level.getGameTime();
        }
    }

    /** Сколько тиков ещё ждать технику {@code id}; 0 — готова. */
    public static int remaining(ResourceLocation id) {
        Minecraft mc = Minecraft.getInstance();
        TechniqueDefinition d = TechniqueLoader.get(id);
        if (mc.level == null || d == null) {
            return 0;
        }
        long left = lastStart + d.cooldownTicks() - mc.level.getGameTime();
        return (int) Math.max(0L, left);
    }

    /** Доля оставшейся перезарядки 0…1. */
    public static float fraction(ResourceLocation id) {
        TechniqueDefinition d = TechniqueLoader.get(id);
        return d == null || d.cooldownTicks() <= 0 ? 0.0F : remaining(id) / (float) d.cooldownTicks();
    }

    private ClientCooldowns() {
    }
}
