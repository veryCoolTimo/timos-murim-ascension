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

    public static void accept(SyncMasteryPayload payload) {
        entries = List.copyOf(payload.entries());
    }

    public static List<SyncMasteryPayload.Entry> entries() {
        return entries;
    }

    /** Что ждёт осмысления — по убыванию: двойник начинает с самого пережитого. */
    public static List<ResourceLocation> pending() {
        return entries.stream()
                .filter(e -> e.pending() > 0.01F)
                .sorted(Comparator.comparingDouble((SyncMasteryPayload.Entry e) -> e.pending()).reversed())
                .map(SyncMasteryPayload.Entry::technique)
                .toList();
    }

    /** Слой освоения техники или −1, если она не выучена. */
    public static int layer(ResourceLocation id) {
        for (SyncMasteryPayload.Entry e : entries()) {
            if (e.technique().equals(id)) {
                return e.layer();
            }
        }
        return -1;
    }

    public static void reset() {
        entries = List.of();
    }

    private ClientMasteryState() {
    }
}
