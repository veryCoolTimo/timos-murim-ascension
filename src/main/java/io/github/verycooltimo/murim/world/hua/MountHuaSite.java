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
    public static final int SUMMIT_Y = 306;

    private final int centerX;
    private final int centerZ;
    private final int baseY;
    private final int rotation;
    private final MountHuaShape shape;
    private final double scale;
    // World-space bounding box of everything the mountain writes.
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;

    public MountHuaSite(int centerX, int centerZ, int baseY, int rotation, long worldSeed) {
        this(centerX, centerZ, baseY, rotation, new MountHuaShape(worldSeed));
    }

    /**
     * Placement only (no height function): what the client needs to put the mist over the massif.
     * {@link #shape()} is null for such a site.
     */
    public static MountHuaSite placement(int centerX, int centerZ, int baseY, int rotation) {
        return new MountHuaSite(centerX, centerZ, baseY, rotation, (MountHuaShape) null);
    }

    private MountHuaSite(int centerX, int centerZ, int baseY, int rotation, MountHuaShape shape) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.baseY = baseY;
        this.rotation = rotation & 3;
        this.shape = shape;
        this.scale = (SUMMIT_Y - baseY) / MountHuaPlan.SUMMIT;
        int[] a = toWorld(MountHuaShape.MIN_U, MountHuaShape.MIN_V);
        int[] b = toWorld(MountHuaShape.MAX_U, MountHuaShape.MAX_V);
        this.minX = Math.min(a[0], b[0]) - 1;
        this.maxX = Math.max(a[0], b[0]) + 1;
        this.minZ = Math.min(a[1], b[1]) - 1;
        this.maxZ = Math.max(a[1], b[1]) + 1;
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

    /** Local direction (du, dv) expressed as a world (dx, dz) direction. */
    public int[] rotateDir(int du, int dv) {
        return switch (rotation) {
            case 1 -> new int[] {-dv, du};
            case 2 -> new int[] {-du, -dv};
            case 3 -> new int[] {dv, -du};
            default -> new int[] {du, dv};
        };
    }
}
