package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.sect.SectBubbles;
import io.github.verycooltimo.murim.sect.SectChatter;
import io.github.verycooltimo.murim.sect.repair.RepairData;
import io.github.verycooltimo.murim.sect.repair.RepairJob;
import io.github.verycooltimo.murim.sect.repair.RepairService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * A servant on an errand to mend a hole in the sect (docs/design/39-player-path-v2.md, «Ремонт секты и казна»): the repair service
 * ({@link RepairService}) picks him and hands him a {@link RepairJob}; he walks to the spot with the sack on his shoulder (the
 * porters' own carry pose), sets it down, and plays the wiping clip while he sets a block or two every {@link #STEP} ticks, each
 * with its placing sound and a puff of dust. When the holes are done, the treasury is dry, or he is called back, the daily routine
 * ({@code ScheduleGoal}) takes him back to his place.
 *
 * <p>Priority 3, beside the escort and the block goal, above the routine: combat, a duel, a talk and a guard order still come
 * first ({@link SectDisciple#free}). The walk is {@link io.github.verycooltimo.murim.sect.SectWalking}'s, with its stuck steps
 * and the unseen relocation; this goal only gives up when even that does not bring him there.
 */
public final class SectRepairGoal extends Goal {
    /** Ticks between two rounds of setting blocks. */
    public static final int STEP = 14;
    /** Blocks set in one round. */
    public static final int PER_STEP = 2;
    /** Ticks he may stay stuck after the walker's last recovery step before the errand is given up. */
    private static final long STUCK_GIVE_UP = 300L;

    private final SectDisciple npc;
    private RepairJob job;
    private boolean working;
    private long stepAt;
    private long stuckSince = -1L;
    private int stalls;
    private boolean spoke;

    public SectRepairGoal(SectDisciple npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    /** No errand: the service may pick him. */
    public boolean idle() {
        return job == null;
    }

    public RepairJob job() {
        return job;
    }

    /** At work (arrived, setting blocks), not on the way. */
    public boolean working() {
        return job != null && working;
    }

    public void assign(RepairJob job) {
        this.job = job;
        this.working = false;
        this.spoke = false;
        this.stuckSince = -1L;
        this.stalls = 0;
        npc.walking().stop();
        npc.wake();
        npc.clearBlock();
    }

    /** The errand is over (or the service took it away): he stops where he is, the routine takes over. */
    public void cancel() {
        if (job == null) {
            return;
        }
        job = null;
        working = false;
        npc.walking().stop();
        npc.setPose(SectPose.NONE);
    }

    @Override
    public boolean canUse() {
        return job != null && npc.isAlive() && npc.free() && !npc.blocking() && !npc.escorting() && npc.guardTarget() == null
                && !npc.dormant() && !npc.isSleeping();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        npc.sit(SeatStyle.NONE);
        npc.workPose(null);
        npc.setPose(SectPose.SACK);
    }

    @Override
    public void stop() {
        npc.walking().stop();
        npc.setPose(SectPose.NONE);
    }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level) || job == null) {
            return;
        }
        RepairData data = RepairData.get(level);
        if (data.job() != job) {
            // the service ended it (finished, out of money, daylight over)
            job = null;
            working = false;
            return;
        }
        long now = level.getGameTime();
        if (working && npc.position().distanceToSqr(job.spot()) > 9.0D) {
            // pushed off the spot (a talk, a crowd): walk back before the next round
            working = false;
        }
        if (!working) {
            walkTo(level, data, now);
            return;
        }
        npc.setPose(SectPose.WIPE);
        BlockPos next = job.targets().isEmpty() ? null : job.targets().get(0);
        if (next != null) {
            npc.getLookControl().setLookAt(next.getX() + 0.5D, next.getY() + 0.5D, next.getZ() + 0.5D, 40.0F, 40.0F);
            npc.faceYaw(yawTo(next), 40.0F);
        }
        if (now >= stepAt) {
            stepAt = now + STEP;
            int set = RepairService.work(level, data, job, npc.position().add(0.0D, 1.0D, 0.0D), PER_STEP, now);
            if (job.finished()) {
                RepairService.finish(level, data, job);
                return;
            }
            if (set > 0) {
                stalls = 0;
            } else if (job.outOfReach() > 0 && ++stalls >= 3) {
                // what is left is out of his reach from here: another spot, a few times at most
                stalls = 0;
                if (job.respots() >= 3 || RepairService.respot(level, job, npc) == null) {
                    RepairService.abort(level, data, job);
                    return;
                }
                job.respotted();
                working = false;
            }
        }
    }

    private void walkTo(ServerLevel level, RepairData data, long now) {
        npc.setPose(SectPose.SACK);
        boolean there = npc.walking().walk(job.spot(), 0.75D) || npc.position().distanceToSqr(job.spot()) < 1.0D;
        if (there) {
            working = true;
            stuckSince = -1L;
            job.reached(true);
            npc.walking().stop();
            stepAt = now + 12;
            npc.playPose(SectPose.WIPE);
            if (!spoke) {
                spoke = true;
                SectChatter.sayIfQuiet(npc, SectBubbles.Group.REPAIR_CREW.pick(npc.getRandom().nextInt(1 << 16)), 300);
            }
            return;
        }
        if (npc.walking().stuckStage() >= 3) {
            // the walker's last step (the unseen relocation, or a warning) has been taken: a little longer, then give up
            if (stuckSince < 0L) {
                stuckSince = now;
            } else if (now - stuckSince > STUCK_GIVE_UP) {
                RepairService.abort(level, data, job);
            }
        } else {
            stuckSince = -1L;
        }
    }

    private float yawTo(BlockPos p) {
        double dx = p.getX() + 0.5D - npc.getX();
        double dz = p.getZ() + 0.5D - npc.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }
}
