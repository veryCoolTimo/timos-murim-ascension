package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.world.camp.CampFight;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Мечник лагеря ждёт очереди (docs/design/24-bandit-camp.md §3, {@link CampFight}): тревога его
 * подняла, но в бою уже двое. Он подходит кольцом на 6–8 блоков к цели, не дальше 12 от своего
 * поста, стоит лицом к игроку и переминается вбок — видно, что следующий он.
 */
public class CampWaitGoal extends Goal {

    /** Дальше этого от поста ждущий не отходит. */
    private static final double LEASH = 12.0D;
    private static final double RING_MIN = 6.0D;
    private static final double RING_MAX = 8.5D;

    private final Bandit bandit;
    private int strafeTicks;
    private int strafeDir = 1;

    public CampWaitGoal(Bandit bandit) {
        this.bandit = bandit;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return bandit.campKey() != Bandit.NO_CAMP && bandit.state() != Bandit.STUN && CampFight.waiting(bandit);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        bandit.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity t = bandit.getTarget();
        if (t == null) {
            return;
        }
        bandit.getLookControl().setLookAt(t, 30.0F, 30.0F);
        BlockPos post = bandit.post();
        double d = bandit.distanceTo(t);
        double fromPost = post == null ? 0.0D : Math.sqrt(bandit.distanceToSqr(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D));
        if (d > RING_MAX && fromPost < LEASH) {
            // Подойти на кольцо: точка в RING_MIN..RING_MAX от цели на линии к себе, не дальше поводка.
            Vec3 away = bandit.position().subtract(t.position()).multiply(1.0D, 0.0D, 1.0D);
            Vec3 dir = away.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : away.normalize();
            Vec3 spot = t.position().add(dir.scale((RING_MIN + RING_MAX) * 0.5D));
            if (post != null && spot.distanceTo(Vec3.atBottomCenterOf(post)) > LEASH) {
                bandit.getNavigation().stop();
                return;
            }
            bandit.getNavigation().moveTo(spot.x, spot.y, spot.z, 0.9D);
            return;
        }
        if (fromPost > LEASH + 2.0D && post != null) {
            bandit.getNavigation().moveTo(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, 0.9D);
            return;
        }
        bandit.getNavigation().stop();
        if (--strafeTicks <= 0) {
            strafeTicks = 30 + bandit.getRandom().nextInt(40);
            strafeDir = bandit.getRandom().nextInt(3) - 1;
        }
        float forward = d < RING_MIN - 1.0D ? -0.4F : 0.0F;
        bandit.getMoveControl().strafe(forward, 0.3F * strafeDir);
        float want = (float) (Mth.atan2(t.getZ() - bandit.getZ(), t.getX() - bandit.getX()) * Mth.RAD_TO_DEG) - 90.0F;
        bandit.setYRot(Mth.approachDegrees(bandit.getYRot(), want, 20.0F));
        bandit.yBodyRot = bandit.getYRot();
    }
}
