package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.DialoguePayloads;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Разговор с NPC на сервере (план секты §4.3): выбор узла, проверка условий, действия.
 * Клиент только показывает реплику и сообщает номер варианта; каждый выбор проверяется заново —
 * вариант, условия которого перестали выполняться, не сработает.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DialogueService {

    /** Дальше этого собеседник уходит — разговор окончен. */
    public static final double MAX_DISTANCE = 8.0D;

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

    /** ПКМ по NPC: первая точка входа диалога его роли, чьи условия выполнены (или перехват младшего — {@link #route}). */
    public static void open(ServerPlayer player, SectDisciple npc) {
        if (busy(player, npc)) return;
        if (npc.wanderer()) Wanderer.noticed(player, npc);
        // The council sits: its members do not talk business now, a word over the head instead (author 21.10).
        if (SectCouncilIntrusion.refuses(player, npc)) {
            return;
        }
        if (PlumBlossomCare.meet(player, npc)) return;
        // A member back after a day or more away is asked where he was, once, by the mentor or a senior (SectAbsence).
        SectAbsence.meet(player, npc);
        SectGoalTalk.acknowledge(player, npc);
        // Приговор к пещере покаяния, который некому было прочесть, читает первый же член секты (seal/PenanceService).
        if (player.getData(ModAttachments.SECT).has(io.github.verycooltimo.murim.sect.seal.PenanceService.SUMMONED)
                && openDialogue(player, npc, io.github.verycooltimo.murim.sect.seal.PenanceService.DIALOGUE, "sentence")) {
            return;
        }
        if (SectProgression.canApologise(player, npc)
                && openDialogue(player, npc, SectProgression.DIALOGUE, "discipline")) return;
        // A witness of a closed art who said he would talk about it and never got to (the player walked off) takes it up now.
        String owed = SectReactions.owed(player, npc);
        if (owed != null && openDialogue(player, npc, SectReactions.WITNESS, owed)) return;
        if (npc.role() == SectRole.LEADER && !SectPositions.disciplined(SectService.state(player))) {
            for (String office : List.of("elder", "first")) {
                SectPositions.offer(player, office, false);
                if (SectService.state(player).has("position.offer." + office)
                        && SectPositions.eligible(player, office)
                        && openDialogue(player, npc, SectPositions.DIALOGUE, office)) return;
            }
        }
        Route r = route(player, npc);
        if (r == null) {
            MurimMod.LOGGER.warn("Нет диалога {} для {}", npc.dialogue(), npc.role());
            return;
        }
        if (r.intercepted()) {
            engage(player, npc, r);
        } else {
            openDialogue(player, r.speaker(), r.id(), r.node());
        }
    }

    /** Shared by RMB and scripted dialogue entry; the current escort may talk to its own player. */
    public static boolean busy(ServerPlayer player, SectDisciple npc) {
        boolean busy = SectBladeExam.busy(player, npc) || npc.guardTarget() != null || npc.defending() || npc.spar() != SectDisciple.Spar.NONE
                || npc.escorting() && !npc.escortGoal().forPlayer(player)
                || npc.talkingTo() >= 0 && npc.talkingTo() != player.getId();
        if (busy && npc.isAlive() && npc.level() == player.level())
            SectChatter.sayIfQuiet(npc, SectBubbles.Group.BUSY.pick(player, npc.getRandom()), 40);
        return busy;
    }

    /**
     * Открыть узел {@code node} диалога {@code id} от имени {@code npc} — не по роли NPC (приговор к пещере покаяния).
     *
     * @return диалог и узел нашлись
     */
    public static boolean openDialogue(ServerPlayer player, SectDisciple npc, ResourceLocation id, String node) {
        if (io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.active(player)) return false;
        Dialogue d = DialogueLoader.get(id);
        if (d == null || !d.nodes().containsKey(node)) {
            MurimMod.LOGGER.warn("Нет диалога {} с узлом {}", id, node);
            return false;
        }
        if (!npc.isAlive() || npc.level() != player.level() || busy(player, npc)) return false;
        Session old = player.getData(ModAttachments.DIALOGUE);
        if (old.active() && old.npc() != npc.getId()) {
            if (player.level().getEntity(old.npc()) instanceof io.github.verycooltimo.murim.npc.DialogueActor guest) {
                GuestDialogueService.close(player, guest);
            } else {
                close(player, player.level().getEntity(old.npc()) instanceof SectDisciple previous ? previous : null);
            }
        }
        npc.wake();
        npc.setTalkingTo(player);
        show(player, npc, id, d, node, "");
        if (SectReactions.WITNESS.equals(id)) {
            // He has spoken: the talk he owed is had, whoever opened it (his own walk up, or the player's right click).
            SectReactions.settle(player, npc);
        }
        return true;
    }

    /** Говорящий встаёт к собеседнику; перехвативший — ещё и между игроком и тем, к кому он шёл. */
    static void engage(ServerPlayer player, SectDisciple npc, Route r) {
        // Сидящий или спящий встаёт к собеседнику.
        if (r.intercepted()) {
            boolean coming = r.speaker().approachDialogue(player, r.id(), r.node(), npc.position());
            MurimMod.LOGGER.info("Секта: {} перехватил {} на пути к {}", r.speaker().memberKey(), player.getName().getString(), npc.memberKey());
            if (coming) {
                // The guard calls out as he steps in his way, and the head waves the newcomer off politely: nobody is punished,
                // he is only turned back (playtest 19.10).
                SectBubbles.Group group = npc.role() == SectRole.LEADER ? SectBubbles.Group.GUARD_STOP : SectBubbles.Group.GUARD_STOP_ELDER;
                SectChatter.sayIfQuiet(r.speaker(), group.pick(player, r.speaker().getRandom()), 60);
                r.speaker().faceEntity(player, 40.0F);
                if (npc.free() && !npc.isSleeping()) {
                    npc.gesture("shake");
                    npc.faceEntity(player, 30.0F);
                }
            }
        } else {
            r.speaker().wake();
            r.speaker().setTalkingTo(player);
        }
    }

    /**
     * Кто ответит и с какого узла: обычно сам NPC; если у его диалога есть {@code audience} и игроку говорить с ним
     * не по положению — ближайший старший или охранник ({@link SectRole#intercepts()}, Гён Так) со своим диалогом
     * перехвата, а если рядом никого — сам NPC узлом {@code busy}.
     *
     * @param speaker     кто говорит
     * @param intercepted перехват (говорит не тот, к кому подошли)
     */
    public record Route(SectDisciple speaker, ResourceLocation id, Dialogue dialogue, String node, boolean intercepted) {
    }

    /**
     * Радиус, в котором ищется перехватчик (от игрока): ближайший страж идёт, а дальний тоже (playtest 19.10: глава сидит
     * в зале, пост стражи у двери, 14 блоков не хватало, и новичок говорил с главой «Не сейчас» без единого стража).
     */
    static final double INTERCEPT_RANGE = 48.0D;

    public static Route route(ServerPlayer player, SectDisciple npc) {
        ResourceLocation id = npc.dialogue();
        if (!SectService.state(player).member() && SectArrival.greeter(npc)) id = SectArrival.DIALOGUE;
        Dialogue d = DialogueLoader.get(id);
        if (d == null) {
            return null;
        }
        if (d.audience().isPresent() && !any(player, npc, d.audience().get().allow())) {
            Dialogue.Audience a = d.audience().get();
            SectDisciple by = a.intercept().isPresent() ? interceptor(player, npc) : null;
            Dialogue cut = a.intercept().map(DialogueLoader::get).orElse(null);
            if (by != null && cut != null) {
                String node = entry(player, by, cut);
                if (node != null) {
                    return new Route(by, a.intercept().get(), cut, node, true);
                }
            }
            if (a.busy().isPresent() && d.nodes().containsKey(a.busy().get())) {
                return new Route(npc, id, d, a.busy().get(), false);
            }
        }
        String node = entry(player, npc, d);
        return node == null ? null : new Route(npc, id, d, node, false);
    }

    /** Страж сам перехватывает младшего, подошедшего к старейшине, без обязательного ПКМ. */
    public static void interceptNearElder(ServerPlayer player) {
        if (player.getData(ModAttachments.DIALOGUE).active()
                || player.getData(ModAttachments.SECT).has(io.github.verycooltimo.murim.sect.seal.PenanceService.SUMMONED)) {
            return;
        }
        // The morning line: everybody stands in the rows round the head and the elders, nobody is «approaching» them.
        if (SectSchedule.at(player.level().getDayTime()) == SectSchedule.Period.FORMATION) {
            return;
        }
        String stamp = "murim_elder_intercept_at";
        long now = player.level().getGameTime();
        if (player.getPersistentData().contains(stamp) && now - player.getPersistentData().getLong(stamp) < 200) {
            return;
        }
        SectDisciple target = player.level().getEntitiesOfClass(SectDisciple.class, player.getBoundingBox().inflate(6.0D),
                        d -> d.isAlive() && d.free() && !d.blocking() && (d.role() == SectRole.ELDER || d.role() == SectRole.LEADER)
                                && d.distanceToSqr(player) <= 36.0D && d.getSensing().hasLineOfSight(player))
                .stream().min(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(player))).orElse(null);
        if (target != null) {
            Route r = route(player, target);
            if (r != null && r.intercepted() && !r.speaker().approachingDialogue(player, r.id())) {
                player.getPersistentData().putLong(stamp, now);
                engage(player, target, r);
            }
        }
    }

    private static String entry(ServerPlayer player, SectDisciple npc, Dialogue d) {
        for (Dialogue.Entry e : d.start()) {
            if (!io.github.verycooltimo.murim.quest.world.HuaDryStreamCave.ENABLED && e.node().startsWith("cache_")) continue;
            if (!io.github.verycooltimo.murim.quest.StoryAvailability.nodeAllowed(e.node())
                    && !io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.legacySites(player)) continue;
            if (all(player, npc, e.when())) {
                return e.node();
            }
        }
        return null;
    }

    /** Ближайший к игроку, кто может перехватить: свободный, не спящий, не сам NPC. */
    static SectDisciple interceptor(ServerPlayer player, SectDisciple target) {
        SectDisciple best = null;
        double bestD = INTERCEPT_RANGE;
        for (SectDisciple d : player.level().getEntitiesOfClass(SectDisciple.class, player.getBoundingBox().inflate(INTERCEPT_RANGE),
                d -> d != target && d.isAlive() && d.free() && !d.isSleeping() && SectWatch.guards(d)
                        && (!d.blocking() || d.blockTarget() == player.getId()) && (d.role().intercepts() || "gyeong_tak".equals(d.memberKey())))) {
            double dist = d.distanceTo(player);
            if (dist < bestD) {
                best = d;
                bestD = dist;
            }
        }
        return best;
    }

    /** Хотя бы одно условие выполнено (пустой список — никто). */
    static boolean any(ServerPlayer player, SectDisciple npc, List<Dialogue.Condition> conditions) {
        for (Dialogue.Condition c : conditions) {
            if (test(player, npc, c)) {
                return true;
            }
        }
        return false;
    }

    /** Клиент выбрал вариант {@code index} показанного узла; −1 — закрыл разговор. */
    public static void choose(ServerPlayer player, int index) {
        Session s = player.getData(ModAttachments.DIALOGUE);
        if (!s.active()) {
            return;
        }
        if (player.level().getEntity(s.npc()) instanceof io.github.verycooltimo.murim.npc.DialogueActor guest) {
            GuestDialogueService.choose(player, guest, index);
            return;
        }
        SectDisciple npc = player.level().getEntity(s.npc()) instanceof SectDisciple n ? n : null;
        Dialogue d = DialogueLoader.get(s.dialogue());
        Dialogue.Node node = d == null ? null : nodeOf(d, s.node());
        if (index < 0 || npc == null || node == null || !npc.isAlive() || index >= s.shown().size()
                || npc.distanceTo(player) > MAX_DISTANCE) {
            close(player, npc);
            return;
        }
        // Shown numbers past the node's own options are the hooks of the goal and the daily task (SectGoalTalk).
        int shownAt = s.shown().get(index);
        if (shownAt == SectGeneralTalk.MAP) {
            if (!Wanderer.needsDirections(player)) { close(player, npc); return; }
            boolean given = Wanderer.giveMap(player);
            player.getPersistentData().putBoolean("murim_gate_map_given", given);
            SectGeneralTalk.ask(player, SectGeneralTalk.QUESTIONS.size());
            show(player, npc, s.dialogue(), d, SectGeneralTalk.NODE, "");
            return;
        }
        if (shownAt == SectGeneralTalk.DECLINE && npc.wanderer()) {
            Wanderer.decline(player);
            npc.approachCooldown(io.github.verycooltimo.murim.entity.WandererApproachGoal.COOLDOWN);
            SectGeneralTalk.farewell(player, npc);
            return;
        }
        if (shownAt == SectGeneralTalk.MATTERS) {
            show(player, npc, s.dialogue(), d, "@matters:" + s.node(), "");
            return;
        }
        if (shownAt == SectGeneralTalk.OPEN) {
            show(player, npc, s.dialogue(), d, SectGeneralTalk.MENU, "");
            return;
        }
        if (shownAt == SectGeneralTalk.CLOSE) {
            SectGeneralTalk.farewell(player, npc);
            return;
        }
        if (shownAt >= SectGeneralTalk.BASE) {
            int question = shownAt - SectGeneralTalk.BASE;
            if (question >= SectGeneralTalk.QUESTIONS.size() || !SectGeneralTalk.MENU.equals(s.node())) {
                show(player, npc, s.dialogue(), d, s.node(), "");
                return;
            }
            SectGeneralTalk.ask(player, question);
            show(player, npc, s.dialogue(), d, SectGeneralTalk.NODE, "");
            return;
        }
        Dialogue.Option option;
        if (shownAt == SectBouts.SHARED) {
            if (!SectBouts.sharedRow(player, npc, d, s.node())) {
                show(player, npc, s.dialogue(), d, s.node(), "");
                return;
            }
            option = SectBouts.sharedOption();
        } else if (shownAt >= node.options().size()) {
            int slot = shownAt - node.options().size();
            SectGoalTalk.Hook hook = slot < SectGoalTalk.SLOTS && s.dialogue().equals(npc.dialogue()) && SectGoalTalk.hub(player, npc, d, s.node())
                    ? SectGoalTalk.hooks(player, npc, d)[slot] : null;
            if (hook == null) {
                show(player, npc, s.dialogue(), d, s.node(), "");
                return;
            }
            option = hook.option();
        } else {
            option = node.options().get(shownAt);
        }
        // Сервер проверяет вариант заново: условие могло перестать выполняться, пока экран был открыт.
        if (!all(player, npc, option.when())) {
            show(player, npc, s.dialogue(), d, s.node(), "");
            return;
        }
        String anim = "";
        boolean closing = false;
        for (Dialogue.Action a : option.actions()) {
            String r = act(player, npc, a);
            if ("switch".equals(r)) return;
            if ("retry".equals(r)) {
                show(player, npc, s.dialogue(), d, s.node(), "");
                return;
            }
            if ("close".equals(r)) {
                closing = true;
            } else if (r != null) {
                anim = r;
            }
        }
        if (!closing && option.next().isPresent() && SectGeneralTalk.BACK.equals(option.next().get())) {
            String start = entry(player, npc, d);
            if (start != null) {
                show(player, npc, s.dialogue(), d, start, anim);
                return;
            }
        }
        if (closing || option.next().isEmpty() || !SectGoalTalk.NODE.equals(option.next().get()) && !d.nodes().containsKey(option.next().get())) {
            if (player.getData(ModAttachments.DIALOGUE).active()) {
                SectGeneralTalk.farewell(player, npc);
            }
            return;
        }
        show(player, npc, s.dialogue(), d, option.next().get(), anim);
    }

    /** Options that must stay reachable when a menu is longer than the cap: the way back to a lost tablet and the rest of the matters. */
    private static final java.util.Set<String> PINNED = java.util.Set.of(
            "dialogue.murim.mentor.opt.lost_book", "dialogue.murim.mentor.opt.lost_tablet", "dialogue.murim.mentor.opt.next_art", "dialogue.murim.social.opt.topics",
            "dialogue.murim.general.opt.gate", "dialogue.murim.wanderer.decline");
    /** What goes first when a menu is too long: the bout menu, then the escort menu (both have other doors). */
    private static final List<String> DROP_FIRST = List.of("dialogue.murim.bout.opt.ring", "dialogue.murim.escort.opt.menu");

    /**
     * Cuts the visible options to {@link Dialogue.MAX_OPTIONS} without ever hiding a pinned one (author rule: a player without
     * a tablet must always find the replacement): the droppable ones go first, then the last ordinary ones; the goal hooks
     * (empty key) and the pinned stay.
     */
    static void trimOptions(List<Integer> shown, List<Component> options, List<String> keys) {
        trimOptions(shown, options, keys, Dialogue.MAX_OPTIONS);
    }

    private static void trimOptions(List<Integer> shown, List<Component> options, List<String> keys, int limit) {
        while (shown.size() > limit) {
            int drop = -1;
            for (String key : DROP_FIRST) {
                drop = keys.indexOf(key);
                if (drop >= 0) break;
            }
            for (int i = keys.size() - 1; drop < 0 && i >= 0; i--) {
                if (!keys.get(i).isEmpty() && !PINNED.contains(keys.get(i))) drop = i;
            }
            if (drop < 0) drop = keys.size() - 1;
            shown.remove(drop);
            options.remove(drop);
            keys.remove(drop);
        }
    }

    /** Показать узел: действия входа, реплика, видимые варианты (не больше четырёх). */
    static void show(ServerPlayer player, SectDisciple npc, ResourceLocation id, Dialogue d, String nodeId, String anim) {
        Dialogue.Node node = nodeOf(d, nodeId);
        if (node == null) {
            close(player, npc);
            return;
        }
        if (SectService.state(player).member() && node.enter().stream().anyMatch(a -> "wanderer_point".equals(a.type()))) {
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
        List<String> keys = new ArrayList<>();
        // The first options of the person's own opening menu: what the goal and the daily task are about (SectGoalTalk). The
        // numbers shown for them lie past the node's own options, so the node's numbering never moves.
        if (id.equals(npc.dialogue()) && SectGoalTalk.hub(player, npc, d, nodeId)) {
            SectGoalTalk.Hook[] hooks = SectGoalTalk.hooks(player, npc, d);
            for (int slot = 0; slot < hooks.length; slot++) {
                if (hooks[slot] != null) {
                    shown.add(node.options().size() + slot);
                    options.add(optionText(player, hooks[slot].option()));
                    keys.add("");
                }
            }
        }
        if (SectBouts.sharedRow(player, npc, d, nodeId)) {
            shown.add(SectBouts.SHARED);
            options.add(optionText(player, SectBouts.sharedOption()));
            keys.add("");
        }
        // The plain "leave" rows of the file are replaced by one closing option at the very bottom (SectGeneralTalk); a custom
        // action or custom reply stays; the explicit polite exit is always pinned last.
        int nativeRows = 0;
        boolean folded = false;
        boolean hub = SectGoalTalk.hub(player, npc, d, nodeId);
        for (int i = 0; i < node.options().size(); i++) {
            Dialogue.Option o = node.options().get(i);
            if (!io.github.verycooltimo.murim.quest.StoryAvailability.nodeAllowed(o.next().orElse(""))
                    && !io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.legacySites(player)) continue;
            if (SectGeneralTalk.standardCloser(o)) {
                continue;
            }
            if (all(player, npc, o.when())) {
                if (hub && nativeRows++ >= 1) { folded = true; continue; }
                shown.add(i);
                options.add(optionText(player, o));
                keys.add(o.text());
            }
        }
        if (folded) {
            shown.add(SectGeneralTalk.MATTERS);
            options.add(Component.translatable("dialogue.murim.general.opt.matters"));
            keys.add("");
        }
        trimOptions(shown, options, keys);
        // General questions at the opening menu; every node, including terminal lines, has a polite way out.
        if (SectGeneralTalk.MENU.equals(nodeId)) {
            for (int q = 0; q < SectGeneralTalk.QUESTIONS.size(); q++) {
                shown.add(SectGeneralTalk.BASE + q);
                options.add(SectGeneralTalk.question(q));
                keys.add("");
            }
        }
        if (SectGoalTalk.hub(player, npc, d, nodeId)) {
            shown.add(SectGeneralTalk.OPEN);
            options.add(Component.translatable("dialogue.murim.general.opt.talk"));
            keys.add("");
        }
        if ((npc.member().isPresent() || npc.wanderer()) && Wanderer.needsDirections(player)) {
            shown.add(SectGeneralTalk.MAP);
            options.add(Component.translatable("dialogue.murim.general.opt.gate"));
            keys.add("dialogue.murim.general.opt.gate");
        }
        if (npc.wanderer()) {
            shown.add(SectGeneralTalk.DECLINE);
            options.add(Component.translatable("dialogue.murim.wanderer.decline"));
            keys.add("dialogue.murim.wanderer.decline");
        }
        trimOptions(shown, options, keys, Dialogue.MAX_OPTIONS - 1);
        shown.add(SectGeneralTalk.CLOSE);
        options.add(SectGeneralTalk.closing(npc, player.getRandom()));
        boolean closer = true;
        // A person who walked up to the player says what he came for first (SectApproach#takeOpeningLine); only a greeting
        // node (a random list) can be replaced, a fixed line of the story stays what it is.
        Component line = node.random().isEmpty() ? null : SectApproach.takeOpeningLine(player, npc);
        if (SectPersonalLines.hasFor(npc, node.random())) line = null;
        if (line == null) {
            String lineKey = node.random().isEmpty() ? node.line()
                    : node.random().stream().anyMatch(k -> k.startsWith("@"))
                    ? SectSocial.pickToken(player, npc, node.random())
                    : SectSocial.pick(player, npc, node.random());
            line = lineKey.startsWith("@") ? talk(player, npc, lineKey)
                    : Component.translatable(lineKey, args(player, npc, node.args()));
        }
        node.gesture().ifPresent(g -> SectSocial.gesture(npc, player, g));
        player.setData(ModAttachments.DIALOGUE, new Session(npc.getId(), id, nodeId, List.copyOf(shown)));
        // Диалог перехвата без титула: титул говорящего — из его собственного диалога.
        String titleKey = d.title();
        if (titleKey.isEmpty()) {
            Dialogue own = DialogueLoader.get(npc.dialogue());
            titleKey = own == null ? "" : own.title();
        }
        Component title = titleKey.isEmpty() ? Component.empty() : Component.translatable(titleKey);
        // Newcomer tour: the node names a fixed camera shot of a building; the client camera glides there and back.
        Optional<DialoguePayloads.Shot> shot = node.shot().flatMap(sid -> SectTour.shot(player, SectLife.layout(player.serverLevel()), sid));
        DialoguePayloads.Open open = new DialoguePayloads.Open(npc.getId(), npc.getName(), title, line, options, anim, shot, closer);
        // GameTest mock players have no mod channel: an NPC-initiated dialogue (guard, elder) must not crash the tick.
        // API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/ICommonPacketListener.java#hasChannel
        send(player, open);
    }

    /** The node: the dialogue's own, or the person's answer to a hook ({@link SectGoalTalk#NODE}). */
    static Dialogue.Node nodeOf(Dialogue d, String nodeId) {
        if (nodeId.startsWith("@matters:")) {
            Dialogue.Node source = d.nodes().get(nodeId.substring("@matters:".length()));
            return source == null ? null : new Dialogue.Node("@matters", List.of(), List.of(), Optional.of("explain"),
                    List.of(), source.options(), Optional.empty());
        }
        if (SectGeneralTalk.MENU.equals(nodeId)) return SectGeneralTalk.menu();
        if (SectGeneralTalk.NODE.equals(nodeId)) return SectGeneralTalk.node();
        return SectGoalTalk.NODE.equals(nodeId) ? SectGoalTalk.node() : d.nodes().get(nodeId);
    }

    /** Текст варианта: у обмена заслуг — с ценой ({@code merit_buy}, {@link MeritShop}); у подсказки цели — со своими числами. */
    static Component optionText(ServerPlayer player, Dialogue.Option o) {
        for (Dialogue.Action a : o.actions()) {
            if ("goal_talk".equals(a.type())) {
                return SectGoalTalk.text(player, a.value().orElse(""));
            }
            if ("merit_buy".equals(a.type())) {
                Optional<MeritShop.Offer> offer = MeritShop.offer(a.value().orElse(""));
                if (offer.isPresent()) {
                    // The price is the player's own: a page costs 8 + 2 s merit for the star it leads to. The second
                    // argument is the personal stock told in words (empty for a row without one).
                    return Component.translatable(o.text(), MeritShop.price(player, offer.get()), MeritShop.stockSuffix(player, offer.get()));
                }
            }
        }
        return Component.translatable(o.text());
    }

    public static void close(ServerPlayer player, SectDisciple npc) {
        Session session = player.getData(ModAttachments.DIALOGUE);
        if (session.active() && SectArrival.DIALOGUE.equals(session.dialogue())
                && !SectService.state(player).member() && !SectService.state(player).has(SectArrival.INVITED)) {
            SectGateWatch.leave(player);
        }
        player.setData(ModAttachments.DIALOGUE, Session.NONE);
        if (npc != null && npc.talkingTo() == player.getId() && !npc.awaitingGuide(player)) {
            npc.setTalkingTo(null);
        }
        send(player, new DialoguePayloads.Close());
    }

    /** Death, logout and a change of dimension end the talk at once (holes review npc-3); the NPC's own watchdog is the net under this. */
    static void dropSession(ServerPlayer player) {
        Session s = player.getData(ModAttachments.DIALOGUE);
        if (!s.active()) {
            return;
        }
        var e = player.level().getEntity(s.npc());
        if (e instanceof io.github.verycooltimo.murim.npc.DialogueActor guest) {
            GuestDialogueService.close(player, guest);
        } else {
            close(player, e instanceof SectDisciple n ? n : null);
        }
    }

    @SubscribeEvent
    static void onDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) dropSession(p);
    }

    @SubscribeEvent
    static void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) dropSession(p);
    }

    @SubscribeEvent
    static void onDimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        // The old level's entity is out of reach by id now: free every sect person who still names this player.
        if (event.getEntity() instanceof ServerPlayer p) {
            var old = p.server.getLevel(event.getFrom());
            if (old != null) {
                for (SectDisciple n : old.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(64.0D), d -> d.talkingTo() == p.getId())) {
                    n.setTalkingTo(null);
                }
            }
            p.setData(ModAttachments.DIALOGUE, Session.NONE);
        }
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
        if (player.level().getEntity(s.npc()) instanceof io.github.verycooltimo.murim.npc.DialogueActor guest) {
            GuestDialogueService.tick(player, guest);
            return;
        }
        SectDisciple npc = player.level().getEntity(s.npc()) instanceof SectDisciple n ? n : null;
        if (npc == null || !npc.isAlive() || npc.distanceTo(player) > MAX_DISTANCE + 2.0D) {
            close(player, npc);
        }
    }

    // ------------------------------------------------------------------ условия

    static boolean all(ServerPlayer player, SectDisciple npc, List<Dialogue.Condition> conditions) {
        for (Dialogue.Condition c : conditions) {
            if (!test(player, npc, c)) {
                return false;
            }
        }
        return true;
    }

    static boolean test(ServerPlayer player, SectDisciple npc, Dialogue.Condition c) {
        if (c.period().isPresent()) {
            String now = SectSchedule.at(player.level().getDayTime()).id();
            if (!java.util.Arrays.asList(c.period().get().split("\\|")).contains(now)) {
                return false;
            }
        }
        if (c.free().isPresent() && npc != null) {
            // Собеседник «свободен», если не в поединке с другим и не в обороне (разговор с нами — не занятость).
            boolean free = npc.spar() == SectDisciple.Spar.NONE && !npc.defending();
            if (free != c.free().get()) {
                return false;
            }
        }
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
            int layer = MasteryService.stars(player, c.technique().get());
            if (c.minStar() >= 0 && layer < c.minStar()) {
                return false;
            }
            if (c.belowStar() >= 0 && layer >= c.belowStar()) {
                return false;
            }
        }
        if (c.minRank() >= 0 && profile.rank() < c.minRank()) {
            return false;
        }
        if (c.belowRank() >= 0 && profile.rank() >= c.belowRank()) {
            return false;
        }
        if (!standing(player, npc, sect, profile, c.standing())) {
            return false;
        }
        return c.awakened().isEmpty() || MasteryRules.canLearn(profile) == c.awakened().get();
    }

    /** Положение, заслуги, предмет в инвентаре. */
    private static boolean standing(ServerPlayer player, SectDisciple npc, SectState sect, DantianProfile profile, Dialogue.Standing c) {
        if (c.social().isPresent() && !SectSocial.matches(player, npc, c.social().get())) return false;
        if (c.shop().isPresent() && !MeritShop.listed(c.shop().get())) return false;
        // The social rank: the office if held (elder > first disciple), otherwise the standing ladder (SectRank).
        SectRank now = SectRank.of(sect, profile.rank());
        if (c.minStanding().isPresent() && !now.atLeast(SectRank.parse(c.minStanding().get()).orElse(SectRank.TRUSTED))) {
            return false;
        }
        if (c.belowStanding().isPresent() && now.atLeast(SectRank.parse(c.belowStanding().get()).orElse(SectRank.OUTSIDER))) {
            return false;
        }
        if (c.minContribution() >= 0 && sect.contribution() < c.minContribution()) {
            return false;
        }
        if (c.attended().isPresent() || c.missed().isPresent() || c.chores().isPresent() || c.logged().isPresent()) {
            // Журнал секты (распорядок для игрока, автор 05.10).
            SectAttendance.Log log = SectAttendance.current(player);
            SectAttendance.Day today = log.today();
            if (c.attended().isPresent() && !SectAttendance.Activity.of(c.attended().get()).map(today::did).orElse(false)) {
                return false;
            }
            if (c.missed().isPresent() && !SectAttendance.Activity.of(c.missed().get()).map(today::missed).orElse(false)) {
                return false;
            }
            if (c.chores().isPresent() && log.chores() != c.chores().get()) {
                return false;
            }
            if (c.logged().isPresent() && (today.present() || log.last().present()) != c.logged().get()) {
                return false;
            }
        }
        if (c.hasItem().isPresent()) {
            SectService.ItemNeed need = SectService.ItemNeed.parse(c.hasItem().get());
            return need != null && need.count(player) >= need.count();
        }
        return true;
    }

    // ------------------------------------------------------------------ личные реплики учеников

    /** Черта, личная фраза или слух ({@link SectTalk}): реплика по ключу говорящего, дню и журналу секты. */
    static Component talk(ServerPlayer player, SectDisciple npc, String token) {
        if ("@six_progress".equals(token)) return MentorNextArt.sixReply(player);
        if ("@next_art".equals(token)) return MentorNextArt.reply(player);
        if ("@war_terms".equals(token)) return SectWar.terms(player);
        if ("@head_offer".equals(token)) return Component.translatable(SectHeadIntro.canOffer(player, npc)
                ? "dialogue.murim.head_intro.offer" : "dialogue.murim.escort.menu");
        if ("@matters".equals(token)) return Component.translatable("dialogue.murim.general.matters");
        if (SectGeneralTalk.MENU.equals(token)) return Component.translatable("dialogue.murim.general.talk");
        if ("@journey".equals(token)) return SectJourney.line(player, npc);
        if (SectGoalTalk.NODE.equals(token)) return SectGoalTalk.reply(player, npc);
        if (SectGeneralTalk.NODE.equals(token)) return SectGeneralTalk.reply(player, npc);
        if ("@council".equals(token)) return SectProgression.councilNews(player);
        if ("@rival".equals(token)) return SectProgression.rivalLine(player);
        if ("@bouts".equals(token)) return SectBouts.menuLine(player, npc);
        if ("@context".equals(token)) return SectSocial.context(player, npc);
        if (SectTalk.RUMOUR.equals(token)) {
            Component bout = SectBouts.rumourLine(player, npc);
            if (bout != null) return bout;
            var rumour = npc.getPersistentData().getCompound(SectSocial.RUMOUR);
            if (rumour.getLong("until") > player.level().getGameTime() && !rumour.getString("player").isEmpty()) {
                String key = SectSocial.pick(player, npc, SectSocial.bubbleKeys("rumour"));
                return Component.translatable(key, Component.literal(rumour.getString("player")));
            }
        }
        long time = player.level().getDayTime();
        SectState sect = player.getData(ModAttachments.SECT);
        String champion = player.level() instanceof net.minecraft.server.level.ServerLevel level ? SectLife.data(level).lastChampion() : "";
        SectTalk.Context ctx = new SectTalk.Context(champion, player.getName().getString(), sect.has(SectTalk.REVIEW_WON),
                false,
                SectReview.daysUntil(time), SectRank.of(sect, player.getData(ModAttachments.PROFILE).rank()).office());
        java.util.Map<String, SectTalk.Line> candidates = new java.util.LinkedHashMap<>();
        for (int roll = 0; roll < 64; roll++) {
            SectTalk.Line candidate = SectTalk.line(token, npc.memberKey(), SectSchedule.day(time),
                    SectSchedule.at(time) == SectSchedule.Period.NIGHT, roll, ctx);
            candidates.putIfAbsent(candidate.key(), candidate);
        }
        SectTalk.Line l = candidates.get(SectSocial.pick(player, npc, List.copyOf(candidates.keySet())));
        Object[] args = new Object[l.args().size()];
        for (int i = 0; i < args.length; i++) {
            Object a = l.args().get(i);
            if ("player".equals(a)) {
                args[i] = player.getName();
            } else if (a instanceof String s && s.startsWith("npc:")) {
                args[i] = SectNames.of(s.substring(4));
            } else if (a instanceof String s && s.startsWith("name:")) {
                args[i] = Component.literal(s.substring(5));
            } else {
                args[i] = a;
            }
        }
        return Component.translatable(l.key(), args);
    }

    // ------------------------------------------------------------------ аргументы реплики

    private static Object[] args(ServerPlayer player, SectDisciple npc, List<String> spec) {
        Object[] out = new Object[spec.size()];
        for (int i = 0; i < spec.size(); i++) {
            String a = spec.get(i);
            if (a.startsWith("tour_")) {
                // the way to the player's own room, named in the mentor's tour lines (SectDormPath)
                SectLayout layout = SectLife.layout(player.serverLevel());
                BlockPos bed = SectRooms.bed(player);
                out[i] = SectDormPath.arg(layout == null || bed == null ? java.util.Optional.empty() : SectDormPath.of(layout.file(), bed), a);
            } else if (a.startsWith("period_")) {
                // a part of the day by its real name (SectSchedule.Period), so a line never states hours of its own
                out[i] = Component.translatable("murim.tablet.period."
                        + SectSchedule.Period.valueOf(a.substring(7).toUpperCase(java.util.Locale.ROOT)).name().toLowerCase(java.util.Locale.ROOT));
            } else if (a.startsWith("quest_readiness:")) {
                Component missing = io.github.verycooltimo.murim.cultivation.MeditationService.readiness(player);
                out[i] = missing == null ? Component.translatable("murim.threshold." + a.substring("quest_readiness:".length()) + ".ready") : missing;
            } else if ("meditation_key".equals(a)) {
                out[i] = Component.keybind("key.murim.meditation");
            } else if ("player".equals(a)) {
                out[i] = player.getName();
            } else if ("standing".equals(a)) {
                out[i] = Component.translatable(SectService.standing(player).nameKey());
            } else if ("position".equals(a)) {
                out[i] = Component.translatable(SectPositions.titleKey(player.getData(ModAttachments.SECT)));
            } else if ("sect_goal".equals(a)) {
                out[i] = talk(player, npc, "@journey");
            } else if ("sect_log".equals(a)) {
                out[i] = SectAttendance.line(player);
            } else if ("sect_verdict".equals(a)) {
                out[i] = SectAttendance.verdict(player);
            } else if ("contribution".equals(a)) {
                out[i] = player.getData(ModAttachments.SECT).contribution();
            } else if ("penance_days".equals(a)) {
                out[i] = io.github.verycooltimo.murim.sect.seal.PenanceRules.DAYS;
            } else if ("penance_minutes".equals(a)) {
                out[i] = io.github.verycooltimo.murim.sect.seal.PenanceRules.MEDITATION_QUOTA / 1200;
            } else if ("penance_cost".equals(a)) {
                out[i] = io.github.verycooltimo.murim.sect.seal.PenanceRules.REFUSE_COST;
            } else if ("merit".equals(a)) {
                out[i] = player.getData(ModAttachments.SECT).merit();
            } else if ("review_days".equals(a)) {
                out[i] = SectReview.daysUntil(player.level().getDayTime());
            } else if ("rank".equals(a)) {
                out[i] = player.getData(ModAttachments.PROFILE).rank();
            } else if (a.startsWith("place:")) {
                // Where a member of the sect stands now, by the built layout (docs/design/38 section 5.7).
                out[i] = SectLife.whereIs(player.serverLevel(), a.substring("place:".length()), player.blockPosition());
            } else if (a.startsWith("star:")) {
                out[i] = Math.max(0, MasteryService.stars(player, ResourceLocation.parse(a.substring(5))));
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
            case "arrival_invite" -> {
                if (!SectArrival.invite(player, npc)) return "retry";
            }
            case "arrival_guide" -> {
                npc.awaitGuide(player);
                SectArrival.order(player, npc.layout());
                openDialogue(player, npc, SectArrival.DIALOGUE, "invited");
                return "switch";
            }
            case "arrival_unguide" -> SectArrival.decline(player);
            case "arrival_introduce" -> { if (!SectArrival.introduce(player, npc)) return "retry"; }
            case "head_intro" -> {
                if (!SectHeadIntro.start(player, npc)) return "retry";
                close(player, npc);
                return "close";
            }
            case "head_intro_done" -> { if (!SectHeadIntro.complete(player, npc)) return "retry"; }
            case "arrival_admit" -> { if (!SectArrival.admit(player, npc)) return "retry"; }
            case "escort" -> {
                if (!SectEscorts.available(player, npc, v)) return "retry";
                Vec3 target = SectEscorts.target(player, npc, v);
                close(player, npc);
                if (!npc.escortGoal().begin(player, target, false)) return "retry";
                if (!"room".equals(v)) npc.escortGoal().follow(() -> SectEscorts.target(player, npc, v));
                return "close";
            }
            case "war_pay", "war_labour" -> {
                if (!SectWar.pay(player, npc, "war_labour".equals(a.type()))) return "retry";
                return "close";
            }
            case "wanderer_point" -> Wanderer.point(player, npc);
            case "journey_help" -> { if (!SectJourney.help(player, npc)) return "retry"; }
            case "book_replace" -> { if (!SectService.replaceBook(player, npc)) return "retry"; }
            case "tablet_replace" -> { if (!SectTablet.replace(player)) return "retry"; }
            case "journey_hud" -> SectJourney.toggleHud(player);
            case "goal_talk" -> { return SectGoalTalk.act(player, npc, v); }
            case "serve_bowl" -> SectMeals.serve(player);
            case "approach_decline" -> SectApproach.decline(player, npc);
            case "progression_menu" -> {
                if (!SectProgression.matches(player, npc, "menu")) return "retry";
                return openDialogue(player, npc, SectProgression.DIALOGUE, v.isEmpty() ? "menu" : v) ? "switch" : "retry";
            }
            case "progression" -> {
                if (!SectProgression.act(player, npc, v)) return "retry";
                if ("rival".equals(v) || "train".equals(v) || "challenge".equals(v)) return "close";
            }
            case "request_menu" -> {
                return openDialogue(player, npc, SectRequests.DIALOGUE, "menu") ? "switch" : "retry";
            }
            case "request_start" -> { if (!SectRequests.start(player, npc, v)) return "retry"; }
            case "request_complete" -> { if (!SectRequests.complete(player, npc, v)) return "retry"; }
            case "promotion_offer" -> {
                if (npc.role() != SectRole.LEADER || !SectPositions.offer(player, v, true)) return "retry";
                return openDialogue(player, npc, SectPositions.DIALOGUE, v) ? "switch" : "retry";
            }
            case "promotion_accept" -> { if (!SectPositions.accept(player, npc, v)) return "retry"; }
            case "promotion_decline" -> SectPositions.decline(player, npc, v);
            case "blade_exam" -> SectBladeExam.accept(player, npc);
            case "blade_exam_decline" -> SectBladeExam.decline(player, npc);
            case "set_flag" -> {
                SectStanding before = SectService.standing(player);
                player.setData(ModAttachments.SECT, player.getData(ModAttachments.SECT).with(v));
                SectService.announceStanding(player, before);
                if (v.equals("lesson.six")) io.github.verycooltimo.murim.quest.MentorStrikeDrill.describe(player);
            }
            case "contribute" -> {
                try {
                    SectService.contribute(player, Integer.parseInt(v.trim()));
                } catch (NumberFormatException e) {
                    MurimMod.LOGGER.warn("Диалог: заслуги не числом: {}", v);
                }
            }
            case "donate" -> { if (!SectService.donate(player, v)) return "retry"; }
            case "chore" -> {
                // Добровольная помощь кухне раз в день; возвращаем пустое ведро.
                SectService.ItemNeed need = SectService.ItemNeed.parse(v.isEmpty() ? SectAttendance.CHORE_ITEM : v);
                if (need != null && need.count(player) >= need.count() && SectService.state(player).member()
                        && !SectAttendance.current(player).today().did(SectAttendance.Activity.CHORE)) {
                    need.take(player);
                    if (need.item() == net.minecraft.world.item.Items.WATER_BUCKET) {
                        net.minecraft.world.item.ItemStack bucket = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET);
                        if (!player.getInventory().add(bucket)) {
                            player.drop(bucket, false);
                        }
                    }
                    SectAttendance.record(player, SectAttendance.Activity.CHORE);
                } else return "retry";
            }
            case "assist_elixir" -> {
                if ("gyeong_cho".equals(npc.memberKey()) || "tae_seong".equals(npc.memberKey())) {
                    io.github.verycooltimo.murim.cultivation.GreatElixirs.assist(player);
                }
            }
            case "heal" -> {
                // Лекарь Гён Чхо лечит раны после поединков (план §1.1: «лечит травмы с тренировок»).
                if (player.getHealth() < player.getMaxHealth()) {
                    player.heal(player.getMaxHealth());
                    player.level().playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.BREWING_STAND_BREW,
                            net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, 1.3F);
                }
                npc.holdPose(io.github.verycooltimo.murim.entity.SectPose.TREAT, 60);
            }
            case "record" -> SectAttendance.record(player, v);
            case "merit_buy" -> MeritShop.buy(player, v);
            case "review_enter" -> SectReview.signUp(player);
            case "penance_accept" -> {
                close(player, npc);
                io.github.verycooltimo.murim.sect.seal.PenanceService.accept(player);
                return "close";
            }
            case "penance_refuse" -> io.github.verycooltimo.murim.sect.seal.PenanceService.refuse(player);
            case "take" -> {
                // Просьба человека секты: отдать ему предметы (без заслуг — заслуги, если нужны, отдельным действием).
                SectService.ItemNeed need = SectService.ItemNeed.parse(v);
                if (need != null && need.count(player) >= need.count()) {
                    need.take(player);
                } else return "retry";
            }
            case "quest_fight", "quest_escort", "quest_hand_in" -> {
                if (!io.github.verycooltimo.murim.quest.QuestScenes.action(player, npc, a.type(), v)) return "retry";
            }
            case "quest_note" -> PlayerJournal.record(player, Component.translatable(v));
            case "quest_lesson_accept" -> {
                if (!io.github.verycooltimo.murim.quest.QuestLessons.accept(player, npc, v)) return "retry";
            }
            case "quest_lesson_start" -> {
                if (!io.github.verycooltimo.murim.quest.QuestLessons.start(player, npc, v)) return "retry";
                return "close";
            }
            case "quest_lesson_review" -> {
                if (!io.github.verycooltimo.murim.quest.QuestLessons.review(player, npc, v)) return "retry";
            }
            case "quest_threshold" -> {
                if (!io.github.verycooltimo.murim.quest.QuestLessons.inspect(player, npc, v)) return "retry";
            }
            case "quest_threshold_decide" -> {
                String[] parts = v.split(":", 2);
                if (parts.length == 2) io.github.verycooltimo.murim.quest.QuestLessons.decision(player, parts[0], parts[1]);
            }
            case "quest_trial" -> {
                // A spar with a condition (docs/design/45-quest-format.md, step "trial"): the rule was said on the node, now the bout.
                if (!SectTrials.start(player, npc, v)) return "retry";
                return "close";
            }
            case "give_clue" -> io.github.verycooltimo.murim.quest.Clues.give(player, v);
            case "quest_how" -> {
                // "quest:way" - the sect remembers how the player solved the quest (io.github.verycooltimo.murim.quest.Quests).
                String[] qw = v.split(":", 2);
                if (qw.length == 2) io.github.verycooltimo.murim.quest.Quests.remember(player, qw[0], qw[1]);
            }
            case "clear_flag" -> player.setData(ModAttachments.SECT, player.getData(ModAttachments.SECT).without(v));
            case "teach_bamboo_hand" -> SectService.teachBamboo(player, npc);
            case "teach_seven_stars" -> SectService.teachSevenStars(player, npc);
            case "give_book" -> SectService.giveBook(player, ResourceLocation.parse(v));
            case "join_sect" -> SectService.join(player);
            case "gesture" -> SectSocial.gesture(npc, player, v);
            case "bow" -> {
                // Поклон игрока (табличкам предков, наставнику): клип spar_bow у игрока на этой реплике.
                return MurimMod.MODID + ":spar_bow";
            }
            case "start_spar" -> {
                if (npc.spars() && npc.spar() == SectDisciple.Spar.NONE && !npc.defending()) {
                    close(player, npc);
                    npc.startSpar(player, 20);
                    return "close";
                }
            }
            default -> MurimMod.LOGGER.warn("Неизвестное действие диалога: {}", a.type());
        }
        return null;
    }

    /**
     * Вариант без экрана (GameTest): условия и действия — те же, что при выборе в разговоре.
     *
     * @return выполнен ли вариант (условия выполнились)
     */
    static boolean applyHeadless(ServerPlayer player, SectDisciple npc, Dialogue.Option option) {
        if (!all(player, npc, option.when())) {
            return false;
        }
        for (Dialogue.Action a : option.actions()) {
            if ("retry".equals(act(player, npc, a))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ проверка данных

    /** Ошибки ссылок в диалоге (узлы, на которые указывают вход и варианты). */
    static List<String> validate(Dialogue d) {
        List<String> bad = new ArrayList<>();
        if (d.start().isEmpty()) {
            bad.add("нет точек входа");
        }
        for (Dialogue.Entry e : d.start()) {
            if (!io.github.verycooltimo.murim.quest.world.HuaDryStreamCave.ENABLED && e.node().startsWith("cache_")) continue;
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

    /** GameTest mock players have no mod channel: NPC-initiated dialogues must not crash the server tick. */
    // API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/ICommonPacketListener.java#hasChannel
    private static void send(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
