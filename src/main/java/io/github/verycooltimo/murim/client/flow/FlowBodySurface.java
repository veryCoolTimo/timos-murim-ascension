package io.github.verycooltimo.murim.client.flow;

import net.minecraft.world.phys.Vec3;

/** Route and decoration vertices on the posed torso's front plane (ADR 0022). */
public final class FlowBodySurface {
    public static final double INSET = 1 / 16D;
    public static final double SURFACE_OFFSET = 0.02D;
    /** Measured on the stand films of 26.10 (b305ff0c): the posed anchors drew the loop 4–6 model px low (shirt collar to belt, both poses), the same standing and in the
     *  lotus, so the plane is lifted by that much. [НЕПРОВЕРЕНО: where BoneAnchorLayer loses those 4 px; a camera-rotation fix moved
     *  the loop onto the legs instead (reverted).] */
    public static final double LIFT_PX = 5.0D;
    private final Vec3 bottom;
    private final Vec3 up;
    private final Vec3 front;
    private final Vec3 screenRight;
    private final double height;
    private final double halfWidth;

    /** Torso-local (0,12,0), (0,0,0), (0,12,-2), transformed by BoneAnchorLayer.pointOf. */
    public FlowBodySurface(Vec3 bottom, Vec3 top, Vec3 frontFace) {
        Vec3 axis = top.subtract(bottom);
        height = axis.length();
        up = axis.normalize();
        Vec3 depth = frontFace.subtract(bottom);
        front = depth.subtract(up.scale(depth.dot(up))).normalize();
        // Both skin models have an 8-pixel torso and a 4-pixel depth; arms are irrelevant.
        halfWidth = depth.length() * 2;
        // Viewed from the front, screen right is the player's left (Fork.goodKey).
        screenRight = up.cross(front).normalize();
        this.bottom = frontFace.add(front.scale(SURFACE_OFFSET)).add(up.scale(height * LIFT_PX / 12));
    }

    public Vec3 front() { return front; }

    public double xScale() { return (halfWidth - height / 12) / .165D; }
    public double yScale() { return height * (10 - 1.5D) / 12 / .61D; }

    public Vec3 at(double x, double y) {
        // Clamp every edge as well as the route to this single torso face, symmetrically.
        // Author's 26.10 bounds: one model pixel clear of arms, 1.5..10 below collar.
        double armInset = height / 12;
        double across = clamp((x - .5D) * xScale(), -halfWidth + armInset, halfWidth - armInset);
        double along = clamp(height * (12 - 10) / 12 + (.80D - y) * yScale(),
                height * (12 - 10) / 12, height * (12 - 1.5D) / 12);
        return bottom.add(screenRight.scale(across)).add(up.scale(along));
    }

    private static double clamp(double n, double min, double max) {
        return Math.max(min, Math.min(max, n));
    }
}
