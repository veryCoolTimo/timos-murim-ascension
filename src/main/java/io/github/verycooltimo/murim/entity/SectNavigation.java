package io.github.verycooltimo.murim.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ground navigation for the built sect (docs/design/38, stuck analysis 17.10). Vanilla treats every block that is not a
 * full cube as walkable ({@code isPathfindable(LAND)}), so cabinets, racks, chests, thin walls and screens were
 * path nodes: the route led into a cabinet and the person stood in front of it until the stuck recovery relocated him.
 * Here a cell holding tall furniture is blocked, and nobody plans to stand on top of it.
 */
public class SectNavigation extends GroundPathNavigation {

    /** Higher than a step the body walks up by itself (0.6): whatever it is, it is in the way. */
    public static final double TALL = 0.62D;
    /** Furniture lower than TALL (bed 0.56, tea table 0.5, stool, bench) is climbed onto by the body: keep off it, bar the seat at the goal. */
    public static final double LOW = 0.3D;

    public SectNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new Evaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    /**
     * A cell whose collision is a tall part-block: furniture, thin walls, screens. Full cubes, stairs, slabs, low
     * things (rugs, benches, cushions) and cells without collision are not.
     */
    public static boolean tallObstacle(BlockGetter level, BlockPos pos, BlockState state) {
        return obstacle(level, pos, state, false);
    }

    /** Murim furniture (not rugs, mats, cushions) lower than a step: beds, tea tables, stools, benches. */
    public static boolean lowFurniture(BlockGetter level, BlockPos pos, BlockState state) {
        return obstacle(level, pos, state, true);
    }

    private static boolean furniture(BlockState state) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!"murim".equals(key.getNamespace())) {
            return false;
        }
        String id = key.getPath();
        if (id.contains("rug") || id.contains("mat") || id.contains("cushion") || id.contains("carpet")) {
            return false;
        }
        return id.startsWith("int_") || id.startsWith("sto_") || id.startsWith("shr_") || id.contains("table") || id.contains("bench")
                || id.contains("stool") || id.contains("shelf") || id.contains("rack") || id.contains("chest") || id.contains("vat");
    }

    private static boolean obstacle(BlockGetter level, BlockPos pos, BlockState state, boolean low) {
        if (state.isAir() || state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock
                || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS) || state.is(BlockTags.DOORS)
                || state.is(BlockTags.TRAPDOORS)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        double top = shape.max(Direction.Axis.Y);
        if (low) {
            return top > LOW && top <= TALL && furniture(state);
        }
        return top > TALL && !state.isCollisionShapeFullBlock(level, pos);
    }

    private static final class Evaluator extends WalkNodeEvaluator {
        /**
         * A thin pillar (5 px) lets the body touch its side while the middle of the body is still in the pillar's cell, so the cell he
         * "stands in" is the pillar, and vanilla starts the path there: the first node is the middle of the pillar, the body cannot get
         * within the waypoint distance of it (0.45) because the pillar is in the way, and he stands pressed against it for good (smoke
         * pass 22.10: the dawn formation row, "nopath" with feet and head in a red pillar; gametest SectSmokeStuckGameTests). Start from the
         * free cell the body also covers instead.
         */
        private boolean startedInObstacle;

        @Override
        public net.minecraft.world.level.pathfinder.Node getStart() {
            startedInObstacle = false;
            net.minecraft.world.level.pathfinder.Node start = super.getStart();
            if (start == null || this.mob == null || this.currentContext == null) {
                return start;
            }
            var level = this.currentContext.level();
            BlockPos at = new BlockPos(start.x, start.y, start.z);
            if (!tallObstacle(level, at, level.getBlockState(at))) {
                return start;
            }
            var box = this.mob.getBoundingBox();
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (double x : new double[] {box.minX, box.maxX}) {
                for (double z : new double[] {box.minZ, box.maxZ}) {
                    BlockPos c = new BlockPos(net.minecraft.util.Mth.floor(x), start.y, net.minecraft.util.Mth.floor(z));
                    if (c.equals(at) || tallObstacle(level, c, level.getBlockState(c)) || !canStartAt(c)) {
                        continue;
                    }
                    double d = c.distToCenterSqr(this.mob.getX(), start.y + 0.5D, this.mob.getZ());
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
            }
            if (best == null) {
                startedInObstacle = true;       // no free cell under the body: he starts where he is, as before
                return start;
            }
            return getStartNode(best);
        }

        @Override
        public PathType getPathType(PathfindingContext context, int x, int y, int z) {
            PathType type = super.getPathType(context, x, y, z);
            if (type == PathType.OPEN || type == PathType.WALKABLE) {
                BlockPos here = this.mob == null ? null : this.mob.blockPosition();
                int dx = here == null ? 9 : Math.abs(here.getX() - x);
                int dz = here == null ? 9 : Math.abs(here.getZ() - z);
                int dy = here == null ? 9 : Math.abs(here.getY() - y);
                BlockPos pos = new BlockPos(x, y, z);
                var state = context.level().getBlockState(pos);
                // The cell he stands in is open for him (furniture he stepped onto), but never a tall obstacle he only touches: the path
                // would lead through the pillar he is pressed against (getStart moves the start out of it).
                if (dx == 0 && dz == 0 && dy <= 2 && (startedInObstacle || !tallObstacle(context.level(), pos, state))) {
                    return type;
                }
                if (tallObstacle(context.level(), pos, state)) {
                    return PathType.BLOCKED;
                }
                if (lowFurniture(context.level(), pos, state)) {
                    // A bench or a bed is the place when the goal is ON it (a seat to sit on) and a step off it when he stands on
                    // one; everywhere else it is in the way. A goal on the floor (the cell beside a bed) does not make the table, the
                    // chest and the bed beside it a way: the walkers climbed them in the one-lane aisles of the dormitories, crowded at
                    // night, and stood on them (smoke pass 23.10: 5 sleepers on tea tables and chests, 480 ticks each).
                    var goal = this.mob instanceof io.github.verycooltimo.murim.entity.SectDisciple d ? d.walking().goal() : null;
                    boolean atGoal = goal != null && Math.abs(goal.x - (x + 0.5D)) <= 1.6D && Math.abs(goal.z - (z + 0.5D)) <= 1.6D
                            && goal.y - y >= 0.25D;
                    boolean onTop = here != null && this.mob.getY() - here.getY() > 0.3D;
                    boolean besideHim = onTop && dx <= 1 && dz <= 1 && dy <= 2;
                    if (!atGoal && !besideHim) {
                        return PathType.BLOCKED;
                    }
                }
            }
            return type;
        }
    }
}
