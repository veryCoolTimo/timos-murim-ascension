package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * The person a goal, a lesson or a daily task points to knows about it (playtest 19.10: «говорят у него зачёт взять, а
 * прямым текстом там нет такой опции»; «прихожу к тренеру, он даже об этом не знает»). When the player talks to that person,
 * the first options of the opening menu are the thing itself, in plain words — «Сдать зачёт по Равновесию Шести», «Тренировка
 * дня: удары 12/20», «Сдать контракт» — and the person answers with what he knows and what to do next.
 *
 * <p>Two slots, always in the same place so a shown menu stays valid until the choice is made: slot 0 is the goal of the
 * journey ({@link SectJourney#next}), slot 1 is one daily task (the teacher's drill, the contract, a favour, today's lessons).
 * Where the dialogue file already has the real option (the lessons of the mentor, the leader's trust, the offices), the hook is
 * that very option with the explicit wording on top; elsewhere the hook runs the same server call the menu behind it ran. The
 * answer is the synthetic node {@link #NODE} ({@link DialogueService}). Nothing here changes a rule: every action re-checks
 * its own conditions.
 */
public final class SectGoalTalk {
    /** The synthetic node that holds the person's answer to a hook. */
    public static final String NODE = "@goal";
    /** The hook the player picked last (the answer reads it). */
    static final String LAST = "murim_hook_last";
    /** Slots: the goal, the daily task. */
    public static final int SLOTS = 2;
    /** A drill that was reported to the teacher: the sect day. */
    static final String REPORTED = "defeat.reported";

    private SectGoalTalk() { }

    /** The goal the player was last told about (persistent data). */
    static final String ACKED = "murim_goal_acked";

    /**
     * The mentor (or the person the new goal points to) acknowledges what was just done and names the next step, before
     * anything else in the talk (author 22.10). Silent the first time and while the goal is the same.
     */
    public static void acknowledge(ServerPlayer p, SectDisciple npc) {
        if (!SectService.state(p).member() || !(is(npc, "gyeong_pil") || npc.role() == SectRole.MENTOR)) return;
        SectJourney.Goal goal = SectJourney.next(p);
        String last = p.getPersistentData().getString(ACKED);
        p.getPersistentData().putString(ACKED, goal.id());
        if (last.isEmpty() || last.equals(goal.id())) return;
        Component line = Component.translatable("dialogue.murim.ack." + (1 + p.getRandom().nextInt(VARIANTS)), goal.name());
        SectNotice.chat(p, Component.translatable("murim.sect.say", npc.getDisplayName(), line));
    }

    /** A ready dialogue option and what it is about. */
    public record Hook(String id, Dialogue.Option option) { }

    /** Does this person stand for the roster key (the mentor of an old save has no key, but is Gyeong Pil)? */
    static boolean is(SectDisciple npc, String key) {
        return key.equals(npc.memberKey()) || npc.memberKey().isEmpty() && "gyeong_pil".equals(key) && npc.role() == SectRole.MENTOR;
    }

    /** Is {@code nodeId} of the dialogue a place where the person opens a conversation, and may the hooks stand in it? */
    public static boolean hub(ServerPlayer p, SectDisciple npc, Dialogue d, String nodeId) {
        // Only the person's own greetings receive task hooks and folding; request menus keep their native rows.
        if (d != DialogueLoader.get(npc.dialogue()) || !SectService.state(p).member()
                || SectPositions.disciplined(SectService.state(p)) || npc.spar() != SectDisciple.Spar.NONE
                || npc.defending() || npc.isSleeping() || SectSchedule.at(p.level().getDayTime()) == SectSchedule.Period.NIGHT) {
            return false;
        }
        // Scripted tours and the one-time basics offer keep all their story choices (DialogueService#show).
        if (nodeId.startsWith("tour_") || nodeId.equals("basics_offer") || nodeId.equals("blade_exam") || nodeId.contains("busy")
                || nodeId.contains("night") || nodeId.startsWith("@")) {
            return false;
        }
        for (Dialogue.Entry e : d.start()) {
            if (e.node().equals(nodeId)) return true;
        }
        return false;
    }

    /** The two hooks of this person for this player, by slot; a slot with nothing to say is null. */
    public static Hook[] hooks(ServerPlayer p, SectDisciple npc, Dialogue d) {
        Hook[] out = new Hook[SLOTS];
        Hook goal = goalHook(p, npc, d);
        out[0] = goal;
        out[1] = taskHook(p, npc, d, goal == null ? "" : goal.id());
        return out;
    }

    private static Dialogue.Option advice(String id) {
        return new Dialogue.Option("dialogue.murim.hook." + id, List.of(), List.of(new Dialogue.Action("goal_talk", Optional.of(id))),
                Optional.of(NODE));
    }

    /** The real option of the person's dialogue with this text, if the player may pick it now, under the new wording. */
    private static Dialogue.Option reuse(ServerPlayer p, SectDisciple npc, Dialogue d, String textKey, String id) {
        for (Dialogue.Node n : d.nodes().values()) {
            for (Dialogue.Option o : n.options()) {
                if (o.text().equals(textKey) && DialogueService.all(p, npc, o.when())) {
                    return new Dialogue.Option("dialogue.murim.hook." + id, o.when(), o.actions(), o.next());
                }
            }
        }
        return null;
    }

    static Hook goalHook(ServerPlayer p, SectDisciple npc, Dialogue d) {
        SectJourney.Goal goal = SectJourney.next(p);
        if (!is(npc, goal.person())) return null;
        String id = goal.id();
        SectState s = SectService.state(p);
        Dialogue.Option real = switch (id) {
            case "method" -> reuse(p, npc, d, "dialogue.murim.mentor.opt.method_help", id);
            case "take_six" -> reuse(p, npc, d, "dialogue.murim.mentor.opt.take_six", id);
            case "submit_six" -> reuse(p, npc, d, "dialogue.murim.mentor.opt.done_six", id);
            case "take_spar" -> reuse(p, npc, d, "dialogue.murim.mentor.opt.take_spar", id);
            case "submit_spar" -> reuse(p, npc, d, "dialogue.murim.mentor.opt.done_spar", id);
            case "approval" -> reuse(p, npc, d, "dialogue.murim.leader.opt.ask_trust", id);
            case "first", "elder" -> reuse(p, npc, d, "dialogue.murim.social.opt." + id, id);
            case "request" -> SectRequests.canStart(p, npc, "pills") ? advice(id) : null;
            case "deliver_pills" -> SectRequests.canComplete(p, npc, "pills") ? advice(id) : null;
            case "direct" -> SectProgression.matches(p, npc, "direct") ? advice(id) : null;
            case "ceremony" -> SectProgression.matches(p, npc, "ceremony") || s.has("mentor.ceremony") ? advice(id) : null;
            case "personal_lesson" -> SectProgression.canLesson(p, npc) ? advice(id) : null;
            case "apology" -> SectProgression.canApologise(p, npc) ? advice(id) : null;
            case "repair" -> SectProgression.canRepair(p, npc) ? advice(id) : null;
            case "clean_hits" -> npc.role().spars() && npc.spar() == SectDisciple.Spar.NONE && !npc.defending() ? advice(id) : null;
            default -> null;
        };
        return new Hook(id, real != null ? real : advice(goal.id()));
    }

    static Hook taskHook(ServerPlayer p, SectDisciple npc, Dialogue d, String goalId) {
        SectDefeat.Open drill = SectDefeat.open(p);
        if (drill != null && is(npc, drill.teacher()) && !"drill".equals(goalId)) {
            return new Hook("drill", advice("drill"));
        }
        if (drill == null && SectDefeat.doneToday(p) && is(npc, SectDefeat.teacher(SectService.state(p)))
                && SectService.state(p).entry(REPORTED, -1) != SectProgression.day(p)) {
            return new Hook("drill_done", advice("drill_done"));
        }
        SectContracts.Guide contract = SectContracts.guide(p);
        if (contract != null && is(npc, contract.giver())) {
            return new Hook(contract.ready() ? "contract_handin" : "contract", advice(contract.ready() ? "contract_handin" : "contract"));
        }
        String[] favour = SectRequests.guide(p);
        if (favour != null && !"deliver_pills".equals(goalId) && is(npc, favour[1]) && SectRequests.canComplete(p, npc, favour[0])) {
            return new Hook("favour", advice("favour"));
        }
        if (is(npc, "gyeong_pil") && lessonsOpen(p)) {
            return new Hook("lessons", advice("lessons"));
        }
        return null;
    }

    /** It is the hour of the lessons and the day's lesson mark is not yet there. */
    static boolean lessonsOpen(ServerPlayer p) {
        SectSchedule.Period period = SectSchedule.at(p.level().getDayTime());
        return (period == SectSchedule.Period.TRAINING || period == SectSchedule.Period.BREAKFAST)
                && !SectAttendance.current(p).today().did(SectAttendance.Activity.LESSON);
    }

    // ------------------------------------------------------------------ the words of a hook

    /** The text of a hook option (some carry numbers: today's drill and how far it is). */
    public static Component text(ServerPlayer p, String id) {
        if ("drill".equals(id)) {
            SectDefeat.Open drill = SectDefeat.open(p);
            if (drill != null) {
                return Component.translatable("dialogue.murim.hook.drill", Component.translatable("murim.sect.defeat.name." + drill.drill().id()),
                        drill.progress(), drill.need());
            }
        }
        if ("contract".equals(id) || "contract_handin".equals(id)) {
            SectContracts.Guide contract = SectContracts.guide(p);
            if (contract != null) return Component.translatable("dialogue.murim.hook." + id, contract.title());
        }
        if (!WORDED.contains(id)) {
            return Component.translatable("dialogue.murim.hook.generic", SectJourney.next(p).name());
        }
        return Component.translatable("dialogue.murim.hook." + id);
    }

    /** The hooks that have wording of their own; any other goal of the person is asked about as "my task: ...". */
    static final java.util.Set<String> WORDED = java.util.Set.of("method", "take_six", "submit_six", "take_spar", "submit_spar",
            "approval", "first", "elder", "request", "deliver_pills", "direct", "ceremony", "personal_lesson", "apology", "repair",
            "clean_hits", "drill", "drill_done", "contract", "contract_handin", "favour", "lessons");

    /** Runs a hook that has no option of its own behind it; "retry" when it cannot be done now. */
    static String act(ServerPlayer p, SectDisciple npc, String id) {
        p.getPersistentData().putString(LAST, id);
        switch (id) {
            case "drill_done" -> SectProgression.save(p, SectService.state(p).record(REPORTED, SectProgression.day(p)));
            case "contract_handin" -> {
                if (!SectContracts.handIn(p, npc)) return "retry";
                DialogueService.close(p, npc);
                return "close";
            }
            case "favour" -> {
                String[] favour = SectRequests.guide(p);
                if (favour == null || !SectRequests.complete(p, npc, favour[0])) return "retry";
            }
            case "request" -> {
                if (!SectRequests.start(p, npc, "pills")) return "retry";
            }
            case "deliver_pills" -> {
                if (!SectRequests.complete(p, npc, "pills")) return "retry";
            }
            case "direct" -> {
                if (!SectProgression.act(p, npc, "direct")) return "retry";
                return io.github.verycooltimo.murim.MurimMod.MODID + ":spar_bow";
            }
            case "ceremony" -> {
                if (!SectProgression.act(p, npc, "ceremony")) p.getPersistentData().putString(LAST, "ceremony_wait");
            }
            case "personal_lesson" -> {
                if (!SectProgression.act(p, npc, "lesson")) return "retry";
            }
            case "apology" -> {
                if (!SectProgression.act(p, npc, "apology")) return "retry";
            }
            case "repair" -> {
                if (!SectProgression.act(p, npc, "repair")) return "retry";
            }
            case "clean_hits" -> {
                if (!npc.role().spars() || npc.spar() != SectDisciple.Spar.NONE || npc.defending()) return "retry";
                DialogueService.close(p, npc);
                SectNotice.chat(p, Component.translatable("dialogue.murim.hook.clean_hint"));
                npc.startSpar(p, 20);
                return "close";
            }
            default -> {
                // advice: the person only tells what he knows
            }
        }
        return null;
    }

    /** The number of variants of every answer: the same question does not get the same words each time. */
    static final int VARIANTS = 3;

    /** A test or a lesson the player asks for before he can have it is answered "not yet" and what is missing; the rest is advice. */
    static String adviceKind(String goalId) {
        return goalId.startsWith("submit") || goalId.startsWith("take") ? "notyet" : "advice";
    }

    private static Component say(ServerPlayer p, String id, Object... args) {
        return Component.translatable("dialogue.murim.hook.reply." + id + "." + (1 + p.getRandom().nextInt(VARIANTS)), args);
    }

    /** The person's answer, in his words, to the hook the player picked last. */
    public static Component reply(ServerPlayer p, SectDisciple npc) {
        String id = p.getPersistentData().getString(LAST);
        switch (id) {
            case "submit_six" -> {
                String missing = SectProgression.firstExamMissing(p);
                return Component.translatable(missing.isEmpty() ? "murim.sect.journey.blocker.ready.submit_six"
                        : "murim.sect.exam.missing." + missing);
            }
            case "rank_two" -> { return SectProgression.secondChecklist(p); }
            case "drill" -> {
                SectDefeat.Open drill = SectDefeat.open(p);
                if (drill != null) {
                    return say(p, "drill", Component.translatable("murim.sect.defeat.drill." + drill.drill().id(), drill.need()),
                            drill.progress(), drill.need());
                }
                return say(p, "drill_done");
            }
            case "drill_done", "favour", "request", "deliver_pills", "direct", "personal_lesson", "apology", "repair", "ceremony_wait",
                 "lessons", "ceremony" -> {
                return say(p, id);
            }
            case "contract" -> {
                SectContracts.Guide contract = SectContracts.guide(p);
                return contract == null ? say(p, "none") : say(p, "contract", contract.title(), SectContracts.status(p));
            }
            default -> {
                SectJourney.Goal g = SectJourney.next(p);
                // A test or a lesson he is asked for before the player can have it: say what is still missing.
                return say(p, adviceKind(g.id()), g.name(), g.blocker());
            }
        }
    }

    /** The synthetic node of the answer: his line, a gesture, the way out. */
    static Dialogue.Node node() {
        return new Dialogue.Node(NODE, List.of(), List.of(), Optional.of("explain"), List.of(),
                List.of(new Dialogue.Option("dialogue.murim.hook.ok", List.of(), List.of(), Optional.empty())), Optional.empty());
    }
}
