package io.github.verycooltimo.murim.world.fortress;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Optional fortress victory reward: a saved trophy flag and a one-time refill at the third rank.
 * Author 21.10: this never supplies sect promotion proof; only the fortress boss calls award.
 */
public final class BossCredit {

    private BossCredit() {
    }

    /**
     * Gives the credit.
     *
     * @param victory the line recorded in the tablet journal (the master's fall)
     * @return whether it was the first one (the flag was not set before)
     */
    public static boolean award(ServerPlayer p, Component victory) {
        boolean first = !p.getData(BossRegistry.BOSS_DEFEATED);
        p.setData(BossRegistry.BOSS_DEFEATED, true);
        io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(p, "boss");
        PlayerJournal.record(p, victory.copy().withStyle(ChatFormatting.GOLD));
        if (first) {
            PlayerJournal.record(p, Component.translatable("murim.fortress.wall_open").withStyle(ChatFormatting.YELLOW));
            fillWall(p);
        }
        return first;
    }

    /**
     * Fills the pool up to the wall of the breakthrough to the second rank. Nothing happens unless the player stands at the
     * third rank with an awakened centre and the pool is below the wall.
     *
     * @return whether qi was added
     */
    public static boolean fillWall(ServerPlayer p) {
        DantianProfile profile = p.getData(ModAttachments.PROFILE);
        if (!profile.isAwakened() || profile.rank() != Realm.THIRD) {
            return false;
        }
        double wall = Realm.wall(profile);
        if (profile.pool() >= wall) {
            return false;
        }
        p.setData(ModAttachments.PROFILE, profile.withPool(wall));
        ProfileNetwork.sync(p);
        MurimMod.LOGGER.info("Boss credit: {} had {} of {} qi at the wall of the second rank, filled", p.getGameProfile().getName(),
                String.format("%.1f", profile.pool()), String.format("%.1f", wall));
        return true;
    }
}
