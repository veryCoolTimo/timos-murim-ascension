package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.world.camp.BanditCampData;
import io.github.verycooltimo.murim.world.camp.BanditCamps;
import io.github.verycooltimo.murim.world.camp.CampLayout;
import io.github.verycooltimo.murim.world.camp.CampLife;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/** Camp errands, patrols, quiet nights and expeditions; combat goals retain priority.
 * Path validation follows SectAlarm.safePoint; poses reuse SectPose through PalClips. */
public class CampIdleGoal extends Goal {
    private final Bandit bandit;
    private BlockPos destination;
    private int pause;
    private int point;
    private int stalledTicks;
    private net.minecraft.world.phys.Vec3 progressPosition;
    private boolean returningHome;

    public static final double WALK_SPEED = .65;
    public static final int PATROL_PAUSE = 30;
    private boolean wasNight;
    private boolean torch;
    private ItemStack oldOffhand = ItemStack.EMPTY;

    public CampIdleGoal(Bandit bandit) {
        this.bandit = bandit;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override public boolean canUse() {
        return bandit.post() != null && bandit.getTarget() == null && !bandit.isStunned();
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { pause = 20; destination = null; }

    @Override public void tick() {
        if (!(bandit.level() instanceof ServerLevel level)) return;
        BanditCampData.Camp camp = BanditCamps.data(level).get(bandit.campKey());
        if (camp == null || camp.cleared) return;
        boolean night = CampLife.night(level);
        boolean travelling = camp.travellers.contains(bandit.campSlot());
        boolean sentry = night && !travelling && CampLife.sentry(bandit, camp);
        int patrolRank = CampLife.walkers(camp).indexOf(bandit.campSlot());
        boolean patrolling = !night && !travelling && patrolRank >= 0 && patrolRank < 2;
        equipTorch(sentry);
        if (wasNight != night) {
            destination = null; pause = 0; bandit.campPose(0); bandit.getNavigation().stop(); wasNight = night;
        }
        if (destination != null) {
            if (bandit.distanceToSqr(destination.getX() + .5, destination.getY(), destination.getZ() + .5) < 2.25) {
                bandit.getNavigation().stop(); destination = null;
                if (sentry || patrolling) point++;
                pause = travelling || sentry || patrolling ? PATROL_PAUSE : 100 + bandit.getRandom().nextInt(160);
                if (!travelling && !sentry && !bandit.holdsPost()) bandit.campPose(CampLife.byFire(bandit) ? 1 : night ? 2 : 0);
            } else {
                // Count lack of movement, not the duration of a long, healthy homeward leg.
                // API: minecraft-src/net/minecraft/world/phys/Vec3.java#distanceToSqr;
                // PathNavigation.java#isDone permits retry after navigation stops.
                if (progressPosition == null || bandit.position().distanceToSqr(progressPosition) >= .25) {
                    progressPosition = bandit.position();
                    stalledTicks = 0;
                } else stalledTicks++;
                if (stalledTicks > 400
                        || bandit.getNavigation().isDone() && !walkToward(level, destination, travelling ? .85 : WALK_SPEED)) {
                    bandit.getNavigation().stop();
                    destination = null; pause = 20;
                    // Skip obstructed perimeter points, but never skip the promised return home.
                    if ((sentry || patrolling) && !returningHome) point++;
                    if (travelling) { camp.tripTarget = camp.centre; BanditCamps.data(level).setDirty(); }
                }
            }
            return;
        }
        if (--pause > 0) return;
        if (night && !sentry && !travelling && bandit.campPose() != 0) return;
        BlockPos want;
        returningHome = false;
        if (travelling) want = CampLife.tripStep(bandit, camp);
        else if (bandit.isChief() || bandit.holdsPost() && !sentry) want = bandit.post();
        else {
            var walkers = CampLife.walkers(camp);
            int rank = walkers.indexOf(bandit.campSlot());
            boolean patrol = !night && rank >= 0 && rank < 2;
            if (sentry || patrol) {
                // Eight perimeter points then home; two patrols start on opposite sides.
                int step = Math.floorMod(point, 9);
                returningHome = step == 8;
                if (step == 8) want = bandit.post();
                else {
                    double angle = (step + bandit.campSlot() * 4) * Math.PI / 4;
                    int radius = sentry ? 12 : 16 + (int) Math.floorMod(camp.seed, 9L);
                    want = CampLife.ground(level, camp.centre.offset((int) Math.round(Math.cos(angle) * radius), 0,
                            (int) Math.round(Math.sin(angle) * radius)));
                }
            } else if (night) {
                want = bandit.post();
            } else {
                want = CampLife.activity(level, camp, point++ + bandit.campSlot());
            }
        }
        bandit.campPose(0);
        if (want != null && bandit.distanceToSqr(want.getX() + .5, want.getY(), want.getZ() + .5) < 2.25) {
            if (sentry || patrolling) point++;
            pause = sentry || patrolling ? PATROL_PAUSE : 100;
            if (night && !sentry && !travelling && !bandit.holdsPost()) bandit.campPose(CampLife.byFire(bandit) ? 1 : 2);
        } else if (want != null && walkToward(level, want, travelling ? .85 : WALK_SPEED)) {
            destination = want; stalledTicks = 0; progressPosition = bandit.position();
        } else {
            if ((sentry || patrolling) && !returningHome) point++;
            pause = 40;
        }
    }

    /** Prefer a complete path; subdivide destinations outside navigation range, including home.
     * API/pattern: CampLife.tripStep + CampLife.walk; retain the final patrol destination
     * while navigation completes individual fully validated, supported paths. */
    private boolean walkToward(ServerLevel level, BlockPos target, double speed) {
        // Try the actual destination first: a blocked straight-line waypoint must not
        // reject a reachable detour that vanilla pathfinding can already find.
        if (CampLife.walk(bandit, target, speed)) return true;
        if (CampLife.horizontal(bandit.blockPosition(), target) <= 64) return false;
        var delta = net.minecraft.world.phys.Vec3.atBottomCenterOf(target).subtract(bandit.position())
                .multiply(1, 0, 1).normalize().scale(8);
        BlockPos step = CampLife.ground(level, BlockPos.containing(bandit.position().add(delta)));
        return step != null && CampLife.walk(bandit, step, speed);
    }

    private void equipTorch(boolean on) {
        if (on == torch) {
            // A sentry may have been saved with his temporary torch before unloading.
            if (!on && bandit.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TORCH))
                bandit.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            return;
        }
        if (on) {
            oldOffhand = bandit.getItemBySlot(EquipmentSlot.OFFHAND).copy();
            bandit.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
            bandit.setDropChance(EquipmentSlot.OFFHAND, 0);
        } else bandit.setItemSlot(EquipmentSlot.OFFHAND, oldOffhand);
        torch = on;
    }

    @Override public void stop() {
        destination = null;
        bandit.campPose(0);
        bandit.getNavigation().stop();
        equipTorch(false);
    }
}
