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

    /** ПКМ по NPC: первая точка входа диалога его роли, чьи условия выполнены (или перехват младшего — {@link #route}). */
    public static void open(ServerPlayer player, SectDisciple npc) {
        // Приговор к пещере покаяния, который некому было прочесть, читает первый же член секты (seal/PenanceService).
        if (player.getData(ModAttachments.SECT).has(io.github.verycooltimo.murim.sect.seal.PenanceService.SUMMONED)
                && openDialogue(player, npc, io.github.verycooltimo.murim.sect.seal.PenanceService.DIALOGUE, "sentence")) {
            return;
        }
        if (SectProgression.canApologise(player, npc)
                && openDialogue(player, npc, SectProgression.DIALOGUE, "discipline")) return;
        if (npc.role() == SectRole.LEADER && !SectPositions.disciplined(SectService.state(player))) {
            for (String office : List.of("elder", "first")) {
                SectPositions.offer(player, office, false);
                if (SectService.state(player).has("position.offer." + office)
                        && SectPositions.eligible(SectService.state(player), player.getData(ModAttachments.PROFILE).rank(), office)
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
        if (!npc.isAlive() || npc.guardTarget() != null || npc.escorting() && !npc.escortGoal().forPlayer(player)
                || npc.level() != player.level() || npc.talkingTo() >= 0 && npc.talkingTo() != player.getId()) {
            return false;
        }
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
        return true;
    }

    /** Говорящий встаёт к собеседнику; перехвативший — ещё и между игроком и тем, к кому он шёл. */
    static void engage(ServerPlayer player, SectDisciple npc, Route r) {
        // Сидящий или спящий встаёт к собеседнику.
        if (r.intercepted()) {
            r.speaker().approachDialogue(player, r.id(), r.node(), npc.position());
            MurimMod.LOGGER.info("Секта: {} перехватил {} на пути к {}", r.speaker().memberKey(), player.getName().getString(), npc.memberKey());
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

    /** Радиус, в котором ищется перехватчик (от игрока). */
    static final double INTERCEPT_RANGE = 14.0D;

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
        Dialogue.Node node = d == null ? null : d.nodes().get(s.node());
        if (index < 0 || npc == null || node == null || !npc.isAlive() || index >= s.shown().size()
                || npc.distanceTo(player) > MAX_DISTANCE) {
            close(player, npc);
            return;
        }
        Dialogue.Option option = node.options().get(s.shown().get(index));
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
            if (all(player, npc, o.when())) {
                shown.add(i);
                options.add(optionText(o));
            }
        }
        String lineKey = node.random().isEmpty() ? node.line()
                : node.random().stream().anyMatch(k -> k.startsWith("@"))
                ? SectSocial.pickToken(player, npc, node.random())
                : SectSocial.pick(player, npc, node.random());
        Component line = lineKey.startsWith("@") ? talk(player, npc, lineKey)
                : Component.translatable(lineKey, args(player, node.args()));
        node.gesture().ifPresent(npc::gesture);
        player.setData(ModAttachments.DIALOGUE, new Session(npc.getId(), id, nodeId, List.copyOf(shown)));
        // Диалог перехвата без титула: титул говорящего — из его собственного диалога.
        String titleKey = d.title();
        if (titleKey.isEmpty()) {
            Dialogue own = DialogueLoader.get(npc.dialogue());
            titleKey = own == null ? "" : own.title();
        }
        Component title = titleKey.isEmpty() ? Component.empty() : Component.translatable(titleKey);
        DialoguePayloads.Open open = new DialoguePayloads.Open(npc.getId(), npc.getName(), title, line, options, anim);
        // GameTest mock players have no mod channel: an NPC-initiated dialogue (guard, elder) must not crash the tick.
        // API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/ICommonPacketListener.java#hasChannel
        send(player, open);
    }

    /** Текст варианта: у обмена заслуг — с ценой ({@code merit_buy}, {@link MeritShop}). */
    static Component optionText(Dialogue.Option o) {
        for (Dialogue.Action a : o.actions()) {
            if ("merit_buy".equals(a.type())) {
                Optional<MeritShop.Offer> offer = MeritShop.offer(a.value().orElse(""));
                if (offer.isPresent()) {
                    return Component.translatable(o.text(), offer.get().price());
                }
            }
        }
        return Component.translatable(o.text());
    }

    public static void close(ServerPlayer player, SectDisciple npc) {
        player.setData(ModAttachments.DIALOGUE, Session.NONE);
        if (npc != null && npc.talkingTo() == player.getId()) {
            npc.setTalkingTo(null);
        }
        send(player, new DialoguePayloads.Close());
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
        SectStanding now = SectStanding.of(sect, profile.rank());
        if (c.minStanding().isPresent() && !now.atLeast(SectStanding.parse(c.minStanding().get()).orElse(SectStanding.TRUSTED))) {
            return false;
        }
        if (c.belowStanding().isPresent() && now.atLeast(SectStanding.parse(c.belowStanding().get()).orElse(SectStanding.OUTSIDER))) {
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
        if ("@journey".equals(token)) return SectJourney.line(player);
        if ("@council".equals(token)) return SectProgression.councilNews(player);
        if ("@rival".equals(token)) return SectProgression.rivalLine(player);
        if ("@context".equals(token)) return SectSocial.context(player, npc);
        if (SectTalk.RUMOUR.equals(token)) {
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
                SectReview.daysUntil(time));
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
                args[i] = Component.translatable("npc.murim." + s.substring(4));
            } else if (a instanceof String s && s.startsWith("name:")) {
                args[i] = Component.literal(s.substring(5));
            } else {
                args[i] = a;
            }
        }
        return Component.translatable(l.key(), args);
    }

    // ------------------------------------------------------------------ аргументы реплики

    private static Object[] args(ServerPlayer player, List<String> spec) {
        Object[] out = new Object[spec.size()];
        for (int i = 0; i < spec.size(); i++) {
            String a = spec.get(i);
            if ("player".equals(a)) {
                out[i] = player.getName();
            } else if ("standing".equals(a)) {
                out[i] = Component.translatable(SectService.standing(player).nameKey());
            } else if ("sect_goal".equals(a)) {
                out[i] = SectJourney.line(player);
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
            case "arrival_invite" -> { if (!SectArrival.invite(player, npc)) return "retry"; }
            case "arrival_guide" -> {
                close(player, npc);
                if (!SectArrival.guide(player, npc.layout()))
                    player.displayClientMessage(Component.translatable("murim.sect.arrival.guide_busy"), false);
                return "close";
            }
            case "arrival_admit" -> { if (!SectArrival.admit(player, npc)) return "retry"; }
            case "escort" -> {
                if (!SectEscorts.available(player, npc, v)) return "retry";
                Vec3 target = SectEscorts.target(player, npc, v);
                close(player, npc);
                return npc.escortGoal().begin(player, target, false) ? "close" : "retry";
            }
            case "journey_help" -> { if (!SectJourney.help(player, npc)) return "retry"; }
            case "progression_menu" -> {
                if (!SectProgression.matches(player, npc, "menu")) return "retry";
                return openDialogue(player, npc, SectProgression.DIALOGUE, v.isEmpty() ? "menu" : v) ? "switch" : "retry";
            }
            case "progression" -> {
                if (!SectProgression.act(player, npc, v)) return "retry";
                if ("rival".equals(v)) return "close";
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
            case "set_flag" -> {
                SectStanding before = SectService.standing(player);
                player.setData(ModAttachments.SECT, player.getData(ModAttachments.SECT).with(v));
                SectService.announceStanding(player, before);
            }
            case "contribute" -> {
                try {
                    SectService.contribute(player, Integer.parseInt(v.trim()));
                } catch (NumberFormatException e) {
                    MurimMod.LOGGER.warn("Диалог: заслуги не числом: {}", v);
                }
            }
            case "donate" -> SectService.donate(player, v);
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
                }
            }
            case "heal" -> {
                // Лекарь Гён Чхо лечит раны после поединков (план §1.1: «лечит травмы с тренировок»).
                if (player.getHealth() < player.getMaxHealth()) {
                    player.heal(player.getMaxHealth());
                    player.level().playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.BREWING_STAND_BREW,
                            net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, 1.3F);
                    player.displayClientMessage(Component.translatable("murim.sect.healed", npc.getName())
                            .withStyle(net.minecraft.ChatFormatting.GREEN), true);
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
                }
            }
            case "clear_flag" -> player.setData(ModAttachments.SECT, player.getData(ModAttachments.SECT).without(v));
            case "give_book" -> SectService.giveBook(player, ResourceLocation.parse(v));
            case "join_sect" -> SectService.join(player);
            case "gesture" -> npc.gesture(v);
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
