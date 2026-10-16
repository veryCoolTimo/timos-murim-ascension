package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** One walker per NPC. No position writes, destination warps or invisible catch-up jumps. */
public final class SectWalking {
    private static final double ARRIVAL_DISTANCE_SQR = 0.25D;
    private final SectDisciple npc;
    private final Map<BlockPos, Long> opened = new HashMap<>();
    private Vec3 destination;
    private Vec3 progress;
    private long progressAt;
    private long nextPath;
    private long sidestepUntil;
    private Vec3 sidestep;
    private SectBuildings.Ladder ladder;
    private long ladderSearchAt;

    public SectWalking(SectDisciple npc) { this.npc = npc; }

    /** Doors opened by this NPC close only after all nearby people have passed. */
    public void tick() {
        if (npc.level().isClientSide || npc.tickCount % 5 != 0) return;
        long now = npc.level().getGameTime();
        var it = opened.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now < entry.getValue() || !npc.level().isLoaded(entry.getKey())) continue;
            BlockPos p = entry.getKey();
            var s = npc.level().getBlockState(p);
            double radius = s.getBlock() instanceof GateDoorBlock || s.getBlock() instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlock ? 5.0D : 2.0D;
            if (!npc.level().getEntitiesOfClass(LivingEntity.class, new AABB(p).inflate(radius), LivingEntity::isAlive).isEmpty()) continue;
            if (s.getBlock() instanceof DoorBlock door && s.is(BlockTags.WOODEN_DOORS) && !s.getValue(DoorBlock.POWERED)) {
                door.setOpen(npc, npc.level(), s, p, false);
            } else if (s.getBlock() instanceof GateDoorBlock gate && s.getValue(GateDoorBlock.OPEN)
                    && !npc.level().hasNeighborSignal(p)) {
                if (npc.level().getBlockEntity(p) instanceof io.github.verycooltimo.murim.decor.GateDoorBlockEntity be
                        && (be.busy() || be.powered())) continue;
                gate.toggle(npc.level(), p, npc);
            } else if (s.getBlock() instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlock grand
                    && s.getValue(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.OPEN) && !npc.level().hasNeighborSignal(p)) {
                if (npc.level().getBlockEntity(p) instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlockEntity be
                        && (be.busy() || be.powered())) continue;
                grand.toggle(npc.level(), p, npc);
            }
            it.remove();
        }
        // Gates are not vanilla doors to the pathfinder; open the nearby obstruction before re-pathing.
        if (destination == null || npc.getNavigation().isDone() && npc.position().distanceToSqr(destination) < ARRIVAL_DISTANCE_SQR) return;
        Vec3 ahead = destination.subtract(npc.position()).multiply(1, 0, 1).normalize();
        for (BlockPos p : BlockPos.betweenClosed(npc.blockPosition().offset(-3, 0, -3), npc.blockPosition().offset(3, 2, 3))) {
            if (!npc.level().isLoaded(p)) continue;
            Vec3 delta = Vec3.atCenterOf(p).subtract(npc.position()).multiply(1, 0, 1);
            if (delta.lengthSqr() > 10 || delta.dot(ahead) < -0.5D) continue;
            var s = npc.level().getBlockState(p);
            if (s.getBlock() instanceof DoorBlock door && s.is(BlockTags.WOODEN_DOORS) && !s.getValue(DoorBlock.OPEN)) {
                BlockPos base = s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? p.below() : p.immutable();
                door.setOpen(npc, npc.level(), npc.level().getBlockState(base), base, true);
                opened.put(base, now + 60);
                nextPath = 0;
            } else if (s.getBlock() instanceof GateDoorBlock gate && !s.getValue(GateDoorBlock.OPEN)) {
                BlockPos base = GateDoorBlock.origin(p, s);
                if (gate.toggle(npc.level(), base, npc)) {
                    opened.put(base, now + 100);
                    nextPath = now + 20;
                }
            } else if (s.getBlock() instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlock grand
                    && !s.getValue(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.OPEN)) {
                BlockPos base = io.github.verycooltimo.murim.decor.HallGrandDoorBlock.origin(p, s);
                if (grand.toggle(npc.level(), base, npc)) {
                    opened.put(base, now + 100);
                    nextPath = now + 20;
                }
            }
        }
    }

    public void stop() {
        destination = null;
        sidestep = null;
        ladder = null;
        npc.getNavigation().stop();
    }

    /** Late arrivals stay late. Every retry is rate limited even when there is no path. */
    public boolean walk(Vec3 target, double speed) {
        long now = npc.level().getGameTime();
        if (npc.getNavigation() instanceof GroundPathNavigation ground) ground.setCanOpenDoors(true);
        if (destination == null || destination.distanceToSqr(target) > 1.0D) {
            progress = npc.position();
            progressAt = now;
            nextPath = 0;
            sidestep = null;
            ladder = null;
            ladderSearchAt = 0;
        }
        destination = target;
        if (npc.position().distanceToSqr(target) < ARRIVAL_DISTANCE_SQR) {
            stop();
            return true;
        }
        // Ground navigation finishes at a block centre, within its own node tolerance.
        // Finish the last fraction of a block with ordinary travel, rather than retrying
        // the same completed path forever (formation slots and handover spots are fractional).
        var path = npc.getNavigation().getPath();
        Vec3 remaining = target.subtract(npc.position());
        if (path != null && path.canReach() && npc.getNavigation().isDone()
                && path.getTarget().equals(BlockPos.containing(target))
                && remaining.multiply(1, 0, 1).lengthSqr() < 2.25D && Math.abs(remaining.y) < 0.5D
                && SectLife.standable(npc.level(), BlockPos.containing(target))
                && npc.level().noCollision(npc, npc.getBoundingBox().expandTowards(remaining))) {
            npc.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed);
            progress = npc.position();
            progressAt = now;
            return false;
        }
        if (ladder != null) {
            if (npc.getY() >= ladder.exit().y - 0.2D) {
                npc.getNavigation().moveTo(ladder.exit().x, ladder.exit().y, ladder.exit().z, speed);
                if (npc.position().distanceToSqr(ladder.exit()) < 0.6D) { ladder = null; nextPath = 0; progressAt = now; }
                return false;
            }
            Vec3 bottom = Vec3.atBottomCenterOf(ladder.bottom());
            if (npc.onClimbable()) {
                npc.getNavigation().stop();
                Vec3 v = npc.getDeltaMovement();
                npc.setDeltaMovement(net.minecraft.util.Mth.clamp(bottom.x - npc.getX(), -0.08D, 0.08D),
                        Math.max(v.y, 0.18D), net.minecraft.util.Mth.clamp(bottom.z - npc.getZ(), -0.08D, 0.08D));
                npc.getMoveControl().setWantedPosition(bottom.x, ladder.exit().y, bottom.z, speed);
            } else if (now >= nextPath) {
                nextPath = now + 30;
                npc.getNavigation().moveTo(bottom.x, bottom.y, bottom.z, speed);
            }
            return false;
        }
        if (now >= ladderSearchAt && now - progressAt >= 60 && target.y > npc.getY() + 1.5D) {
            ladderSearchAt = now + 100;
            ladder = SectBuildings.ladder(npc, target);
            if (ladder != null) { nextPath = 0; return false; }
        }
        if (progress == null || npc.position().distanceToSqr(progress) > 0.25D) {
            progress = npc.position();
            progressAt = now;
        } else if (now - progressAt >= 80 && now >= sidestepUntil) {
            // A collision-safe two-block side path, alternating sides. Never move through a wall.
            Vec3 dir = target.subtract(npc.position()).multiply(1, 0, 1).normalize();
            double sign = (Math.floorDiv(now, 80) + npc.getId()) % 2 == 0 ? 1 : -1;
            Vec3 guess = npc.position().add(-dir.z * sign * 2, 0, dir.x * sign * 2);
            BlockPos at = BlockPos.containing(guess);
            sidestep = npc.level().isLoaded(at) && SectLife.standable(npc.level(), at) ? guess : null;
            sidestepUntil = now + 40;
            progressAt = now;
            nextPath = 0;
        }
        Vec3 to = sidestep != null && now < sidestepUntil ? sidestep : target;
        // Vanilla travel provides collision and ladder sliding; give a climber upward velocity, never a warp.
        if (npc.onClimbable() && target.y > npc.getY() + 0.5D) {
            Vec3 v = npc.getDeltaMovement();
            npc.setDeltaMovement(v.x, Math.max(v.y, 0.18D), v.z);
        }
        if (now >= nextPath) {
            nextPath = now + 30 + Math.floorMod(npc.getId() * 7, 21);
            npc.getNavigation().moveTo(to.x, to.y, to.z, speed);
        }
        return false;
    }
}
