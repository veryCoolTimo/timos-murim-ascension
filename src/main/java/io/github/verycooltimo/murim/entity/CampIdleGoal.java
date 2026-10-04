package io.github.verycooltimo.murim.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Бандит лагеря без боя (docs/design/24-bandit-camp.md §2): возвращается на свой пост и держится
 * его — у костра, у ворот, на вышке. Время от времени делает пару шагов около поста (дозор), но не
 * уходит бродить по лесу: обычная прогулка ниже приоритетом и при живом посте не включается.
 * Лучник на вышке стоит на месте.
 */
public class CampIdleGoal extends Goal {

    /** Дальше этого от поста — идти обратно. */
    private static final double RETURN = 2.5D;

    private final Bandit bandit;
    private BlockPos wanderTo;
    private int nextWander;

    public CampIdleGoal(Bandit bandit) {
        this.bandit = bandit;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return bandit.post() != null && bandit.getTarget() == null && !bandit.isStunned();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        nextWander = 60 + bandit.getRandom().nextInt(140);
        wanderTo = null;
    }

    @Override
    public void tick() {
        BlockPos post = bandit.post();
        double d = bandit.distanceToSqr(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D);
        if (wanderTo == null && d > RETURN * RETURN) {
            if (bandit.getNavigation().isDone()) {
                bandit.getNavigation().moveTo(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, 0.85D);
            }
            return;
        }
        if (wanderTo != null) {
            if (bandit.getNavigation().isDone()) {
                wanderTo = null;
                nextWander = 120 + bandit.getRandom().nextInt(200);
            }
            return;
        }
        if (bandit.holdsPost()) {
            return;
        }
        if (--nextWander <= 0) {
            int dx = bandit.getRandom().nextInt(7) - 3;
            int dz = bandit.getRandom().nextInt(7) - 3;
            wanderTo = post.offset(dx, 0, dz);
            if (!bandit.getNavigation().moveTo(wanderTo.getX() + 0.5D, wanderTo.getY(), wanderTo.getZ() + 0.5D, 0.6D)) {
                wanderTo = null;
                nextWander = 80;
            }
        }
    }

    @Override
    public void stop() {
        wanderTo = null;
    }
}
