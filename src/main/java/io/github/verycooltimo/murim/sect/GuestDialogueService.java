package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.npc.DialogueActor;
import io.github.verycooltimo.murim.network.DialoguePayloads;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Outsiders use the same JSON codec, session and screen as sect NPCs, with a small action whitelist. */
public final class GuestDialogueService {
    private GuestDialogueService() { }

    private static boolean valid(ServerPlayer p, DialogueActor n) {
        return n.actor().level() == p.level() && n.available() && n.actor().distanceTo(p) <= DialogueService.MAX_DISTANCE
                && (n.talkingTo() < 0 || n.talkingTo() == p.getId());
    }

    private static boolean conditions(ServerPlayer p, DialogueActor n, List<Dialogue.Condition> list) {
        for (var c : list) {
            if (c.free().isPresent() && c.free().get() != n.available()) return false;
            // Existing rank, item, membership and flag checks remain authoritative. No invented sect role.
            if (!DialogueService.test(p, null, c)) return false;
        }
        return true;
    }

    public static boolean open(ServerPlayer p, DialogueActor n) {
        if (!valid(p, n)) return false;
        Dialogue d = DialogueLoader.get(n.profile().dialogue());
        if (d == null) return false;
        for (var e : d.start()) {
            if (conditions(p, n, e.when())) {
                // Release whichever actor owns the previous session before switching speakers.
                var old = p.getData(ModAttachments.DIALOGUE);
                if (old.active()) DialogueService.choose(p, -1);
                n.talkingTo(p);
                show(p, n, d, e.node());
                return true;
            }
        }
        return false;
    }

    public static void choose(ServerPlayer p, DialogueActor n, int index) {
        var s = p.getData(ModAttachments.DIALOGUE);
        Dialogue d = DialogueLoader.get(s.dialogue());
        var node = d == null ? null : d.nodes().get(s.node());
        if (index < 0 || !valid(p, n) || n.talkingTo() != p.getId() || s.npc() != n.actor().getId()
                || !n.profile().dialogue().equals(s.dialogue()) || node == null || index >= s.shown().size()) {
            close(p, n);
            return;
        }
        int shown = s.shown().get(index);
        if (shown == SectGeneralTalk.CLOSE) {
            farewell(p, n);
            return;
        }
        if (shown < 0 || shown >= node.options().size()) { close(p, n); return; }
        var o = node.options().get(shown);
        if (!conditions(p, n, o.when())) { show(p, n, d, s.node()); return; }
        for (var a : o.actions()) {
            if (!action(p, n, a)) { show(p, n, d, s.node()); return; }
            if (!p.getData(ModAttachments.DIALOGUE).active()) return;
        }
        if (o.next().isPresent()) show(p, n, d, o.next().get());
        else farewell(p, n);
    }

    private static boolean action(ServerPlayer p, DialogueActor n, Dialogue.Action a) {
        String v = a.value().orElse("");
        switch (a.type()) {
            case "set_flag" -> {
                if (!v.startsWith("guest.") && !v.startsWith("quest.")) return false;
                p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(v));
                return true;
            }
            case "guest_spar", "guest_challenge", "guest_trade", "guest_buy_tip", "guest_report" -> {
                return n.dialogueAction(p, a.type());
            }
            case "quest_fight", "quest_escort", "quest_hand_in" -> {
                return io.github.verycooltimo.murim.quest.QuestScenes.action(p, n.actor(), a.type(), v);
            }
            case "give_clue" -> {
                io.github.verycooltimo.murim.quest.Clues.give(p, v);
                return true;
            }
            case "clear_flag" -> {
                p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without(v));
                return true;
            }
            case "quest_how" -> {
                String[] qw = v.split(":", 2);
                if (qw.length == 2) io.github.verycooltimo.murim.quest.Quests.remember(p, qw[0], qw[1]);
                return true;
            }
            default -> { return false; }
        }
    }

    private static void show(ServerPlayer p, DialogueActor n, Dialogue d, String id) {
        var node = d.nodes().get(id);
        if (node == null || !valid(p, n)) { close(p, n); return; }
        for (var a : node.enter()) if (!action(p, n, a)) { close(p, n); return; }
        List<Integer> shown = new ArrayList<>();
        List<Component> labels = new ArrayList<>();
        for (int i = 0; i < node.options().size() && shown.size() < Dialogue.MAX_OPTIONS; i++) {
            var o = node.options().get(i);
            if (SectGeneralTalk.standardCloser(o)) continue;
            if (conditions(p, n, o.when())) { shown.add(i); labels.add(DialogueService.optionText(p, o)); }
        }
        shown.add(SectGeneralTalk.CLOSE);
        labels.add(Component.translatable(SectGeneralTalk.pick(SectGeneralTalk.CLOSE_STEM + "plain", p.getRandom())));
        String line = node.random().isEmpty() ? node.line() : node.random().get(n.actor().getRandom().nextInt(node.random().size()));
        p.setData(ModAttachments.DIALOGUE, new DialogueService.Session(n.actor().getId(), n.profile().dialogue(), id, List.copyOf(shown)));
        send(p, new DialoguePayloads.Open(n.actor().getId(), n.actor().getName(), Component.translatable(d.title()),
                Component.translatable(line), labels, "", java.util.Optional.empty(), true));
    }

    private static void farewell(ServerPlayer p, DialogueActor n) {
        String key = SectGeneralTalk.pick(SectGeneralTalk.BUBBLE_STEM + "plain", p.getRandom());
        SectChatter.sendAbove(n.actor(), key, Component.translatable(key));
        close(p, n);
    }

    public static void close(ServerPlayer p, DialogueActor n) {
        if (n != null && n.talkingTo() == p.getId()) n.talkingTo(null);
        p.setData(ModAttachments.DIALOGUE, DialogueService.Session.NONE);
        send(p, new DialoguePayloads.Close());
    }

    static void tick(ServerPlayer p, DialogueActor n) {
        if (!valid(p, n) || n.talkingTo() != p.getId()) close(p, n);
    }

    private static void send(ServerPlayer p, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (p.connection != null && p.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(p, payload);
    }
}
