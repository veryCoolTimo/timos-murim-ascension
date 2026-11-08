package io.github.verycooltimo.murim.training;

import java.util.Locale;

/**
 * One vanilla statistic (or a group of them) the body is read from, with what a unit of it is worth
 * (docs/design/27-body-training.md §2). Pure: the reading itself is {@link VanillaBody}. {@link #key} is saved in
 * {@link BodyState#seen()}, so it never changes; the rates may.
 *
 * <p>Distances are statistics in centimetres. Mining groups the per-block {@code Stats.BLOCK_MINED} counters by what the
 * block is (ore, log, pickaxe work, shovel work).
 */
public enum BodyProbe {
    WALK(BodySource.LEGS, TrainingBalance.WALK_PER_KM / 100_000.0D),
    SPRINT(BodySource.LEGS, TrainingBalance.SPRINT_PER_KM / 100_000.0D),
    CROUCH(BodySource.LEGS, TrainingBalance.CROUCH_PER_KM / 100_000.0D),
    /** Swimming, walking on the sea bed, wading. */
    SWIM(BodySource.LEGS, TrainingBalance.SWIM_PER_KM / 100_000.0D),
    /** Upward centimetres on ladders and vines. */
    CLIMB(BodySource.LEGS, TrainingBalance.CLIMB_PER_KM / 100_000.0D),
    JUMP(BodySource.JUMP, TrainingBalance.JUMP_EACH),
    ORE(BodySource.LABOUR, TrainingBalance.MINE_ORE),
    LOG(BodySource.LABOUR, TrainingBalance.MINE_LOG),
    STONE(BodySource.LABOUR, TrainingBalance.MINE_STONE),
    DIG(BodySource.LABOUR, TrainingBalance.MINE_DIG),
    KILLS(BodySource.FIGHT, TrainingBalance.KILL_EACH),
    /** Tenths of a health point of melee damage. */
    DAMAGE(BodySource.FIGHT, TrainingBalance.DAMAGE_PER_100 / 1000.0D);

    private final BodySource source;
    private final double rate;

    BodyProbe(BodySource source, double rate) {
        this.source = source;
        this.rate = rate;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public BodySource source() {
        return source;
    }

    /** Points per unit of the statistic. */
    public double rate() {
        return rate;
    }
}
