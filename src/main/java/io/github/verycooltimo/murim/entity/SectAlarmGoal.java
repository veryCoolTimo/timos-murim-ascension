package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.sect.SectAlarm;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * What a person of the sect does when he hears a blast or sees fire in the grounds (playtest 19.10: «Дом взрывается, там взрывы, и
 * все продолжают спать»). {@link SectAlarm} wakes him and hands him one of several errands, all short:
 *
 * <ul>
 *   <li>{@link Mode#LOOK}: stops what he does and looks toward the spot;</li>
 *   <li>{@link Mode#RUN}: a guard runs to the spot and stands there looking about;</li>
 *   <li>{@link Mode#FLEE}: servants and cooks flee; servants promise repairs once safe;</li>
 *   <li>{@link Mode#GAWK}: disciples gather at a safe distance;</li>
 *   <li>{@link Mode#CONFRONT}: the nearest elder investigates, then confronts the culprit.</li>
 * </ul>
 *
 * Priority 3, beside the escort, the repair and the block goal, above the routine: combat, a duel, a talk and a guard order still
 * come first ({@link SectDisciple#free}). The walk is {@link io.github.verycooltimo.murim.sect.SectWalking}'s; the errand has a
 * deadline, so a spot he cannot reach only costs him a few seconds, and the daily routine takes him back.
 */
public final class SectAlarmGoal extends Goal {

    /**
     * LOOK: stops and turns to the spot. RUN: a guard runs there. INSPECT: legacy inspection errand. FLEE: a
     * non-fighter hurries away from it. GAWK: a disciple runs to look and stops at a safe distance. ELDER: the nearest elder
     * comes. CONFRONT: the elder walks up to the culprit, pins him with his aura and says a sharp word.
     */
    public enum Mode { LOOK, RUN, INSPECT, FLEE, GAWK, ELDER, CONFRONT }

    /** A runner keeps up this pace, a servant walks briskly. */
    private static final double RUN_SPEED = 1.25D;
    private static final double WALK_SPEED = 0.95D;
    /** After arriving at the spot he stays and looks this many ticks (an inspector a little longer, he has a word to say). */
    private static final int STAY_RUN = 160;
    private static final int STAY_INSPECT = 140;
    private static final int STAY_GAWK = 140;
    private static final int STAY_FLEE = 120;
    /** The elder holds the culprit under his aura this long. */
    private static final int STAY_CONFRONT = 140;
    /** The elder stops this close to the culprit. */
    private static final double CONFRONT_REACH_SQ = 3.5D * 3.5D;

    private final SectDisciple npc;
    private Mode mode;
    private Vec3 spot;
    private Vec3 incident;
    private Vec3 stand;
    private long until;
    private boolean arrived;
    private int repath;
    private boolean destinationChecked;
    /** CONFRONT: the culprit and which strike of the day this is (1, 2, 3 ...). */
    private net.minecraft.server.level.ServerPlayer culprit;
    private int strike;
    private boolean aura;
    private boolean investigated;

    public SectAlarmGoal(SectDisciple npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** The errand now, or null when there is none. */
    public Mode mode(long now) {
        return mode != null && now < until ? mode : null;
    }

    /** Owns the temporary aura until the confrontation ends or penance takes over. */
    public boolean holdingAura() {
        return aura && mode(npc.level().getGameTime()) == Mode.CONFRONT;
    }

    public boolean arrived() {
        return arrived;
    }

    /**
     * Sends him to look, to run or to inspect.
     *
     * @param spot  the blast or the fire
     * @param stand where he stands to do it (short of the spot)
     * @param until the game time the errand ends at the latest
     */
    public void assign(Mode mode, Vec3 spot, Vec3 stand, long until) {
        this.mode = mode;
        this.spot = spot;
        this.incident = spot;
        this.stand = stand;
        this.until = until;
        this.arrived = false;
        this.investigated = false;
        this.repath = 0;
        this.destinationChecked = false;
        this.culprit = null;
        this.strike = 0;
        dropAura();
        npc.repairGoal().cancel();
        npc.walking().stop();
        npc.wake();
        npc.clearBlock();
        npc.sit(SeatStyle.NONE);
        npc.workPose(null);
        npc.setPose(SectPose.NONE);
    }

    /** Makes this errand a {@link Mode#CONFRONT} of this player (call right after {@link #assign}). */
    public void target(net.minecraft.server.level.ServerPlayer player, int strike) {
        this.culprit = player;
        this.strike = strike;
    }

    public void cancel() {
        dropAura();
        mode = null;
        arrived = false;
        npc.walking().stop();
    }

    @Override
    public boolean canUse() {
        long now = npc.level().getGameTime();
        return mode != null && now < until && npc.isAlive() && npc.free() && !npc.escorting() && npc.guardTarget() == null
                && !npc.wounded() && npc.repairGoal().idle();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        // The routine may have laid him down again between the word and the first tick of this goal: up again.
        npc.wake();
        npc.sit(SeatStyle.NONE);
        npc.workPose(null);
        npc.setPose(SectPose.NONE);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        npc.walking().stop();
        dropAura();
    }

    private void dropAura() {
        if (aura) {
            aura = false;
            npc.suppressionGoal().restoreAura();
        }
    }

    private static double speedOf(Mode mode) {
        return mode == Mode.INSPECT ? WALK_SPEED : mode == Mode.FLEE ? 1.35D : RUN_SPEED;
    }

    private static int stayOf(Mode mode) {
        return switch (mode) {
            case INSPECT -> STAY_INSPECT;
            case GAWK -> STAY_GAWK;
            case FLEE -> STAY_FLEE;
            case CONFRONT -> STAY_CONFRONT;
            default -> STAY_RUN;
        };
    }

    @Override
    public void tick() {
        if (mode == null) {
            return;
        }
        if (!destinationChecked && mode != Mode.LOOK) {
            // A freshly spawned or woken listener can still be in the air; ground navigation cannot plan yet.
            if (!npc.onGround()) return;
            var level = (net.minecraft.server.level.ServerLevel) npc.level();
            // Match SectWalking's door policy before asking whether the assigned endpoint is reachable.
            if (npc.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation ground) {
                ground.setCanOpenDoors(true);
            }
            var path = npc.getNavigation().createPath(net.minecraft.core.BlockPos.containing(stand), 0);
            if (path == null || !path.canReach()) stand = switch (mode) {
                case FLEE -> SectAlarm.fleePoint(level, npc, incident);
                case GAWK -> SectAlarm.standPoint(level, npc, incident, SectAlarm.GAWK_STANDOFF);
                case INSPECT -> SectAlarm.standPoint(level, npc, incident, SectAlarm.INSPECT_STANDOFF);
                case ELDER, CONFRONT -> SectAlarm.standPoint(level, npc, incident, SectAlarm.ELDER_STANDOFF);
                default -> SectAlarm.standPoint(level, npc, incident, SectAlarm.RUN_STANDOFF);
            };
            destinationChecked = true;
        }
        net.minecraft.server.level.ServerPlayer culprit = null;
        if (mode == Mode.CONFRONT) {
            culprit = this.culprit;
            if (culprit == null || !culprit.isAlive() || culprit.level() != npc.level() || culprit.isCreative() || culprit.isSpectator()) {
                // He is gone (or no longer a mortal to be held): the elder only looks at the spot
                dropAura();
                mode = Mode.ELDER;
                spot = incident;
                arrived = false;
                stand = SectAlarm.standPoint((net.minecraft.server.level.ServerLevel) npc.level(), npc, spot, SectAlarm.ELDER_STANDOFF);
                culprit = null;
            } else if (investigated) {
                spot = culprit.position();
                stand = culprit.position();
            }
        }
        if (mode != Mode.LOOK && !arrived) {
            double dx = stand.x - npc.getX();
            double dz = stand.z - npc.getZ();
            // Source: SectWalking.ARRIVAL_DISTANCE_SQR and its collision-checked final approach.
            // Fleeing must finish the retreat, not stop 1.5 blocks short and announce safety early.
            double reach = mode == Mode.CONFRONT && investigated ? CONFRONT_REACH_SQ
                    : mode == Mode.FLEE ? 0.25D : 2.25D;
            if (dx * dx + dz * dz < reach) {
                if (mode == Mode.CONFRONT && !investigated) {
                    investigated = true;
                    repath = 0;
                    return;
                }
                arrived = true;
                npc.walking().stop();
                // He has come: stays a little, looking, and the inspector says what he came to say.
                until = Math.min(until, npc.level().getGameTime() + stayOf(mode));
                SectAlarm.arrived(npc, mode);
                if (culprit != null) {
                    aura = true;
                    SectAlarm.confronted(npc, culprit, strike);
                }
            } else {
                if (--repath <= 0 || npc.getNavigation().isDone()) {
                    repath = 10;
                    npc.walking().walk(stand, speedOf(mode));
                }
                npc.getLookControl().setLookAt(spot.x, spot.y, spot.z, 30.0F, 30.0F);
                return;
            }
        }
        if (culprit != null && arrived) {
            npc.suppressionGoal().fullAura();
        }
        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(spot.x, spot.y, spot.z, 30.0F, 30.0F);
        float yaw = (float) (Mth.atan2(spot.z - npc.getZ(), spot.x - npc.getX()) * Mth.RAD_TO_DEG) - 90.0F;
        npc.faceYaw(yaw, 30.0F);
    }
}
