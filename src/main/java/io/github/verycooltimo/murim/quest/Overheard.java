package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import io.github.verycooltimo.murim.sect.SectChatter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;


/**
 * Author 31.10: a finite series of three lines, one minute apart by default. Each heard repeat refreshes
 * the expiring window; exhausting the series never prevents explicitly asking about a quest.
 * Overheard lines: a bubble that a person of the sect says over his head when the player is near, and that unlocks a
 * dialogue branch with that person only if the player really saw it and walks up within {@link #WINDOW_MS} (author 21.10:
 * about a minute, not five). The window is real time, not game time: a lagging server must not stretch it.
 *
 * <p>The bubble goes through the ordinary {@link SectChatter#send} path. Whoever it reaches (the bubble range) gets the
 * journal entry {@code heard.<id>} with the wall-clock milliseconds; the dialogue condition {@code social: "quest:heard:<id>"}
 * is true while it is fresh. Once the player starts the conversation the quest records its own flag and the branch no longer
 * depends on the clock. Per player, saved with the player: a reload inside the minute keeps the window.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class Overheard {

    /** How long after seeing the bubble the player may still speak to the person: a real minute. */
    public static final long WINDOW_MS = 60_000L;
    /** The same line is not repeated to one player more often than this. */
    public static final long REPEAT_MS = 60_000L;
    private static final double SPEAK_RANGE = SectChatter.HEAR_RANGE;

    private Overheard() {
    }

    public static QuestDef.Overheard line(String id) {
        var l = QuestLoader.line(id);
        return l == null ? null : l.step();
    }

    static String journalKey(String id) {
        return "heard." + id;
    }

    /** Wall-clock milliseconds when the player last saw the line's bubble; 0 if never. */
    public static long heardAt(ServerPlayer p, String id) {
        return Quests.state(p).entry(journalKey(id), 0L);
    }

    /** Records that the player saw the line at {@code atMs} (also what tests use to age a line). */
    public static void markHeard(ServerPlayer p, String id, long atMs) {
        p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT, Quests.state(p).record(journalKey(id), atMs));
    }

    /** The player saw the bubble within the window: a branch that asks for it is open. */
    public static boolean fresh(ServerPlayer p, String id) {
        return fresh(p, id, System.currentTimeMillis());
    }

    public static boolean fresh(ServerPlayer p, String id, long nowMs) {
        var located = QuestLoader.line(id);
        if (located == null || !QuestLoader.available(p, located.quest())) return false;
        long at = heardAt(p, id);
        QuestDef.Overheard def = line(id);
        long window = def == null ? WINDOW_MS : def.windowSeconds() * 1000L;
        return at > 0 && nowMs - at >= 0 && nowMs - at <= window;
    }

    /** Called by the bubble sender for every player the bubble reaches. */
    public static void onBubble(ServerPlayer p, String key) {
        onBubble(p, key, System.currentTimeMillis());
    }

    public static long repeatCount(ServerPlayer p, String id) {
        return Quests.state(p).entry("heard.count." + id, 0);
    }

    public static void onBubble(ServerPlayer p, String key, long nowMs) {
        String prefix = "murim.bubble.quest.";
        if (key.startsWith(prefix) && QuestLoader.line(key.substring(prefix.length())) != null) {
            String id = key.substring(prefix.length());
            if (!QuestLoader.available(p, QuestLoader.line(id).quest())) return;
            long count = Quests.state(p).entry("heard.count." + id, 0);
            QuestDef.Overheard def = line(id);
            if (count >= def.repeats()) return;
            if (count > 0 && nowMs - heardAt(p, id) < def.repeatSeconds() * 1000L) return;
            markHeard(p, id, nowMs);
            p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT,
                    Quests.state(p).record("heard.count." + id, count + 1));
            io.github.verycooltimo.murim.sect.PlayerJournal.record(p, Component.translatable("murim.quest.rumour." + id,
                    io.github.verycooltimo.murim.sect.SectNames.of(def.speaker()), Component.translatable(key)));
        }
    }

    /** The person says the line now, for the players near him. False if he cannot (asleep, in a talk, away). */
    public static boolean say(SectDisciple speaker, QuestDef.Overheard line) {
        var located = QuestLoader.line(line.id());
        if (located == null || !StoryAvailability.issued(located.quest())) return false;
        if (!speaker.isAlive() || speaker.isSleeping() || speaker.talkingTo() >= 0) return false;
        return SectChatter.send(speaker, line.key(), Component.translatable(line.key()));
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 13) return;
        ServerLevel level = event.getServer().overworld();
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator()) tickPlayer(level, p, System.currentTimeMillis());
        }
    }

    public static void tickPlayer(ServerLevel level, ServerPlayer p, long nowMs) {
        // Author 31.10: awake encounters, never a schedule requirement.
        for (var located : QuestLoader.lines()) {
            if (!QuestLoader.available(p, located.quest())) continue;
            QuestDef.Overheard line = located.step();
            if (!io.github.verycooltimo.murim.quest.world.HuaDryStreamCave.ENABLED && "cache_whisper".equals(line.id())) continue;
            long count = Quests.state(p).entry("heard.count." + line.id(), 0);
            if (count >= line.repeats() || !QuestService.passes(p, line.gate())) continue;
            if (heardAt(p, line.id()) > 0 && nowMs - heardAt(p, line.id()) < line.repeatSeconds() * 1000L) continue;
            for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(SPEAK_RANGE),
                    d -> d.isAlive() && d.member().isPresent() && d.memberKey().equals(line.speaker()) && d.free() && !d.isSleeping())) {
                if (d.distanceToSqr(p) <= SPEAK_RANGE * SPEAK_RANGE && d.getSensing().hasLineOfSight(p)
                        && level.getGameTime() - d.bubbleAt() > 100 && say(d, line)) break;
            }
        }
    }
}
