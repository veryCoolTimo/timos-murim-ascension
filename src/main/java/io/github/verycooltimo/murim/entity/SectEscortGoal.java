package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.sect.SectChatter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.EnumSet;

/** A guide walks ahead; an expelled player is nudged along the same travelled path, with normal collisions. */
public final class SectEscortGoal extends Goal {
    private final SectDisciple npc;
    private final ArrayDeque<Vec3> trail = new ArrayDeque<>();
    private ServerPlayer player;
    private Vec3 target;
    private boolean forced;
    private boolean collecting;
    private long lineAt;
    private long expires;
    /** The collecting walk: the smallest gap to the player seen so far and when it last got smaller. */
    private double bestGap;
    private long progressAt;
    /** Where the target is asked again (the mentor walks off on his schedule): null for a fixed place. */
    private java.util.function.Supplier<Vec3> follow;
    /** Guide mode: the waiting for a newcomer who fell behind started at this tick, -1 when he is not waiting. */
    private long waitingSince = -1L;
    /** The guide has come up to the newcomer and said his word. */
    private boolean welcomed;
    /** Server-only completion hook; no client state beyond existing bubbles and movement sync. */
    private Runnable arrived;
    private boolean holdOnArrival;
    private boolean holding;
    public void holdOnArrival() { holdOnArrival = true; }
    public boolean holding() { return holding; }
    /** Where the guide stood when he last moved and when: a guide who walks and does not move is stuck. */
    private Vec3 movedFrom = Vec3.ZERO;
    private long movedAt;
    private int unstuck;

    /** Normal travel pace; the arrival GameTest checks the measured minimum of 0.9 × player walking speed. */
    public static final double LEAD_SPEED = 1.1D;
    /** The factor of catching up with a player who is ahead or far. */
    public static final double CATCH_SPEED = 1.4D;
    /** The guide stops and calls when the player is further than this (blocks). */
    public static final double WAIT_GAP = 9.0D;
    /** The newcomer who fell behind is waited for this long, then the guide goes back to his post (20 s). */
    public static final int GIVE_UP_TICKS = 400;
    /** A guide who tries to walk and has not moved this far in this many ticks is stuck. */
    public static final int STUCK_TICKS = 100;
    /** The player this close to the end of the way (blocks): he has arrived, the guide has done. */
    public static final double ARRIVED_RANGE = 5.0D;

    /** A guide who has not come a block nearer in this many ticks is not coming (frozen, stuck): the request is given to another. */
    public static final int STALL_TICKS = 300;

    public SectEscortGoal(SectDisciple npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public boolean begin(ServerPlayer player, Vec3 target, boolean forced) {
        if (active() && this.player != player) return false;
        if (player == null || target == null || npc.level() != player.level() || !npc.isAlive()
                || npc.spar() != SectDisciple.Spar.NONE || npc.defending() || npc.guardTarget() != null) return false;
        this.player = player;
        this.target = target;
        this.forced = forced;
        collecting = npc.distanceToSqr(player) > 36;
        bestGap = npc.distanceTo(player);
        progressAt = npc.level().getGameTime();
        trail.clear();
        trail.add(npc.position());
        lineAt = 0;
        follow = null;
        arrived = null;
        holdOnArrival = false;
        holding = false;
        waitingSince = -1L;
        welcomed = false;
        movedFrom = npc.position();
        movedAt = npc.level().getGameTime();
        unstuck = 0;
        expires = npc.level().getGameTime() + 12000;
        npc.walking().stop();
        npc.wake();
        npc.clearBlock();
        return true;
    }

    /** The destination is a person: ask again where he stands each tick, and walk there (UX review 21.10, item 16). */
    public void follow(java.util.function.Supplier<Vec3> where) { follow = where; }

    /** Called only on reaching the destination, never on cancellation or interruption. */
    public void onArrival(Runnable action) { arrived = action; }

    /** One line for the sect-check telemetry (tools/sectcheck). */
    public String debug() {
        return "active=" + active() + " collecting=" + collecting + " forced=" + forced + " unstuck=" + unstuck + " target=" + (target == null ? "-" : (int) target.x + "," + (int) target.y + "," + (int) target.z)
                + " gap=" + (player == null ? -1 : (int) npc.distanceTo(player)) + " welcomed=" + welcomed;
    }

    public boolean forcedFor(ServerPlayer p) { return forced && forPlayer(p); }
    public boolean active() { return player != null && target != null; }

    /**
     * The guide is still on his way to the player and has not got nearer for {@link #STALL_TICKS}: he does not come. A goal only
     * ticks while its person ticks, so a frozen guide (his chunk does not tick) shows here and nowhere else.
     */
    public boolean stalled(long now) {
        return active() && collecting && now - progressAt > STALL_TICKS;
    }
    public boolean forPlayer(ServerPlayer p) { return active() && player == p; }
    public void cancel() { holding = false; holdOnArrival = false; player = null; target = null; follow = null; arrived = null; trail.clear(); npc.walking().stop(); }

    @Override
    public boolean canUse() {
        if (!active()) return false;
        if (!player.isAlive() || player.isRemoved() || player.level() != npc.level() || player.isSpectator() || forced && player.isCreative()
                || npc.level().getGameTime() > expires || (collecting || forced) && npc.distanceToSqr(player) > (collecting ? 192 * 192 : 64 * 64)) { cancel(); return false; }
        return npc.free() && npc.guardTarget() == null;
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { npc.sit(SeatStyle.NONE); npc.workPose(null); npc.setPose(SectPose.NONE); }
    @Override public void stop() { npc.walking().stop(); }

    private void say(String key) {
        if (npc.level().getGameTime() - npc.bubbleAt() >= 100)
            SectChatter.send(npc, key, Component.translatable(key, npc.getName()));
    }

    @Override
    public void tick() {
        // cancel() clears the request immediately, even if this goal still has a scheduled tick.
        if (!active()) return;
        // Preparation must keep ticking during the outside hold as well as the walk.
        io.github.verycooltimo.murim.sect.SectHeadIntro.tickEscort(player, npc);
        if (!active()) return;
        if (holding) {
            npc.walking().stop();
            npc.getLookControl().setLookAt(player, 30, 30);
            return;
        }
        if (forced) { tickForced(); return; }
        long now = npc.level().getGameTime();
        if (follow != null) {
            Vec3 t = follow.get();
            if (!active()) return;
            if (t != null) target = t;
        }
        // The player is at the end of the way (or he found the mentor himself): the escort is over, cleanly.
        double arrivalRange = arrived != null ? ARRIVED_RANGE : 2.0D;
        if (player.position().distanceToSqr(target) < arrivalRange * arrivalRange
                && ((!holdOnArrival && arrived != null) || npc.position().distanceToSqr(target) < 4.0D)) {
            say("murim.sect.escort.arrived");
            Runnable action = arrived;
            if (holdOnArrival) {
                holding = true;
                arrived = null;
                npc.walking().stop();
            } else cancel();
            if (action != null) action.run();
            return;
        }
        double gap = Math.sqrt(horizontal2(npc.position(), player.position()));
        if (collecting) {
            if (gap < bestGap - 1.0D) {
                bestGap = gap;
                progressAt = now;
            }
            if (gap > 5.0D) {
                walkTo(player.position(), CATCH_SPEED, now);
                // walkTo() cancels the request after repeated stuck recovery failures.
                if (!active()) return;
                npc.getLookControl().setLookAt(player, 30, 30);
                return;
            }
            collecting = false;
            trail.clear();
            trail.add(npc.position());
        }
        if (!welcomed) {
            String key = arrived != null
                    ? io.github.verycooltimo.murim.sect.SectBubbles.Group.GUIDE_WELCOME.pick(player, npc.getRandom())
                    : "murim.sect.escort.follow";
            SectChatter.send(npc, key, Component.translatable(key));
            welcomed = true;
            io.github.verycooltimo.murim.sect.SectArrival.releaseReception(player);
        }
        double toTarget = Math.sqrt(horizontal2(npc.position(), target));
        double playerToTarget = Math.sqrt(horizontal2(player.position(), target));
        boolean ahead = playerToTarget + 2.0D < toTarget;
        // Brake before the limit: ordinary travel still carries momentum after navigation stops.
        if (gap > WAIT_GAP - 0.75D && !ahead) {
            // He lags: stop and call, give up after 20 s.
            npc.walking().stop();
            npc.getLookControl().setLookAt(player, 30, 30);
            if (waitingSince < 0) waitingSince = now;
            if (now - waitingSince >= GIVE_UP_TICKS) {
                SectChatter.sayIfQuiet(npc, io.github.verycooltimo.murim.sect.SectBubbles.Group.GUIDE_BACK.pick(player, npc.getRandom()), 20);
                player.getPersistentData().putLong(io.github.verycooltimo.murim.sect.SectArrival.GUIDE_OFF, now);
                cancel();
                return;
            }
            if ((now - waitingSince) % 100 == 0) {
                SectChatter.sayIfQuiet(npc, io.github.verycooltimo.murim.sect.SectBubbles.Group.GUIDE_WAIT.pick(player, npc.getRandom()), 20);
            }
            movedFrom = npc.position();
            movedAt = now;
            return;
        }
        waitingSince = -1L;
        if (toTarget < 1.0D) {
            // At the place and the player has not come yet.
            npc.walking().stop();
            npc.getLookControl().setLookAt(player, 30, 30);
            movedAt = now;
            return;
        }
        // The player who overtook the guide is caught up with, not waited for (UX review 21.10, item 17).
        walkTo(target, ahead || gap > 6.0D ? CATCH_SPEED : LEAD_SPEED, now);
    }

    /** One step of the walk; a guide who walks and does not get on is put on the way, then handed over (UX review 21.10, item 18). */
    private void walkTo(Vec3 to, double speed, long now) {
        npc.walking().walk(to, speed);
        if (npc.position().distanceToSqr(movedFrom) > 0.5D) {
            movedFrom = npc.position();
            movedAt = now;
            return;
        }
        if (now - movedAt < STUCK_TICKS) return;
        movedAt = now;
        if (++unstuck > 2) {
            SectChatter.sayIfQuiet(npc, io.github.verycooltimo.murim.sect.SectBubbles.Group.GUIDE_STUCK.pick(player, npc.getRandom()), 20);
            io.github.verycooltimo.murim.sect.SectNotice.bar(player, Component.translatable("murim.sect.arrival.guide_stuck", npc.getName()));
            npc.getPersistentData().putLong("murim_guide_ban", now + 2400L);
            cancel();
            return;
        }
        Vec3 dir = new Vec3(to.x - npc.getX(), 0.0D, to.z - npc.getZ());
        if (dir.lengthSqr() < 1.0E-4D) return;
        Vec3 step = dir.normalize().scale(unstuck * 3.0D);
        Vec3 at = io.github.verycooltimo.murim.sect.SectLife.stand(npc.level(), npc.position().add(step));
        if (at != null && npc.level().noCollision(npc, npc.getBoundingBox().move(at.subtract(npc.position())))) {
            npc.walking().stop();
            npc.moveTo(at.x, at.y, at.z, npc.getYRot(), 0.0F);
            movedFrom = npc.position();
        }
    }

    private static double horizontal2(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private void tickForced() {
        long now = npc.level().getGameTime();
        if (collecting) {
            double gap = npc.distanceTo(player);
            if (gap < bestGap - 1.0D) {
                bestGap = gap;
                progressAt = now;
            }
            if (npc.distanceToSqr(player) > 36) {
                npc.walking().walk(player.position(), 0.75D);
                npc.getLookControl().setLookAt(player, 30, 30);
                return;
            }
            collecting = false;
            trail.clear();
            trail.add(npc.position());
        }
        if (player.position().distanceToSqr(target) < 4 && npc.position().distanceToSqr(target) < 4) {
            say("murim.sect.escort.arrived");
            cancel();
            return;
        }
        if (now >= lineAt) {
            lineAt = now + 160;
            say(npc.distanceToSqr(player) > 64 ? "murim.sect.escort.wait" : "murim.sect.escort.follow");
        }
        if (npc.distanceToSqr(player) > 64 || npc.position().distanceToSqr(target) < 1) {
            npc.walking().stop();
            npc.getLookControl().setLookAt(player, 30, 30);
        } else {
            npc.walking().walk(target, 0.65D);
        }
        if (trail.isEmpty() || trail.peekLast().distanceToSqr(npc.position()) > 0.5D) {
            trail.addLast(npc.position());
            if (trail.size() > 64) trail.removeFirst();
        }
        // Only follow breadcrumbs already walked by the guard. Velocity is handled by normal player collision.
        while (trail.size() > 1 && player.position().distanceToSqr(trail.peekFirst()) < 1.5D) trail.removeFirst();
        if (forced && now % 10 == 0 && !trail.isEmpty()) {
            Vec3 to = trail.peekFirst().subtract(player.position());
            if (to.lengthSqr() > 0.5 && to.lengthSqr() < 64) {
                Vec3 push = to.multiply(1, 0, 1).normalize().scale(0.18D);
                player.push(push.x, player.horizontalCollision ? 0.2D : 0, push.z);
                player.hurtMarked = true;
            }
        }
    }
}
