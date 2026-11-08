package io.github.verycooltimo.murim.decor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every block of the mod that keeps a direction in its state turns that state when a structure is turned or mirrored
 * (docs/adr/0004: the baked sect is stamped at all four site rotations; a block whose {@code rotate()} is missing keeps the
 * state of the rotation-0 sect, and a transom, a door, a rug or a bracket then points the wrong way in three of four new worlds).
 *
 * <p>The expected state is NOT taken from the block's own rotate (that would test the block against itself, as the stamp test of
 * the baked sect once did): it is derived from the property names by a small independent rule set, and the collision and
 * selection shapes are compared geometrically, which does not depend on property conventions at all.
 * <ul>
 *   <li>{@code facing}, {@code slope_up}, {@code slope_down}, {@code offset} (names of directions): the direction is turned;</li>
 *   <li>{@code axis}: x and z swap on a quarter turn; {@code rotation} (0..15): +4 per quarter turn;</li>
 *   <li>{@code north}, {@code east}, {@code south}, {@code west} and the corner flags {@code north_east}...: the value moves
 *       to the side the old side was turned to;</li>
 *   <li>{@code neg_end/pos_end}, {@code negative/positive} (counted along {@code axis} or the axis of {@code facing}) swap when
 *       the positive direction of that axis becomes a negative one, {@code side=plus/minus} likewise along the cross axis;</li>
 *   <li>{@code reach} (a bracket's shift along its arms, neg_/pos_) swaps likewise along the bracket's {@code axis};</li>
 *   <li>everything else (hinge, stair shape, left/right, part, half, ...) is relative and stays.</li>
 * </ul>
 * Run with {@code ./gw test --tests '*BlockRotationTest*'}; the mod is loaded, so the registry holds the mod's blocks.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java#rotate,#mirror;
 * reference/minecraft-src/net/minecraft/world/level/block/Rotation.java; reference/minecraft-src/net/minecraft/world/level/block/Mirror.java.
 */
class BlockRotationTest {

    private static final List<String> DIRS = List.of("north", "east", "south", "west");
    private static final List<String> CORNERS = List.of("north_east", "south_east", "south_west", "north_west");

    /** Blocks that keep a direction in a way the rule set does not model: block id -> why. Keep this list short. */
    private static final Map<String, String> EXCEPTIONS = Map.of();

    /** A turn or a mirror as a map on horizontal direction names. */
    private record Transform(String label, UnaryOperator<String> dir, Rotation rotation, Mirror mirror) {
        String axisAfter(String axis) {
            String d = dir.apply(axis.equals("x") ? "east" : "south");
            return d.equals("east") || d.equals("west") ? "x" : "z";
        }

        /** True if the positive direction of {@code axis} points to a negative direction afterwards. */
        boolean flips(String axis) {
            String d = dir.apply(axis.equals("x") ? "east" : "south");
            return d.equals("north") || d.equals("west");
        }
    }

    private static Transform turn(Rotation rotation) {
        int steps = rotation.ordinal();
        return new Transform("rotation " + rotation, d -> {
            int i = DIRS.indexOf(d);
            return i < 0 ? d : DIRS.get((i + steps) % 4);
        }, rotation, null);
    }

    private static Transform mirror(Mirror mirror) {
        return new Transform("mirror " + mirror, d -> switch (mirror) {
            case LEFT_RIGHT -> d.equals("north") ? "south" : d.equals("south") ? "north" : d;
            case FRONT_BACK -> d.equals("east") ? "west" : d.equals("west") ? "east" : d;
            default -> d;
        }, null, mirror);
    }

    private static List<Transform> rotations() {
        return List.of(turn(Rotation.CLOCKWISE_90), turn(Rotation.CLOCKWISE_180), turn(Rotation.COUNTERCLOCKWISE_90));
    }

    private static List<Transform> mirrors() {
        return List.of(mirror(Mirror.LEFT_RIGHT), mirror(Mirror.FRONT_BACK));
    }

    private static BlockState apply(BlockState state, Transform t) {
        return t.rotation() != null ? state.rotate(t.rotation()) : state.mirror(t.mirror());
    }

    private static List<Block> modBlocks() {
        List<Block> out = new ArrayList<>();
        for (Map.Entry<net.minecraft.resources.ResourceKey<Block>, Block> e : BuiltInRegistries.BLOCK.entrySet()) {
            if (e.getKey().location().getNamespace().equals("murim")) {
                out.add(e.getValue());
            }
        }
        out.sort((a, b) -> id(a).compareTo(id(b)));
        return out;
    }

    private static String id(Block b) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(b);
        return key.toString();
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    private static Map<String, String> props(BlockState state) {
        Map<String, String> m = new TreeMap<>();
        for (Property<?> p : state.getProperties()) {
            m.put(p.getName(), valueName(state, p));
        }
        return m;
    }

    private static boolean isDir(String v) {
        return DIRS.contains(v);
    }

    /** True if the block keeps a direction in its state, by the names the rule set knows. */
    private static boolean directional(Block block) {
        BlockState any = block.defaultBlockState();
        for (Property<?> p : any.getProperties()) {
            String n = p.getName();
            if (DIRS.contains(n) || CORNERS.contains(n) || n.equals("rotation") || n.equals("offset") || n.startsWith("slope_")) {
                return true;
            }
            if ((n.equals("facing") || n.equals("axis")) && hasHorizontalValue(p)) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Comparable<T>> boolean hasHorizontalValue(Property<T> p) {
        for (T v : p.getPossibleValues()) {
            String s = p.getName(v);
            if (isDir(s) || s.equals("x") || s.equals("z")) {
                return true;
            }
        }
        return false;
    }

    /** The expected property map of a state after the transform, by the rule set in the class comment. */
    static Map<String, String> expected(Map<String, String> p, Transform t) {
        Map<String, String> out = new TreeMap<>(p);
        String axis = p.get("axis") != null && (p.get("axis").equals("x") || p.get("axis").equals("z")) ? p.get("axis") : null;
        if (axis == null && p.get("facing") != null && isDir(p.get("facing"))) {
            axis = p.get("facing").equals("east") || p.get("facing").equals("west") ? "x" : "z";
        }
        // the cross-section offset of a pillar is a position in the horizontal plane only while the pillar stands; a lying one counts
        // it in its own plane, which the geometric test checks instead
        boolean standing = !"x".equals(p.get("axis")) && !"z".equals(p.get("axis"));
        for (Map.Entry<String, String> e : p.entrySet()) {
            String n = e.getKey();
            String v = e.getValue();
            if (n.equals("offset") && !standing) {
                continue;
            }
            if ((n.equals("facing") || n.startsWith("slope_") || n.equals("offset")) && isDir(v)) {
                out.put(n, t.dir().apply(v));
            } else if (n.equals("offset") && v.contains("_")) {
                String[] parts = v.split("_");
                String a = t.dir().apply(parts[0]);
                String b = t.dir().apply(parts[1]);
                out.put(n, (a.equals("north") || a.equals("south")) ? a + "_" + b : b + "_" + a);
            } else if (n.equals("axis") && (v.equals("x") || v.equals("z"))) {
                out.put(n, t.axisAfter(v));
            } else if (n.equals("rotation") && v.chars().allMatch(Character::isDigit)) {
                int r = Integer.parseInt(v);
                out.put(n, String.valueOf(t.rotation() != null ? t.rotation().rotate(r, 16) : t.mirror().mirror(r, 16)));
            }
        }
        for (String d : DIRS) {
            if (p.containsKey(d)) {
                out.put(t.dir().apply(d), p.get(d));
            }
        }
        for (int i = 0; i < 4; i++) {
            String c = CORNERS.get(i);
            if (p.containsKey(c)) {
                String[] parts = c.split("_");
                String a = t.dir().apply(parts[0]);
                String b = t.dir().apply(parts[1]);
                String name = (a.equals("north") || a.equals("south")) ? a + "_" + b : b + "_" + a;
                out.put(name, p.get(c));
            }
        }
        if (axis != null && t.flips(axis)) {
            swap(out, p, "neg_end", "pos_end");
            swap(out, p, "negative", "positive");
            String reach = p.get("reach"); // beam_bracket: the shift along the arms, counted from the - end
            if (reach != null && (reach.startsWith("neg_") || reach.startsWith("pos_"))) {
                out.put("reach", reach.startsWith("neg_") ? "pos_" + reach.substring(4) : "neg_" + reach.substring(4));
            }
        }
        String cross = axis == null ? null : (axis.equals("x") ? "z" : "x");
        if (cross != null && p.containsKey("side") && (p.get("side").equals("plus") || p.get("side").equals("minus")) && t.flips(cross)) {
            out.put("side", p.get("side").equals("plus") ? "minus" : "plus");
        }
        return out;
    }

    private static void swap(Map<String, String> out, Map<String, String> p, String lo, String hi) {
        if (p.containsKey(lo) && p.containsKey(hi)) {
            out.put(lo, p.get(hi));
            out.put(hi, p.get(lo));
        }
    }

    @Test
    @DisplayName("every state with a direction is turned and mirrored the way its property names say")
    void rotateAndMirrorFollowTheProperties() {
        List<String> failures = new ArrayList<>();
        int checkedBlocks = 0;
        for (Block block : modBlocks()) {
            if (!directional(block) || EXCEPTIONS.containsKey(id(block))) {
                continue;
            }
            checkedBlocks++;
            Map<String, String> firstBad = new HashMap<>();
            Map<String, Integer> badCount = new TreeMap<>();
            List<Transform> all = rotations(); // the sect is only ever turned; mirrors are checked for closure below
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                Map<String, String> before = props(state);
                for (Transform t : all) {
                    Map<String, String> want = expected(before, t);
                    Map<String, String> got = props(apply(state, t));
                    if (before.containsKey("offset") && before.containsKey("axis") && !before.get("axis").equals("y")) {
                        // a lying timber's offset is a position in its cross-section: the geometric test below judges it
                        want.remove("offset");
                        got.remove("offset");
                    }
                    if (!want.equals(got)) {
                        badCount.merge(t.label(), 1, Integer::sum);
                        firstBad.putIfAbsent(t.label(), before + " -> expected " + want + " but " + got);
                    }
                }
            }
            for (Map.Entry<String, Integer> e : badCount.entrySet()) {
                failures.add(id(block) + ": " + e.getKey() + " wrong on " + e.getValue() + " states, e.g. " + firstBad.get(e.getKey()));
            }
        }
        assertTrue(checkedBlocks > 50, "too few directional blocks found: " + checkedBlocks);
        assertTrue(failures.isEmpty(), failures.size() + " block/transform pairs turn their state wrongly (" + checkedBlocks
                + " directional blocks checked):\n" + String.join("\n", failures));
    }

    @Test
    @DisplayName("four quarter turns and two mirrors give the state back")
    void turnsAndMirrorsClose() {
        List<String> failures = new ArrayList<>();
        for (Block block : modBlocks()) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                BlockState s = state;
                for (int i = 0; i < 4; i++) {
                    s = s.rotate(Rotation.CLOCKWISE_90);
                }
                if (s != state) {
                    failures.add(id(block) + ": four quarter turns of " + props(state) + " give " + props(s));
                    break;
                }
                BlockState m = state.mirror(Mirror.LEFT_RIGHT).mirror(Mirror.LEFT_RIGHT);
                if (m != state) {
                    failures.add(id(block) + ": two LEFT_RIGHT mirrors of " + props(state) + " give " + props(m));
                    break;
                }
                BlockState f = state.mirror(Mirror.FRONT_BACK).mirror(Mirror.FRONT_BACK);
                if (f != state) {
                    failures.add(id(block) + ": two FRONT_BACK mirrors of " + props(state) + " give " + props(f));
                    break;
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static VoxelShape turned(List<AABB> boxes, Transform t) {
        VoxelShape out = Shapes.empty();
        for (AABB b : boxes) {
            double minX = b.minX;
            double maxX = b.maxX;
            double minZ = b.minZ;
            double maxZ = b.maxZ;
            double nMinX = minX;
            double nMaxX = maxX;
            double nMinZ = minZ;
            double nMaxZ = maxZ;
            if (t.rotation() != null) {
                switch (t.rotation()) {
                    case CLOCKWISE_90 -> { // (x, z) -> (1 - z, x)
                        nMinX = 1 - maxZ;
                        nMaxX = 1 - minZ;
                        nMinZ = minX;
                        nMaxZ = maxX;
                    }
                    case CLOCKWISE_180 -> {
                        nMinX = 1 - maxX;
                        nMaxX = 1 - minX;
                        nMinZ = 1 - maxZ;
                        nMaxZ = 1 - minZ;
                    }
                    case COUNTERCLOCKWISE_90 -> { // (x, z) -> (z, 1 - x)
                        nMinX = minZ;
                        nMaxX = maxZ;
                        nMinZ = 1 - maxX;
                        nMaxZ = 1 - minX;
                    }
                    default -> {
                    }
                }
            } else if (t.mirror() == Mirror.LEFT_RIGHT) { // z flips
                nMinZ = 1 - maxZ;
                nMaxZ = 1 - minZ;
            } else if (t.mirror() == Mirror.FRONT_BACK) { // x flips
                nMinX = 1 - maxX;
                nMaxX = 1 - minX;
            }
            // API: reference/minecraft-src/net/minecraft/world/phys/shapes/Shapes.java#joinUnoptimized
            out = Shapes.joinUnoptimized(out, Shapes.create(nMinX, b.minY, nMinZ, nMaxX, b.maxY, nMaxZ), BooleanOp.OR);
        }
        return out.optimize();
    }

    private static VoxelShape shapeOf(BlockState state, boolean collision) {
        try {
            return collision ? state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                    : state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return null; // a shape that needs a real level (neighbours, block entity): not testable here
        }
    }

    @Test
    @DisplayName("the shape of a turned or mirrored state is the shape of the state turned or mirrored")
    void shapesFollowTheTurn() {
        record TurnKey(List<AABB> baseBoxes, String transform) {}
        record ComparisonKey(List<AABB> baseBoxes, List<AABB> gotBoxes, String transform) {}
        // AABB has value equality; keep these caches local to this test run and never mutate the box lists.
        // API: reference/minecraft-src/net/minecraft/world/phys/AABB.java#equals,#hashCode
        Map<TurnKey, VoxelShape> turnedShapes = new HashMap<>();
        Map<ComparisonKey, Boolean> comparisons = new HashMap<>();
        List<String> failures = new ArrayList<>();
        int compared = 0;
        List<Transform> all = rotations();
        for (Block block : modBlocks()) {
            if (!directional(block) || EXCEPTIONS.containsKey(id(block))) {
                continue;
            }
            Map<String, Integer> badCount = new TreeMap<>();
            Map<String, String> firstBad = new HashMap<>();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                for (boolean collision : new boolean[] {false, true}) {
                    VoxelShape base = shapeOf(state, collision);
                    if (base == null) {
                        continue;
                    }
                    List<AABB> baseBoxes = base.toAabbs();
                    for (Transform t : all) {
                        VoxelShape got = shapeOf(apply(state, t), collision);
                        if (got == null) {
                            continue;
                        }
                        compared++;
                        ComparisonKey comparisonKey = new ComparisonKey(baseBoxes, got.toAabbs(), t.label());
                        boolean differs = comparisons.computeIfAbsent(comparisonKey, key -> {
                            VoxelShape want = turnedShapes.computeIfAbsent(new TurnKey(baseBoxes, t.label()),
                                    turnKey -> turned(turnKey.baseBoxes(), t));
                            return Shapes.joinIsNotEmpty(want, got, BooleanOp.NOT_SAME);
                        });
                        if (differs) {
                            String key = (collision ? "collision " : "selection ") + t.label();
                            badCount.merge(key, 1, Integer::sum);
                            firstBad.putIfAbsent(key, props(state) + " -> " + props(apply(state, t)));
                        }
                    }
                }
            }
            for (Map.Entry<String, Integer> e : badCount.entrySet()) {
                failures.add(id(block) + ": " + e.getKey() + " shape differs on " + e.getValue() + " states, e.g. " + firstBad.get(e.getKey()));
            }
        }
        assertTrue(compared > 1000, "too few shapes compared: " + compared);
        assertTrue(failures.isEmpty(), failures.size() + " block/transform pairs have a shape that does not follow the turn:\n"
                + String.join("\n", failures));
    }
}
