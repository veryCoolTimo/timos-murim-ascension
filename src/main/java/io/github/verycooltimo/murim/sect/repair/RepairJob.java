package io.github.verycooltimo.murim.sect.repair;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The crew's errand: one servant, the holes he is sent to mend (lowest first, so every block has its support), the spot he
 * stands at and the bookkeeping of what he did. Transient: it lives in {@link RepairData#job()} and is lost on a restart;
 * the damage list is what persists.
 */
public final class RepairJob {
    /** How far from the crew a block can be set (a servant reaches over a wall, not across a yard). */
    public static final double REACH = 5.6D;
    /**
     * The reach the spot is chosen with: a block short of {@link #REACH}, because he stops up to a block from the spot he walks to.
     */
    public static final double PLAN_REACH = 4.4D;
    /** Ticks a job may last without setting a block before the crew is called back. */
    public static final long PATIENCE = 1800L;

    private final UUID crew;
    private final RepairEnv env;
    private final List<BlockPos> targets;
    private final long seed;
    private final String place;
    private Vec3 spot;
    private long deadline;
    private int placed;
    private int spent;
    private boolean outOfFunds;
    private boolean reached;
    private int outOfReach;
    private int respots;

    RepairJob(UUID crew, RepairEnv env, List<BlockPos> targets, long seed, Vec3 spot, String place, long now) {
        this.crew = crew;
        this.env = env;
        this.targets = new ArrayList<>(targets);
        this.seed = seed;
        this.spot = spot;
        this.place = place;
        this.deadline = now + PATIENCE;
    }

    public UUID crew() {
        return crew;
    }

    RepairEnv env() {
        return env;
    }

    /** The holes still to mend, lowest first. */
    public List<BlockPos> targets() {
        return Collections.unmodifiableList(targets);
    }

    List<BlockPos> mutableTargets() {
        return targets;
    }

    /** The damage record the job was planned from (a seed that cannot be reached is banned for a while). */
    public long seed() {
        return seed;
    }

    /** Place function of the first hole ({@code main_hall}), "" when unknown. */
    public String place() {
        return place;
    }

    /** Where the crew stands to work. */
    public Vec3 spot() {
        return spot;
    }

    void moveSpot(Vec3 spot) {
        this.spot = spot;
    }

    public long deadline() {
        return deadline;
    }

    void extend(long until) {
        deadline = Math.max(deadline, until);
    }

    public int placed() {
        return placed;
    }

    public int spent() {
        return spent;
    }

    void placedOne(int wen) {
        placed++;
        spent += wen;
    }

    /** The treasury ran dry: the crew goes home and the rest waits for money. */
    public boolean outOfFunds() {
        return outOfFunds;
    }

    void outOfFunds(boolean broke) {
        outOfFunds = broke;
    }

    /** The crew has walked up to the spot at least once. */
    public boolean reached() {
        return reached;
    }

    public void reached(boolean reached) {
        this.reached = reached;
    }

    /** Holes that were ready at the last round but too far from where the crew stood. */
    public int outOfReach() {
        return outOfReach;
    }

    void outOfReach(int n) {
        outOfReach = n;
    }

    /** How often the crew was moved to a better spot; after a few times the errand is given up. */
    public int respots() {
        return respots;
    }

    public void respotted() {
        respots++;
    }

    public boolean finished() {
        return targets.isEmpty() || outOfFunds;
    }
}
