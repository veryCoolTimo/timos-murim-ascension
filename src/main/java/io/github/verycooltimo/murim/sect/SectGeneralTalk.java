package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.List;
import java.util.Optional;

/**
 * What every sect person can be asked, whatever his work (playtest 21.10: "the options merge, and there is too little to ask"):
 * the general questions at the end of his opening menu, and the polite way out at the bottom of every menu.
 *
 * <p>The questions are fixed slots, {@link #QUESTIONS}; the menu shows them under the number {@link #BASE} + slot (the node's own
 * numbering never moves, like the hooks of {@link SectGoalTalk}). The answer is the synthetic node {@link #NODE}: his line,
 * then "another question" back to the opening menu. The way out is the number {@link #CLOSE}: the player says a short thanks
 * in the tone he would use to this person, the person nods and answers with a short farewell bubble over his head.
 */
public final class SectGeneralTalk {
    /** The synthetic node that holds the person's answer to a general question. */
    public static final String NODE = "@general";
    public static final String MENU = "@smalltalk";
    public static final int MAP = 1997;
    public static final int DECLINE = 1996;
    public static final int OPEN = 1999;
    public static final int MATTERS = 1998;
    /** The next-node of an option that goes back to the opening menu of the person. */
    public static final String BACK = "@back";
    /** Shown numbers of the general questions: BASE + slot. */
    public static final int BASE = 1000;
    /** The shown number of the closing option. */
    public static final int CLOSE = 2000;
    /** The slots: where to find, what is on today, about himself, rumours. */
    public static final List<String> QUESTIONS = List.of("where", "today", "self", "rumour", "wanderer");
    /** The question the player asked last (the answer reads it). */
    static final String LAST = "murim_general_last";
    /** Lines of every pool below (the player hears them often, so at least {@link SectLines#MIN_VARIANTS}). */
    public static final int VARIANTS = 6;
    /** Stems of the pools: the player's closing words and the person's farewell bubble, by tone; the self and rumour answers. */
    public static final String CLOSE_STEM = "dialogue.murim.farewell.";
    public static final String BUBBLE_STEM = "murim.bubble.farewell.";
    public static final String SELF_STEM = "dialogue.murim.general.self.";
    public static final String RUMOUR_STEM = "dialogue.murim.general.rumour";
    /** The two tones. */
    public static final List<String> TONES = List.of("formal", "plain");
    /** The standard way out that dialogue files already carry: replaced by the one closing option at the bottom. */
    private static final java.util.Set<String> STANDARD_CLOSERS = java.util.Set.of("dialogue.murim.opt.leave", "dialogue.murim.hook.ok");

    private SectGeneralTalk() { }

    /** Is this option one of the plain "leave" rows (no action, no next node) that the closing option replaces? */
    public static boolean standardCloser(Dialogue.Option o) {
        return STANDARD_CLOSERS.contains(o.text()) && o.actions().isEmpty() && o.next().isEmpty();
    }

    /** Does this option end the talk without doing anything (a custom "bye" of a dialogue file)? */
    public static boolean plainCloser(Dialogue.Option o) {
        return o.actions().isEmpty() && o.next().isEmpty();
    }

    /** Elders, the first generation and the council are addressed formally; juniors and servants plainly. */
    public static String tone(SectDisciple npc) {
        SectVoice.Voice v = SectVoice.of(npc);
        return v == SectVoice.Voice.ELDER || v == SectVoice.Voice.FIRST ? "formal" : "plain";
    }

    /** A random line of a pool {@code stem.1 .. stem.VARIANTS}. */
    static String pick(String stem, RandomSource random) {
        return stem + "." + (1 + random.nextInt(VARIANTS));
    }

    /** The player's closing words for this person. */
    public static Component closing(SectDisciple npc, RandomSource random) {
        return Component.translatable(pick(CLOSE_STEM + tone(npc), random));
    }

    /** The text of the general question in a slot. */
    public static Component question(int slot) {
        return Component.translatable("dialogue.murim.general.opt." + QUESTIONS.get(slot));
    }

    /** The player thanks and goes: a nod, a short farewell over his head, the talk ends. */
    static void farewell(ServerPlayer p, SectDisciple npc) {
        if (npc != null && npc.isAlive()) {
            npc.gesture("nod");
            String key = pick(BUBBLE_STEM + tone(npc), p.getRandom());
            SectChatter.send(npc, key, Component.translatable(key));
        }
        DialogueService.close(p, npc);
    }

    /** Remembers the asked slot for the answer line. */
    static void ask(ServerPlayer p, int slot) {
        p.getPersistentData().putInt(LAST, slot);
        if (slot == QUESTIONS.indexOf("wanderer")) Wanderer.request(p);
    }

    /** The answer of the person to the question asked last. */
    public static Component reply(ServerPlayer p, SectDisciple npc) {
        int slot = p.getPersistentData().getInt(LAST);
        if (slot == QUESTIONS.size()) return Component.translatable(p.getPersistentData().getBoolean("murim_gate_map_given")
                ? "dialogue.murim.gate_map.given" : "dialogue.murim.gate_map.wait");
        return switch (slot < 0 || slot >= QUESTIONS.size() ? "rumour" : QUESTIONS.get(slot)) {
            case "wanderer" -> Component.translatable("dialogue.murim.wanderer.requested");
            case "where" -> where(p, npc);
            case "today" -> Component.translatable("dialogue.murim.general.today." + SectSchedule.at(p.level().getDayTime()).id());
            case "self" -> Component.translatable(SELF_STEM + npc.role().name().toLowerCase(java.util.Locale.ROOT), npc.getName(),
                    SectRoleNames.of(npc.memberKey(), npc.role()));
            default -> Component.translatable(RUMOUR_STEM + "." + npc.role().name().toLowerCase(java.util.Locale.ROOT));
        };
    }

    /** Where to find the person of the player's goal and of the day's task, from the same guide the tablet and the mote use. */
    static Component where(ServerPlayer p, SectDisciple npc) {
        List<SectGuide.Spot> spots = SectGuide.spots(p);
        if (spots.isEmpty()) {
            return Component.translatable("dialogue.murim.general.where.none");
        }
        Component out = Component.empty();
        for (int i = 0; i < Math.min(2, spots.size()); i++) {
            SectGuide.Spot s = spots.get(i);
            if (i > 0) out = out.copy().append(Component.literal(" "));
            if (SectGoalTalk.is(npc, s.key())) {
                out = out.copy().append(Component.translatable("dialogue.murim.general.where.here", s.what(), SectJourney.next(p).blocker()));
                continue;
            }
            out = out.copy().append(Component.translatable(s.where().getString().isEmpty() ? "dialogue.murim.general.where.found"
                    : "dialogue.murim.general.where.at", s.what(), s.name(), s.role(), s.where()));
        }
        return out;
    }

    /** The synthetic node of the answer: his line, a gesture, the way back to the opening menu. */
    static Dialogue.Node menu() {
        return new Dialogue.Node(MENU, List.of(), List.of(), Optional.of("explain"), List.of(), List.of(new Dialogue.Option("dialogue.murim.general.opt.more", List.of(), List.of(), Optional.of(BACK))), Optional.empty());
    }

    static Dialogue.Node node() {
        return new Dialogue.Node(NODE, List.of(), List.of(), Optional.of("explain"), List.of(),
                List.of(new Dialogue.Option("dialogue.murim.general.opt.more", List.of(), List.of(), Optional.of(BACK))), Optional.empty());
    }
}
