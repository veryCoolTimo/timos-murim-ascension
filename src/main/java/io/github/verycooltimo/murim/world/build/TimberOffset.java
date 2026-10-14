package io.github.verycooltimo.murim.world.build;

import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Rotation;

/**
 * Where a timber piece sits inside its block on the horizontal plane (author 06.10, building kit:
 * «по единичке ставишь столб, но нужно контролировать, где будет палка вертикальная»). Each axis has
 * three slots: the low edge (−1), the middle (0) and the high edge (+1). Edge slots are centred
 * 3 px from the block face, so a 5 px pillar stands 0.5 px off the face and its 6 px rings touch it.
 *
 * <p>The slot is picked by the point you click: the block face is cut into thirds (zone edges at
 * 6/16 and 10/16). Pure logic, covered by {@code TimberOffsetTest}.
 */
public enum TimberOffset implements StringRepresentable {
    CENTER("center", 0, 0),
    NORTH("north", 0, -1),
    NORTH_EAST("north_east", 1, -1),
    EAST("east", 1, 0),
    SOUTH_EAST("south_east", 1, 1),
    SOUTH("south", 0, 1),
    SOUTH_WEST("south_west", -1, 1),
    WEST("west", -1, 0),
    NORTH_WEST("north_west", -1, -1);

    /** Edge of the middle third, in block units (6 px / 16). */
    public static final double LOW_ZONE = 6.0 / 16.0;
    public static final double HIGH_ZONE = 10.0 / 16.0;

    private final String name;
    private final int dx;
    private final int dz;

    TimberOffset(String name, int dx, int dz) {
        this.name = name;
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    /** Component along a horizontal axis (−1, 0, +1). */
    public int along(Direction.Axis axis) {
        return axis == Direction.Axis.X ? dx : dz;
    }

    public static TimberOffset of(int dx, int dz) {
        for (TimberOffset o : values()) {
            if (o.dx == Integer.signum(dx) && o.dz == Integer.signum(dz)) {
                return o;
            }
        }
        return CENTER;
    }

    /** −1 / 0 / +1 for a coordinate inside the block (0..1), cut into thirds. */
    public static int zone(double t) {
        if (t < LOW_ZONE) {
            return -1;
        }
        return t > HIGH_ZONE ? 1 : 0;
    }

    /** Slot under a clicked point given in block-local coordinates (0..1, clamped). */
    public static TimberOffset fromLocal(double x, double z) {
        return of(zone(clamp(x)), zone(clamp(z)));
    }

    /** Centre of a slot in pixels: 3, 8 or 13. */
    public static double centrePx(int slot) {
        return slot < 0 ? 3.0 : slot > 0 ? 13.0 : 8.0;
    }

    /** Next slot for the sneak-click cycle: centre, then clockwise round the edge from north. */
    public TimberOffset next() {
        TimberOffset[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public TimberOffset withAlong(Direction.Axis axis, int slot) {
        return axis == Direction.Axis.X ? of(slot, dz) : of(dx, slot);
    }

    public TimberOffset rotate(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> of(-dz, dx);
            case CLOCKWISE_180 -> of(-dx, -dz);
            case COUNTERCLOCKWISE_90 -> of(dz, -dx);
            default -> this;
        };
    }

    public TimberOffset mirrorX() {
        return of(-dx, dz);
    }

    public TimberOffset mirrorZ() {
        return of(dx, -dz);
    }

    static double clamp(double t) {
        return Math.max(0.0, Math.min(1.0, t));
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
