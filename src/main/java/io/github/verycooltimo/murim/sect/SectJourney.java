package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MethodLoader;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.item.MethodScrollItem;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.SectGoalPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.training.BodyRules;
import io.github.verycooltimo.murim.training.TrainingService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;


/** One personal invitation, derived from existing progress. No deadlines or server-wide newcomer gates. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectJourney {
    public static final String SCROLL_GIVEN = "journey.method_given";
    public static final ResourceLocation PETAL = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "falling_petal_sword");
    // The awarded style manual teaches its basic first; the Cut is still locked by its own requires.
    public static final ResourceLocation SEVEN = Styles.SEVEN_PLUM.basic().orElseThrow();
    /** What the goal reminder last sent to a player; kept per session in {@link SectSiteData#goalSent}. */
    record Sent(Component text, ResourceLocation dimension) { }

    private SectJourney() { }

    /** The goal reminder on the screen is on by default; the journal (mentor dialogue, /murim dev sect log hud) toggles it. */
    public static final String HUD_OFF = "journey.hud_off";

    public record Goal(String id, String person, Component blocker, String progress) {
        public Goal(String id, String person, Component blocker) {
            this(id, person, blocker, "");
        }

        /** The same goal with a visible count such as {@code 2/3}. */
        public Goal counted(int done, int total) {
            return new Goal(id, person, blocker, Math.min(done, total) + "/" + total);
        }

        public Component name() {
            Component name = Component.translatable("murim.sect.journey.goal." + id);
            return progress.isEmpty() ? name : Component.empty().append(name).append(" (" + progress + ")");
        }

        /** One short line for the screen: the action and the person only, without the long hint. */
        public Component hudText() {
            return Component.translatable("murim.sect.journey.hud", name(),
                    SectNames.of(person));
        }

        public Component text() {
            return Component.translatable("murim.sect.journey.next", name(), blocker,
                    SectNames.of(person));
        }
    }

    private static Goal goal(String id, String person, String blocker, Object... args) {
        // «ready» says what to ask for, goal by goal; the person is named by the line itself.
        String key = "ready".equals(blocker) ? "ready." + id : blocker;
        return new Goal(id, person, Component.translatable("murim.sect.journey.blocker." + key, args));
    }

    private static boolean needsMethod(ServerPlayer p) {
        var cultivation = p.getData(ModAttachments.CULTIVATION);
        return cultivation.method().isEmpty() && !cultivation.seeded()
                && !p.getData(ModAttachments.PROFILE).isAwakened();
    }

    private static boolean hasScroll(ServerPlayer p) {
        return java.util.stream.Stream.concat(p.getInventory().items.stream(), p.getInventory().offhand.stream())
                .anyMatch(s -> s.is(ModItems.METHOD_SCROLL.get()) && SectService.SIX.equals(s.get(ModDataComponents.METHOD.get())));
    }

    public static boolean canHelp(ServerPlayer p, SectDisciple npc) {
        return npc != null && npc.role() == SectRole.MENTOR && npc.isAlive() && npc.level() == p.level()
                && npc.distanceToSqr(p) <= 64 && p.isAlive() && !p.isSpectator()
                && SectService.state(p).member() && needsMethod(p);
    }

    /** First issue is saved only after inventory delivery. Recovery teaches from the mentor's copy, never pays items. */
    public static boolean help(ServerPlayer p, SectDisciple npc) {
        return canHelp(p, npc) && issue(p);
    }

    /**
     * At joining a newcomer without a centre always gets the inner method scroll from Gyeong Pil, whoever took the
     * oath and wherever it happened (doc 39 item 2). Until the seed forms, plain attacks and body exercises work.
     */
    public static boolean grantAtJoin(ServerPlayer p) {
        return SectService.state(p).member() && needsMethod(p) && issue(p);
    }

    private static boolean issue(ServerPlayer p) {
        var method = MethodLoader.get(SectService.SIX);
        if (method == null) return false;
        SectState state = SectService.state(p);
        if (hasScroll(p)) {
            p.setData(ModAttachments.SECT, state.with(SCROLL_GIVEN));
            return true;
        }
        if (state.has(SCROLL_GIVEN)) {
            MethodScrollItem.learn(p, method);
            return true;
        }
        ItemStack scroll = new ItemStack(ModItems.METHOD_SCROLL.get());
        scroll.set(ModDataComponents.METHOD.get(), SectService.SIX);
        if (!p.getInventory().add(scroll)) {
            PlayerJournal.refuse(p, Component.translatable("murim.sect.journey.inventory_full"));
            return false;
        }
        p.setData(ModAttachments.SECT, state.with(SCROLL_GIVEN));
        SectNotice.chat(p, Component.translatable("murim.sect.journey.scroll_given",
                Component.translatable("method.murim.six_harmonies")));
        return true;
    }

    /** The main path in order: what the tablet's chain is made of (labels {@code murim.tablet.step.<id>}). */
    public static final java.util.List<String> TRACK = java.util.List.of("method", "read_method", "seed_feel", "seed_hold",
            "seed_compress", "centre", "learn_six", "take_six", "practice_six", "rank_one", "submit_six", "learn_petal",
            "take_spar", "clean_hits", "rank_two", "submit_spar", "learn_seven", "request", "partners", "ceremony", "direct",
            "standing", "approval", "rank_three", "first", "peak", "elder", "peak_stage", "free_practice", "personal_lesson");
    /** What an office holder still has ahead of him. */
    public static final java.util.List<String> OFFICE_TRACK = java.util.List.of("rank_three", "first", "peak", "elder",
            "peak_stage", "free_practice", "personal_lesson");

    /** The place of a goal on the path: the rank goals depend on the stage of the path, a pill delivery is the favour. */
    public static String slot(ServerPlayer p, Goal g) {
        SectState s = SectService.state(p);
        int rank = p.getData(ModAttachments.PROFILE).rank();
        boolean office = s.has(SectPositions.FIRST) || s.has(SectPositions.ELDER);
        return switch (g.id()) {
            case "rank_one", "rank_two", "rank_three", "peak" -> office ? (rank < Realm.FIRST ? "rank_three" : "peak")
                    : !s.has(SectStanding.LESSON_ONE) ? "rank_one" : !s.has(SectStanding.LESSON_TWO) ? "rank_two"
                    : rank < Realm.FIRST ? "rank_three" : "peak";
            case "deliver_pills" -> "request";
            default -> g.id();
        };
    }

    /**
     * The chain window and the index of the current step: the last two done, the current and only the next one; what lies
     * further is not shown, {@code more} only says that something does.
     */
    public record Chain(java.util.List<String> slots, int current, boolean more) { }

    public static Chain chain(ServerPlayer p) {
        SectState s = SectService.state(p);
        boolean office = s.has(SectPositions.FIRST) || s.has(SectPositions.ELDER);
        java.util.List<String> order = office ? OFFICE_TRACK : TRACK;
        Goal now = next(p);
        java.util.List<String> slots = new java.util.ArrayList<>();
        Goal main = interruption(now.id()) ? track(p) : now;
        int at = Math.max(0, order.indexOf(slot(p, main)));
        if (interruption(now.id())) {
            // an interruption is the current step; the path waits behind it
            slots.add(now.id());
            slots.add(order.get(at));
            return new Chain(slots, 0, at + 1 < order.size());
        }
        int from = Math.max(0, at - 2);
        for (int i = from; i < Math.min(order.size(), at + 2); i++) slots.add(order.get(i));
        return new Chain(slots, at - from, at + 2 < order.size());
    }

    /** Choosing another method, or arriving with a centre, never replaces that choice or grants a duplicate scroll. */
    public static Goal next(ServerPlayer p) {
        SectState s = SectService.state(p);
        var profile = p.getData(ModAttachments.PROFILE);
        var cultivation = p.getData(ModAttachments.CULTIVATION);
        SectStanding standing = SectStanding.of(s, profile.rank());
        if (standing == SectStanding.OUTSIDER) return goal("join", "gyeong_pil", "join");
        if (SectPositions.disciplined(s)) return goal("sentence", "tae_hwi", "sentence");
        if (s.has(SectProgression.REPAIR)) {
            String person = s.flags().stream().filter(f -> f.startsWith("discipline.victim."))
                    .map(f -> f.substring("discipline.victim.".length())).sorted().findFirst().orElse(SectProgression.MENTOR);
            return s.has("discipline.apologised") ? goal("repair", SectProgression.MENTOR, "repair")
                    : goal("apology", person, "apology");
        }
        // An invitation of his office (the council of elders, the audit, the morning line...) is the goal of the day while it is out;
        // it is never a duty: when the hour passes it simply goes away (docs/design/38, «Совет и приглашения»).
        Goal invitation = SectOffices.goal(p);
        return invitation != null ? invitation : track(p);
    }

    /** The goals that interrupt the path: the sentence, restitution, the apology, an invitation of the office. */
    public static boolean interruption(String id) {
        return "sentence".equals(id) || "repair".equals(id) || "apology".equals(id) || SectOffices.isInvitation(id);
    }

    /** The main path of a member, without the interruptions. Office holders skip the lessons that earn an office. */
    static Goal track(ServerPlayer p) {
        SectState s = SectService.state(p);
        var profile = p.getData(ModAttachments.PROFILE);
        var cultivation = p.getData(ModAttachments.CULTIVATION);
        SectStanding standing = SectStanding.of(s, profile.rank());
        boolean office = s.has(SectPositions.FIRST) || s.has(SectPositions.ELDER);
        if (needsMethod(p)) {
            return hasScroll(p) ? goal("read_method", "gyeong_pil", "read_method") : goal("method", "gyeong_pil", "method");
        }
        if (!profile.isAwakened()) {
            if (cultivation.seeded()) return goal("centre", "gyeong_pil", "centre");
            return goal(switch (cultivation.beats()) {
                case 0 -> "seed_feel";
                case 1 -> "seed_hold";
                default -> "seed_compress";
            }, "gyeong_pil", "seed", Component.keybind("key.murim.meditation"));
        }
        // Seven Stars is an optional mentor offer; it never gates the sword lessons or tablet.
        // The lessons, the favours and the trust steps are how an office is earned; a holder has them behind him.
        if (!office && !MasteryService.knows(p, SectService.SIX)) return goal("learn_six", "gyeong_pil", "book");
        if (!office && !s.has(SectStanding.LESSON_ONE)) {
            if (!s.has("lesson.six")) return goal("take_six", "gyeong_pil", "ready");
            if ("practice".equals(SectProgression.firstExamMissing(p))) return goal("practice_six", "gyeong_pil", "practice").counted(Math.max(0, MasteryService.stars(p, SectService.SIX)), 2);
            if ("rank".equals(SectProgression.firstExamMissing(p))) return rankGoal(p);
            if ("strike".equals(SectProgression.firstExamMissing(p)))
                return goal("submit_six", "gyeong_pil", "strike").counted(
                        (int) Math.min(8, s.entry("quest.mentor_strike.hits", 0)), 8);
            return goal("submit_six", "gyeong_pil", "ready");
        }
        if (!office && !s.has(SectStanding.LESSON_TWO)) {
            if (!MasteryService.knows(p, PETAL)) return goal("learn_petal", "gyeong_pil", "book");
            if (!s.has(SectService.LESSON_SPAR)) return goal("take_spar", "gyeong_pil", "ready");
            if (!s.has(SectService.SPAR_CLEAN)) return goal("clean_hits", "seo_rang", "clean_hits")
                    .counted((int) Math.min(3L, s.entry(SectService.CLEAN_TOTAL, 0)), 3);
            if (profile.rank() < 2) return rankGoal(p);
            return goal("submit_spar", "gyeong_pil", "ready");
        }
        if (!office && !MasteryService.knows(p, SEVEN)) return goal("learn_seven", "gyeong_pil", "book");
        if (!office && !s.has(SectProgression.DIRECT)) {
            if (!SectRequests.completedAny(s)) {
                if (s.has(SectRequests.flag("pills", "active")) && !SectRequests.pillsLost(p, s)) return goal("deliver_pills", "gyeong_cho", "pills");
                // A local, one-shot favour is enough; no bandit trip or review week is required.
                return goal("request", "tae_gyun", "request");
            }
            if (SectProgression.partners(s) < 3)
                return goal("partners", SectProgression.MENTOR, "partners").counted(
                        (int) SectProgression.partners(s), 3);
            if (s.has("mentor.ceremony")) return goal("ceremony", SectProgression.MENTOR, "ceremony");
            if (SectProgression.directEligible(s)) return goal("direct", SectProgression.MENTOR, "ready");
        }
        if (!office && standing != SectStanding.TRUSTED) {
            if (s.contribution() < SectStanding.TRUSTED_CONTRIBUTION) return goal("standing", "tae_gyun", "standing",
                    SectStanding.TRUSTED_CONTRIBUTION - s.contribution()).counted(s.contribution(), SectStanding.TRUSTED_CONTRIBUTION);
            if (!s.has(SectStanding.APPROVAL)) return goal("approval", "tae_hwi", "ready");
        }
        // Beyond the second lesson: rank three, the first-disciple office, the Peak, the elder office, the Peak stages.
        if (profile.rank() < Realm.FIRST) return rankGoal(p);
        if (SectPositions.eligible(p, "first") && !s.has("position.declined.first"))
            return goal("first", "tae_hwi", "ready");
        if (profile.rank() < Realm.PEAK) return rankGoal(p);
        if (SectPositions.eligible(p, "elder") && !s.has("position.declined.elder"))
            return goal("elder", "tae_hwi", "ready");
        if (profile.rank() == Realm.PEAK && profile.stage() < Realm.STAGES - 1) {
            Realm.Blocker stage = Realm.checkStage(profile, p.getData(ModAttachments.MASTERY).combatStars());
            return goal("peak_stage", SectProgression.MENTOR, stage == Realm.Blocker.NONE ? (BodyRules.stageReady(profile.stage() + 1, TrainingService.level(p)) ? "breakthrough" : "body")
                    : stage == Realm.Blocker.NOT_AT_WALL ? "wall" : "technique");
        }
        var practice = p.getData(ModAttachments.MASTERY).progress(SectProgression.lessonTechnique(p));
        if (practice != null && practice.atCap()) return goal("free_practice", SectProgression.MENTOR, "cap");
        return goal("personal_lesson", SectProgression.MENTOR,
                s.entry("mentor.lesson.day", -1) >= SectSchedule.day(p.level().getDayTime()) ? "rest"
                        : s.entry("spar.count", 0) <= s.entry("mentor.lesson.spar", 0) ? "new_spar" : "ready");
    }

    static Goal rankGoal(ServerPlayer p) {
        var profile = p.getData(ModAttachments.PROFILE);
        Realm.Blocker blocker = Realm.check(profile, p.getData(ModAttachments.MASTERY).combatStars(), SectProgression.secondSparProof(p), io.github.verycooltimo.murim.cultivation.GreatElixirs.ready(p));
        if (profile.rank() == Realm.THIRD) return new Goal("rank_two", "gyeong_pil", SectProgression.secondChecklist(p));
        String reason = switch (blocker) {
            case NO_DANTIAN -> "centre";
            case NOT_AT_WALL -> "wall";
            case NO_TECHNIQUE -> "technique";
            case NO_SECT_SKILLS -> "sect_skills";
            case MAX_RANK -> "max";
            case NONE -> BodyRules.breakthroughReady(profile.rank() + 1, TrainingService.level(p)) ? "breakthrough" : "body";
        };
        return goal(switch (profile.rank()) {
            case 0 -> "rank_one";
            case 1 -> "rank_two";
            case 2 -> "rank_three";
            default -> "peak";
        }, profile.rank() >= 2 ? SectProgression.MENTOR : "gyeong_pil", reason);
    }

    public static Component line(ServerPlayer p) {
        return line(p, null);
    }

    /** Person hints spoken by their target use the first person; the journal retains names. */
    static Component line(ServerPlayer p, SectDisciple speaker) {
        Goal goal = next(p);
        Component line = speaker != null && SectGoalTalk.is(speaker, goal.person())
                ? Component.translatable("dialogue.murim.general.where.here", goal.name(), goal.blocker()) : goal.text();
        // The journal also keeps the challenges to seniors ("3/5"), today's drill after a lost bout and the contract.
        Component bouts = SectBouts.progress(p);
        Component drill = SectDefeat.line(p);
        if (bouts != null) line = line.copy().append(" ").append(bouts);
        if (drill != null) line = line.copy().append(" ").append(drill);
        Component contract = SectContracts.journal(p);
        return contract == null ? line : Component.empty().append(line).append("\n").append(contract);
    }

    /** Toggles the on-screen reminder; the goal itself stays in the journal and in the mentor's words. */
    public static boolean toggleHud(ServerPlayer p) {
        SectState s = SectService.state(p);
        boolean off = !s.has(HUD_OFF);
        p.setData(ModAttachments.SECT, off ? s.with(HUD_OFF) : s.without(HUD_OFF));
        PlayerJournal.record(p, Component.translatable(off ? "murim.sect.journey.hud_off" : "murim.sect.journey.hud_on"));
        syncHud(p);
        return !off;
    }

    /** What the client reminder shows now: empty for non-members and when the journal switched it off. */
    static Component hudLine(ServerPlayer p) {
        SectState s = SectService.state(p);
        return s.member() && !s.has(HUD_OFF) ? next(p).hudText() : Component.empty();
    }

    /** Player data key: id of the goal shown last time; a change means the previous goal is done. */
    private static final String LAST_GOAL = "murim_journey_goal";

    /** A finished goal gets a short sound and one line; the mentor repeats it at the next meeting (the new goal line). */
    static void cue(ServerPlayer p) {
        SectState s = SectService.state(p);
        if (!s.member()) return;
        String now = next(p).id();
        String before = p.getPersistentData().getString(LAST_GOAL);
        p.getPersistentData().putString(LAST_GOAL, now);
        if (before.isEmpty() || before.equals(now)) return;
        SectTablet.touch(p);
        // An invitation coming or going is not a goal done: no "done" cue for the path it interrupts.
        if (SectOffices.isInvitation(now) || SectOffices.isInvitation(before)) return;
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.2F);
        PlayerJournal.record(p, Component.translatable("murim.sect.journey.done",
                Component.translatable("murim.sect.journey.goal." + before)));
    }

    static void syncHud(ServerPlayer p) {
        Component text = hudLine(p);
        Sent snapshot = new Sent(text, p.level().dimension().location());
        SectSiteData session = SectLife.data(p.serverLevel());
        if (snapshot.equals(session.goalSent.get(p.getUUID()))) return;
        SectGoalPayload payload = new SectGoalPayload(text);
        if (p.connection != null && p.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(p, payload);
            session.goalSent.put(p.getUUID(), snapshot);
        }
    }

    /** Player data key: the sect period the reminder last announced. */
    private static final String LAST_PERIOD = "murim_goal_period";
    /** The goal's person counts as near within this many blocks. */
    public static final double NEAR_RANGE = 12.0D;

    /**
     * Should the reminder show again now? Yes at the start of each sect period (and the first check after joining or
     * logging in) and when the player walks up to the person the goal names. A change of goal is handled by the text
     * sync itself. Updates the remembered state, so it answers true once per trigger.
     */
    public static boolean pulseDue(ServerPlayer p) {
        SectState s = SectService.state(p);
        if (!s.member() || s.has(HUD_OFF)) return false;
        boolean due = false;
        String period = SectSchedule.at(p.level().getDayTime()).id();
        if (!period.equals(p.getPersistentData().getString(LAST_PERIOD))) {
            p.getPersistentData().putString(LAST_PERIOD, period);
            due = true;
        }
        String person = next(p).person();
        boolean near = !p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(NEAR_RANGE),
                d -> d.isAlive() && person.equals(d.memberKey())).isEmpty();
        SectSiteData session = SectLife.data(p.serverLevel());
        if (near && !session.goalNear.getOrDefault(p.getUUID(), false)) due = true;
        session.goalNear.put(p.getUUID(), near);
        return due;
    }

    /** A player who leaves forgets nothing on the client's side but this: his state is dropped, a return sends the line again. */
    @SubscribeEvent
    static void loggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            SectSiteData session = SectLife.data(p.serverLevel());
            session.goalSent.remove(p.getUUID());
            session.goalNear.remove(p.getUUID());
        }
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 11) return;
        cue(p);
        syncHud(p);
        if (pulseDue(p)) {
            io.github.verycooltimo.murim.network.SectGoalPulsePayload pulse = new io.github.verycooltimo.murim.network.SectGoalPulsePayload();
            if (p.connection != null && p.connection.hasChannel(pulse)) PacketDistributor.sendToPlayer(p, pulse);
        }
    }
}
