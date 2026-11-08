package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectState;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player state of the side quests. There are no global markers: everything lives in the player's {@link SectState}
 * (the sect attachment is saved with the player and copied on death), so it survives save/load and is never shared.
 *
 * <p>Key shapes, all flags unless noted (a dialogue reads them with {@code flag}/{@code not_flag}):
 * <ul>
 *   <li>{@code quest.<quest>.<step>} - a step of a quest happened (asked, solved, rewarded...)</li>
 *   <li>{@code quest.<quest>.how.<way>} - how the player solved it; the people of the sect remember it and speak of it later</li>
 *   <li>{@code clue.<clue>} - the player has held this clue (item picked up or copied); it unlocks dialogue options</li>
 *   <li>{@code seen.<spot>} - the player examined this spot of the world</li>
 *   <li>journal {@code heard.<line>} - wall-clock milliseconds when he last overheard the bubble ({@link Overheard})</li>
 * </ul>
 */
public final class Quests {

    private Quests() {
    }

    static SectState state(ServerPlayer p) {
        return p.getData(ModAttachments.SECT);
    }

    public static boolean has(ServerPlayer p, String flag) {
        return state(p).has(flag);
    }

    /** Sets a flag; true if it was not set before. */
    public static boolean set(ServerPlayer p, String flag) {
        if (state(p).has(flag)) return false;
        p.setData(ModAttachments.SECT, state(p).with(flag));
        io.github.verycooltimo.murim.advancement.MurimAdvancements.onFlag(p, flag);
        return true;
    }

    public static void clear(ServerPlayer p, String flag) {
        p.setData(ModAttachments.SECT, state(p).without(flag));
    }

    /** The step flag of a quest: {@code quest.<quest>.<step>}. */
    public static String step(String quest, String step) {
        return "quest." + quest + "." + step;
    }

    public static boolean done(ServerPlayer p, String quest, String step) {
        return has(p, step(quest, step));
    }

    public static boolean mark(ServerPlayer p, String quest, String step) {
        return set(p, step(quest, step));
    }

    /** NPC memory: how the player solved a quest. The first way stays; people speak of the way he took first. */
    public static void remember(ServerPlayer p, String quest, String way) {
        if (recalled(p, quest) == null) set(p, step(quest, "how." + way));
    }

    /** The way the player solved the quest, or null. {@code ways} are tried in order. */
    public static String recalled(ServerPlayer p, String quest, String... ways) {
        for (String w : ways) if (has(p, step(quest, "how." + w))) return w;
        if (ways.length == 0) {
            String prefix = "quest." + quest + ".how.";
            for (String f : state(p).flags()) if (f.startsWith(prefix)) return f.substring(prefix.length());
        }
        return null;
    }

    /** Dialogue {@code social} conditions of the quests: {@code quest:heard:<line>} (overheard within the window), {@code quest:trial:<id>}. */
    public static boolean matches(ServerPlayer p, SectDisciple npc, String value) {
        int colon = value.indexOf(':');
        String kind = colon < 0 ? value : value.substring(0, colon);
        String arg = colon < 0 ? "" : value.substring(colon + 1);
        return switch (kind) {
            case "lesson" -> QuestLessons.offered(p, npc, arg);
            case "threshold" -> QuestLessons.thresholdOffered(p, npc, arg);
            case "heard" -> Overheard.fresh(p, arg);
            // quest:trial:<id> - this person offers that trial now; quest:trial_any - he offers some trial now
            case "trial" -> io.github.verycooltimo.murim.sect.SectTrials.offered(p, npc, arg);
            case "trial_any" -> io.github.verycooltimo.murim.sect.SectTrials.offeredAny(p, npc);
            default -> false;
        };
    }
}
