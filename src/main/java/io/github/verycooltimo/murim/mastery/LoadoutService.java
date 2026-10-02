package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.network.LoadoutPayloads;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * Раскладка техник по слотам на сервере. Клиент присылает намерение — положить технику
 * или выбрать слот; сервер проверяет, что техника выучена и слот открыт.
 */
public final class LoadoutService {

    public static int openSlots(ServerPlayer player) {
        return Loadout.slotsFor(player.getData(ModAttachments.MASTERY).wisdom());
    }

    /** Номер «слота» в пакете, означающий ячейку основы меча. */
    public static final int FOUNDATION_SLOT = -1;

    public static void setSlot(ServerPlayer player, int slot, Optional<ResourceLocation> technique) {
        if (slot == FOUNDATION_SLOT) {
            setFoundation(player, technique);
            return;
        }
        // Основу в слот техники не кладём: у неё нет своей клавиши, она живёт на ЛКМ.
        if (technique.isPresent() && isFoundation(technique.get())) {
            return;
        }
        if (slot < 0 || slot >= openSlots(player)) {
            return;
        }
        if (technique.isPresent() && !MasteryService.knows(player, technique.get())) {
            return;
        }
        player.setData(ModAttachments.LOADOUT, player.getData(ModAttachments.LOADOUT).with(slot, technique));
        sync(player);
    }

    public static void setFoundation(ServerPlayer player, Optional<ResourceLocation> technique) {
        if (technique.isPresent() && (!isFoundation(technique.get()) || !MasteryService.knows(player, technique.get()))) {
            return;
        }
        player.setData(ModAttachments.LOADOUT, player.getData(ModAttachments.LOADOUT).withFoundation(technique));
        sync(player);
    }

    public static boolean isFoundation(ResourceLocation id) {
        io.github.verycooltimo.murim.technique.TechniqueDefinition d = io.github.verycooltimo.murim.technique.TechniqueLoader.get(id);
        return d != null && d.foundation();
    }

    public static void select(ServerPlayer player, int slot) {
        if (slot < 0 || slot >= openSlots(player)) {
            return;
        }
        Loadout next = player.getData(ModAttachments.LOADOUT).select(slot);
        // Выбрал слот стиля — ЛКМ переходит на основу этого стиля (если выучена); шаг или
        // ладонь стойку не меняют (решение codex 02.10).
        Optional<ResourceLocation> chosen = next.at(slot);
        if (chosen.isPresent()) {
            Optional<ResourceLocation> basic = io.github.verycooltimo.murim.technique.Styles.of(chosen.get())
                    .flatMap(io.github.verycooltimo.murim.technique.Styles.Style::basic);
            if (basic.isPresent() && MasteryService.knows(player, basic.get())) {
                next = next.withFoundation(basic);
            }
        }
        player.setData(ModAttachments.LOADOUT, next);
        sync(player);
    }

    /** Только что выученная техника встаёт в первый свободный открытый слот — меньше возни. */
    public static void placeLearned(ServerPlayer player, ResourceLocation technique) {
        Loadout loadout = player.getData(ModAttachments.LOADOUT);
        // Выученная основа сразу встаёт в ячейку основы, если та пуста.
        if (isFoundation(technique)) {
            if (loadout.foundation().isEmpty()) {
                player.setData(ModAttachments.LOADOUT, loadout.withFoundation(Optional.of(technique)));
            }
            return;
        }
        int open = openSlots(player);
        for (int i = 0; i < open; i++) {
            // Форма стиля, чей слот уже занят другой формой, своего слота не получает.
            if (loadout.at(i).equals(Optional.of(technique)) || loadout.at(i).isPresent()
                    && io.github.verycooltimo.murim.technique.Styles.sameStyle(loadout.at(i).get(), technique)) {
                return;
            }
        }
        for (int i = 0; i < open; i++) {
            if (loadout.at(i).isEmpty()) {
                player.setData(ModAttachments.LOADOUT, loadout.with(i, Optional.of(technique)));
                return;
            }
        }
    }

    public static void sync(ServerPlayer player) {
        Loadout loadout = player.getData(ModAttachments.LOADOUT);
        Loadout normal = loadout.normalized();
        if (normal != loadout) {
            player.setData(ModAttachments.LOADOUT, normal);
            loadout = normal;
        }
        int open = openSlots(player);
        java.util.List<Optional<ResourceLocation>> slots = new java.util.ArrayList<>();
        for (int i = 0; i < open; i++) {
            slots.add(loadout.at(i));
        }
        PacketDistributor.sendToPlayer(player, new LoadoutPayloads.Sync(slots,
                Math.min(loadout.active(), Math.max(0, open - 1)), open, loadout.foundation()));
    }

    private LoadoutService() {
    }
}
