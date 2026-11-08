package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.network.BambooPayload;
import net.minecraft.world.phys.Vec3;

/** Bamboo-only, batched procedural geometry on the existing bambooLeaves material.
 * API verified in /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java:
 * constructor, add, subtract, scale, normalize, cross, dot, lengthSqr, lerp, multiply. No entities or world mutation.
 * Design: docs/adr/bamboo-scale-2026-11-04.md. All motion uses fractional client ticks.
 */
final class BambooScale {
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final VfxColour JADE = new VfxColour(53 / 255F, 201 / 255F, 135 / 255F);
    private static final VfxColour LIGHT = new VfxColour(133 / 255F, 245 / 255F, 176 / 255F);
    private static final VfxColour INK = new VfxColour(18 / 255F, 61 / 255F, 48 / 255F);
    private static final VfxColour MINT = new VfxColour(127/255F, 217/255F, 154/255F);
    private static final VfxColour EDGE = new VfxColour(46/255F, 139/255F, 87/255F);
    private static final VfxColour IVORY = new VfxColour(1, .98F, .88F);
    private static final VfxColour STONE = new VfxColour(.39F, .35F, .28F);
    private static final VfxColour SHALE = new VfxColour(.21F, .23F, .20F);
    private static final VfxColour HOT = new VfxColour(229 / 255F, 1, 240 / 255F);
    // Metres/blocks: 5 high, 3 wide, 1.2 wrist; .3 gaps separate four tapered fingers.
    static final double[][] HAND = {
        {-.6,-2.5},{.6,-2.5},{.8,-1.3},{1.5,-.25},{1.5,.35},{1.2,.5},
        {.8,-.1},{.8,1.85},{.65,2.05},{.5,1.85},{.5,.15},
        {.2,.15},{.2,2.3},{.05,2.5},{-.1,2.3},{-.1,.15},
        {-.4,.15},{-.4,2.05},{-.55,2.25},{-.7,2.05},{-.7,.05},
        {-1,.05},{-1,1.6},{-1.15,1.8},{-1.3,1.6},{-1.3,-.7}
    };
    private static final int[][] HAND_TRIANGLES = triangulate(HAND);
    private static final double[][][] PALM_PIECES = palmPieces();

    static boolean blow(int kind) {
        return kind == BambooPayload.CONTACT || kind == BambooPayload.ASIDE || kind == BambooPayload.WALL
                || kind == BambooPayload.JAB || kind == BambooPayload.GROVE || kind == BambooPayload.COLLAPSE
                || kind == BambooPayload.RETURN || kind == BambooPayload.LANDING || kind == BambooPayload.KNOCKDOWN;
    }

    static void signature(VertexConsumer v, PoseStack.Pose pose, BambooPayload cue, Vec3 at,
                          Vec3 forward, Vec3 camera, float age, double groundY) {
        int kind = cue.kind();
        if (!blow(kind)) return;
        double tier = Math.clamp((cue.stars() - 1) / 11.0, 0, 1);
        double size = .24 + .76 * tier;
        forward = forward.multiply(1, 0, 1).normalize();
        if (forward.lengthSqr() < .001) forward = new Vec3(0, 0, 1);
        Vec3 right = UP.cross(forward).normalize();
        if (right.lengthSqr() < .01) right = new Vec3(1, 0, 0);
        // Fixed oblique world plane: a palm has area in both the side and rear
        // shots, without rotating with the camera or filling the finger gaps.
        Vec3 across = right.add(forward).normalize();
        if (kind == BambooPayload.JAB) {
            // Pressure points precede the smaller follow-through; preserve the blade silhouette.
            float burstAge = age - 2;
            if (burstAge < 0) return;
            for (int i = 0; i < 8; i++) {
                double t = Math.max(0, burstAge - i * .16);
                int face = i % 2 == 0 ? 1 : -1;
                double launch = 1 - Math.exp(-t / 3);
                Vec3 origin = at.add(forward.scale(face * .32)).add(0, (i % 4 - 1.5) * .12, 0);
                Vec3 position = origin.add(forward.scale(face * launch * (.5 + random(i) * .4)))
                        .add(right.scale((random(i + 3) - .5) * launch * 1.1))
                        .add(0, .18 * launch - .004 * t * t, 0);
                double turn = i * 2.39996 + t * (.12 + random(i) * .08);
                Vec3 direction = right.scale(Math.cos(turn)).add(UP.scale(Math.sin(turn)))
                        .add(forward.scale(Math.sin(turn * .7) * .4));
                float fade = (float) (Math.clamp(t * 2, 0, 1)
                        * Math.pow(Math.clamp((12 + i * 1.7 - burstAge) / 5, 0, 1), 2));
                fallingLeaf(v, pose, position, direction, camera, .32 + random(i + 4) * .12,
                        fade, t, i);
            }
            return;
        }
        if (kind == BambooPayload.WALL) {
            int count = 3 + (int) (8 * tier);
            for (int i = 0; i < count; i++) {
                Vec3 root = at.add(across.scale((i - (count - 1) * .5) * .46 * size))
                        .add(forward.scale(.8));
                root = new Vec3(root.x, groundY, root.z);
                rupture(v, pose, root, age, .65 * size);
                stalk(v, pose, root, camera, age, (5.8 + (i % 3) * .2) * size, i, false, true, age, count);
            }
            palm(v, pose, at.add(forward.scale(.35)).add(0, .9 * size, 0), across,
                    size, age, 26, true);
        } else if (kind == BambooPayload.CONTACT || kind == BambooPayload.ASIDE) {
            double small = kind == BambooPayload.ASIDE ? .4 : .8;
            Vec3 centre = at.add(0, kind == BambooPayload.ASIDE ? .25 : .9 * size, 0)
                    .add(across.scale(kind == BambooPayload.ASIDE ? -.7 * Math.min(1, age / 5) : 0));
            palm(v, pose, centre, across, size * small, age, kind == BambooPayload.CONTACT ? 26 : 20, true, kind == BambooPayload.ASIDE);
        } else if (kind == BambooPayload.GROVE) {
            // Six independent, body-attached miniature palm imprints.
            palm(v, pose, at, across, (.055 + .045 * tier), age,
                    24 - 2 * Math.clamp(cue.duration(), 0, 5));
        } else if (kind == BambooPayload.RETURN) {
            palm(v, pose, at, across, size * .35, age, 12);
        }
        contact(v, pose, at, forward, camera, age);
    }

    /** Subdivide the existing silhouette without changing its filled area or finger gaps. */
    private static double[][][] palmPieces() {
        java.util.List<double[][]> pieces = new java.util.ArrayList<>();
        for (int[] t : HAND_TRIANGLES) {
            double[] a = HAND[t[0]], b = HAND[t[1]], c = HAND[t[2]];
            double[] ab = {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
            double[] bc = {(b[0] + c[0]) / 2, (b[1] + c[1]) / 2};
            double[] ca = {(c[0] + a[0]) / 2, (c[1] + a[1]) / 2};
            pieces.add(new double[][]{a, ab, ca});
            pieces.add(new double[][]{ab, b, bc});
            pieces.add(new double[][]{ca, bc, c});
            pieces.add(new double[][]{ab, bc, ca});
        }
        pieces.sort(java.util.Comparator.comparingDouble(p -> -(p[0][1] + p[1][1] + p[2][1])));
        return pieces.toArray(double[][][]::new);
    }

    private static boolean palmBoundary(double[] a, double[] b) {
        for (int i = 0; i < HAND.length; i++) {
            double[] x = HAND[i], y = HAND[(i + 1) % HAND.length];
            if (Math.abs(cross(x, y, a)) > 1E-8 || Math.abs(cross(x, y, b)) > 1E-8) continue;
            if (Math.min(a[0], b[0]) >= Math.min(x[0], y[0]) - 1E-8
                    && Math.max(a[0], b[0]) <= Math.max(x[0], y[0]) + 1E-8
                    && Math.min(a[1], b[1]) >= Math.min(x[1], y[1]) - 1E-8
                    && Math.max(a[1], b[1]) <= Math.max(x[1], y[1]) + 1E-8) return true;
        }
        return false;
    }

    /** Joined offsets of the counterclockwise HAND boundary, including concave finger valleys.
     * Design: docs/adr/bamboo-round4-2026-11-06.md, corner follow-up.
     * API: read-only minecraft-src/net/minecraft/world/phys/Vec3.java#add,scale,normalize,dot.
     */
    private static Vec3 palmCornerOffset(double[] point, Vec3 across, Vec3 fallback) {
        for (int i = 0; i < HAND.length; i++) {
            double[] corner = HAND[i];
            if (point[0] != corner[0] || point[1] != corner[1]) continue;
            double[] before = HAND[(i + HAND.length - 1) % HAND.length];
            double[] after = HAND[(i + 1) % HAND.length];
            Vec3 incoming = across.scale(corner[1] - before[1])
                    .add(UP.scale(before[0] - corner[0])).normalize();
            Vec3 outgoing = across.scale(after[1] - corner[1])
                    .add(UP.scale(corner[0] - after[0])).normalize();
            // Intersection of the two parallel offset lines, rather than independent
            // endpoint normals which can enter the adjacent finger at a concave corner.
            return incoming.add(outgoing).scale(1 / (1 + incoming.dot(outgoing)));
        }
        return fallback; // Subdivision midpoint lies on a straight boundary edge.
    }

    private static void palm(VertexConsumer v, PoseStack.Pose pose, Vec3 centre,
                             Vec3 across, double size, float age, float end, boolean breakup) {
        palm(v, pose, centre, across, size, age, end, breakup, false);
    }

    private static void palm(VertexConsumer v, PoseStack.Pose pose, Vec3 centre,
                             Vec3 across, double size, float age, float end, boolean breakup, boolean deflect) {
        palm(v, pose, centre, across, size, age, end, breakup, deflect, false, false);
    }

    /** Build palm and shattering, with broad contours only for the mastered presentation. */
    static void masteredPalm(VertexConsumer v, PoseStack.Pose pose, Vec3 centre, Vec3 across, float age, boolean glow) {
        palm(v, pose, centre, across, 1, age, 26, true, true, true, glow);
    }

    private static void palm(VertexConsumer v, PoseStack.Pose pose, Vec3 centre, Vec3 across,
                             double size, float age, float end, boolean breakup, boolean deflect, boolean mastered, boolean glow) {
        if (!breakup) { palm(v, pose, centre, across, size, age, end); return; }
        if (age >= end) return;
        double settle = Math.clamp((age - 5) / 10, 0, 1);
        size *= Math.min(1, .82 + age * .18) * (1 - .08 * settle);
        Vec3 normal = across.cross(UP).normalize();
        for (int i = 0; i < PALM_PIECES.length; i++) {
            double start = deflect ? BambooEnding.pieceStart(end, i, PALM_PIECES.length)
                    : BambooEnding.start(end - 20, end, i, PALM_PIECES.length);
            float fade = deflect ? BambooEnding.pieceFade(age, start) : BambooEnding.fade(age, start);
            if (fade <= 0) continue;
            double t = Math.max(0, age - start);
            double morph = Math.clamp(t / 2, 0, 1);
            double[][] p = PALM_PIECES[i];
            Vec3 a = point(centre, across, UP, p[0], size);
            Vec3 b = point(centre, across, UP, p[1], size);
            Vec3 c = point(centre, across, UP, p[2], size);
            Vec3 mid = a.add(b).add(c).scale(1.0 / 3);
            Vec3 drift = across.scale((random(i) - .5) * t * .12)
                    .add(normal.scale(t * .035)).add(0, -.012 * t * t, 0);
            // Deflect pieces retain full opacity until their centres travel > half a block.
            // Wind bends/rolls each shard independently; travel is in world blocks at every tier.
            double turn = deflect ? t * (.22 + random(i + 5) * .14) : 0;
            if (deflect) drift = normal.scale(BambooEnding.pieceDistance(age, start))
                    .add(across.scale(Math.sin(i * 2.39996 + t * .3) * t * .07))
                    .add(0, .065 * t + .04 * Math.sin(t * .8 + i) * t, 0);
            Vec3 axis = across.scale(.3 * Math.cos(turn) - .7 * Math.sin(turn))
                    .add(UP.scale(.7 * Math.cos(turn) + .3 * Math.sin(turn)))
                    .normalize().scale(size * .32);
            Vec3 width = axis.cross(normal).normalize().scale(size * .12)
                    .add(normal.scale(deflect ? Math.sin(turn) * size * .06 : 0));
            boolean segment = deflect && i % 3 == 0;
            Vec3 q0 = a.lerp(segment ? mid.subtract(axis).subtract(width) : mid.subtract(axis), morph).add(drift);
            Vec3 q1 = b.lerp(segment ? mid.subtract(axis).add(width) : mid.add(width), morph).add(drift);
            Vec3 q2 = c.lerp(segment ? mid.add(axis).add(width) : mid.add(axis), morph).add(drift);
            Vec3 q3 = c.lerp(segment ? mid.add(axis).subtract(width) : mid.subtract(width), morph).add(drift);
            Vec3 depth = normal.scale(mastered ? Math.max(.04, .38 * size * (1 - morph * .9)) : .38 * size * (1 - morph * (deflect ? .9 : 1)));
            if (mastered && glow) {
                // One exterior strip per silhouette edge, never the closed palm volume.
                // NO_CULL + COLOR_WRITE in TechniqueGlow otherwise adds front, back and sides
                // on the same pixels, then BtGlow adds both blurred copies over the mint body.
                Vec3[] q = {q0, q1, q2, q3};
                for (int edge = 0; edge < 3; edge++) {
                    if (!palmBoundary(p[edge], p[(edge + 1) % 3])) continue;
                    Vec3 x = q[edge == 2 ? 3 : edge], y = q[(edge + 1) % 3];
                    Vec3 outward = normal.cross(y.subtract(x)).normalize();
                    Vec3 inside = q0.add(q1).add(q2).scale(1.0 / 3).subtract(x);
                    if (outward.dot(inside) > 0) outward = outward.scale(-1);
                    // Joined corners while intact; transition to each departing shard's normal.
                    Vec3 ox = palmCornerOffset(p[edge], across, outward).scale(1 - morph).add(outward.scale(morph));
                    Vec3 oy = palmCornerOffset(p[(edge + 1) % 3], across, outward).scale(1 - morph).add(outward.scale(morph));
                    VfxDraw.quad(v, pose, x.add(ox.scale(.04)), y.add(oy.scale(.04)),
                            y.add(oy.scale(.10)), x.add(ox.scale(.10)), normal, fade * .08F, EDGE);
                }
                continue;
            }
            VfxDraw.quad(v, pose, q0.add(depth), q1.add(depth), q2.add(depth), q3.add(depth), normal, fade, mastered ? MINT : p[1][0] > .1 ? LIGHT : JADE);
            VfxDraw.quad(v, pose, q0.subtract(depth), q1.subtract(depth), q2.subtract(depth), q3.subtract(depth), normal, deflect ? fade : fade * (float) (1 - morph), mastered ? MINT : INK);
            Vec3[] q = {q0, q1, q2, q3};
            // Preserve the original ink contour and pale lip without outlining internal seams.
            for (int edge = 0; edge < 3; edge++) {
                if (!palmBoundary(p[edge], p[(edge + 1) % 3])) continue;
                Vec3 x = q[edge == 2 ? 3 : edge], y = q[(edge + 1) % 3];
                Vec3 inset = normal.cross(y.subtract(x)).normalize().scale(mastered ? .08 : .055 * size);
                for (int face = -1; face <= 1; face += 2) {
                    Vec3 a0 = x.add(depth.scale(face)), b0 = y.add(depth.scale(face));
                    VfxDraw.quad(v, pose, a0, b0, b0.add(inset), a0.add(inset), normal, fade, mastered ? EDGE : INK);
                    VfxDraw.quad(v, pose, a0.add(inset), b0.add(inset), b0.add(inset.scale(mastered ? 1.2 : 1.45)),
                            a0.add(inset.scale(mastered ? 1.2 : 1.45)), normal, fade, mastered ? IVORY : LIGHT);
                }
            }
            for (int edge = 0; edge < 4; edge++) {
                Vec3 x = q[edge], y = q[(edge + 1) % 4];
                VfxDraw.quad(v, pose, x.subtract(depth), y.subtract(depth), y.add(depth), x.add(depth), across, fade, mastered ? MINT : LIGHT);
            }
        }
    }

    private static void palm(VertexConsumer v, PoseStack.Pose pose, Vec3 centre,
                             Vec3 across, double size, float age, float end) {
        float fade = (float) Math.clamp((end - age) / 8, 0, 1);
        if (fade <= 0) return;
        // Arrive in one tick, hold the strongest silhouette for four, then recede.
        double settle = Math.clamp((age - 5) / 10, 0, 1);
        size *= Math.min(1, .82 + age * .18) * (1 - .08 * settle);
        Vec3 normal = across.cross(UP).normalize();
        // Solid front/back triangles and closed side walls, two-tone jade.
        for (int face = -1; face <= 1; face += 2) {
            Vec3 c = centre.add(normal.scale(face * .38 * size));
            for (int[] t : HAND_TRIANGLES) {
                VfxDraw.quad(v, pose, point(c, across, UP, HAND[t[0]], size),
                        point(c, across, UP, HAND[t[1]], size),
                        point(c, across, UP, HAND[t[2]], size),
                        point(c, across, UP, HAND[t[2]], size), normal, fade,
                        face < 0 ? INK : HAND[t[1]][0] > .1 ? LIGHT : JADE);
            }
            for (int i = 0; i < HAND.length; i++) {
                Vec3 a = point(c, across, UP, HAND[i], size);
                Vec3 b = point(c, across, UP, HAND[(i + 1) % HAND.length], size);
                Vec3 inset = normal.cross(b.subtract(a)).normalize().scale(.055 * size);
                // A dark contour with a pale inner lip on each material face.
                VfxDraw.quad(v, pose, a, b, b.add(inset), a.add(inset), normal, fade, INK);
                VfxDraw.quad(v, pose, a.add(inset), b.add(inset), b.add(inset.scale(1.45)),
                        a.add(inset.scale(1.45)), normal, fade, LIGHT);
                if (face == -1) {
                    Vec3 depth = normal.scale(.76 * size);
                    // Broad shoulder bevel exposes a lit edge beside a dark, deep side.
                    Vec3 bevel = depth.scale(.82);
                    VfxDraw.quad(v, pose, a, b, b.add(bevel), a.add(bevel), across, fade, INK);
                    VfxDraw.quad(v, pose, a.add(bevel), b.add(bevel), b.add(depth), a.add(depth),
                            across, fade, i % 3 == 0 ? JADE : LIGHT);
                }
            }
        }
    }

    private static void contact(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 forward,
                                Vec3 camera, float age) {
        if (age >= 3) return;
        ImpactFlash.draw(v, pose, at.add(forward.scale(.04)), camera, age / 3, .22 / (1 + 2.8 * age / 3), HOT, JADE);
    }

    static void sword(VertexConsumer v, PoseStack.Pose pose, Vec3 finger, Vec3 forward,
                      float age, double reach) {
        // Three-tick lunge, two-tick hold, then withdrawal within the original clip.
        float alpha = (float) Math.clamp((11 - age) / 4, 0, 1);
        double thrust = 1 - Math.pow(1 - Math.clamp(age / 3, 0, 1), 2);
        double retract = Math.clamp((11 - age) / 6, 0, 1);
        Vec3 side = forward.cross(UP).normalize();
        if (side.lengthSqr() < .01) side = new Vec3(1, 0, 0);
        Vec3 ridge = forward.cross(side).normalize();
        // Stack the broad faces between index/middle roots: open gap in both oblique views.
        Vec3 spread = ridge.add(side.scale(.65)).normalize();
        for (int fingerIndex = 0; fingerIndex < 2; fingerIndex++) {
            double length = Math.max(0, reach - fingerIndex * .06) * thrust * retract;
            Vec3 root = finger.add(spread.scale((fingerIndex - .5) * .46));
            Vec3 tip = root.add(forward.scale(length));
            // A single closed triangular wedge; every face ends at the SAME point.
            // Insets lie on its surface, so the rim surrounds a broad core without a tip spike.
            Vec3 width = ridge.scale(.20);
            Vec3 depth = side.scale(.075);
            for (int face = -1; face <= 1; face += 2) {
                Vec3 a = root.subtract(width).add(depth.scale(face));
                Vec3 b = root.add(width).add(depth.scale(face));
                Vec3 centre = a.add(b).add(tip).scale(1.0 / 3);
                Vec3 ia = a.lerp(centre, .16), ib = b.lerp(centre, .16);
                Vec3 it = tip.lerp(centre, .16);
                VfxDraw.quad(v, pose, ia, ib, it, it, side, alpha, JADE);
                VfxDraw.quad(v, pose, a, b, ib, ia, side, alpha, HOT);
                VfxDraw.quad(v, pose, b, tip, it, ib, side, alpha, HOT);
                VfxDraw.quad(v, pose, tip, a, ia, it, side, alpha, HOT);
                // Nested shells fade outwards; the solid wedge remains the dominant shape.
                for (int shell = 3; shell >= 1; shell--) {
                    Vec3 offset = depth.scale(face * (1 + shell * .35));
                    Vec3 wa = root.subtract(width.scale(1 + shell * .18)).add(offset);
                    Vec3 wb = root.add(width.scale(1 + shell * .18)).add(offset);
                    VfxDraw.quad(v, pose, wa, wb, tip, tip, side,
                            alpha * .025F * (4 - shell), JADE);
                }
            }
            VfxDraw.quad(v, pose, root.add(width).add(depth), root.add(width).subtract(depth),
                    tip, tip, ridge, alpha, JADE);
            VfxDraw.quad(v, pose, root.subtract(width).add(depth), root.subtract(width).subtract(depth),
                    tip, tip, ridge, alpha, JADE);
            VfxDraw.quad(v, pose, root.subtract(width).subtract(depth), root.add(width).subtract(depth),
                    root.add(width).add(depth), root.subtract(width).add(depth), forward, alpha, JADE);
        }
    }

    static void bending(VertexConsumer v, PoseStack.Pose pose, Vec3 root, Vec3 camera, float age, int stars) {
        double tier = Math.clamp((stars - 1) / 11.0, 0, 1);
        stalk(v, pose, root.add(.6, 0, 0), camera, age, 2 + tier * 6.4, 0, true, false);
    }

    static void grove(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 camera, float age, int stars) {
        if (stars < 1) return;
        double tier = Math.clamp((stars - 1) / 11.0, 0, 1);
        int count = 12 + (int) (tier * 4);
        for (int ring = 0; ring < 3; ring++) for (int i = 0; i < count; i++) {
            double angle = (i + .5 + ring * .37) * Math.PI * 2 / count;
            double radius = (1.2 + tier * 2.3) * (1 - ring * .20);
            Vec3 root = at.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            // Outer ring keeps round 3's maximum height; inner tiers frame the opponent.
            double height = (2.6 + random(i + 7)) * (1 + tier * 3) * (1 - ring * .22);
            float localAge = age - i * 3F / count - ring * .7F;
            stalk(v, pose, root, camera, localAge, height, i + ring * count, false, false, age, count * 3);
            rupture(v, pose, root, localAge, .55);
            double crown = height * Math.min(1, Math.max(0, localAge) / 6);
            if (localAge > 0) for (int leaf = 0; leaf < 9; leaf++) {
                float fade = BambooGroveEnding.pieceFade(age, leaf, 9 + (int) Math.ceil(height) / 2 + (int) Math.ceil(height),
                        groveOrder(i + ring * count, count * 3), count * 3)
                        * (float) Math.clamp(localAge / 2, 0, 1);
                if (fade <= 0) continue;
                double a = leaf * 2.39996 + i;
                Vec3 direction = new Vec3(Math.cos(a), .15, Math.sin(a));
                blade(v, pose, root.add(0, crown, 0).add(direction.scale(.45)), direction,
                        camera, .45 + random(leaf + i) * .25, fade, leaf + i);
            }
        }
    }

    /** Bounded ground cracks and ballistic stone chips; purely rendered geometry. */
    private static void rupture(VertexConsumer v, PoseStack.Pose pose, Vec3 root,
                                float age, double extent) {
        if (age < 0 || age > 24) return;
        float fade = (float) (Math.clamp((24 - age) / 9, 0, 1) * Math.min(1, age * 2));
        for (int i = 0; i < 7; i++) {
            double angle = i * 2.39996;
            Vec3 direction = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            Vec3 side = direction.cross(UP).scale(.045);
            Vec3 end = root.add(direction.scale(extent * (.6 + random(i))));
            VfxDraw.quad(v, pose, root.add(side).add(0, .025, 0), end.add(0, .025, 0),
                    end.add(0, .025, 0), root.subtract(side).add(0, .025, 0), UP, fade, INK);
            double t = Math.min(age, 12) / 12;
            float chipFade = (float) (fade * Math.clamp((14 - age - random(i) * 3) / 5, 0, 1));
            Vec3 chip = root.add(direction.scale(t * extent)).add(0, .08 + Math.sin(t * Math.PI) * .6, 0);
            Vec3 width = side.scale(2 + random(i));
            VfxDraw.quad(v, pose, chip.subtract(width), chip.add(0, .18, 0), chip.add(width),
                    chip.add(0, -.08, 0), UP, chipFade, i % 2 == 0 ? STONE : SHALE);
        }
    }

    private static void stalk(VertexConsumer v, PoseStack.Pose pose, Vec3 root, Vec3 camera,
                              float age, double height, int seed, boolean diagonal, boolean wall) {
        stalk(v, pose, root, camera, age, height, seed, diagonal, wall, age, 0);
    }

    private static void stalk(VertexConsumer v, PoseStack.Pose pose, Vec3 root, Vec3 camera,
                              float age, double height, int seed, boolean diagonal, boolean wall,
                              float groveAge, int stalkCount) {
        boolean dissolve = !wall && !diagonal;
        if (age < 0 || (!dissolve && age >= 32)) return;
        // Wall rises in six ticks; full-length pieces then fall/fade on separate deadlines.
        // Grove also keeps its grown height throughout dissolution.
        double growth = Math.min(1, (age + (wall ? 1.8 : 0)) / 6);
        double retract = (dissolve || wall) ? 1 : Math.clamp((32 - age) / 8, 0, 1);
        double visible = height * growth * retract;
        float fade = (float) Math.min(1, retract * 3);
        Vec3 bend = new Vec3(diagonal ? Math.sin(age * .12) * .4 : .025 * Math.sin(seed), 1, 0).normalize();
        int nodes = (int) Math.ceil(height);
        double radius = wall ? .27 : .225 + random(seed) * .1;
        for (int n = 0; n < nodes && n < visible; n++) {
            Vec3 a = root.add(bend.scale(n));
            Vec3 b = root.add(bend.scale(Math.min(n + 1, visible)));
            float nodeFade = wall ? BambooEnding.fade(age,
                    BambooEnding.start(6, 26, (nodes - 1 - n) * stalkCount + seed, nodes * stalkCount))
                    : dissolve ? BambooGroveEnding.pieceFade(groveAge,
                    9 + nodes / 2 + nodes - 1 - n, 9 + nodes / 2 + nodes,
                    groveOrder(seed, stalkCount), stalkCount) : fade;
            if (wall) {
                double t = Math.max(0, age - (BambooEnding.start(6, 26,
                        (nodes - 1 - n) * stalkCount + seed, nodes * stalkCount) - 4));
                Vec3 drift = new Vec3(Math.sin(seed) * t * .08, .14 * t, Math.cos(seed) * t * .08);
                Vec3 middle = a.add(b).scale(.5).add(drift);
                double half = Math.min(1, visible - n) * .5;
                Vec3 tilt = new Vec3(Math.sin(t * .3) * Math.cos(seed), Math.cos(t * .3),
                        Math.sin(t * .3) * Math.sin(seed)).scale(half);
                a = middle.subtract(tilt); b = middle.add(tilt);
            }
            if (nodeFade <= 0) continue;
            tube(v, pose, a, b, radius, nodeFade, false, seed);
            tube(v, pose, a, a.add((wall ? b.subtract(a).normalize() : bend).scale(Math.min(.10, visible - n))),
                    radius + .04, nodeFade, false, seed + 1);
            if (!wall && n % 2 == 1) blade(v, pose, b,
                    new Vec3(Math.sin(seed + n), .3, Math.cos(seed + n)), camera, .25, dissolve ? BambooGroveEnding.pieceFade(groveAge,
                            9 + (nodes - 1 - n) / 2, 9 + nodes / 2 + nodes,
                            groveOrder(seed, stalkCount), stalkCount) : nodeFade, seed);
        }
    }

    // Coprime permutation for counts 36/39/42/45/48: neighbours never vanish as a batch.
    private static int groveOrder(int stalk, int count) {
        return stalk * 17 % count;
    }

    static int groveLifetime() {
        return BambooGroveEnding.END_TICK;
    }

    static void pointFlash(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 camera, float fade) {
        if (fade <= 0) return;
        Vec3 normal = camera.subtract(at).normalize();
        Vec3 side = normal.cross(new Vec3(0, 1, .01)).normalize();
        Vec3 up = side.cross(normal).normalize();
        for (int ring = 2; ring >= 0; ring--) for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12, b = (i + 1) * Math.PI / 12;
            double radius = .065 + ring * .035;
            Vec3 p = at.add(side.scale(Math.cos(a) * radius)).add(up.scale(Math.sin(a) * radius));
            Vec3 q = at.add(side.scale(Math.cos(b) * radius)).add(up.scale(Math.sin(b) * radius));
            VfxDraw.quad(v, pose, at, p, q, q, normal, fade * (ring == 0 ? .85F : .12F),
                    ring == 0 ? LIGHT : JADE);
        }
    }

    private static void fallingLeaf(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 direction,
                                    Vec3 camera, double length, float alpha, double age, int seed) {
        Vec3 normal = camera.subtract(at).normalize();
        Vec3 axis = direction.subtract(normal.scale(direction.dot(normal))).normalize();
        if (axis.lengthSqr() < .001) axis = normal.cross(UP).normalize();
        if (axis.lengthSqr() < .001) axis = new Vec3(1, 0, 0);
        Vec3 across = axis.cross(normal).normalize();
        // Roll without collapsing to a needle: the existing direction also turns in-plane.
        double roll = age * .24 + seed;
        Vec3 widthAxis = across.scale(.90 + .10 * Math.cos(roll))
                .add(normal.scale(.32 * Math.sin(roll)));
        // Continuous lanceolate contour, four times longer than its maximum width.
        // Adjacent convex strips share their midrib; no crossing/concave star quads.
        for (int section = 0; section < 8; section++) {
            double u = section / 8.0, next = (section + 1) / 8.0;
            double w0 = length * .25 * Math.sin(Math.PI * u);
            double w1 = length * .25 * Math.sin(Math.PI * next);
            Vec3 c0 = at.add(axis.scale(length * (2 * u - 1)))
                    .add(across.scale(length * .09 * Math.sin(Math.PI * u)));
            Vec3 c1 = at.add(axis.scale(length * (2 * next - 1)))
                    .add(across.scale(length * .09 * Math.sin(Math.PI * next)));
            VfxDraw.quad(v, pose, c0, c0.add(widthAxis.scale(w0)),
                    c1.add(widthAxis.scale(w1)), c1, normal, alpha, JADE);
            VfxDraw.quad(v, pose, c0, c1, c1.subtract(widthAxis.scale(w1)),
                    c0.subtract(widthAxis.scale(w0)), normal, alpha, LIGHT);
            // A narrow, curved vein follows the shared centre, tapered at both tips.
            Vec3 vein0 = across.scale(w0 * .07), vein1 = across.scale(w1 * .07);
            Vec3 lift = normal.scale(.002);
            VfxDraw.quad(v, pose, c0.subtract(vein0).add(lift), c1.subtract(vein1).add(lift),
                    c1.add(vein1).add(lift), c0.add(vein0).add(lift), normal, alpha, HOT);
        }
    }

    private static void tube(VertexConsumer v, PoseStack.Pose pose, Vec3 a, Vec3 b, double radius,
                             float alpha, boolean half, int seed) {
        Vec3 axis = b.subtract(a).normalize();
        Vec3 x = axis.cross(new Vec3(.1, 1, .13)).normalize().scale(radius);
        Vec3 y = axis.cross(x).normalize().scale(radius);
        for (int i = 0; i < (half ? 4 : 8); i++) {
            double p = i * Math.PI / 4, q = (i + 1) * Math.PI / 4;
            Vec3 r = x.scale(Math.cos(p)).add(y.scale(Math.sin(p)));
            Vec3 s = x.scale(Math.cos(q)).add(y.scale(Math.sin(q)));
            VfxColour colour = i < 3 ? INK : i == 5 ? LIGHT : JADE;
            VfxDraw.quad(v, pose, a.add(r), b.add(r), b.add(s), a.add(s), r.normalize(), alpha, colour);
            // Hollow cut ends on half-tubes; closed solid segments on full prisms.
            if (!half) VfxDraw.quad(v, pose, b, b.add(s), b.add(r), b, axis, alpha, LIGHT);
        }
    }

    static int storm(VertexConsumer v, PoseStack.Pose pose, BambooPayload cue, Vec3 at, Vec3 forward,
                     Vec3 camera, float age, int allowance, double quality) {
        if (cue.kind() == BambooPayload.GROUND || cue.kind() == BambooPayload.KNOCKDOWN) return 0;
        boolean grove = cue.kind() == BambooPayload.GROVE_RING;
        if (!grove || age < 18 || age >= 54) return 0;
        // The crown withdraws into a storm; overlap the first falling leaves with stalks.
        double tier = Math.clamp((cue.stars() - 1) / 11.0, 0, 1);
        double extent = (1 + tier * 3) / 4;
        return ribbons(v, pose, at.add(0, 2 * extent, 0), forward, camera, age - 18,
                allowance, quality * (.08 + .92 * tier), false, null, null, 0, extent);
    }

    static int ribbons(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 forward,
                       Vec3 camera, float age, int allowance, double quality, boolean wind,
                       Vec3 turnAt, Vec3 turnForward, float turnAge, double extent) {
        int count = Math.min(allowance, (int) (2048 * quality));
        if (forward.lengthSqr() < .001) forward = new Vec3(0, 0, 1);
        Vec3 right = UP.cross(forward).normalize();
        for (int i = 0; i < count; i++) {
            int stream = i % 5;
            double seed = random(i + 41);
            double end = wind ? 26 + seed * 6 : 24 + seed * 12;
            double fadeAge = turnAt == null ? age : turnAge;
            if (turnAt != null) end = 18 + seed * 6;
            float fade = (float) (Math.clamp((end - fadeAge) / 10, 0, 1)
                    * Math.clamp((age - seed * 3) / 3, 0, 1));
            if (fade <= 0) continue;
            double t = (i / 5.0) / Math.max(1, count / 5.0);
            // Wind shares one C-shaped spine; five lanes are thickness, not separate vortices.
            double angle = wind ? -.6 + t * 4.2 + age * .07
                    : stream * Math.PI * 2 / 5 + t * 3.8 + age * .12;
            double radius = (wind ? 2.4 + stream * .08 : 3.5) * extent + .42 * extent * (random(i + 19) - .5);
            double reach = wind ? 8 * extent * Math.min(1, age / 12) * t * t : 0;
            double taper = wind ? 1 - .9 * t * Math.min(1, age / 12) : 1;
            Vec3 radial = right.scale(Math.cos(angle)).add(forward.scale(Math.sin(angle)));
            double height = wind ? (t - .5) * 2 * extent : (t * 10 + 1) * extent * (1 - .6 * Math.min(1, age / 22));
            Vec3 pos = at.add(radial.scale(radius * taper)).add(forward.scale(reach))
                    .add(0, height + .35 * (seed - .5), 0);
            Vec3 tangent = right.scale(-Math.sin(angle)).add(forward.scale(Math.cos(angle)))
                    .add(forward.scale(wind ? t * 2 : 0)).add(0, .15, 0);
            if (turnAt != null) {
                Vec3 turnUp = UP.subtract(turnForward.scale(turnForward.y)).normalize();
                Vec3 turnSide = turnForward.cross(turnUp).normalize();
                double travel = (t + turnAge * .045) % 1;
                double curve = travel * Math.PI;
                double bendRadius = (.55 + stream * .12) * extent;
                Vec3 bent = turnAt.add(turnForward.scale(-Math.sin(curve) * bendRadius))
                        .add(turnUp.scale((1 - Math.cos(curve)) * bendRadius))
                        .add(turnSide.scale((seed - .5) * .45));
                double blend = 1 - Math.pow(1 - Math.min(1, turnAge / 4), 3);
                pos = pos.lerp(bent, blend);
                tangent = tangent.lerp(turnForward.scale(-Math.cos(curve))
                        .add(turnUp.scale(Math.sin(curve))), blend);
                fade *= (float) (1 - blend + blend * Math.min(1, Math.sin(curve) * 4));
            }
            double length = .15 + seed * .15;
            blade(v, pose, pos, tangent, camera, length, fade, i);
        }
        return count;
    }

    private static void blade(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 direction,
                              Vec3 camera, double length, float alpha, int seed) {
        Vec3 normal = camera.subtract(at).normalize();
        Vec3 projected = direction.subtract(normal.scale(direction.dot(normal))).normalize();
        if (projected.lengthSqr() < .001) projected = normal.cross(new Vec3(0, 1, .1)).normalize();
        Vec3 axis = projected.scale(length);
        Vec3 width = axis.cross(normal).normalize().scale(length * .20);
        Vec3 base = at.subtract(axis), tip = at.add(axis), shoulder = at.subtract(axis.scale(.3));
        // Fold the midrib away from the underside; long points replace flat diamonds.
        Vec3 ridge = at.add(camera.subtract(at).normalize().scale(length * .045));
        VfxDraw.quad(v, pose, base, shoulder.add(width), tip, ridge, UP, alpha, seed % 2 == 0 ? LIGHT : JADE);
        VfxDraw.quad(v, pose, base, ridge, tip, shoulder.subtract(width), UP, alpha, INK);
    }
    // Ear clipping preserves the gaps between fingers; a centre fan fills concave notches.
    static int[][] triangulate(double[][] polygon) {
        java.util.List<Integer> vertices = new java.util.ArrayList<>();
        java.util.List<int[]> result = new java.util.ArrayList<>();
        for (int i = 0; i < polygon.length; i++) vertices.add(i);
        while (vertices.size() > 3) {
            boolean clipped = false;
            for (int j = 0; j < vertices.size(); j++) {
                int a = vertices.get((j + vertices.size() - 1) % vertices.size());
                int b = vertices.get(j), c = vertices.get((j + 1) % vertices.size());
                if (cross(polygon[a], polygon[b], polygon[c]) <= 0) continue;
                boolean inside = false;
                for (int k : vertices) if (k != a && k != b && k != c
                        && cross(polygon[a], polygon[b], polygon[k]) >= 0
                        && cross(polygon[b], polygon[c], polygon[k]) >= 0
                        && cross(polygon[c], polygon[a], polygon[k]) >= 0) { inside = true; break; }
                if (inside) continue;
                result.add(new int[]{a, b, c}); vertices.remove(j); clipped = true; break;
            }
            if (!clipped) throw new IllegalArgumentException("Invalid Bamboo palm silhouette");
        }
        result.add(new int[]{vertices.get(0), vertices.get(1), vertices.get(2)});
        return result.toArray(int[][]::new);
    }
    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }
    private static Vec3 point(Vec3 at, Vec3 right, Vec3 up, double[] p, double size) {
        return at.add(right.scale(p[0] * size)).add(up.scale(p[1] * size));
    }
    private static double random(int seed) { double n = Math.sin(seed * 127.1 + 311.7) * 43758.5453; return n - Math.floor(n); }
    private BambooScale() { }
}
