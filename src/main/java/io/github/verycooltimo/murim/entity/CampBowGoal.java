package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.world.camp.CampFight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;

import java.util.EnumSet;

/**
 * Лучник лагеря в бою (docs/design/24-bandit-camp.md §3): стреляет со своего места — с вышки или
 * из-за шатра, не бежит к игроку. Стреляют не больше двух сразу ({@link CampFight}); остальные
 * стоят на постах лицом к цели. Натяжение и выстрел — как у ванильного скелета.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/entity/ai/goal/RangedBowAttackGoal.java
 * (startUsingItem / getTicksUsingItem / BowItem.getPowerForTime / performRangedAttack).
 */
public class CampBowGoal extends Goal {

    /** Дальше этого лучник не стреляет. */
    private static final double RANGE = 24.0D;
    /** Ходит в пределах этого от поста (вышка — стоит). */
    private static final double LEASH = 4.0D;

    private final BanditArcher archer;
    private int cooldown = 20;
    private int unseen;

    public CampBowGoal(BanditArcher archer) {
        this.archer = archer;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = archer.getTarget();
        return archer.campKey() != Bandit.NO_CAMP && t != null && t.isAlive() && archer.state() != Bandit.STUN;
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
        archer.setAggressive(true);
    }

    @Override
    public void stop() {
        archer.setAggressive(false);
        archer.stopUsingItem();
        archer.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity t = archer.getTarget();
        if (t == null) {
            return;
        }
        archer.getLookControl().setLookAt(t, 30.0F, 30.0F);
        BlockPos post = archer.post();
        double leash = archer.holdsPost() ? 0.8D : LEASH;
        if (post != null && archer.distanceToSqr(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D) > leash * leash) {
            archer.stopUsingItem();
            if (archer.getNavigation().isDone()) {
                archer.getNavigation().moveTo(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, 1.0D);
            }
            return;
        }
        archer.getNavigation().stop();
        boolean sees = archer.getSensing().hasLineOfSight(t);
        unseen = sees ? 0 : unseen + 1;
        boolean shoot = sees && archer.distanceTo(t) <= RANGE && CampFight.mayFight(archer);
        if (cooldown > 0) {
            cooldown--;
        }
        if (archer.isUsingItem()) {
            if (!shoot && unseen > 40) {
                archer.stopUsingItem();
            } else if (shoot && archer.getTicksUsingItem() >= 20) {
                int used = archer.getTicksUsingItem();
                archer.stopUsingItem();
                archer.performRangedAttack(t, BowItem.getPowerForTime(used));
                cooldown = 30 + archer.getRandom().nextInt(20);
            }
        } else if (shoot && cooldown <= 0) {
            archer.startUsingItem(ProjectileUtil.getWeaponHoldingHand(archer, item -> item instanceof BowItem));
        }
    }
}
