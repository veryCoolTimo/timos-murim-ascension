package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.network.SyncMasteryPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.List;

/**
 * Зеркало освоения техник на клиенте — только для отрисовки: что выучено и что ждёт
 * осмысления. Решения принимает сервер (docs/design/19 §3г).
 */
public final class ClientMasteryState {

    private static volatile List<SyncMasteryPayload.Entry> entries = List.of();

    private static float qiCostFactor = 1.0F;
    private static long qiDiscountUntil;

    public static double qiCostFactor() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level != null && level.getGameTime() < qiDiscountUntil ? qiCostFactor : 1.0D;
    }

    public static void accept(SyncMasteryPayload payload) {
        qiCostFactor = payload.qiCostFactor();
        qiDiscountUntil = payload.qiDiscountUntil();
        entries = List.copyOf(payload.entries());
    }

    public static List<SyncMasteryPayload.Entry> entries() {
        return entries;
    }

    /** Что ждёт осмысления — по убыванию: двойник начинает с самого пережитого. */
    public static List<ResourceLocation> pending() {
        java.util.Set<ResourceLocation> seen = new java.util.HashSet<>();
        return entries.stream()
                .filter(e -> e.pending() > 0.01F)
                .sorted(Comparator.comparingDouble((SyncMasteryPayload.Entry e) -> e.pending()).reversed())
                .filter(e -> seen.add(io.github.verycooltimo.murim.mastery.MasteryState.masteryId(e.technique())))
                .map(SyncMasteryPayload.Entry::technique) // echo animations need a learned FORM ID
                .toList();
    }

    /** Звёзды искусства этой формы или −1, если она не выучена. */
    public static int stars(ResourceLocation id) {
        for (SyncMasteryPayload.Entry e : entries()) {
            if (e.technique().equals(id)) {
                return e.stars();
            }
        }
        return -1;
    }

    public static int executionTier(ResourceLocation id) {
        return io.github.verycooltimo.murim.mastery.Arts.executionTier(id, stars(id));
    }

    public static void reset() {
        qiCostFactor = 1.0F;
        qiDiscountUntil = 0L;
        entries = List.of();
    }

    private ClientMasteryState() {
    }
}
