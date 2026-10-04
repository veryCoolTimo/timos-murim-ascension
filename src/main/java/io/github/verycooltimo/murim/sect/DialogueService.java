package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.DialoguePayloads;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Разговор с NPC на сервере (план секты §4.3): выбор узла, проверка условий, действия.
 * Клиент только показывает реплику и сообщает номер варианта; каждый выбор проверяется заново —
 * вариант, условия которого перестали выполняться, не сработает.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DialogueService {

    /** Дальше этого собеседник уходит — разговор окончен. */
    static final double MAX_DISTANCE = 8.0D;

    /**
     * Идущий разговор: NPC, диалог, узел и индексы показанных вариантов в узле.
     * {@code npc} −1 — разговора нет.
     */
    public record Session(int npc, ResourceLocation dialogue, String node, List<Integer> shown) {
        public static final Session NONE = new Session(-1, null, "", List.of());

        public boolean active() {
            return npc >= 0;
        }
    }

    private DialogueService() {
    }

    // ------------------------------------------------------------------ вход и выбор

    /** ПКМ по NPC: первая точка входа диалога его роли, чьи условия выполнены. */
    public static void open(ServerPlayer player, SectDisciple npc) {
        Dialogue d = DialogueLoader.get(npc.role().dialogue());
        if (d == null) {
            MurimMod.LOGGER.warn("Нет диалога {} для {}", npc.role().dialogue(), npc.role());
            return;
        }
        for (Dialogue.Entry e : d.start()) {
            if (all(player, e.when())) {
                npc.setTalkingTo(player);
                show(player, npc, npc.role().dialogue(), d, e.node(), "");
                return;
            }
        }
    }

    /** Клиент выбрал вариант {@code index} показанного узла; −1 — закрыл разговор. */
    public static void choose(ServerPlayer player, int index) {
        Session s = player.getData(ModAttachments.DIALOGUE);
        if (!s.active()) {
            return;
        }
        SectDisciple npc = player.level().getEntity(s.npc()) instanceof SectDisciple n ? n : null;
        Dialogue d = DialogueLoader.get(s.dialogue());
        Dialogue.Node node = d == null ? null : d.nodes().get(s.node());
        if (index < 0 || npc == null || node == null || !npc.isAlive() || index >= s.shown().size()
                || npc.distanceTo(player) > MAX_DISTANCE) {
            close(player, npc);
            return;
        }
        Dialogue.Option option = node.options().get(s.shown().get(index));
        // Сервер проверяет вариант заново: условие могло перестать выполняться, пока экран был открыт.
        if (!all(player, option.when())) {
            show(player, npc, s.dialogue(), d, s.node(), "");
            return;
        }
        String anim = "";
        boolean closing = false;
        for (Dialogue.Action a : option.actions()) {
            String r = act(player, npc, a);
            if ("close".equals(r)) {
                closing = true;
            } else if (r != null) {
                anim = r;
            }
        }
        if (closing || option.next().isEmpty() || !d.nodes().containsKey(option.next().get())) {
            if (player.getData(ModAttachments.DIALOGUE).active()) {
                close(player, npc);
            }
            return;
        }
        show(player, npc, s.dialogue(), d, option.next().get(), anim);
    }

    /** Показать узел: действия входа, реплика, видимые варианты (не больше четырёх). */
    static void show(ServerPlayer player, SectDisciple npc, ResourceLocation id, Dialogue d, String nodeId, String anim) {
        Dialogue.Node node = d.nodes().get(nodeId);
        if (node == null) {
            close(player, npc);
            return;
        }
        for (Dialogue.Action a : node.enter()) {
            String r = act(player, npc, a);
            if (r != null && !"close".equals(r)) {
                anim = r;
            }
        }
        List<Integer> shown = new ArrayList<>();
        List<Component> options = new ArrayList<>();
        for (int i = 0; i < node.options().size() && shown.size() < Dialogue.MAX_OPTIONS; i++) {
            Dialogue.Option o = node.options().get(i);
            if (all(player, o.when())) {
                shown.add(i);
                options.add(Component.translatable(o.text()));
            }
        }
        String lineKey = node.random().isEmpty() ? node.line()
                : node.random().get(player.getRandom().nextInt(node.random().size()));
        Component line = Component.translatable(lineKey, args(player, node.args()));
        node.gesture().ifPresent(npc::gesture);
        player.setData(ModAttachments.DIALOGUE, new Session(npc.getId(), id, nodeId, List.copyOf(shown)));
        Component title = d.title().isEmpty() ? Component.empty() : Component.translatable(d.title());
        PacketDistributor.sendToPlayer(player, new DialoguePayloads.Open(npc.getId(), npc.getName(), title, line, options, anim));
    }

    public static void close(ServerPlayer player, SectDisciple npc) {
        player.setData(ModAttachments.DIALOGUE, Session.NONE);
        if (npc != null && npc.talkingTo() == player.getId()) {
            npc.setTalkingTo(null);
        }
        PacketDistributor.sendToPlayer(player, new DialoguePayloads.Close());
    }

    /** Собеседник ушёл или NPC исчез — разговор окончен. */
    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 10 != 0) {
            return;
        }
        Session s = player.getData(ModAttachments.DIALOGUE);
        if (!s.active()) {
            return;
        }
        SectDisciple npc = player.level().getEntity(s.npc()) instanceof SectDisciple n ? n : null;
        if (npc == null || !npc.isAlive() || npc.distanceTo(player) > MAX_DISTANCE + 2.0D) {
            close(player, npc);
        }
    }

    // ------------------------------------------------------------------ условия

    static boolean all(ServerPlayer player, List<Dialogue.Condition> conditions) {
        for (Dialogue.Condition c : conditions) {
            if (!test(player, c)) {
                return false;
            }
        }
        return true;
    }

    static boolean test(ServerPlayer player, Dialogue.Condition c) {
        SectState sect = player.getData(ModAttachments.SECT);
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (c.flag().isPresent() && !sect.has(c.flag().get())) {
            return false;
        }
        if (c.notFlag().isPresent() && sect.has(c.notFlag().get())) {
            return false;
        }
        if (c.member().isPresent() && sect.member() != c.member().get()) {
            return false;
        }
        if (c.knows().isPresent() && !MasteryService.knows(player, c.knows().get())) {
            return false;
        }
        if (c.notKnows().isPresent() && MasteryService.knows(player, c.notKnows().get())) {
            return false;
        }
        if (c.technique().isPresent()) {
            int layer = MasteryService.layer(player, c.technique().get());
            if (c.minLayer() >= 0 && layer < c.minLayer()) {
                return false;
            }
            if (c.belowLayer() >= 0 && layer >= c.belowLayer()) {
                return false;
            }
        }
        if (c.minRank() >= 0 && profile.rank() < c.minRank()) {
            return false;
        }
        if (c.belowRank() >= 0 && profile.rank() >= c.belowRank()) {
            return false;
        }
        return c.awakened().isEmpty() || MasteryRules.canLearn(profile) == c.awakened().get();
    }

    // ------------------------------------------------------------------ аргументы реплики

    private static Object[] args(ServerPlayer player, List<String> spec) {
        Object[] out = new Object[spec.size()];
        for (int i = 0; i < spec.size(); i++) {
            String a = spec.get(i);
            if ("player".equals(a)) {
                out[i] = player.getName();
            } else if ("rank".equals(a)) {
                out[i] = player.getData(ModAttachments.PROFILE).rank();
            } else if (a.startsWith("layer:")) {
                out[i] = Math.max(0, MasteryService.layer(player, ResourceLocation.parse(a.substring(6))));
            } else {
                out[i] = a;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ действия

    /**
     * Выполняет действие. Возвращает клип игрока для этой реплики, {@code "close"} — закрыть разговор,
     * иначе {@code null}.
     */
    private static String act(ServerPlayer player, SectDisciple npc, Dialogue.Action a) {
        String v = a.value().orElse("");
        switch (a.type()) {
            case "set_flag" -> player.setData(ModAttachments.SECT, player.getData(ModAttachments.SECT).with(v));
            case "clear_flag" -> player.setData(ModAttachments.SECT, player.getData(ModAttachments.SECT).without(v));
            case "give_book" -> SectService.giveBook(player, ResourceLocation.parse(v));
            case "join_sect" -> SectService.join(player);
            case "gesture" -> npc.gesture(v);
            case "bow" -> {
                // Поклон игрока (табличкам предков, наставнику): клип spar_bow у игрока на этой реплике.
                return MurimMod.MODID + ":spar_bow";
            }
            case "start_spar" -> {
                if (npc.spars()) {
                    close(player, npc);
                    npc.startSpar(player, 20);
                    return "close";
                }
            }
            default -> MurimMod.LOGGER.warn("Неизвестное действие диалога: {}", a.type());
        }
        return null;
    }

    // ------------------------------------------------------------------ проверка данных

    /** Ошибки ссылок в диалоге (узлы, на которые указывают вход и варианты). */
    static List<String> validate(Dialogue d) {
        List<String> bad = new ArrayList<>();
        for (Dialogue.Entry e : d.start()) {
            if (!d.nodes().containsKey(e.node())) {
                bad.add("вход ведёт в несуществующий узел " + e.node());
            }
        }
        d.nodes().forEach((id, n) -> {
            if (n.line().isEmpty() && n.random().isEmpty()) {
                bad.add("узел " + id + " без реплики");
            }
            for (Dialogue.Option o : n.options()) {
                o.next().filter(x -> !d.nodes().containsKey(x)).ifPresent(x -> bad.add("узел " + id + ": вариант ведёт в " + x));
            }
        });
        return bad;
    }
}
