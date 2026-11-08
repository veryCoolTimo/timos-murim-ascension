package io.github.verycooltimo.murim.world.hua;

/**
 * Where Mount Hua stands in a world: centre of the local frame, foot height, quarter-turn rotation.
 * Immutable; built once per server from {@link MountHuaSiteData} and then shared with worldgen
 * threads. The {@link MountHuaShape} is built here once (it only depends on the world seed).
 *
 * <p>Rotation is a multiple of 90° on purpose: terraces for the author's buildings must stay
 * aligned with the block grid. {@code rotation} = quarter turns clockwise (seen from above).
 */
public final class MountHuaSite {

    /** South Peak summit y. World height limit in 1.21.1 overworld is 319 (pines need ~12 more). */
    public static final int SUMMIT_Y = 314;

    private final int centerX;
    private final int centerZ;
    private final int baseY;
    private final int rotation;
    private final MountHuaShape shape;
    /** Seed of the height function and of the writer's noise (the world seed, or {@link HuaBaked#CANONICAL_SEED}). */
    private final long terrainSeed;
    private final boolean canonical;
    private final double scale;
    // World-space bounding box of everything the mountain writes.
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;

    public MountHuaSite(int centerX, int centerZ, int baseY, int rotation, long worldSeed) {
        this(centerX, centerZ, baseY, rotation, worldSeed, false);
    }

    /**
     * @param canonical true: the mountain is shaped by {@link HuaBaked#CANONICAL_SEED} whatever the world seed is, so the
     *                  baked sect fits it ({@link MountHuaSiteData})
     */
    public MountHuaSite(int centerX, int centerZ, int baseY, int rotation, long worldSeed, boolean canonical) {
        this(centerX, centerZ, baseY, rotation, canonical ? HuaBaked.CANONICAL_SEED : worldSeed, canonical,
                new MountHuaShape(canonical ? HuaBaked.CANONICAL_SEED : worldSeed));
    }

    /**
     * Placement only (no height function): what the client needs to put the mist over the massif.
     * {@link #shape()} is null for such a site.
     */
    public static MountHuaSite placement(int centerX, int centerZ, int baseY, int rotation) {
        return new MountHuaSite(centerX, centerZ, baseY, rotation, 0L, false, null);
    }

    private MountHuaSite(int centerX, int centerZ, int baseY, int rotation, long terrainSeed, boolean canonical,
            MountHuaShape shape) {
        this.terrainSeed = terrainSeed;
        this.canonical = canonical;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.baseY = baseY;
        this.rotation = rotation & 3;
        this.shape = shape;
        this.scale = scaleFor(baseY, canonical);
        int[] a = toWorld(MountHuaShape.MIN_U, MountHuaShape.MIN_V);
        int[] b = toWorld(MountHuaShape.MAX_U, MountHuaShape.MAX_V);
        this.minX = Math.min(a[0], b[0]) - 1;
        this.maxX = Math.max(a[0], b[0]) + 1;
        this.minZ = Math.min(a[1], b[1]) - 1;
        this.maxZ = Math.max(a[1], b[1]) + 1;
    }

    /**
     * Heights of the plan are multiplied by this to reach the world. A canonical site (the baked sect, docs/adr/0018) at or below the
     * bake's foot height keeps the scale of the bake whatever its foot height is, so the procedural mountain around the baked part is
     * the bake's mountain moved by exactly {@code baseY - HuaBaked.BAKE_BASE_Y} blocks, as the baked part is. Any other site scales
     * the plan so that the South Peak reaches {@link #SUMMIT_Y}.
     */
    static double scaleFor(int baseY, boolean canonical) {
        int foot = canonical && baseY <= HuaBaked.BAKE_BASE_Y ? HuaBaked.BAKE_BASE_Y : baseY;
        return (SUMMIT_Y - foot) / MountHuaPlan.SUMMIT;
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public int baseY() {
        return baseY;
    }

    public int rotation() {
        return rotation;
    }

    public MountHuaShape shape() {
        return shape;
    }

    /** Seed the chunk writer's noise and hashes use (not always the world seed). */
    public long terrainSeed() {
        return terrainSeed;
    }

    /** True if the mountain has the canonical shape the baked sect was built on. */
    public boolean canonical() {
        return canonical;
    }

    /** True if (x, z) lies within {@code margin} blocks of the massif's bounding box (bandit camps keep away). */
    public boolean near(int x, int z, int margin) {
        return x >= minX - margin && x <= maxX + margin && z >= minZ - margin && z <= maxZ + margin;
    }

    /** True if the 16x16 chunk at (chunkX, chunkZ) may receive mountain blocks. */
    public boolean touchesChunk(int chunkX, int chunkZ) {
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        return x0 + 15 >= minX && x0 <= maxX && z0 + 15 >= minZ && z0 <= maxZ;
    }

    public int minX() {
        return minX;
    }

    public int maxX() {
        return maxX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxZ() {
        return maxZ;
    }

    /** Local (u, v) of a world column. */
    public double localU(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return switch (rotation) {
            case 1 -> dz;
            case 2 -> -dx;
            case 3 -> -dz;
            default -> dx;
        };
    }

    public double localV(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return switch (rotation) {
            case 1 -> -dx;
            case 2 -> -dz;
            case 3 -> dx;
            default -> dz;
        };
    }

    /** World (x, z) of a local point, rounded to the block grid. */
    public int[] toWorld(double u, double v) {
        double x;
        double z;
        switch (rotation) {
            case 1 -> {
                x = -v;
                z = u;
            }
            case 2 -> {
                x = -u;
                z = -v;
            }
            case 3 -> {
                x = v;
                z = -u;
            }
            default -> {
                x = u;
                z = v;
            }
        }
        return new int[] {(int) Math.floor(centerX + x), (int) Math.floor(centerZ + z)};
    }

    /** World y of a nominal height (0 = foot, {@link MountHuaPlan#SUMMIT} = South Peak). */
    public double worldY(double nominal) {
        return baseY + nominal * scale;
    }

    /**
     * Local u of the CENTRE of the world block (x, z), as the baked cells turn it: the block stamped there is (u, v) of
     * {@link #stampBlock}, its centre (u + 0.5, v + 0.5). Sampling the plan at {@code localU(x + 0.5, z + 0.5)} instead turns the plane
     * about the corner of the centre block and lands one block beside the stamp at rotations 1-3 (west or north of it), which put the
     * procedural mountain one block off the baked columns: a step at the edge of the stamp and pockets of air in the rock below it
     * (docs/adr/0018). Rotation 0 gives the same numbers as before, and so does a non-canonical (older) world: its chunks were generated with the corner turn.
     */
    public double centerU(int x, int z) {
        return canonical ? stampBlock(x, z)[0] + 0.5 : localU(x + 0.5, z + 0.5);
    }

    /** Local v of the centre of the world block (x, z); see {@link #centerU}. */
    public double centerV(int x, int z) {
        return canonical ? stampBlock(x, z)[1] + 0.5 : localV(x + 0.5, z + 0.5);
    }

    /**
     * Local block (u, v) that a baked cell puts on the world block (x, z): the inverse of {@code centre + rotateDir(u, v)}.
     * Integers in, integers out (the continuous {@link #localU} answers for points).
     */
    public int[] stampBlock(int x, int z) {
        int dx = x - centerX;
        int dz = z - centerZ;
        return switch (rotation) {
            case 1 -> new int[] {dz, -dx};
            case 2 -> new int[] {-dx, -dz};
            case 3 -> new int[] {-dz, dx};
            default -> new int[] {dx, dz};
        };
    }

    /** Nominal height of a world y (inverse of {@link #worldY}). */
    public double nominal(double worldY) {
        return (worldY - baseY) / scale;
    }

    public int[] rotateDir(int du, int dv) {
        return switch (rotation) {
            case 1 -> new int[] {-dv, du};
            case 2 -> new int[] {-du, -dv};
            case 3 -> new int[] {dv, -du};
            default -> new int[] {du, dv};
        };
    }
}
