package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectNavigation;
import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One walker per NPC. No position writes, destination warps or invisible catch-up jumps; the single exception is the
 * last stuck-recovery step (relocation to the nearest waypoint, unseen only, config relocateWhenUnseen).
 */
public final class SectWalking {
    private static final double ARRIVAL_DISTANCE_SQR = 0.25D;
    private final SectDisciple npc;
    private Vec3 destination;
    private java.util.function.Predicate<Vec3> recoveryArea = point -> true;
    private boolean positionRecovery = true;
    private Vec3 progress;
    private long progressAt;
    private long nextPath;
    private long sidestepUntil;
    private Vec3 sidestep;
    private SectBuildings.Ladder ladder;
    private long ladderSearchAt;

    public SectWalking(SectDisciple npc) { this.npc = npc; }

    /** The point he is walking to now (null: not walking). */
    public Vec3 goal() {
        return planGoal != null ? planGoal : destination;
    }

    /** Doors opened by this NPC close only after all nearby people have passed. */
    public void tick() {
        if (npc.level().isClientSide || npc.tickCount % 5 != 0) return;
        long now = npc.level().getGameTime();
        // SectDoors owns closing: ordinary leaves belong to their opener, big doors to their keepers.
        // Gates are not vanilla doors to the pathfinder; open the nearby obstruction before re-pathing.
        if (npc.sitting() || destination == null || npc.getNavigation().isDone() && npc.position().distanceToSqr(destination) < ARRIVAL_DISTANCE_SQR) return;
        Vec3 ahead = destination.subtract(npc.position()).multiply(1, 0, 1).normalize();
        for (BlockPos p : BlockPos.betweenClosed(npc.blockPosition().offset(-3, 0, -3), npc.blockPosition().offset(3, 2, 3))) {
            if (!npc.level().isLoaded(p)) continue;
            Vec3 delta = Vec3.atCenterOf(p).subtract(npc.position()).multiply(1, 0, 1);
            if (delta.lengthSqr() > 10 || delta.dot(ahead) < -0.5D) continue;
            var s = npc.level().getBlockState(p);
            // ADR 0023: proximity is not intent. Only open a leaf intersected by this walk.
            if (!needsDoor(p, s)) continue;
            if (s.getBlock() instanceof DoorBlock door && s.is(BlockTags.WOODEN_DOORS) && !s.getValue(DoorBlock.OPEN)) {
                BlockPos base = s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? p.below() : p.immutable();
                door.setOpen(npc, npc.level(), npc.level().getBlockState(base), base, true);
                SectDoors.mark(npc, base, now);
                nextPath = 0;
            } else if (s.getBlock() instanceof GateDoorBlock gate && !s.getValue(GateDoorBlock.OPEN)) {
                BlockPos base = GateDoorBlock.origin(p, s);
                if (gate.toggle(npc.level(), base, npc)) {
                    SectDoors.mark(npc, base, now);
                    nextPath = now + 20;
                }
            } else if (s.getBlock() instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlock grand
                    && !s.getValue(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.OPEN)) {
                BlockPos base = io.github.verycooltimo.murim.decor.HallGrandDoorBlock.origin(p, s);
                if (grand.toggle(npc.level(), base, npc)) {
                    SectDoors.mark(npc, base, now);
                    nextPath = now + 20;
                }
            }
        }
    }

    private boolean needsDoor(BlockPos cell, net.minecraft.world.level.block.state.BlockState state) {
        boolean big = state.getBlock() instanceof GateDoorBlock
                || state.getBlock() instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlock;
        // Routed walks open big doors only at their explicit OPEN leg (gateOpen), never on an adjacent leg.
        if (big && plan != null) return false;
        var path = npc.getNavigation().getPath();
        if (path != null && path.canReach() && !npc.getNavigation().isDone()) {
            Vec3 from = npc.position();
            for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
                Vec3 to = Vec3.atBottomCenterOf(path.getNode(i).asBlockPos());
                if (crossesDoor(from, to, cell, state)) return true;
                from = to;
            }
            return false;
        }
        // A closed big leaf can itself prevent a complete vanilla path in layouts without a route graph.
        return crossesDoor(npc.position(), destination, cell, state);
    }

    /** The segment to the current waypoint must cross the door plane within its cells. */
    static boolean crossesDoor(Vec3 from, Vec3 to, BlockPos cell, net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.core.Direction facing;
        if (state.getBlock() instanceof DoorBlock) facing = state.getValue(DoorBlock.FACING);
        else if (state.getBlock() instanceof GateDoorBlock) facing = state.getValue(GateDoorBlock.FACING);
        else if (state.getBlock() instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlock)
            facing = state.getValue(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.FACING);
        else return false;
        boolean x = facing.getAxis() == net.minecraft.core.Direction.Axis.X;
        double plane = (x ? cell.getX() : cell.getZ()) + 0.5D;
        double a = x ? from.x : from.z, b = x ? to.x : to.z;
        if (Math.abs(b - a) < 0.0001D) return false;
        double t = (plane - a) / (b - a);
        if (t < 0 || t > 1) return false;
        double across = x ? from.z + t * (to.z - from.z) : from.x + t * (to.x - from.x);
        double edge = x ? cell.getZ() : cell.getX();
        return across >= edge - 0.3D && across <= edge + 1.3D && Math.abs(from.y - cell.getY()) < 2.5D;
    }

    public void stop() {
        release();
        gateSince = -1L;
        gateDoor = null;
        plan = null;
        planGoal = null;
        destination = null;
        sidestep = null;
        ladder = null;
        npc.getNavigation().stop();
    }

    // ------------------------------------------------------------------ routed walking (docs/design/38 sections 5.4-5.6)

    /** A gate that stays shut this long after the walker reached it counts as a stuck walk like any other. */
    public static final int GATE_PATIENCE = 200;
    /** Stuck steps, in ticks without progress. */
    public static final int STUCK_REPATH = 100;
    public static final int STUCK_STEP_BACK = 240;
    public static final int STUCK_RELOCATE = 480;
    /** How far the body of the last-step sweep is lifted: whatever is lower (rugs, carpets, mats) is no obstacle. */
    static final double SWEEP_LIFT = 0.1D;

    /** Day simulation probe: ticks spent waiting at a passage queue or a gate, and stuck re-plans, since the walker was made. */
    private long probeWait;
    private int probeRepaths;

    public long probeWait() {
        return probeWait;
    }

    public int probeRepaths() {
        return probeRepaths;
    }

    private SectRouter.Plan plan;
    /** Cost of the route planned for the current goal in cells; -1 when he walks plain (day simulation probe). */
    private double routeCost = -1.0D;
    private int leg;
    private Vec3 planGoal;
    private final Set<Integer> bannedEdges = new HashSet<>();
    private List<SectFlow.Need> held = List.of();
    private int heldEdge = -2;
    private int waited;
    /** Next time the walker may swing the gate of an OPEN leg (rate limit only; it does not say how long he has waited). */
    private long openTry;
    /**
     * When the walker reached the gate he waits at, -1 when he waits at none: the gate gets {@link #GATE_PATIENCE} ticks.
     * A re-plan does not give it more time (the same gate id keeps the clock), only passing it or a new goal does.
     */
    private long gateSince = -1L;
    private String gateDoor;
    private long openedAt = -1L;
    private double bestRemaining = Double.MAX_VALUE;
    private long bestAt;
    private long lastWalk = Long.MIN_VALUE;
    private long lastStepCheck;
    private int stage;
    private boolean spotBan;

    /** Two cells see each other: no solid block on the straight line between their middles (a closed door is solid). */
    public static java.util.function.BiPredicate<BlockPos, BlockPos> clear(net.minecraft.world.level.Level level) {
        return (a, b) -> {
            Vec3 from = Vec3.atBottomCenterOf(a).add(0.0D, 1.0D, 0.0D);
            Vec3 to = Vec3.atBottomCenterOf(b).add(0.0D, 1.0D, 0.0D);
            if (!level.isLoaded(a) || !level.isLoaded(b)) {
                return true;
            }
            return level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()))
                    .getType() == net.minecraft.world.phys.HitResult.Type.MISS;
        };
    }

    private SectLayoutFile file() {
        return npc.layout() == null ? null : npc.layout().file();
    }

    /** One line for the sect-check telemetry (tools/sectcheck): where the walker is in his plan. */
    public String debug() {
        return "plan=" + (plan == null ? "-" : plan.legs().size()) + "/leg" + leg + " held=" + held.size() + " heldEdge=" + heldEdge + " waited=" + waited
                + " wait=" + probeWait + " repaths=" + probeRepaths + " gate=" + gateDoor + " best=" + (bestRemaining == Double.MAX_VALUE ? -1 : (int) bestRemaining)
                + (plan != null && leg < plan.legs().size() ? " legTo=" + (int) plan.legs().get(leg).x + "," + (int) plan.legs().get(leg).y + "," + (int) plan.legs().get(leg).z
                        + "/" + plan.legs().get(leg).type : "")
                + " path=" + (npc.getNavigation().getPath() == null ? "none" : npc.getNavigation().getPath().getNodeCount() + (npc.getNavigation().getPath().canReach() ? "ok" : "partial"));
    }

    /** Stuck stage reached for this walk (0 none, 1 re-path, 2 step back and other spot, 3 relocate or warning). */
    public int stuckStage() {
        return stage;
    }

    /** True once after the walker gave up on the spot he was walking to: the routine should pick another one. */
    public boolean takeSpotBan() {
        boolean b = spotBan;
        spotBan = false;
        return b;
    }

    /** Observed lesson scenes may time out, but must never use position recovery. */
    public boolean walkObserved(Vec3 target, double speed) {
        positionRecovery = false;
        try {
            return walk(target, speed);
        } finally {
            positionRecovery = true;
        }
    }

    /**
     * Walk to a point. In the built sect the way between buildings follows the waypoint graph (vanilla pathfinding
     * covers one leg at a time, closed big gates are opened explicitly, narrow stairs and doors are queued); anywhere
     * else it is plain pathfinding. True once the target is reached.
     */
    public boolean walk(Vec3 target, double speed) {
        return walk(target, speed, point -> true);
    }

    /** Recovery steps must obey the same work boundary as the requested destination. Server-local, per walk. */
    public boolean walk(Vec3 target, double speed, java.util.function.Predicate<Vec3> area) {
        recoveryArea = area;
        try {
            return walkWithinArea(target, speed);
        } finally {
            recoveryArea = point -> true;
        }
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/ai/navigation/PathNavigation.java#stop
    private boolean walkWithinArea(Vec3 target, double speed) {
        if (sidestep != null && !recoveryArea.test(sidestep)) {
            sidestep = null;
            nextPath = 0;
            npc.getNavigation().stop();
        }
        SectLayoutFile file = file();
        if (file == null || !(npc.level() instanceof ServerLevel)) {
            return walkDirect(target, speed);
        }
        long now = npc.level().getGameTime();
        if (now - lastWalk > 40L) {
            bestRemaining = Double.MAX_VALUE;
            bestAt = now;
            stage = 0;
        }
        lastWalk = now;
        // Only when he has made no headway for two seconds: crossing a counter or a rack on the way to a task is the walk, standing on one is not.
        if (positionRecovery && now - lastStepCheck >= 5L && now - bestAt >= 40L) {
            lastStepCheck = now;
            if (stepOffFurniture()) {
                bestAt = now;       // getting down is not standing still
            }
        }
        if (planGoal == null || planGoal.distanceToSqr(target) > 9.0D) {
            newGoal(file, target, now);
        }
        double remaining = plan == null ? Math.hypot(target.x - npc.getX(), target.z - npc.getZ())
                : plan.remaining(npc.getX(), npc.getZ(), Math.min(leg, plan.legs().size()), target.x, target.z);
        monitor(file, target, remaining, now);
        if (SectTimings.ON) {
            SectTimings.stairWatch(npc, file, plan != null, p -> planPasses(file, p));
        }
        if (plan == null || leg >= plan.legs().size()) {
            release();
            return walkDirect(target, speed);
        }
        SectRouter.Leg l = plan.legs().get(leg);
        if (l.edgeStart && heldEdge != l.edge) {
            if (!enter(file, l, now)) {
                npc.getNavigation().stop();
                probeWait++;
                return false;
            }
        } else if (!held.isEmpty() && now % 20L == 0L) {
            SectFlow.of(npc.level()).enter(held, npc.getStringUUID(), edgeStart(l).dir, now);
        }
        Vec3 at = new Vec3(l.x, l.y, l.z);
        if (npc.level().isLoaded(BlockPos.containing(at))) {
            at = SectLife.stand(npc.level(), at);
        }
        if (l.type == SectRouter.Type.OPEN) {
            if (gateOpen(file, l, now)) {
                finish(l);
            }
            probeWait++;
            return false;
        }
        double flat = Math.hypot(at.x - npc.getX(), at.z - npc.getZ());
        if (flat < 1.3D && Math.abs(at.y - npc.getY()) < 1.6D) {
            finish(l);
            return false;
        }
        // The pathfinder accepts an end node one block short of the target (accuracy 1) and the body stops at that node
        // centre, up to 1.5 blocks from the waypoint: the way is walked, take the next leg instead of waiting for a
        // pixel it cannot reach (rugs, doorways, slabs; 17.10 day simulation).
        var done = npc.getNavigation().getPath();
        if (flat < 2.0D && Math.abs(at.y - npc.getY()) < 1.6D && done != null && done.canReach() && npc.getNavigation().isDone()
                && sameTarget(done.getTarget(), at)) {
            finish(l);
            return false;
        }
        walkDirect(at, speed);
        return false;
    }

    /**
     * A walker brushing a bed, a tea table or a chest (0.5 to 0.75 high) steps up onto it (the body climbs 0.6, furniture is 0.5 to 0.56), and from the top
     * the pathfinder finds no way off: he stood there until the stuck rescue moved him (fresh sect, day simulation 20.10: 6 of 6 rescues).
     * Seen on the top of such a piece, he is nudged to the nearest free cell beside it. True when he was nudged.
     */
    boolean stepOffFurniture() {
        if (!npc.onGround() || npc.sitting() || npc.isSleeping()) {
            return false;       // a seat or a bed is where he is meant to be
        }
        Vec3 meant = planGoal != null ? planGoal : destination;
        var level = npc.level();
        BlockPos p = npc.blockPosition();
        boolean on = false;
        // The cells under the whole body, not only the one his middle is in: standing on the edge of a chest in the next cell his middle is
        // still over the aisle (smoke pass 22.10: a sleeper stood on a 0.75 high chest edge 480 ticks, 1.8 blocks from his bed).
        java.util.List<BlockPos> under = new java.util.ArrayList<>();
        var body = npc.getBoundingBox();
        for (int x = net.minecraft.util.Mth.floor(body.minX); x <= net.minecraft.util.Mth.floor(body.maxX); x++) {
            for (int z = net.minecraft.util.Mth.floor(body.minZ); z <= net.minecraft.util.Mth.floor(body.maxZ); z++) {
                under.add(new BlockPos(x, p.getY(), z));
                under.add(new BlockPos(x, p.getY() - 1, z));
            }
        }
        // What is under his middle. A stair or a slab is a floor, but a body that rests on the edge of one with only a finger of it,
        // its middle over lower ground, is perched like on a chest: under a low ceiling (a stairwell, a loft) he cannot walk on at that
        // height and cannot get down (smoke pass 23.10: the gatekeeper stood 480 ticks on the edge of the first stair of a loft, head in the planks).
        double centreFloor = Double.NEGATIVE_INFINITY;
        for (BlockPos q : new BlockPos[] {p, p.below()}) {
            var shape = level.getBlockState(q).getCollisionShape(level, q);
            if (!shape.isEmpty()) {
                double t = q.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                if (t <= npc.getY() + 0.01D) {
                    centreFloor = Math.max(centreFloor, t);
                }
            }
        }
        // only when the body is also jammed (a dais of slabs or stairs is a place to stand: the mentor dwells on its edge)
        boolean perched = npc.getY() - centreFloor > 0.3D && npc.horizontalCollision;
        // The place of the task is this very piece (a counter, a rack): no walk away from it. Only when the spot is the piece he is on or
        // beside it within a pace; a spot a diagonal cell away (the cell beside the bed, with the chest between: smoke pass 23.10, a sleeper
        // stood on the chest 1.4 blocks from his bed for 480 ticks under the beams) is a floor cell and he has to get down to reach it.
        if (meant != null) {
            BlockPos spot = BlockPos.containing(meant);
            boolean overSpot = false;
            for (BlockPos q : under) {
                overSpot |= q.getX() == spot.getX() && q.getZ() == spot.getZ();
            }
            // a spot at his own height within a pace and a half is a place on the piece (a dais, a counter); a spot well below him is a floor
            if (overSpot || Math.hypot(meant.x - npc.getX(), meant.z - npc.getZ()) < 1.5D && meant.y > npc.getY() - 0.3D) {
                return false;
            }
        }
        for (BlockPos q : under) {
            double top = q.getY() + level.getBlockState(q).getCollisionShape(level, q).max(net.minecraft.core.Direction.Axis.Y);
            var state = level.getBlockState(q);
            // Anything part-height he stands on top of that is no floor: a bed, a table, a chest, a straw pallet (the rug-named ones are
            // 0.56 high, which SectNavigation does not count as furniture). Slabs and stairs are floors.
            double rise = top - q.getY();
            boolean floorLike = state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                    || state.getBlock() instanceof net.minecraft.world.level.block.StairBlock || state.isCollisionShapeFullBlock(level, q);
            boolean partFloor = state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                    || state.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
            if ((!floorLike || perched && partFloor) && rise > 0.3D && rise <= 1.0D && Math.abs(npc.getY() - top) < 0.15D) {
                on = true;
            }
        }
        if (!on) {
            return false;
        }
        Vec3 aim = destination != null ? destination : planGoal;
        // The nearest floor cell that is free of furniture and where the body fits, within two blocks and in sight, nearest to where
        // he is going among the nearest.
        java.util.List<BlockPos> cands = new java.util.ArrayList<>();
        var clear = clear(level);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy >= -1; dy--) {
                    BlockPos n = p.offset(dx, dy, dz);
                    var below = level.getBlockState(n.below());
                    if ((dx != 0 || dz != 0) && SectLife.standable(level, n)
                            && !io.github.verycooltimo.murim.entity.SectNavigation.lowFurniture(level, n.below(), below)
                            && !io.github.verycooltimo.murim.entity.SectNavigation.tallObstacle(level, n.below(), below)
                            && !io.github.verycooltimo.murim.entity.SectNavigation.lowFurniture(level, n, level.getBlockState(n))
                            && !io.github.verycooltimo.murim.entity.SectNavigation.tallObstacle(level, n, level.getBlockState(n))
                            && clear.test(p, n)) {
                        cands.add(n);
                    }
                }
            }
        }
        cands.sort(java.util.Comparator.<BlockPos>comparingInt(n -> n.distManhattan(p) * 4)
                .thenComparingDouble(n -> aim == null ? 0.0D : n.distToCenterSqr(aim)));
        Vec3 to = null;
        for (BlockPos n : cands) {
            // lifted a hair: a rug under the feet (collision up to 0.1) is not a wall
            Vec3 v = SectLife.stand(level, Vec3.atBottomCenterOf(n)).add(0.0D, 0.1D, 0.0D);
            // The body plus a finger of clearance on every side: a pillar's collision is as thin as its model (5 to 10 px), so a box that
            // only just clears it stands with the robe in the wood.
            if (level.noCollision(npc, npc.getBoundingBox().move(v.subtract(npc.position())).inflate(0.08D, 0.0D, 0.08D))) {
                to = v;
                break;
            }
        }
        if (to == null) {
            return false;
        }
        // A push does not do it: the move control walks him back up the same edge every tick (seen: 3000 pushes in a day). He is put on the
        // floor beside the piece, two blocks away at most and only where the body fits: a correction of where he already is, not a jump to
        // another place (the stuck rescue, which does that, stays the last step).
        npc.getNavigation().stop();
        npc.moveTo(to.x, to.y, to.z, npc.getYRot(), 0.0F);
        nextPath = 0;
        if (SectTimings.ON) {
            MurimMod.LOGGER.info("Sect-furniture: {} stepped off furniture at {}", npc.memberKey(), p.toShortString());
        }
        return true;
    }

    private void newGoal(SectLayoutFile file, Vec3 target, long now) {
        release();
        planGoal = target;
        plan = null;
        leg = 0;
        bannedEdges.clear();
        bestRemaining = Double.MAX_VALUE;
        bestAt = now;
        stage = 0;
        gateSince = -1L;
        gateDoor = null;
        routeCost = -1.0D;
        double flat = Math.hypot(target.x - npc.getX(), target.z - npc.getZ());
        if (flat < 8.0D && Math.abs(target.y - npc.getY()) < 2.0D) {
            return;
        }
        BlockPos from = npc.blockPosition();
        BlockPos to = BlockPos.containing(target);
        var here = file.roomAt(from);
        if (here != null && here == file.roomAt(to)) {
            return;
        }
        plan = SectRouter.plan(file, from, to, bannedEdges, clear(npc.level()));
        routeCost = plan == null ? -1.0D : plan.cost();
    }

    /**
     * Day simulation probe: the planned route (edge polylines of the current plan) passes within 3.5 blocks of {@code p}, at a
     * height within 3 levels. Null plan: false.
     */
    private boolean planPasses(SectLayoutFile file, BlockPos p) {
        if (plan == null) {
            return false;
        }
        for (SectLayoutFile.Edge e : plan.edges()) {
            java.util.List<BlockPos> pts = new java.util.ArrayList<>();
            pts.add(file.waypoint(e.a()).pos());
            pts.addAll(e.via());
            pts.add(file.waypoint(e.b()).pos());
            for (int i = 0; i + 1 < pts.size(); i++) {
                BlockPos u = pts.get(i);
                BlockPos v = pts.get(i + 1);
                double dx = v.getX() - u.getX();
                double dz = v.getZ() - u.getZ();
                double len2 = dx * dx + dz * dz;
                double t = len2 < 1.0E-6D ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((p.getX() - u.getX()) * dx + (p.getZ() - u.getZ()) * dz) / len2));
                double d = Math.hypot(u.getX() + t * dx - p.getX(), u.getZ() + t * dz - p.getZ());
                double y = u.getY() + t * (v.getY() - u.getY());
                if (d < 3.5D && Math.abs(y - p.getY()) <= 3.0D) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Cells of the planned route that are still to walk, -1 when he walks plain (no plan). */
    public double remainingCells() {
        if (plan == null || planGoal == null || leg >= plan.legs().size()) {
            return -1.0D;
        }
        return plan.remaining(npc.getX(), npc.getZ(), leg, planGoal.x, planGoal.z);
    }

    /** Cost in cells of the route planned for the goal he walks to now; -1: none (plain walking). */
    public double routeCost() {
        return routeCost;
    }

    private void replan(SectLayoutFile file) {
        probeRepaths++;
        release();
        leg = 0;
        if (planGoal != null) {
            plan = SectRouter.plan(file, npc.blockPosition(), BlockPos.containing(planGoal), bannedEdges, clear(npc.level()));
        }
    }

    private void finish(SectRouter.Leg l) {
        if (l.edgeEnd) {
            release();
        }
        leg++;
        openedAt = -1L;
        if (l.type == SectRouter.Type.OPEN) {
            gateSince = -1L;
            gateDoor = null;
        }
    }

    /** The first leg of the edge the walker is on (it carries the climbing direction), or the current one. */
    private SectRouter.Leg edgeStart(SectRouter.Leg l) {
        for (int i = Math.min(leg, plan.legs().size() - 1); i >= 0; i--) {
            SectRouter.Leg c = plan.legs().get(i);
            if (c.edge != l.edge) {
                break;
            }
            if (c.edgeStart) {
                return c;
            }
        }
        return l;
    }

    private boolean enter(SectLayoutFile file, SectRouter.Leg l, long now) {
        List<SectFlow.Need> needs = SectRouter.needs(file, plan.edges().get(l.edge));
        if (needs.isEmpty() || SectFlow.of(npc.level()).enter(needs, npc.getStringUUID(), l.dir, now)) {
            held = needs;
            heldEdge = l.edge;
            waited = 0;
            return true;
        }
        if (++waited > 600) {
            MurimMod.LOGGER.warn("Sect: {} waited 600 ticks at a narrow passage, goes on", npc.memberKey());
            held = List.of();
            heldEdge = l.edge;
            waited = 0;
            return true;
        }
        return false;
    }

    private void release() {
        if (!held.isEmpty() && npc.level() != null) {
            SectFlow.of(npc.level()).leave(held, npc.getStringUUID());
        }
        held = List.of();
        heldEdge = -2;
        waited = 0;
    }

    /** Open the big gate of an OPEN leg; true when it stands open (and has finished swinging). */
    private boolean gateOpen(SectLayoutFile file, SectRouter.Leg l, long now) {
        SectLayoutFile.Door d = file.door(l.door);
        if (d == null || !npc.level().isLoaded(d.min())) {
            return true;
        }
        BlockPos cell = d.min();
        var s = npc.level().getBlockState(cell);
        // A push or interrupted route may already have put him beyond the door: do not reopen it behind him.
        if (plan != null && leg + 1 < plan.legs().size()) {
            var far = plan.legs().get(leg + 1);
            Vec3 target = new Vec3(far.x, far.y, far.z);
            boolean crossing = false;
            for (BlockPos part : BlockPos.betweenClosed(d.min(), d.max())) {
                if (crossesDoor(npc.position(), target, part, s)) { crossing = true; break; }
            }
            if (!crossing) return true;
        }
        BlockPos base;
        java.util.function.BooleanSupplier swing;
        if (s.getBlock() instanceof GateDoorBlock gate) {
            BlockPos origin = GateDoorBlock.origin(cell, s);
            base = origin;
            swing = () -> gate.toggle(npc.level(), origin, npc);
        } else if (s.getBlock() instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlock grand) {
            BlockPos origin = io.github.verycooltimo.murim.decor.HallGrandDoorBlock.origin(cell, s);
            base = origin;
            swing = () -> grand.toggle(npc.level(), origin, npc);
        } else {
            return true;
        }
        if (gateSince < 0L || !l.door.equals(gateDoor)) {
            gateSince = now;
            gateDoor = l.door;
        }
        if (s.getValue(GateDoorBlock.OPEN)) {
            SectDoors.mark(npc, base, now);
            if (openedAt < 0) {
                openedAt = now;
            }
            return now - openedAt >= 16L;
        }
        if (now >= openTry) {
            openTry = now + 10L;
            if (swing.getAsBoolean()) {
                SectDoors.mark(npc, base, now);
            }
        }
        return false;
    }

    /**
     * Stuck recovery without teleporting first: no progress along the remaining route for 100 ticks re-plans from where
     * he stands; 240 steps back to the previous waypoint, bans the edge and the spot; 480 relocates him to the nearest
     * waypoint, but only when no player can see him (config {@code relocateWhenUnseen}); otherwise a warning is logged.
     */
    private void monitor(SectLayoutFile file, Vec3 target, double remaining, long now) {
        if (now % 5L != 0L) {
            return;
        }
        if (waited > 0 || (plan != null && leg < plan.legs().size() && plan.legs().get(leg).type == SectRouter.Type.OPEN
                && gateSince >= 0L && now - gateSince < GATE_PATIENCE)) {
            bestAt = now;
            return;
        }
        if (remaining < bestRemaining - 1.0D) {
            bestRemaining = remaining;
            bestAt = now;
            stage = 0;
            return;
        }
        if (remaining < 1.0D) {
            bestAt = now;
            return;
        }
        long idle = now - bestAt;
        if (stage < 1 && idle > STUCK_REPATH) {
            stage = 1;
            replan(file);
            nextPath = 0;
        } else if (stage < 2 && idle > STUCK_STEP_BACK) {
            stage = 2;
            if (plan != null && leg < plan.legs().size()) {
                int edge = plan.legs().get(leg).edge;
                if (edge >= 0) {
                    // every way between the same two waypoints: a parallel copy of the dead edge is the same dead end
                    SectLayoutFile.Edge dead = plan.edges().get(edge);
                    for (SectLayoutFile.Edge e : file.edges()) {
                        if (e.samePair(dead)) {
                            bannedEdges.add(e.index());
                        }
                    }
                }
            }
            replan(file);
            spotBan = true;
            nextPath = 0;
        } else if (stage < 3 && idle > STUCK_RELOCATE) {
            stage = 3;
            if (positionRecovery && Config.SECT_RELOCATE_WHEN_UNSEEN.get() && unseen()) {
                relocate(file);
            } else {
                MurimMod.LOGGER.warn("Sect: {} is stuck at {} on the way to {}", npc.memberKey(), npc.blockPosition().toShortString(),
                        BlockPos.containing(target).toShortString());
            }
        }
    }

    /** No player is near enough or has a line of sight to the person. */
    public boolean unseen() {
        for (Player p : npc.level().players()) {
            if (p.isSpectator()) {
                continue;
            }
            double d = p.distanceTo(npc);
            if (d < 12.0D || d < 96.0D && p.hasLineOfSight(npc)) {
                return false;
            }
        }
        return true;
    }

    /** Last stuck step: to the nearest waypoint of the layout (the author allowed it 17.10, never in view). */
    void relocate(SectLayoutFile file) {
        // A goal a few steps away that he can see and that holds his body (the cell beside his bed, his seat's cell) is where he is put:
        // the nearest waypoint is a doorway or an aisle end that sends him through the same crowd again (smoke pass: 24 relocations of one
        // guard in a run, and sleepers put back at the door of the room they could not cross).
        if (planGoal != null) {
            Vec3 goal = SectLife.stand(npc.level(), planGoal);
            BlockPos cell = feetCell(goal);
            if (Math.hypot(goal.x - npc.getX(), goal.z - npc.getZ()) <= 6.0D && Math.abs(goal.y - npc.getY()) <= 2.5D
                    && npc.level().isLoaded(cell) && SectLife.standable(npc.level(), cell)
                    && !SectNavigation.tallObstacle(npc.level(), cell, npc.level().getBlockState(cell))
                    && clear(npc.level()).test(npc.blockPosition(), cell)
                    && npc.level().noCollision(npc, npc.getBoundingBox().move(goal.subtract(npc.position())))) {
                diagnose(file);
                MurimMod.LOGGER.warn("Sect: {} stuck for {} ticks at {}, moved unseen to his goal {}", npc.memberKey(), STUCK_RELOCATE,
                        npc.blockPosition().toShortString(), cell.toShortString());
                npc.getNavigation().stop();
                npc.moveTo(goal.x, goal.y, goal.z, npc.getYRot(), 0.0F);
                bestRemaining = Double.MAX_VALUE;
                bestAt = npc.level().getGameTime();
                stage = 0;
                nextPath = 0;
                return;
            }
        }
        var near = file.linksNear(npc.blockPosition(), 40.0D, 6, 1);
        if (near.isEmpty()) {
            // Fallen down the slope, or carried off the built ground: the nearest waypoint at any height (17.10: twenty
            // people stood on the mountainside for good because only waypoints within 6 levels counted).
            near = file.linksNear(npc.blockPosition(), 400.0D, 400, 1);
        }
        if (near.isEmpty()) {
            MurimMod.LOGGER.warn("Sect: {} is stuck at {} and no waypoint is near", npc.memberKey(), npc.blockPosition().toShortString());
            return;
        }
        BlockPos w = file.waypoint(near.get(0).waypoint()).pos();
        diagnose(file);
        Vec3 to = SectLife.stand(npc.level(), new Vec3(w.getX() + 0.5D, w.getY(), w.getZ() + 0.5D));
        MurimMod.LOGGER.warn("Sect: {} stuck for {} ticks at {}, moved unseen to waypoint {}", npc.memberKey(), STUCK_RELOCATE,
                npc.blockPosition().toShortString(), w.toShortString());
        npc.getNavigation().stop();
        npc.moveTo(to.x, to.y, to.z, npc.getYRot(), 0.0F);
        bestRemaining = Double.MAX_VALUE;
        bestAt = npc.level().getGameTime();
        stage = 0;
        replan(file);
        nextPath = 0;
    }

    /**
     * The block a body at {@code p} stands in: for a standing height on top of a slab (y = 225.5) the cell above it,
     * which is also the cell the pathfinder names (floor(225.5) would name the slab itself).
     */
    static BlockPos feetCell(Vec3 p) {
        return BlockPos.containing(p.x, Math.ceil(p.y - 0.01D), p.z);
    }

    /** The path was made for this point: same column, height within one block (slabs stand half a block up). */
    static boolean sameTarget(BlockPos pathTarget, Vec3 target) {
        BlockPos c = feetCell(target);
        return pathTarget.getX() == c.getX() && pathTarget.getZ() == c.getZ() && Math.abs(pathTarget.getY() - c.getY()) <= 1;
    }

    /**
     * The volume a short last step sweeps: a narrower body than the real one (a person hugging a table edge still
     * slides along it, collision handles the rest) lifted a hair so the top of a rug under the feet does not count.
     */
    private net.minecraft.world.phys.AABB sweep(Vec3 by) {
        // A seat is a few pixels above the floor (a cushion 0.16, a mat 0.06): the body steps up onto it, so the sweep is lifted by the
        // rise of the step too, or the last half block to a cushion never counts as free and the sitter stays standing beside it
        // (council hall, 19.10).
        // Only the feet are lifted by 0.1 (a rug is 1.2 px = 0.075 high, a carpet 0.06: 0.02 let the last step onto a rug count as walking
        // into a wall); the top of the head is not (a hanging lantern 0.1 over a person of 1.9 would be one). The whole box rises with the step.
        double rise = Math.max(0.0D, by.y);
        net.minecraft.world.phys.AABB body = npc.getBoundingBox().deflate(0.2D, 0.0D, 0.2D);
        return new net.minecraft.world.phys.AABB(body.minX, body.minY + SWEEP_LIFT + rise, body.minZ, body.maxX, body.maxY + rise, body.maxZ)
                .expandTowards(by.x, 0.0D, by.z);
    }

    /** One-line report of why a walker made no progress (stuck diagnostics). */
    private void diagnose(SectLayoutFile file) {
        try {
            MurimMod.LOGGER.warn(describe(file));
        } catch (RuntimeException ex) {
            MurimMod.LOGGER.warn("Sect-stuck diagnostic failed", ex);
        }
    }

    /** The blocks whose collision meets the box, for the stuck report. */
    private String blockers(net.minecraft.world.phys.AABB box) {
        StringBuilder sb = new StringBuilder("[");
        var level = npc.level();
        for (BlockPos q : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var shape = level.getBlockState(q).getCollisionShape(level, q);
            if (!shape.isEmpty() && shape.move(q.getX(), q.getY(), q.getZ()).toAabbs().stream().anyMatch(box::intersects)) {
                sb.append(q.toShortString()).append(' ').append(level.getBlockState(q).getBlock().builtInRegistryHolder().key().location().getPath()).append("; ");
            }
        }
        return sb.append(']').toString();
    }

    /** The stuck report of {@link #diagnose} as text (tests print it when a walk does not arrive). */
    String describe() {
        return describe(file());
    }

    private String describe(SectLayoutFile file) {
        {
            StringBuilder sb = new StringBuilder("Sect-stuck: ").append(npc.memberKey()).append(" at ").append(npc.blockPosition().toShortString())
                    .append(" doing=").append(npc.doingKind()).append('/').append(npc.doingSpot())
                    .append(" goal=").append(planGoal == null ? "-" : BlockPos.containing(planGoal).toShortString());
            if (plan == null) {
                sb.append(" plan=none");
            } else {
                sb.append(" leg=").append(leg).append('/').append(plan.legs().size());
                if (leg < plan.legs().size()) {
                    SectRouter.Leg l = plan.legs().get(leg);
                    sb.append(" legAt=").append((int) Math.floor(l.x)).append(',').append(l.y).append(',').append((int) Math.floor(l.z))
                            .append(" type=").append(l.type).append(" edge=").append(l.edge >= 0 ? plan.edges().get(l.edge).a() + ">" + plan.edges().get(l.edge).b() : "-");
                }
            }
            Vec3 aim = planGoal;
            if (plan != null && leg < plan.legs().size()) {
                SectRouter.Leg ll = plan.legs().get(leg);
                aim = SectLife.stand(npc.level(), new Vec3(ll.x, ll.y, ll.z));
            }
            if (aim != null) {
                Vec3 rem = aim.subtract(npc.position());
                var pth = npc.getNavigation().getPath();
                sb.append(String.format(" aim=%.2f,%.2f,%.2f rem=%.2f,%.2f", aim.x, aim.y, aim.z, rem.horizontalDistance(), rem.y));
                sb.append(" lastfrac[reach=").append(pth != null && pth.canReach()).append(" done=").append(npc.getNavigation().isDone())
                        .append(" ptarget=").append(pth == null ? "-" : pth.getTarget().toShortString())
                        .append(" same=").append(pth != null && sameTarget(pth.getTarget(), aim))
                        .append(" stand=").append(SectLife.standable(npc.level(), feetCell(aim)))
                        .append(" free=").append(npc.level().noCollision(npc, sweep(rem)));
                if (!npc.level().noCollision(npc, sweep(rem))) {
                    sb.append(" blockers=").append(blockers(sweep(rem)));
                }
                sb.append(']');
            }
            sb.append(" held=").append(held.size()).append(" waited=").append(waited).append(" banned=").append(bannedEdges.size());
            var path = npc.getNavigation().getPath();
            sb.append(" nav=").append(path == null ? "nopath" : (path.canReach() ? "reach" : "partial") + (npc.getNavigation().isDone() ? "-done" : "-run")
                    + "@" + path.getNextNodeIndex() + "/" + path.getNodeCount() + " end=" + path.getEndNode());
            if (path != null) {
                sb.append(" nodes=");
                for (int i = path.getNextNodeIndex(); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + 4); i++) {
                    var n = path.getNode(i);
                    BlockPos np = n.asBlockPos();
                    var ns = npc.level().getBlockState(np);
                    sb.append('[').append(n.x).append(',').append(n.y).append(',').append(n.z).append(' ').append(n.type).append(' ')
                            .append(ns.getBlock().builtInRegistryHolder().key().location().getPath()).append(']');
                }
            }
            sb.append(" hcol=").append(npc.horizontalCollision).append(" ground=").append(npc.onGround()).append(" sit=").append(npc.sitting())
                    .append(" sleep=").append(npc.isSleeping()).append(" dm=").append(String.format("%.2f", npc.getDeltaMovement().horizontalDistance()))
                    .append(" pos=").append(String.format("%.2f,%.2f,%.2f", npc.getX(), npc.getY(), npc.getZ()));
            sb.append(" sidestep=").append(sidestep != null).append(" climb=").append(npc.onClimbable());
            BlockPos b = npc.blockPosition();
            var lv = npc.level();
            sb.append(" feet=").append(lv.getBlockState(b).getBlock().builtInRegistryHolder().key().location().getPath())
                    .append(" below=").append(lv.getBlockState(b.below()).getBlock().builtInRegistryHolder().key().location().getPath())
                    .append(" head=").append(lv.getBlockState(b.above()).getBlock().builtInRegistryHolder().key().location().getPath());
            int crowd = lv.getEntitiesOfClass(LivingEntity.class, npc.getBoundingBox().inflate(1.6D), e -> e != npc && e.isAlive()).size();
            sb.append(" crowd=").append(crowd);
            return sb.toString();
        }
    }

    private int sidestepTries;

    private Vec3 pickSidestep(Vec3 target) {
        Vec3 dir = target.subtract(npc.position()).multiply(1, 0, 1);
        if (dir.lengthSqr() < 1.0E-4D) {
            return null;
        }
        dir = dir.normalize();
        var level = npc.level();
        // First the old step (two blocks aside, the side alternating): it is what the sect's timings were tuned with. Only when that cell is
        // missing or behind a wall, the right-hand side and one block (a two-wide aisle has no cell two blocks aside).
        long now = level.getGameTime();
        double sign = (Math.floorDiv(now, 80) + npc.getId()) % 2 == 0 ? 1 : -1;
        Vec3 old = npc.position().add(-dir.z * sign * 2, 0, dir.x * sign * 2);
        BlockPos oldAt = BlockPos.containing(old);
        if (recoveryArea.test(old) && level.isLoaded(oldAt) && SectLife.standable(level, oldAt) && clear(level).test(npc.blockPosition(), oldAt)) {
            return old;
        }
        double[] sides = sidestepTries++ % 2 == 0 ? new double[] {1.0D, -1.0D} : new double[] {-1.0D, 1.0D};
        for (double side : sides) {
            for (double len : new double[] {1.0D, 2.0D}) {
                Vec3 guess = npc.position().add(-dir.z * side * len, 0, dir.x * side * len);
                BlockPos at = BlockPos.containing(guess);
                if (recoveryArea.test(guess) && level.isLoaded(at) && SectLife.standable(level, at) && clear(level).test(npc.blockPosition(), at)
                        && level.noCollision(npc, npc.getBoundingBox().move(guess.x - npc.getX(), 0.1D, guess.z - npc.getZ()))) {
                    return guess;
                }
            }
        }
        return null;
    }

    /** Late arrivals stay late. Every retry is rate limited even when there is no path. */
    private boolean walkDirect(Vec3 target, double speed) {
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
        // A reachable end node can stop nearly two blocks short (gate night relief regression).
        // Keep ordinary collision-checked travel until ARRIVAL_DISTANCE_SQR, rather than accepting that gap.
        var path = npc.getNavigation().getPath();
        Vec3 remaining = target.subtract(npc.position());
        if (path != null && path.canReach() && npc.getNavigation().isDone()
                && sameTarget(path.getTarget(), target)
                && remaining.multiply(1, 0, 1).lengthSqr() < 4.0D && Math.abs(remaining.y) < 0.5D
                && SectLife.standable(npc.level(), feetCell(target))
                && npc.level().noCollision(npc, sweep(remaining))) {
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
        // Standing still for two seconds a block and a third from a seat or a desk he cannot walk onto (a mat behind a low desk, a
        // cushion in a row): the last step is a slide into place. Only where he sees the spot, so never through a wall.
        if (positionRecovery && progress != null && now - progressAt >= 50 && ladder == null) {
            Vec3 rest = target.subtract(npc.position());
            if (rest.multiply(1, 0, 1).lengthSqr() <= 1.82D && Math.abs(rest.y) < 0.6D
                    && npc.level().noCollision(npc, npc.getBoundingBox().move(rest))
                    && clear(npc.level()).test(npc.blockPosition(), BlockPos.containing(target))) {
                npc.getNavigation().stop();
                npc.moveTo(target.x, target.y, target.z, npc.getYRot(), 0.0F);
                progress = npc.position();
                progressAt = now;
                return true;
            }
        }
        if (progress == null || npc.position().distanceToSqr(progress) > 0.25D) {
            progress = npc.position();
            progressAt = now;
            sidestepTries = 0;
        } else if (now - progressAt >= 80 && now >= sidestepUntil) {
            // A collision-safe side step to a cell he sees (see pickSidestep).
            sidestep = pickSidestep(target);
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
