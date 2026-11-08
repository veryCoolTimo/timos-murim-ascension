package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/** Painted 4x3 Rain atlas, folded once; never use the legacy 2x2 UV helper here.
 * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java
 * #subtract,normalize,cross,scale,add,distanceTo (1.21.1, read-only source).
 */
// [НЕПРОВЕРЕНО: night/side/first-person visual acceptance, including <=15% hottest
// painted area and fighter visibility; requires the tester's game capture, prohibited here.]
final class SeaScatterPetals {
    static void draw(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3 at, double size,
                     int slot, float spin, float alpha, float red, float green, float blue) {
        Vec3 normal = camera.subtract(at);
        if (normal.lengthSqr() < .36 || alpha <= 0) return;
        normal = normal.normalize();
        Vec3 right = normal.cross(Math.abs(normal.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(normal).normalize();
        Vec3 r = right.scale(Math.cos(spin)).add(up.scale(Math.sin(spin)));
        Vec3 u = up.scale(Math.cos(spin)).subtract(right.scale(Math.sin(spin)));
        drawPlane(v, pose, at, r, u, normal, size, slot, alpha, red, green, blue);
    }

    static void drawPlane(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 right, Vec3 up, Vec3 normal,
                          double size, int slot, float alpha, float red, float green, float blue) {
        int cell = SeaScatterFinish.cell(slot);
        int pigment = PlumPolish.pigment(slot, false);
        if (pigment == 1) { red *= .29F; green *= .05F; blue *= .13F; }
        else if (pigment == 0) { red *= .89F; green *= .15F; blue *= .306F; }
        Vec3 r = right.scale(size), u = up.scale(size * .6), ridge = normal.scale(size * .1);
        float u0 = cell % 4 / 4F, u1 = u0 + .25F, mid = (u0 + u1) / 2;
        float v0 = cell / 4 / 3F, v1 = v0 + 1 / 3F;
        // The painted face supplies its light core and wine rim; backs occupy exactly one third.
        quad(v, pose, at.subtract(r).subtract(u), at.subtract(r).add(u), at.add(u).add(ridge), at.subtract(u).add(ridge),
                normal, u0, mid, v0, v1, alpha, red, green, blue);
        quad(v, pose, at.subtract(u).add(ridge), at.add(u).add(ridge), at.add(r).add(u), at.add(r).subtract(u),
                normal, mid, u1, v0, v1, alpha, red * .85F, green * .85F, blue * .85F);
    }

    private static void quad(VertexConsumer v, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 n,
                             float u0, float u1, float v0, float v1, float alpha, float red, float green, float blue) {
        VfxDraw.vertex(v, pose, a, n, u0, v1, alpha, red, green, blue);
        VfxDraw.vertex(v, pose, b, n, u0, v0, alpha, red, green, blue);
        VfxDraw.vertex(v, pose, c, n, u1, v0, alpha, red, green, blue);
        VfxDraw.vertex(v, pose, d, n, u1, v1, alpha, red, green, blue);
    }
    private SeaScatterPetals() {}
}
