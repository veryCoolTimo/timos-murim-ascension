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
        trail.clear();
        trail.add(npc.position());
        lineAt = 0;
        expires = npc.level().getGameTime() + 12000;
        npc.walking().stop();
        npc.wake();
        npc.clearBlock();
        return true;
    }

    public boolean forcedFor(ServerPlayer p) { return forced && forPlayer(p); }
    public boolean active() { return player != null && target != null; }
    public boolean forPlayer(ServerPlayer p) { return active() && player == p; }
    public void cancel() { player = null; target = null; trail.clear(); npc.walking().stop(); }

    @Override
    public boolean canUse() {
        if (!active()) return false;
        if (!player.isAlive() || player.isRemoved() || player.level() != npc.level() || player.isSpectator() || forced && player.isCreative()
                || npc.level().getGameTime() > expires || npc.distanceToSqr(player) > (collecting ? 192 * 192 : 64 * 64)) { cancel(); return false; }
        return npc.free() && npc.guardTarget() == null;
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { npc.sit(false); npc.workPose(null); npc.setPose(SectPose.NONE); }
    @Override public void stop() { npc.walking().stop(); }

    private void say(String key) {
        player.displayClientMessage(Component.translatable(key, npc.getName()), false);
        if (npc.level().getGameTime() - npc.bubbleAt() >= 100)
            SectChatter.send(npc, key, Component.translatable(key, npc.getName()));
    }

    @Override
    public void tick() {
        long now = npc.level().getGameTime();
        if (collecting) {
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
