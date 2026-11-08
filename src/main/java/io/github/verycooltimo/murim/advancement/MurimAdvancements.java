package io.github.verycooltimo.murim.advancement;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Mount Hua achievements: vanilla advancements under {@code data/murim/advancement/} with {@code minecraft:impossible}
 * criteria, granted from the gameplay code through {@link #grant}. Every call is safe to repeat and never throws.
 */
public final class MurimAdvancements {

    /** The one criterion of a plain achievement. */
    public static final String DONE = "done";
    /** Side quests, and the achievement that wants all of them (one criterion per quest). */
    public static final java.util.List<String> QUESTS =
            java.util.List.of("missing", "debt", "impostor", "thief", "cache", "strike", "fragments");

    private MurimAdvancements() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    /** Awards the plain achievement {@code path}; true if it was newly granted. */
    public static boolean grant(ServerPlayer player, String path) {
        return award(player, path, DONE);
    }

    /** Awards one criterion of an achievement. */
    public static boolean award(ServerPlayer player, String path, String criterion) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        AdvancementHolder holder = server.getAdvancements().get(id(path));
        if (holder == null) {
            MurimMod.LOGGER.warn("Achievement {} is not loaded", path);
            return false;
        }
        return player.getAdvancements().award(holder, criterion);
    }

    /** A side quest is finished: its own achievement and one step of the "all of them" one. */
    public static void questDone(ServerPlayer player, String quest) {
        if (!QUESTS.contains(quest)) return;
        award(player, "all_quests", quest);
    }

    /** The fragments the «fragments» errand wants: all six, minus F2 and F4 while the stele (their only source) is parked. */
    public static java.util.List<Integer> requiredFragments() {
        return io.github.verycooltimo.murim.quest.world.HuaStele.ENABLED ? java.util.List.of(0, 1, 2, 3, 4, 5) : java.util.List.of(0, 1, 3, 5);
    }

    /** Called for each newly set quest flag. */
    public static void onFlag(ServerPlayer player, String flag) {
        if (flag.startsWith("quest.") && flag.endsWith(".done")) {
            questDone(player, flag.substring(6, flag.length() - 5));
        } else if (flag.startsWith("clue.frag_")) {
            for (int i : requiredFragments()) {
                if (!io.github.verycooltimo.murim.quest.Quests.has(player, "clue.frag_" + i)) return;
            }
            questDone(player, "fragments");
        }
    }

    public static void stars(ServerPlayer player, int stars) {
        if (stars >= 1) grant(player, "stars_1");
        if (stars >= 6) grant(player, "stars_6");
        if (stars >= 12) grant(player, "stars_12");
    }

    public static void rank(ServerPlayer player, int rank) {
        switch (rank) {
            case 1 -> grant(player, "rank_third");
            case 2 -> grant(player, "rank_second");
            case 3 -> grant(player, "rank_first");
            case 4 -> grant(player, "rank_peak");
            default -> { }
        }
    }
}
