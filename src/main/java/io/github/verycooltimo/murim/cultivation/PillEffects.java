package io.github.verycooltimo.murim.cultivation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

/**
 * What the everyday pills do when they are eaten (author 24.10, canon names in {@link PillKind}). They are not qi pills: no dantian
 * is needed, no window opens, nothing is absorbed. Numbers are small on purpose, next to Snow Plum (a middle pill, 0.05 of the wall):
 * <ul>
 *   <li>Fasting: like a meal (8 hunger, saturation of cooked meat), only when hungry;</li>
 *   <li>Antidote: clears poison (the Thousand-Poison backlash and the Tang poisons are all {@code MobEffects.POISON}) and for
 *       {@link #ANTIDOTE_TICKS} makes a new poison last half as long (the same halving as the Thousand-Poison pill, see
 *       {@link PillService#onEffect});</li>
 *   <li>Blood Replenishing: heals {@link #BLOOD_HEAL} (7 hearts) and a short regeneration; 30 s before the next one;</li>
 *   <li>Quick Recovery: circulating qi refills {@link #QUICK_FACTOR} times faster for {@link #QUICK_TICKS} (canon: 2-3 times);
 *       one minute before the next one;</li>
 *   <li>Vitality (a qi pill, small share): a few seconds of regeneration on top of the qi.</li>
 * </ul>
 * State is two game-time stamps in the player's persistent data (a few minutes long, they may be lost on death).
 */
public final class PillEffects {

    public static final int FASTING_FOOD = 8;
    public static final float FASTING_SATURATION = 0.6F;
    public static final int ANTIDOTE_TICKS = 3600;
    public static final float BLOOD_HEAL = 14.0F;
    public static final int BLOOD_REGEN_TICKS = 120;
    public static final int QUICK_TICKS = 3600;
    public static final double QUICK_FACTOR = 2.5D;
    public static final int VITALITY_REGEN_TICKS = 160;

    private static final String ANTIDOTE_KEY = "murim_antidote_until";
    private static final String QUICK_KEY = "murim_quick_until";

    private PillEffects() {
    }

    /** Ticks before the next pill of this kind may be eaten (shown by the item cooldown); 0 for none. */
    public static int cooldown(PillKind kind) {
        return switch (kind) {
            case BLOOD_REPLENISHING -> 600;
            case QUICK_RECOVERY -> 1200;
            default -> 0;
        };
    }

    /** May this utility pill be eaten now? Fasting only when hungry, so it is not wasted. */
    public static boolean usable(Player player, PillKind kind) {
        if (kind == PillKind.MENDING) return MendingCare.usable(player);
        return kind != PillKind.FASTING || player.getFoodData().needsFood();
    }

    /** Applies a utility pill. The caller takes the item and starts the cooldown. */
    public static void use(ServerPlayer player, PillKind kind) {
        long now = player.level().getGameTime();
        switch (kind) {
            // API verified: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/LivingEntity.java#removeEffect
            case PLUM_BLOSSOM -> {
                io.github.verycooltimo.murim.decor.WineService.cure(player);
                player.removeEffect(MobEffects.WEAKNESS);
                player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            }
            case MENDING -> MendingCare.start(player);
            case FASTING -> player.getFoodData().eat(FASTING_FOOD, FASTING_SATURATION);
            case ANTIDOTE -> {
                player.removeEffect(MobEffects.POISON);
                player.getPersistentData().putLong(ANTIDOTE_KEY, now + ANTIDOTE_TICKS);
            }
            case BLOOD_REPLENISHING -> {
                player.heal(BLOOD_HEAL);
                player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, BLOOD_REGEN_TICKS, 1));
            }
            case QUICK_RECOVERY -> {
                player.getPersistentData().putLong(QUICK_KEY, now + QUICK_TICKS);
            }
            default -> {
            }
        }
    }

    /** After a qi pill was eaten (window opened): Vitality also mends a little. */
    public static void onEaten(ServerPlayer player, PillKind kind) {
        if (kind == PillKind.VITALITY) {
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, VITALITY_REGEN_TICKS, 0));
        }
    }

    /** Is the antidote's resistance on? */
    public static boolean antidoteActive(Player player) {
        return player.getPersistentData().getLong(ANTIDOTE_KEY) > player.level().getGameTime();
    }

    /** How much faster the circulating qi refills now: 1 normally, {@link #QUICK_FACTOR} under Quick Recovery. */
    public static double qiRegenFactor(Player player) {
        return player.getPersistentData().getLong(QUICK_KEY) > player.level().getGameTime() ? QUICK_FACTOR : 1.0D;
    }
}
