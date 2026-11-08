package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.sect.DialogueService;
import io.github.verycooltimo.murim.sect.SectBubbles;
import io.github.verycooltimo.murim.sect.SectChatter;
import io.github.verycooltimo.murim.sect.SectService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * The wandering disciple speaks first (docs/adr/0020): he sees a stranger within {@link #SEE} blocks, turns, waves, says a line
 * over his head, walks up to {@link #CLOSE} blocks and then opens the talk himself. A player who is busy (running, flying,
 * mid-fight, using an item, building) is left alone until idle for at least five seconds. After any ending he waits
 * {@link #COOLDOWN} ticks, so a player who walks away or declines is not chased.
 */
public final class WandererApproachGoal extends Goal {
    public static final double SEE = 24.0D;
    public static final double CLOSE = 3.0D;
    public static final long COOLDOWN = 6000L;
    static final int WAVE_TICKS = 24;
    static final int GIVE_UP = 320;

    private final SectDisciple npc;
    private ServerPlayer who;
    private int ticks;
    private Vec3 pathTarget;
    private int nextPathTick;

    public WandererApproachGoal(SectDisciple npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private ServerPlayer find() {
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        ServerPlayer best = null;
        double bestD = SEE * SEE;
        for (ServerPlayer p : level.players()) {
            double d = p.distanceToSqr(npc);
            if (d <= bestD && p.isAlive() && !p.isSpectator() && !SectService.state(p).member() && io.github.verycooltimo.murim.sect.Wanderer.mayApproach(p)
                    && io.github.verycooltimo.murim.sect.GuidanceIdle.idle(p) && npc.hasLineOfSight(p)) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    @Override
    public boolean canUse() {
        if (!npc.wanderer() || !npc.free() || npc.pointing() || npc.level().getGameTime() < npc.approachReadyAt()) {
            return false;
        }
        who = find();
        return who != null;
    }

    @Override
    public boolean canContinueToUse() {
        return who != null && who.isAlive() && !who.hasDisconnected() && !who.isSpectator() && io.github.verycooltimo.murim.sect.Wanderer.needsDirections(who)
                && npc.level().getGameTime() >= npc.approachReadyAt() && npc.free() && !npc.pointing() && ticks < GIVE_UP
                && npc.distanceTo(who) <= SEE + 16.0D;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Like ScheduleGoal: these counters measure server ticks, including the 24-tick wave.
        return true;
    }

    @Override
    public void start() {
        ticks = 0;
        pathTarget = null;
        nextPathTick = WAVE_TICKS;
        npc.getNavigation().stop();
        npc.gesture("wave");
        String key = (npc.wandererIndex() > 0 ? SectBubbles.Group.WANDER_RECRUIT : SectBubbles.Group.WANDER_GREET).pick(who, npc.getRandom());
        SectChatter.send(npc, key, Component.translatable(key));
    }

    @Override
    public void tick() {
        // end() clears who before the selector necessarily calls stop(); every-tick updates can still arrive.
        if (who == null) {
            return;
        }
        ticks++;
        npc.getLookControl().setLookAt(who, 40.0F, 40.0F);
        if (ticks < WAVE_TICKS) {
            npc.getNavigation().stop();
            return;
        }
        if (npc.distanceTo(who) > CLOSE) {
            // Keep an active route to a stationary player. Rebuilding it from a new start cell every ten
            // ticks can send the walker back to that cell's centre before he finishes the next step.
            // Retry completed/failed paths too, but rate-limit failures rather than retrying every tick.
            boolean moved = pathTarget == null || pathTarget.distanceToSqr(who.position()) > 1.0D;
            if (ticks >= nextPathTick && (moved || npc.getNavigation().isDone())) {
                npc.getNavigation().moveTo(who, 1.0D);
                pathTarget = who.position();
                nextPathTick = ticks + 10;
            }
            return;
        }
        npc.getNavigation().stop();
        arrive();
    }

    private boolean calm() {
        return io.github.verycooltimo.murim.sect.GuidanceIdle.idle(who) && !who.isSprinting() && !who.isFallFlying() && !who.isUsingItem() && !who.isSwimming() && !who.getAbilities().flying
                // Zero means no recorded attack; a fresh player is calm too (docs/adr/0020).
                && who.hurtTime == 0 && (who.getLastHurtMobTimestamp() == 0
                || who.tickCount - who.getLastHurtMobTimestamp() > 100)
                // SectDisciple extends Bandit/Monster: peaceful disciples (including this guide) are not threats.
                && who.level().getEntitiesOfClass(Mob.class, who.getBoundingBox().inflate(12.0D),
                m -> m.isAlive() && (m instanceof SectDisciple ? m.getTarget() == who : m instanceof Enemy)).isEmpty();
    }

    private void arrive() {
        if (calm()) {
            DialogueService.open(who, npc);
            end();
        }
    }

    private void end() {
        npc.approachCooldown(COOLDOWN);
        who = null;
    }

    @Override
    public void stop() {
        if (who != null) {
            npc.approachCooldown(COOLDOWN);
        }
        who = null;
        npc.getNavigation().stop();
    }
}
