package io.github.verycooltimo.murim.entity.boss;

/**
 * The player adapts to the boss's aura pressure (author 14.11): "only the first ~20 s, so it does not get in the way
 * of the fight, you adapt, but only if the boss is not more than 2 realms above you". Pure rule, no Minecraft.
 *
 * <p>"Realm" is the aura rank scale of {@code Realm} (1 third, 2 second, 3 first, 4 peak ...). The boss stands at
 * {@code Realm.SECOND}; a player at rank 0 is 2 below him, so every player adapts unless the boss is raised.
 */
public final class BossAdaptation {

    /** Ticks from the first moment under pressure in a fight until the player has adapted (20 s). */
    public static final int TICKS = 400;

    /** Largest realm gap that still allows adaptation. */
    public static final int MAX_GAP = 2;

    private BossAdaptation() {
    }

    public static boolean canAdapt(int bossRealm, int playerRank) {
        return bossRealm - playerRank <= MAX_GAP;
    }

    /** {@code since} is the game time of the first pressure in this fight, or a negative number if none yet. */
    public static boolean adapted(int bossRealm, int playerRank, long since, long now) {
        return since >= 0 && canAdapt(bossRealm, playerRank) && now - since >= TICKS;
    }
}
