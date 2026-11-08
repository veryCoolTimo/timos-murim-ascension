package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.network.LoadoutPayloads;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/** Зеркало раскладки техник — для кольца, слотов боя и экрана раскладки. */
public final class ClientLoadoutState {

    private static volatile LoadoutPayloads.Sync state = new LoadoutPayloads.Sync(List.of(), 0, 2, Optional.empty(), Optional.empty(), Optional.empty());

    private record Selection(int slot, ResourceLocation form) {}
    private static final SelectionHistory<Selection> HISTORY = new SelectionHistory<>();
    private static int pulseTicks;

    private static void observe() {
        HISTORY.observe(activeTechnique().map(form -> new Selection(active(), form)).orElse(null));
    }

    /** Uses the same validated SelectForm request as the wheel, without changing the pinned step. */
    public static boolean previous() {
        var previous = HISTORY.previous(pair -> pair.slot() >= 0 && pair.slot() < open()
                && pair.slot() < slots().size() && slots().get(pair.slot()).filter(now ->
                now.equals(pair.form()) || io.github.verycooltimo.murim.technique.Styles.sameStyle(now, pair.form())).isPresent()
                && TechniqueSlotsHud.mastery(pair.form()) != null);
        if (previous.isEmpty()) return false;
        var pair = previous.get();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new LoadoutPayloads.SelectForm(pair.slot(), pair.form(), true));
        choose(pair.slot(), pair.form(), true);
        pulseTicks = 6;
        CombatMode.engage();
        return true;
    }

    static void tickPulse() { if (pulseTicks > 0) pulseTicks--; }
    static int pulseTicks() { return pulseTicks; }

    public static void accept(LoadoutPayloads.Sync payload) {
        state = payload;
        observe();
    }

    /** Выбор сразу доступен клавише R; сервер проверяет запрос и присылает окончательное состояние. */
    public static void choose(int slot, ResourceLocation form, boolean activate) {
        if (slot < 0 || slot >= state.open()) {
            return;
        }
        var next = new io.github.verycooltimo.murim.mastery.Loadout(state.slots(), state.active(), state.foundation(), state.bodyProtection())
                .with(slot, Optional.of(form));
        if (activate) {
            next = next.select(slot);
            Optional<ResourceLocation> basic = io.github.verycooltimo.murim.technique.Styles.of(form)
                    .flatMap(io.github.verycooltimo.murim.technique.Styles.Style::basic);
            if (basic.isPresent() && TechniqueSlotsHud.mastery(basic.get()) != null) {
                next = next.withFoundation(basic);
            }
        }
        state = new LoadoutPayloads.Sync(next.slots(), next.active(), state.open(), next.foundation(), state.breathing(), state.bodyProtection());
        observe();
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

    public static Optional<ResourceLocation> breathing() { return state.breathing(); }

    public static Optional<ResourceLocation> bodyProtection() { return state.bodyProtection(); }

    public static void reset() {
        HISTORY.clear();
        pulseTicks = 0;
        state = new LoadoutPayloads.Sync(List.of(), 0, 2, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private ClientLoadoutState() {
    }
}
