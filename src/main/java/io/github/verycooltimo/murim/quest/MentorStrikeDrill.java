package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Optional first lesson. API: SixExecutor confirms hurt before MasteryService.onHit.
 * Counters persist in SectState; the tablet synchronizes the journal only when opened.
 */
public final class MentorStrikeDrill {
    public static final int REQUIRED = 8;
    public static boolean completed(ServerPlayer p) {
        return Quests.has(p, "quest.mentor_strike.done")
                || (QuestLoader.available(p, "strike") && Quests.has(p, "quest.strike.done"));
    }

    public static boolean asked(ServerPlayer p) {
        if (Quests.has(p, "quest.mentor_strike.done")) return Quests.has(p, "quest.mentor_strike.how.asked");
        return QuestLoader.available(p, "strike") && Quests.has(p, "quest.strike.how.asked");
    }

    public static final String HITS = "quest.mentor_strike.hits";
    private MentorStrikeDrill() { }

    public static void describe(ServerPlayer p) {
        PlayerJournal.record(p, Component.translatable("murim.strike.drill.progress",
                p.getData(ModAttachments.SECT).entry(HITS, 0), REQUIRED));
    }

    public static void hit(ServerPlayer p, ResourceLocation art, TrainingDummy dummy) {
        var s = p.getData(ModAttachments.SECT);
        if (!s.member() || !s.has("lesson.six") || s.has("quest.mentor_strike.done")
                || !art.getPath().equals("six_harmonies_strike") || !art.getNamespace().equals("murim")
                || dummy.yardSlot() < 0 || dummy.distanceToSqr(p) > 36) return;
        long hits = Math.min(REQUIRED, s.entry(HITS, 0) + 1);
        s = s.record(HITS, hits);
        if (hits == REQUIRED) s = s.with("quest.mentor_strike.done").with(s.has("quest.mentor_strike.ask")
                ? "quest.mentor_strike.how.asked" : "quest.mentor_strike.how.alone");
        p.setData(ModAttachments.SECT, s);
        describe(p);
    }
}
