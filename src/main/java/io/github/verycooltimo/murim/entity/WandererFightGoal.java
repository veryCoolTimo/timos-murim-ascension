package io.github.verycooltimo.murim.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * The wandering disciple fights back (author 23.10): a player who struck him is chased and hit with a plain blow for a while.
 * Kept simple: no techniques, no spar, no sect war (he is not a member).
 */
public final class WandererFightGoal extends Goal {
    private final SectDisciple npc;
    private ServerPlayer target;

    public WandererFightGoal(SectDisciple npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!npc.wanderer() || npc.talkingTo() >= 0) {
            return false;
        }
        target = npc.grudgeTarget();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        target = npc.grudgeTarget();
        return target != null && npc.talkingTo() < 0;
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (npc.distanceToSqr(target) > 4.0D) {
            npc.getNavigation().moveTo(target, 1.15D);
        } else {
            npc.getNavigation().stop();
        }
        npc.wandererBlow(target);
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        target = null;
    }
}
