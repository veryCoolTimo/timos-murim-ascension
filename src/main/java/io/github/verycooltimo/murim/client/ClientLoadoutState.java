package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.network.LoadoutPayloads;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/** Зеркало раскладки техник — для кольца, слотов боя и экрана раскладки. */
public final class ClientLoadoutState {

    private static volatile LoadoutPayloads.Sync state = new LoadoutPayloads.Sync(List.of(), 0, 2, Optional.empty());

    public static void accept(LoadoutPayloads.Sync payload) {
        state = payload;
    }

    /** Открытые слоты (их столько, сколько даёт прогрессия). */
    public static List<Optional<ResourceLocation>> slots() {
        return state.slots();
    }

    public static int open() {
        return state.open();
    }

    public static int active() {
        return state.active();
    }

    public static Optional<ResourceLocation> activeTechnique() {
        List<Optional<ResourceLocation>> slots = state.slots();
        int active = state.active();
        return active >= 0 && active < slots.size() ? slots.get(active) : Optional.empty();
    }

    /** Основа меча — стиль обычной атаки; пусто — ЛКМ ванильная. */
    public static Optional<ResourceLocation> foundation() {
        return state.foundation();
    }

    public static void reset() {
        state = new LoadoutPayloads.Sync(List.of(), 0, 2, Optional.empty());
    }

    private ClientLoadoutState() {
    }
}
