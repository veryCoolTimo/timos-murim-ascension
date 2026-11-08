package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.sect.SectBouts;
import io.github.verycooltimo.murim.sect.SectService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Data-driven lessons and invitations. No merit, pills, forced meditation or forced breakthrough.
 * API: SectBouts, MasteryService#help and MeditationService#readiness (the actual breakthrough gates).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class QuestLessons {
    private QuestLessons() { }
    public static void afterBout(ServerPlayer p, boolean won) {
        for (var l : QuestLoader.lessons()) {
            Quests.mark(p, l.quest(), "bout");
            if (!won) Quests.mark(p, l.quest(), "lost");
        }
    }
    public static boolean offered(ServerPlayer p, SectDisciple npc, String id) {
        var l = QuestLoader.lesson(id);
        return l != null && npc != null && npc.memberKey().equals(l.step().npc()) && SectService.state(p).member()
                && Quests.done(p, l.quest(), "bout") && MasteryService.knows(p, ResourceLocation.parse(l.step().art().art()));
    }
    public static boolean accept(ServerPlayer p, SectDisciple npc, String id) {
        if (!offered(p, npc, id)) return false;
        var l = QuestLoader.lesson(id);
        Quests.mark(p, l.quest(), "asked");
        PlayerJournal.record(p, Component.translatable("murim.lesson." + id + ".advice"));
        return true;
    }
    public static boolean start(ServerPlayer p, SectDisciple npc, String id) {
        if (io.github.verycooltimo.murim.sect.SectNightWatch.refuse(p, npc, "lesson")) return false;
        if (!offered(p, npc, id)) return false;
        var l = QuestLoader.lesson(id);
        if (!Quests.done(p, l.quest(), "asked")) return false;
        if (SectBouts.beginLesson(p, npc, id)) return true;
        PlayerJournal.record(p, Component.translatable("murim.lesson.no_ring"));
        return false;
    }
    public static void practiced(ServerPlayer p, String id) {
        var l = QuestLoader.lesson(id);
        if (l == null || !Quests.done(p, l.quest(), "asked")) return;
        Quests.mark(p, l.quest(), "practiced");
        PlayerJournal.record(p, Component.translatable("murim.lesson." + id + ".practiced"));
    }
    public static boolean review(ServerPlayer p, SectDisciple npc, String id) {
        if (!offered(p, npc, id)) return false;
        var l = QuestLoader.lesson(id);
        if (!Quests.done(p, l.quest(), "practiced")) return false;
        if (!Quests.mark(p, l.quest(), "paid")) return true;
        ResourceLocation art = ResourceLocation.parse(l.step().art().art());
        // The existing shared help pool and cap remain authoritative. A capped art receives advice only.
        double gained = MasteryService.help(p, art, MasteryRules.HelpSource.LESSON,
                l.step().art().share() * MasteryService.nextCost(p, art), false);
        PlayerJournal.record(p, Component.translatable("murim.lesson." + id + (gained > 0 ? ".reward" : ".cap")));
        return true;
    }
    private static String transition(ServerPlayer p) {
        var profile = p.getData(ModAttachments.PROFILE);
        return profile.rank() + "." + profile.stage();
    }
    public static boolean thresholdOffered(ServerPlayer p, SectDisciple npc, String id) {
        var t = QuestLoader.threshold(id);
        return t != null && npc != null && npc.memberKey().equals(t.step().npc()) && SectService.state(p).member()
                && (MeditationService.readiness(p) == null || Quests.done(p, t.quest(), "invited." + transition(p)));
    }
    public static boolean inspect(ServerPlayer p, SectDisciple npc, String id) {
        if (!thresholdOffered(p, npc, id)) return false;
        var t = QuestLoader.threshold(id);
        Quests.mark(p, t.quest(), "invited." + transition(p));
        Quests.mark(p, t.quest(), "asked." + transition(p));
        Component missing = MeditationService.readiness(p);
        PlayerJournal.record(p, missing == null ? Component.translatable("murim.threshold." + id + ".ready") : missing);
        return true;
    }
    public static void decision(ServerPlayer p, String id, String choice) {
        var t = QuestLoader.threshold(id);
        if (t == null || !Quests.done(p, t.quest(), "asked." + transition(p))) return;
        if (!java.util.Set.of("try", "prepare", "later").contains(choice)) return;
        Quests.mark(p, t.quest(), choice + "." + transition(p));
        PlayerJournal.record(p, Component.translatable("murim.threshold." + id + "." + choice));
    }
    public static void meditated(ServerPlayer p) {
        for (var t : QuestLoader.thresholds()) {
            if (Quests.done(p, t.quest(), "asked." + transition(p))) {
                Quests.mark(p, t.quest(), "meditated." + transition(p));
                PlayerJournal.record(p, Component.translatable("murim.threshold." + t.step().id() + ".meditated"));
            }
        }
    }
    public static void advanced(ServerPlayer p, int rank, int stage) {
        for (var t : QuestLoader.thresholds()) {
            if (Quests.done(p, t.quest(), "asked." + rank + "." + stage)) {
                Quests.mark(p, t.quest(), "advanced." + rank + "." + stage);
                PlayerJournal.record(p, Component.translatable("murim.threshold." + t.step().id() + ".advanced"));
            }
        }
    }
    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && !p.isSpectator() && p.tickCount % 20 == 0) tickPlayer(p);
    }
    public static void tickPlayer(ServerPlayer p) {
        if (!SectService.state(p).member() || MeditationService.readiness(p) != null) return;
        for (var t : QuestLoader.thresholds()) {
            if (Quests.done(p, t.quest(), "invited." + transition(p))) continue;
            for (SectDisciple npc : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(10),
                    d -> d.isAlive() && d.free() && !d.isSleeping() && d.memberKey().equals(t.step().npc())
                            && d.distanceToSqr(p) <= 100 && d.getSensing().hasLineOfSight(p))) {
                Quests.mark(p, t.quest(), "invited." + transition(p));
                String key = "murim.threshold." + t.step().id() + ".invitation";
                io.github.verycooltimo.murim.sect.SectChatter.send(npc, key, Component.translatable(key));
                PlayerJournal.record(p, Component.translatable(key));
                break;
            }
        }
    }
}
