package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.sect.SectWatch;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Guard enforcement is separate from practice duels: no lessons, spar rewards or lethal techniques. */
public final class SectGuardGoal extends Goal {
    private final SectDisciple npc;
    private ServerPlayer target;
    private Vec3 exit;
    private long nextHit;
    public SectGuardGoal(SectDisciple npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    public ServerPlayer target() { return target; }
    public boolean begin(ServerPlayer player, Vec3 exit) {
        if (exit == null || player.level() != npc.level() || target != null && target != player
                || !npc.free() && target != player) return false;
        npc.escortGoal().cancel();
        npc.clearBlock();
        npc.wake();
        npc.sit(false);
        this.target = player;
        this.exit = exit;
        nextHit = npc.level().getGameTime() + 20;
        return true;
    }
    public void cancel() { target = null; npc.walking().stop(); npc.setPose(SectPose.NONE); }
    public void defeated() {
        ServerPlayer p = target;
        Vec3 out = exit;
        cancel();
        if (p != null) {
            SectWatch.defeated(p, npc);
            npc.escortGoal().begin(p, out, true);
        }
    }
    @Override public boolean canUse() {
        if (target == null) return false;
        if (!target.isAlive() || target.isRemoved() || target.level() != npc.level() || target.isCreative() || target.isSpectator()
                || npc.distanceToSqr(target) > 64 * 64 || SectService.state(target).member() && !SectWatch.repeatOffender(target)) {
            cancel(); return false;
        }
        return npc.spar() == SectDisciple.Spar.NONE && !npc.defending() && npc.talkingTo() < 0;
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { npc.workPose(null); npc.setPose(SectPose.GUARD); }
    @Override public void stop() { npc.walking().stop(); }
    @Override public void tick() {
        npc.getLookControl().setLookAt(target, 30, 30);
        npc.faceEntity(target, 15);
        if (target.getHealth() <= SectWatch.guardFloor(target) + 0.01F) { defeated(); return; }
        if (npc.distanceToSqr(target) > 2.4D * 2.4D || !npc.getSensing().hasLineOfSight(target)) {
            npc.walking().walk(target.position(), 0.95D);
            return;
        }
        npc.walking().stop();
        long now = npc.level().getGameTime();
        if (now + 8 == nextHit) npc.gesture("point");
        if (now >= nextHit) {
            nextHit = now + 24;
            npc.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            target.hurt(npc.damageSources().mobAttack(npc), 3.0F);
        }
    }
}
