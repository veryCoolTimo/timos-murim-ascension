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

    private static long lastAny = Long.MIN_VALUE / 2;
    private static final java.util.Map<ResourceLocation, Long> LAST = new java.util.HashMap<>();

    static void started(ResourceLocation id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            lastAny = mc.level.getGameTime();
            LAST.put(id, lastAny);
        }
    }

    /** Maintained art's own cooldown begins when the server ends it; global gap keeps its cast timestamp. */
    public static void maintainedEnded(ResourceLocation id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) LAST.put(id, mc.level.getGameTime());
    }

    /** Сколько тиков ещё ждать технику {@code id}; 0 — готова. */
    public static int remaining(ResourceLocation id) {
        Minecraft mc = Minecraft.getInstance();
        TechniqueDefinition d = TechniqueLoader.get(id);
        if (mc.level == null || d == null) {
            return 0;
        }
        long own = LAST.getOrDefault(id, Long.MIN_VALUE / 2) + io.github.verycooltimo.murim.combat.TechniqueService.effectiveCooldown(d, ClientMasteryState.executionTier(id)) - mc.level.getGameTime();
        long gap = LAST.containsKey(id) && LAST.get(id) == lastAny ? 0L
                : lastAny + io.github.verycooltimo.murim.combat.TechniqueService.gapAfter(LAST, lastAny) - mc.level.getGameTime();
        return (int) Math.max(0L, Math.max(own, gap));
    }

    /** Доля оставшейся перезарядки 0…1. */
    public static float fraction(ResourceLocation id) {
        TechniqueDefinition d = TechniqueLoader.get(id);
        int cooldown = d == null ? 0 : io.github.verycooltimo.murim.combat.TechniqueService.effectiveCooldown(d, ClientMasteryState.executionTier(id));
        return cooldown <= 0 ? 0.0F : Math.min(1.0F, remaining(id) / (float) cooldown);
    }

    private ClientCooldowns() {
    }
}
