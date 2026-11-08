package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.network.LoadoutPayloads;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
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
    public static final int FOUNDATION_SLOT = ArtSlots.FOUNDATION;

    public static void setSlot(ServerPlayer player, int slot, Optional<ResourceLocation> technique) {
        // Six Elements Sword on a technique slot is its second volume once the sub-techniques are known.
        if (slot >= 0 && technique.isPresent() && ArtSlots.SIX.equals(technique.get())) {
            if (!MasteryService.knows(player, ArtSlots.SIX_VOLUME_TWO)) {
                PlayerJournal.refuse(player, net.minecraft.network.chat.Component.translatable("murim.loadout.six_volume_two"));
                sync(player);
                return;
            }
            technique = Optional.of(ArtSlots.SIX_VOLUME_TWO);
        }
        if (technique.isPresent() && !ArtSlots.accepts(slot, technique.get())) {
            sync(player);
            return;
        }
        if (slot == ArtSlots.BREATHING) {
            setBreathing(player, technique);
            return;
        }
        if (slot == ArtSlots.BODY_PROTECTION) {
            if (technique.isEmpty() || MasteryService.knows(player, technique.get())) {
                player.setData(ModAttachments.LOADOUT,
                        player.getData(ModAttachments.LOADOUT).withBodyProtection(technique));
            }
            sync(player);
            return;
        }
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
        var before = player.getData(ModAttachments.LOADOUT);
        var after = before.with(slot, technique);
        boolean footwork = before.at(slot).map(io.github.verycooltimo.murim.technique.TechniqueLoader::get)
                .map(io.github.verycooltimo.murim.combat.FootworkService::family).isPresent()
                || technique.map(io.github.verycooltimo.murim.technique.TechniqueLoader::get)
                .map(io.github.verycooltimo.murim.combat.FootworkService::family).isPresent();
        if (footwork && !before.equals(after)) io.github.verycooltimo.murim.combat.FootworkService.cancel(player);
        player.setData(ModAttachments.LOADOUT, after);
        sync(player);
    }

    /** Switching a known method only changes cultivation selection: neither qi pool is touched. */
    private static void setBreathing(ServerPlayer player, Optional<ResourceLocation> art) {
        if (!canChangeBreathing(player)) {
            sync(player);
            return;
        }
        if (art.isPresent() && (!MasteryService.knows(player, art.get())
                || io.github.verycooltimo.murim.cultivation.MethodLoader.get(Arts.methodId(art.get())) == null)) {
            sync(player);
            return;
        }
        var old = player.getData(ModAttachments.CULTIVATION);
        player.setData(ModAttachments.CULTIVATION, new io.github.verycooltimo.murim.cultivation.CultivationState(
                art.map(Arts::methodId), old.beats()));
        io.github.verycooltimo.murim.cultivation.MeditationService.sync(player,
                io.github.verycooltimo.murim.network.SyncMeditationPayload.Event.NONE);
        sync(player);
    }

    /** Shared with scroll learning, so rereading a method cannot bypass the combat gate. */
    public static boolean canChangeBreathing(ServerPlayer player) {
        String reason = breathingChangeReason(player);
        if (!reason.isEmpty()) {
            PlayerJournal.refuse(player, net.minecraft.network.chat.Component.translatable(reason));
            return false;
        }
        return true;
    }

    /** Read-only gate for reader state; opening a scroll must not write a refusal. */
    public static String breathingChangeReason(ServerPlayer player) {
        boolean fighting = io.github.verycooltimo.murim.combat.CombatSignal.engaged(player)
                || player.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                || player.getLastHurtByMob() != null && player.tickCount - player.getLastHurtByMobTimestamp() < 200
                || player.getLastHurtMobTimestamp() > 0 && player.tickCount - player.getLastHurtMobTimestamp() < 200;
        if (fighting || player.getData(ModAttachments.MEDITATION).active()) {
            return "murim.loadout.breathing.busy";
        }
        return "";
    }

    public static void setFoundation(ServerPlayer player, Optional<ResourceLocation> technique) {
        if (technique.isPresent() && (!ArtSlots.accepts(ArtSlots.FOUNDATION, technique.get()) || !MasteryService.knows(player, technique.get()))) {
            return;
        }
        player.setData(ModAttachments.LOADOUT, player.getData(ModAttachments.LOADOUT).withFoundation(technique));
        sync(player);
    }

    public static boolean isFoundation(ResourceLocation id) {
        io.github.verycooltimo.murim.technique.TechniqueDefinition d = io.github.verycooltimo.murim.technique.TechniqueLoader.get(id);
        return d != null && d.foundation();
    }

    /** Атомарный выбор формы существующего слота стиля; неверный запрос возвращает клиенту текущую раскладку. */
    public static boolean selectForm(ServerPlayer player, int slot, ResourceLocation technique, boolean activate) {
        Loadout loadout = player.getData(ModAttachments.LOADOUT);
        Optional<ResourceLocation> old = loadout.at(slot);
        if (slot < 0 || slot >= openSlots(player) || old.isEmpty() || !ArtSlots.accepts(slot, technique)
                || !MasteryService.knows(player, technique) || !old.get().equals(technique)
                && !io.github.verycooltimo.murim.technique.Styles.sameStyle(old.get(), technique)) {
            // Diagnostics for «I pick another form on the wheel but the old one stays» (author 14.10).
            io.github.verycooltimo.murim.MurimMod.LOGGER.info("Кольцо: отказ выбора {} в слот {} (открыто {}, было {}, основа {}, знает {}, тот же стиль {})",
                    technique, slot, openSlots(player), old.orElse(null), isFoundation(technique),
                    MasteryService.knows(player, technique),
                    old.isPresent() && io.github.verycooltimo.murim.technique.Styles.sameStyle(old.get(), technique));
            sync(player);
            return false;
        }
        io.github.verycooltimo.murim.MurimMod.LOGGER.info("Кольцо: выбрано {} в слот {} (активировать {})", technique, slot, activate);
        player.setData(ModAttachments.LOADOUT, loadout.with(slot, Optional.of(technique)));
        if (activate) {
            select(player, slot);
        } else {
            sync(player);
        }
        return true;
    }

    public static void select(ServerPlayer player, int slot) {
        if (slot < 0 || slot >= openSlots(player)) {
            return;
        }
        if (player.getData(ModAttachments.LOADOUT).active() != slot) {
            io.github.verycooltimo.murim.combat.FootworkService.cancel(player);
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

    /**
     * Отдельная техника (ладонь, Падающий Цветок) занимает R на одно применение: после
     * успешного старта активным снова становится слот стиля, чья основа стоит на ЛКМ
     * (двойное кольцо, автор 03.10). Формы стилей и шаги ничего не возвращают.
     */
    public static void returnToStance(ServerPlayer player, ResourceLocation cast) {
        if (io.github.verycooltimo.murim.technique.Styles.of(cast).isPresent()
                || io.github.verycooltimo.murim.combat.FootworkService.family(
                        io.github.verycooltimo.murim.technique.TechniqueLoader.get(cast)) != null) {
            return;
        }
        Loadout loadout = player.getData(ModAttachments.LOADOUT);
        if (loadout.foundation().isEmpty()) {
            return;
        }
        for (int i = 0; i < openSlots(player); i++) {
            Optional<ResourceLocation> t = loadout.at(i);
            if (i != loadout.active() && t.isPresent() && io.github.verycooltimo.murim.technique.Styles.of(t.get())
                    .flatMap(io.github.verycooltimo.murim.technique.Styles.Style::basic).equals(loadout.foundation())) {
                player.setData(ModAttachments.LOADOUT, loadout.select(i));
                sync(player);
                return;
            }
        }
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
        if (ArtSlots.kind(technique) == ArtSlots.Kind.BODY_PROTECTION) {
            if (loadout.bodyProtection().isEmpty()) {
                player.setData(ModAttachments.LOADOUT, loadout.withBodyProtection(Optional.of(technique)));
            }
            return;
        }
        if (!ArtSlots.accepts(0, technique)) return;
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
        for (int i = 0; i < normal.slots().size(); i++) {
            var art = normal.at(i);
            if (art.isPresent() && !ArtSlots.accepts(i, art.get())) {
                if (ArtSlots.kind(art.get()) == ArtSlots.Kind.BODY_PROTECTION && normal.bodyProtection().isEmpty()) {
                    normal = normal.withBodyProtection(art);
                }
                normal = normal.with(i, Optional.empty());
            }
        }
        if (normal != loadout) {
            player.setData(ModAttachments.LOADOUT, normal);
            loadout = normal;
        }
        io.github.verycooltimo.murim.combat.FootworkService.selectionChanged(player);
        int open = openSlots(player);
        java.util.List<Optional<ResourceLocation>> slots = new java.util.ArrayList<>();
        for (int i = 0; i < open; i++) {
            slots.add(loadout.at(i));
        }
        LoadoutPayloads.Sync sync = new LoadoutPayloads.Sync(slots,
                Math.min(loadout.active(), Math.max(0, open - 1)), open, loadout.foundation(),
                player.getData(ModAttachments.CULTIVATION).method().map(Arts::methodArt), loadout.bodyProtection());
        // GameTest mock players have no mod channel.
        // API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/ICommonPacketListener.java#hasChannel
        if (player.connection != null && player.connection.hasChannel(sync)) {
            PacketDistributor.sendToPlayer(player, sync);
        }
    }

    private LoadoutService() {
    }
}
