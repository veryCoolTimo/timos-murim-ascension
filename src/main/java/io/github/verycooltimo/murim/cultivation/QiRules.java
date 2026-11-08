package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.world.entity.player.Player;

/**
 * Who pays qi for techniques and footwork.
 *
 * <p>Creative and spectator players are free, like vanilla hunger there (author 18.10: the bar is not shown in creative
 * because qi is not used). Every spend site asks {@link #has} and applies {@link #charge}; meditation, breakthroughs and
 * the dev/capture setups write the profile directly and do not go through here.
 */
public final class QiRules {

    private QiRules() {
    }

    /** Creative and spectator players cast without paying and are never refused for lack of qi. */
    public static boolean free(Player player) {
        return player.isCreative() || player.isSpectator();
    }

    /** May {@code player} spend {@code cost} of circulating qi right now? */
    public static boolean has(Player player, DantianProfile profile, double cost) {
        return free(player) || profile.circulating() >= cost;
    }

    /** The profile after {@code player} spent {@code cost}; unchanged when the player is free. */
    public static DantianProfile charge(Player player, DantianProfile profile, double cost) {
        return free(player) ? profile : profile.withCirculating(profile.circulating() - cost);
    }
}
