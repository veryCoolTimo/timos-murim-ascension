package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FoundationService;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.quest.QuestLoader;
import io.github.verycooltimo.murim.quest.QuestService;
import io.github.verycooltimo.murim.quest.Quests;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Spars with a condition (author 24.10: «побочные квесты остановим, можно максимум сделать испытания с условием дуэли»). The data is the
 * quest step {@code trial} ({@link QuestDef.Trial}, docs/design/45-quest-format.md); this class is the engine and knows no trial by
 * name. A senior offers the trial in the bouts menu of his dialogue and says the rule first; the dialogue action {@code quest_trial}
 * opens a tracked bout in a ring ({@link SectBouts#beginRule}); the bout counts what the rule reads (blows taken, strikes parried,
 * techniques begun, time), and when it ends a win that kept the rule pays the reward once. A bout that broke the rule is told so
 * at once and goes on as plain practice; nothing is lost and the trial can be tried again.
 */
public final class SectTrials {

    private SectTrials() {
    }

    /** The flag of a won trial: {@code quest.<quest>.won.<trial>}. */
    public static String wonFlag(String quest, String trial) {
        return Quests.step(quest, "won." + trial);
    }

    public static boolean won(ServerPlayer p, QuestLoader.Located<QuestDef.Trial> t) {
        return Quests.has(p, wonFlag(t.quest(), t.step().id()));
    }

    // ------------------------------------------------------------------ offers

    /** This person offers this trial to this player now: a member, the gate holds, not won yet. */
    public static boolean offered(ServerPlayer p, SectDisciple npc, String id) {
        var t = QuestLoader.trial(id);
        return t != null && offeredBy(p, npc, t);
    }

    /** This person offers some trial now. */
    public static boolean offeredAny(ServerPlayer p, SectDisciple npc) {
        return QuestLoader.trials().stream().anyMatch(t -> offeredBy(p, npc, t));
    }

    private static boolean offeredBy(ServerPlayer p, SectDisciple npc, QuestLoader.Located<QuestDef.Trial> t) {
        return npc != null && SectService.state(p).member() && t.step().npc().equals(npc.memberKey()) && npc.spars()
                && QuestService.passes(p, t.step().gate()) && !won(p, t);
    }

    /** Why the trial cannot begin now, as a lang key, or null when it can. */
    public static String refusal(ServerPlayer p, SectDisciple npc, String id) {
        var t = QuestLoader.trial(id);
        if (t == null || !offeredBy(p, npc, t)) {
            return "murim.trial.refused";
        }
        if (t.step().rule().equals("six_only") && !sixInHand(p)) {
            return "murim.trial.six_needed";
        }
        return SectBouts.canTrain(p, npc) ? null : "murim.trial.no_ring";
    }

    /** The Six Harmonies foundation is the one in the player's hand (a sword in the hand and the art chosen). */
    static boolean sixInHand(ServerPlayer p) {
        return FoundationService.active(p).filter(SectService.SIX::equals).isPresent();
    }

    /** Dialogue action {@code quest_trial:<id>}: begin the bout. False: the dialogue stays on its node, with the reason said. */
    public static boolean start(ServerPlayer p, SectDisciple npc, String id) {
        if (SectNightWatch.refuse(p, npc, "bout")) return false;
        String no = refusal(p, npc, id);
        if (no != null) {
            SectNotice.bar(p, Component.translatable(no).withStyle(ChatFormatting.GRAY));
            return false;
        }
        return SectBouts.beginRule(p, npc, id);
    }

    // ------------------------------------------------------------------ the rule, said

    /** The rule of the trial in a line: the title and the short condition. */
    public static Component ruleLine(QuestDef.Trial t) {
        return Component.translatable("murim.trial.rule_line", Component.translatable("murim.trial." + t.id() + ".title"),
                Component.translatable("murim.trial.short." + t.rule(), t.value()));
    }

    /** At the begin of the bout: the rule again in the chat, so nobody fights without it. */
    static void announce(ServerPlayer p, SectBouts.Bout b) {
        var t = QuestLoader.trial(b.trial);
        if (t != null) {
            SectNotice.chat(p, ruleLine(t.step()).copy().withStyle(ChatFormatting.GOLD));
            SectGuide.sync(p);
        }
    }

    // ------------------------------------------------------------------ what the bout counts

    private static QuestDef.Trial def(SectBouts.Bout b) {
        var t = b.trial == null ? null : QuestLoader.trial(b.trial);
        return t == null ? null : t.step();
    }

    /** The player took a blow of the partner. */
    static void touched(ServerPlayer p, SectBouts.Bout b) {
        QuestDef.Trial t = def(b);
        if (t != null && t.rule().equals("no_hits") && !b.told) {
            broken(p, b, "murim.presence.trial.touched");
        }
    }

    /** The player parried a strike of the partner. */
    static void parried(ServerPlayer p, SectBouts.Bout b) {
        QuestDef.Trial t = def(b);
        if (t != null && t.rule().equals("parry")) {
            SectNotice.bar(p, Component.translatable("murim.trial.parried", Math.min(b.parries, Math.max(1, t.value())),
                    Math.max(1, t.value())).withStyle(ChatFormatting.AQUA));
        }
    }

    /** Damage must belong to a recent Six Harmonies form, including plain vanilla attacks. */
    static void struck(ServerPlayer p, SectBouts.Bout b, net.minecraft.world.damagesource.DamageSource source) {
        QuestDef.Trial t = def(b);
        if (t == null || !"six_only".equals(t.rule())) return;
        int[] swing = p.getData(io.github.verycooltimo.murim.registry.ModAttachments.FOUNDATION_SWING);
        if (source.getDirectEntity() == p && source.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                && sixInHand(p) && swing.length >= 2 && swing[1] >= 0
                && p.tickCount - swing[1] >= 0 && p.tickCount - swing[1] <= 4) return;
        b.cast = true;
        cast(p, b);
    }

    /** The player began a technique or a footwork form. */
    static void cast(ServerPlayer p, SectBouts.Bout b) {
        QuestDef.Trial t = def(b);
        if (t != null && t.rule().equals("six_only") && !b.told) {
            broken(p, b, "murim.presence.trial.cast");
        }
    }

    /** A trial with a time limit runs out when the fight has lasted longer than the limit. */
    static boolean expired(SectBouts.Bout b, long now) {
        QuestDef.Trial t = def(b);
        return t != null && t.rule().equals("time") && b.fightStart >= 0 && now - b.fightStart > t.value() * 20L;
    }

    static void timeUp(ServerPlayer p, SectBouts.Bout b) {
        if (!b.told) broken(p, b, "murim.presence.trial.time");
    }

    private static void broken(ServerPlayer p, SectBouts.Bout b, String key) {
        b.told = true;
        b.ruleBrokenAt = p.level().getGameTime();
        SectDisciple examiner = SectGuide.find(p.serverLevel(), b.key, p.position());
        if (examiner != null) SectChatter.send(examiner, key, Component.translatable(key));
        SectNotice.bar(p, Component.translatable(key));
        SectGuide.sync(p);
    }

    /** Only a live, accepted rule bout owns this state; offers never do. */
    static SectGuide.Spot marker(ServerPlayer p) {
        SectBouts.Bout b = SectBouts.active(p);
        if (b == null || b.trial == null) return null;
        return SectGuide.person(p.serverLevel(), p, true, b.told ? "trial_broken" : "trial_intact",
                Component.empty(), b.key);
    }

    // ------------------------------------------------------------------ the end

    /** Did the bout keep the rule? (The win itself is checked by the caller.) */
    public static boolean kept(QuestDef.Trial t, int taken, int parries, boolean cast, long fightTicks) {
        return switch (t.rule()) {
            case "no_hits" -> taken == 0;
            case "six_only" -> !cast;
            case "parry" -> parries >= Math.max(1, t.value());
            case "time" -> fightTicks >= 0 && fightTicks <= t.value() * 20L;
            default -> false;
        };
    }

    /** The bout is over: pay a win that kept the rule, say what was missing otherwise. */
    static void ended(ServerPlayer p, SectDisciple npc, SectBouts.Bout b, boolean won) {
        var located = b.trial == null ? null : QuestLoader.trial(b.trial);
        if (located == null) {
            return;
        }
        QuestDef.Trial t = located.step();
        long ticks = b.fightStart < 0 ? -1L : p.level().getGameTime() - b.fightStart;
        if (!won) {
            SectNotice.bar(p, Component.translatable("murim.trial.fail.lost", Component.translatable("murim.trial." + t.id() + ".title"))
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!kept(t, b.taken, b.parries, b.cast, ticks)) {
            SectNotice.bar(p, Component.translatable("murim.trial.fail." + t.rule()).withStyle(ChatFormatting.GRAY));
            return;
        }
        pay(p, npc, located);
    }

    /** The reward, once: the flag, the effects (flags, merit) and the share of the next star of the art. */
    static void pay(ServerPlayer p, SectDisciple npc, QuestLoader.Located<QuestDef.Trial> located) {
        QuestDef.Trial t = located.step();
        if (!Quests.set(p, wonFlag(located.quest(), t.id()))) {
            return;
        }
        QuestService.apply(p, t.onWin());
        Optional<Double> gained = t.art().map(a -> {
            ResourceLocation art = ResourceLocation.tryParse(a.art());
            if (art == null || !MasteryService.knows(p, art)) {
                return 0.0D;
            }
            return MasteryService.help(p, art, MasteryRules.HelpSource.LESSON, a.share() * MasteryService.nextCost(p, art), false);
        });
        SectNotice.chat(p, Component.translatable("murim.trial.passed", Component.translatable("murim.trial." + t.id() + ".title"))
                .withStyle(ChatFormatting.GOLD));
        t.art().ifPresent(a -> {
            ResourceLocation art = ResourceLocation.tryParse(a.art());
            if (art != null && MasteryService.knows(p, art) && gained.orElse(0.0D) > 0.0D) {
                SectNotice.chat(p, Component.translatable("murim.trial.reward.art", MasteryService.artName(art)).withStyle(ChatFormatting.AQUA));
            }
        });
        SectChatter.send(npc, "murim.trial." + t.id() + ".praise", Component.translatable("murim.trial." + t.id() + ".praise"));
        MurimMod.LOGGER.info("Sect: {} won the trial {} of {}", p.getName().getString(), t.id(), npc.memberKey());
    }
}
