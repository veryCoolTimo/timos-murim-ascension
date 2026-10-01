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

    public static void setSlot(ServerPlayer player, int slot, Optional<ResourceLocation> technique) {
        if (slot < 0 || slot >= openSlots(player)) {
            return;
        }
        if (technique.isPresent() && !MasteryService.knows(player, technique.get())) {
            return;
        }
        player.setData(ModAttachments.LOADOUT, player.getData(ModAttachments.LOADOUT).with(slot, technique));
        sync(player);
    }

    public static void select(ServerPlayer player, int slot) {
        if (slot < 0 || slot >= openSlots(player)) {
            return;
        }
        player.setData(ModAttachments.LOADOUT, player.getData(ModAttachments.LOADOUT).select(slot));
        sync(player);
    }

    /** Только что выученная техника встаёт в первый свободный открытый слот — меньше возни. */
    public static void placeLearned(ServerPlayer player, ResourceLocation technique) {
        Loadout loadout = player.getData(ModAttachments.LOADOUT);
        int open = openSlots(player);
        for (int i = 0; i < open; i++) {
            if (loadout.at(i).equals(Optional.of(technique))) {
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
        int open = openSlots(player);
        java.util.List<Optional<ResourceLocation>> slots = new java.util.ArrayList<>();
        for (int i = 0; i < open; i++) {
            slots.add(loadout.at(i));
        }
        PacketDistributor.sendToPlayer(player, new LoadoutPayloads.Sync(slots,
                Math.min(loadout.active(), Math.max(0, open - 1)), open));
    }

    private LoadoutService() {
    }
}
