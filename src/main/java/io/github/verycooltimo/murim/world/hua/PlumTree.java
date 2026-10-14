package io.github.verycooltimo.murim.world.hua;

import com.mojang.serialization.Codec;
import io.github.verycooltimo.murim.registry.ModBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.PinkPetalsBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Plum tree (梅) after the author's refs (docs/design/reference/build2/ref_01, 02, 05, 13;
 * mount-hua/author2/sect-layout-1/2) and his note 07.10 («выше и красивше»): a visible trunk with a graceful
 * lean, limbs splitting in tiers, a deep crown of overlapping rounded clusters at different heights with soft
 * rims, gaps that show the limbs, drips under the clusters, two blossom shades and petals on the ground.
 *
 * <p>Variants: {@link Variant#COURTYARD} (10-13 high, crown about 60% as high as wide, pink),
 * {@link Variant#CLIFF} (a long trunk curving out over the drop, airy flatter clusters, red) and
 * {@link Variant#YOUNG} (6-7 high). The shape is built in memory first, then written: wood wins over
 * blossom, only air and soft plants are replaced. Deterministic per seed, so worldgen is stable.
 * Used by the Mount Hua generator, the plum saplings and {@code /murim tree}.
 */
public final class PlumTree {

    public enum Variant implements StringRepresentable {
        COURTYARD("courtyard"), CLIFF("cliff"), YOUNG("young");

        public static final Codec<Variant> CODEC = StringRepresentable.fromEnum(Variant::values);
        private final String name;

        Variant(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public Palette defaultPalette() {
            return this == CLIFF ? Palette.RED : Palette.PINK;
        }
    }

    /** Blossom colours: body and the sunlit top layer of the crown. */
    public enum Palette {
        PINK, PALE, RED;

        BlockState body() {
            return blossom(this == PINK ? ModBlocks.PLUM_BLOSSOM.get().defaultBlockState()
                    : this == PALE ? ModBlocks.PLUM_BLOSSOM_PALE.get().defaultBlockState()
                    : ModBlocks.PLUM_BLOSSOM_RED.get().defaultBlockState());
        }

        BlockState top() {
            return blossom(this == RED ? ModBlocks.PLUM_BLOSSOM.get().defaultBlockState()
                    : ModBlocks.PLUM_BLOSSOM_PALE.get().defaultBlockState());
        }

        /** Second shade mixed into every other cluster: pink gets white, red and white get pink. */
        BlockState second() {
            return blossom(this == PINK ? ModBlocks.PLUM_BLOSSOM_PALE.get().defaultBlockState()
                    : ModBlocks.PLUM_BLOSSOM.get().defaultBlockState());
        }

        /** Inner shade: pale trees get a pink underside, the others keep their body colour. */
        BlockState under() {
            return this == PALE ? blossom(ModBlocks.PLUM_BLOSSOM.get().defaultBlockState()) : body();
        }

        /** Share (percent) of body blocks swapped for {@link #under()}: pale trees keep pink speckles. */
        int speckle() {
            return this == PALE ? 35 : 0;
        }

        private static BlockState blossom(BlockState s) {
            return s.setValue(LeavesBlock.PERSISTENT, true);
        }
    }

    private static final int TRUNK = 1;
    private static final int WOOD = 2;
    private static final int BLOSSOM = 3;
    private static final int BLOSSOM_ALT = 4;

    private PlumTree() {
    }

    /** True for the plum's own wood, so the Mount Hua cleanup keeps it (it removes vanilla logs). */
    public static boolean isPlumWood(BlockState st) {
        return st.is(ModBlocks.PLUM_LOG.get()) || st.is(ModBlocks.PLUM_WOOD.get());
    }

    /**
     * Grows a tree with its trunk foot at {@code base}. {@code lean} is the side the tree bends to
     * (cliff: over the drop); null picks one from the seed. Returns false when the foot is blocked.
     */
    public static boolean grow(LevelAccessor level, BlockPos base, Variant variant, Palette palette, long seed,
            @Nullable Direction lean, int flags) {
        if (!replaceable(level.getBlockState(base), false)) {
            return false;
        }
        Map<BlockPos, Integer> shape = shape(variant, seed, lean);
        write(level, base, shape, palette, seed, flags);
        return true;
    }

    /**
     * The tree as offsets from the foot: TRUNK (upright log at the foot), WOOD (bark on all sides), BLOSSOM or
     * BLOSSOM_ALT (the second shade, for depth inside one crown).
     *
     * <p>Author 07.10 («выше и красивше»): a tall elegant tree, not a flat mushroom. A visible trunk with a
     * graceful lean, limbs splitting in two or three tiers, and a crown of several overlapping rounded clusters
     * at different heights (vanilla CherryFoliagePlacer idea: wide body, rounded corners, hanging leaves below,
     * reference/minecraft-src/.../foliageplacers/CherryFoliagePlacer.java), soft sparse rims, gaps between the
     * clusters that show the limbs, drips under every cluster.
     */
    static Map<BlockPos, Integer> shape(Variant variant, long seed, @Nullable Direction lean) {
        SplittableRandom r = new SplittableRandom(seed);
        Map<BlockPos, Integer> out = new HashMap<>();
        double leanAngle = lean != null ? Math.atan2(lean.getStepZ(), lean.getStepX()) : r.nextDouble() * Math.PI * 2;
        double lc = Math.cos(leanAngle);
        double ls = Math.sin(leanAngle);
        // Trunk: one curve from the foot. Courtyard: 6-7 high, a soft lean of 1-2 blocks; cliff: 6-8 long,
        // rising first and then bending out over the drop by 3-5 blocks; young: 3-4 with a slight bend.
        double height;
        double out1;
        double bendAt;
        switch (variant) {
            case COURTYARD -> {
                height = 6 + r.nextInt(2);
                out1 = 1.6 + r.nextDouble() * 1.0;
                bendAt = 0.5;
            }
            case CLIFF -> {
                height = 6 + r.nextInt(3);
                out1 = 3.0 + r.nextDouble() * 2.0;
                bendAt = 0.75;
            }
            default -> {
                height = 3 + r.nextInt(2);
                out1 = 0.5 + r.nextDouble() * 0.8;
                bendAt = 0.5;
            }
        }
        double[] foot = {0, 0, 0};
        double[] top = {lc * out1, height - 1, ls * out1};
        // Control point: up first (bendAt of the height) with a slight counter-sway, so the trunk is an S/C curve.
        double sway = (r.nextDouble() - 0.5) * 1.2;
        double[] ctrl = {-ls * sway, (height - 1) * bendAt + (variant == Variant.CLIFF ? 2.5 : 0), lc * sway};
        line(out, foot, ctrl, top);
        out.put(BlockPos.ZERO, TRUNK);
        // Limbs in tiers: tier 1 from the upper trunk outward, tier 2 from the top upward and out; a few
        // forks on the longest limbs (tier 3). Cliff limbs reach on toward the lean.
        List<double[]> tips = new ArrayList<>();
        int tiers = variant == Variant.YOUNG ? 1 : 2;
        double phase = r.nextDouble() * Math.PI * 2;
        for (int tier = 0; tier < tiers; tier++) {
            double t0 = tier == 0 && tiers > 1 ? 0.8 : 1.0;
            double[] from = bezier(foot, ctrl, top, t0);
            int limbs = variant == Variant.YOUNG ? 2 : (tier == 0 ? 2 : 2 + r.nextInt(2));
            for (int b = 0; b < limbs; b++) {
                double a;
                if (variant == Variant.CLIFF) {
                    a = leanAngle + (b - (limbs - 1) / 2.0) * 1.1 + (r.nextDouble() - 0.5) * 0.5;
                } else {
                    a = phase + tier * 1.3 + b * Math.PI * 2 / limbs + (r.nextDouble() - 0.5) * 0.8;
                }
                double len;
                double rise;
                switch (variant) {
                    case COURTYARD -> {
                        len = tier == 0 ? 3.8 + r.nextDouble() * 1.2 : 2.6 + r.nextDouble() * 1.0;
                        rise = tier == 0 ? 1.4 + r.nextDouble() * 0.8 : 2.0 + r.nextDouble() * 0.8;
                    }
                    case CLIFF -> {
                        len = 2.4 + r.nextDouble() * 1.6;
                        rise = 1.0 + r.nextDouble() * 1.4;
                    }
                    default -> {
                        len = 1.6 + r.nextDouble() * 0.8;
                        rise = 1.6 + r.nextDouble() * 0.8;
                    }
                }
                double[] tip = {from[0] + Math.cos(a) * len, from[1] + rise, from[2] + Math.sin(a) * len};
                double side = (r.nextDouble() - 0.5) * 1.6;
                double[] c = {from[0] + Math.cos(a) * len * 0.45 - Math.sin(a) * side, from[1] + rise * 0.8,
                        from[2] + Math.sin(a) * len * 0.45 + Math.cos(a) * side};
                line(out, from, c, tip);
                tips.add(tip);
                if (variant != Variant.YOUNG && r.nextInt(3) > 0) {
                    double[] mid = bezier(from, c, tip, 0.6);
                    double a2 = a + (r.nextBoolean() ? 1 : -1) * (0.6 + r.nextDouble() * 0.5);
                    double l2 = 1.4 + r.nextDouble() * 1.2;
                    double[] tip2 = {mid[0] + Math.cos(a2) * l2, mid[1] + 0.8 + r.nextDouble() * 0.8, mid[2] + Math.sin(a2) * l2};
                    line(out, mid, new double[] {(mid[0] + tip2[0]) / 2, tip2[1], (mid[2] + tip2[2]) / 2}, tip2);
                    tips.add(tip2);
                }
            }
        }
        // Clusters: an ellipsoid over each tip, wide and a little flattened, at the tip's own height (so the
        // crown is deep: clusters of tier 1 sit lower and wider, tier 2 higher). The rim is sparse (soft
        // edge), the second shade goes to every other cluster.
        long noiseSeed = r.nextLong();
        int index = 0;
        for (double[] t : tips) {
            double rx;
            double ry;
            switch (variant) {
                case COURTYARD -> {
                    rx = 2.8 + r.nextDouble() * 0.6;
                    ry = 1.7 + r.nextDouble() * 0.4;
                }
                case CLIFF -> {
                    rx = 2.2 + r.nextDouble() * 0.7;
                    ry = 1.2 + r.nextDouble() * 0.3;
                }
                default -> {
                    rx = 2.0 + r.nextDouble() * 0.5;
                    ry = 1.5 + r.nextDouble() * 0.3;
                }
            }
            int kind = index++ % 2 == 0 ? BLOSSOM : BLOSSOM_ALT;
            cluster(out, t[0] + 0.5, t[1] + 0.7, t[2] + 0.5, rx, ry, kind, noiseSeed, variant == Variant.CLIFF ? 0.55 : 0.75, r);
        }
        // Courtyard: a crown cap over the leader closes the dome (no ring with a hole when seen from above).
        if (variant == Variant.COURTYARD) {
            cluster(out, top[0] + 0.5, top[1] + 2.6, top[2] + 0.5, 2.4, 1.5, BLOSSOM, noiseSeed + 3, 0.75, r);
        }
        // Limb ends inside a cluster turn to blossom (no bare stubs), only ends, so no limb is cut off.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<BlockPos, Integer> e : out.entrySet()) {
                if (e.getValue() != WOOD) {
                    continue;
                }
                BlockPos p = e.getKey();
                int woodNb = 0;
                int bloomNb = 0;
                for (Direction d : Direction.values()) {
                    Integer n = out.get(p.relative(d));
                    if (n == null) {
                        continue;
                    }
                    if (n == WOOD || n == TRUNK) {
                        woodNb++;
                    } else {
                        bloomNb++;
                    }
                }
                if (woodNb <= 1 && bloomNb >= 4) {
                    e.setValue(BLOSSOM);
                    changed = true;
                }
            }
        }
        // Windows round the limbs (codex r2: branches must show through gaps): blossom right under a limb
        // and beside a limb that still runs below the crown middle goes, so the dark wood reads through.
        double crownMid = 0;
        for (double[] t : tips) {
            crownMid += (t[1] + 0.7) / tips.size();
        }
        double mid = crownMid;
        out.entrySet().removeIf(e -> {
            if (!isBlossom(e.getValue())) {
                return false;
            }
            BlockPos p = e.getKey();
            Integer above = out.get(p.above());
            if (above != null && !isBlossom(above)) {
                return true;
            }
            if (p.getY() >= mid) {
                return false;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                Integer n = out.get(p.relative(d));
                if (n != null && !isBlossom(n)) {
                    return true;
                }
            }
            return false;
        });
        // No floating specks: a blossom with no neighbour on any face goes.
        out.entrySet().removeIf(e -> isBlossom(e.getValue()) && java.util.Arrays.stream(Direction.values())
                .noneMatch(d -> out.containsKey(e.getKey().relative(d))));
        // Drips under the clusters (vanilla cherry: hanging leaves under the wide layer), one or two blocks.
        List<BlockPos> hang = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> e : out.entrySet()) {
            BlockPos p = e.getKey();
            if (isBlossom(e.getValue()) && !out.containsKey(p.below()) && r.nextInt(100) < 16) {
                // Chains of one to three blocks under the cluster rims.
                int len = 1 + (r.nextInt(100) < 35 ? 1 : 0) + (r.nextInt(100) < 15 ? 1 : 0);
                for (int k = 1; k <= len; k++) {
                    hang.add(p.below(k));
                }
            }
        }
        for (BlockPos p : hang) {
            out.putIfAbsent(p, out.getOrDefault(p.above(), BLOSSOM) == BLOSSOM_ALT ? BLOSSOM_ALT : BLOSSOM);
        }
        return out;
    }

    static boolean isBlossom(Integer kind) {
        return kind != null && (kind == BLOSSOM || kind == BLOSSOM_ALT);
    }

    /**
     * One rounded cluster: an ellipsoid ({@code rx} across, {@code ry} up, a flatter underside) with a lobed
     * surface (smooth noise) and a soft rim: past {@code soft} of the radius blocks thin out toward the edge.
     */
    private static void cluster(Map<BlockPos, Integer> out, double cx, double cy, double cz, double rx, double ry,
            int kind, long seed, double soft, SplittableRandom r) {
        int ir = (int) Math.ceil(rx) + 1;
        int iy = (int) Math.ceil(ry) + 1;
        for (int dy = -iy; dy <= iy; dy++) {
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    int x = (int) Math.floor(cx) + dx;
                    int y = (int) Math.floor(cy) + dy;
                    int z = (int) Math.floor(cz) + dz;
                    double fx = x + 0.5 - cx;
                    double fy = y + 0.5 - cy;
                    double fz = z + 0.5 - cz;
                    double vy = fy >= 0 ? fy / ry : fy / (ry * 0.75);
                    double q = Math.sqrt((fx * fx + fz * fz) / (rx * rx) + vy * vy);
                    double lobe = 1.0 + 0.22 * noise(seed, x / 1.8, y / 1.6, z / 1.8);
                    if (q >= lobe) {
                        continue;
                    }
                    double rim = (q / lobe - soft) / (1.0 - soft);
                    if (rim > 0 && r.nextDouble() < rim * 0.85) {
                        continue;
                    }
                    BlockPos p = new BlockPos(x, y, z);
                    Integer cur = out.get(p);
                    if (cur == null) {
                        out.put(p, kind);
                    }
                }
            }
        }
    }

    private static void write(LevelAccessor level, BlockPos base, Map<BlockPos, Integer> shape, Palette palette,
            long seed, int flags) {
        BlockState log = ModBlocks.PLUM_LOG.get().defaultBlockState();
        BlockState wood = ModBlocks.PLUM_WOOD.get().defaultBlockState();
        SplittableRandom r = new SplittableRandom(seed * 31 + 7);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Wood first (it may push through soft plants and other blossom), then blossom into air only.
        for (Map.Entry<BlockPos, Integer> e : shape.entrySet()) {
            if (isBlossom(e.getValue())) {
                continue;
            }
            BlockPos p = e.getKey();
            pos.setWithOffset(base, p);
            if (p.getY() == 0 && (p.getX() != 0 || p.getZ() != 0)) {
                // Root flare only on firm ground.
                if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                    continue;
                }
            }
            if (replaceable(level.getBlockState(pos), true)) {
                level.setBlock(pos, e.getValue() == TRUNK ? log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : wood, flags);
            }
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Map.Entry<BlockPos, Integer> e : shape.entrySet()) {
            if (!isBlossom(e.getValue())) {
                continue;
            }
            BlockPos p = e.getKey();
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
            boolean sunlit = shape.get(p.above()) == null;
            boolean inner = shape.get(p.below()) != null && shape.get(p.above()) != null;
            // Two shades in one crown: every other cluster (BLOSSOM_ALT) is mixed with the second shade.
            // Second shade in clustered patches (smooth noise), not per-block noise (codex r2).
            boolean alt = noise(seed ^ 0x2545F4914F6CDD1DL, p.getX() / 2.2, p.getY() / 2.2, p.getZ() / 2.2)
                    > (e.getValue() == BLOSSOM_ALT ? -0.1 : 0.25);
            BlockState s = sunlit && r.nextInt(100) < 40 ? palette.top()
                    : alt ? palette.second()
                    : inner || r.nextInt(100) < palette.speckle() ? palette.under() : palette.body();
            pos.setWithOffset(base, p);
            if (replaceable(level.getBlockState(pos), false)) {
                level.setBlock(pos, s, flags);
            }
        }
        // Fallen petals on the grass under pink and white crowns (vanilla pink petals, cheap and readable).
        if (palette == Palette.RED || minX > maxX) {
            return;
        }
        Direction[] facings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (r.nextInt(100) >= 32) {
                    continue;
                }
                for (int dy = 2; dy >= -6; dy--) {
                    pos.set(base.getX() + x, base.getY() + dy, base.getZ() + z);
                    BlockState here = level.getBlockState(pos);
                    BlockState below = level.getBlockState(pos.below());
                    if (here.isAir() && (below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.PODZOL) || below.is(BlockTags.DIRT)
                            && !below.is(Blocks.MUD))) {
                        level.setBlock(pos, Blocks.PINK_PETALS.defaultBlockState()
                                .setValue(PinkPetalsBlock.FACING, facings[r.nextInt(4)])
                                .setValue(PinkPetalsBlock.AMOUNT, 1 + r.nextInt(3)), flags);
                        break;
                    }
                    if (!here.isAir()) {
                        break;
                    }
                }
            }
        }
    }

    private static boolean replaceable(BlockState st, boolean wood) {
        if (st.isAir()) {
            return true;
        }
        if (!st.getFluidState().isEmpty()) {
            return false;
        }
        return st.canBeReplaced() || (wood && st.is(BlockTags.LEAVES)) || st.is(Blocks.PINK_PETALS);
    }

    /** Rasterises a quadratic curve into face-connected blocks: diagonals become log steps. */
    private static void line(Map<BlockPos, Integer> out, double[] a, double[] c, double[] b) {
        BlockPos prev = null;
        for (int i = 0; i <= 24; i++) {
            double[] p = bezier(a, c, b, i / 24.0);
            BlockPos cur = BlockPos.containing(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
            if (prev != null && !cur.equals(prev)) {
                // Step axis by axis (y first: limbs climb, then reach), so every block touches the last.
                BlockPos step = prev;
                if (cur.getY() != step.getY()) {
                    step = new BlockPos(step.getX(), cur.getY(), step.getZ());
                    put(out, step);
                }
                if (cur.getX() != step.getX()) {
                    step = new BlockPos(cur.getX(), step.getY(), step.getZ());
                    put(out, step);
                }
                if (cur.getZ() != step.getZ()) {
                    step = new BlockPos(step.getX(), step.getY(), cur.getZ());
                    put(out, step);
                }
            }
            put(out, cur);
            prev = cur;
        }
    }

    private static void put(Map<BlockPos, Integer> out, BlockPos p) {
        if (!Integer.valueOf(TRUNK).equals(out.get(p))) {
            out.put(p, WOOD);
        }
    }

    private static double[] bezier(double[] a, double[] c, double[] b, double t) {
        double u = 1 - t;
        return new double[] {u * u * a[0] + 2 * u * t * c[0] + t * t * b[0],
                u * u * a[1] + 2 * u * t * c[1] + t * t * b[1],
                u * u * a[2] + 2 * u * t * c[2] + t * t * b[2]};
    }

    /** Smooth value noise in [-1, 1]: clumped gaps instead of lone holes. */
    static double noise(long seed, double x, double y, double z) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = smooth(x - x0);
        double fy = smooth(y - y0);
        double fz = smooth(z - z0);
        double v = 0;
        for (int i = 0; i < 8; i++) {
            int dx = i & 1;
            int dy = (i >> 1) & 1;
            int dz = (i >> 2) & 1;
            double w = (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
            v += w * lattice(seed, x0 + dx, y0 + dy, z0 + dz);
        }
        return v;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lattice(long seed, int x, int y, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }
}
