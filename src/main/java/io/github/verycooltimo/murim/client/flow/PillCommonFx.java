package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * The short version of the ordinary (common) pill (author 15.11: «в место голой линии»): a warm flash on the chest, a push of dust over the floor
 * and a couple of seconds of haze in the pill's colour with a few embers rising in the wind. Closed-form in the seconds since the start, so nothing is
 * simulated and nothing is kept between frames. Soft motes only (no lines, no rings), nothing on the face or above the collar.
 */
final class PillCommonFx {
    private static final int DUST = 60, EMBERS = 36;

    private PillCommonFx() { }

    /** Haze 0..1 over the whole frame: in over about half a second, thinning out by 2.6 s. */
    static float haze(double t) {
        return (float) (PillScenePlan.smooth((float) ((t - .15) / .45)) * (1 - PillScenePlan.smooth((float) ((t - 1.6) / 1.6))));
    }

    static void draw(Minecraft mc, FlowBodySurface s, PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, double t, float visibility, int colour) {
        Vec3 bot = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_BOTTOM), top = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_TOP);
        if (bot == null || top == null || visibility <= 0) return;
        Vec3 front = s.front();
        double unit = s.unit();
        Vec3 chest = s.pt(0, 8.2).add(front.scale(.03)), belly = s.pt(0, 3.4).add(front.scale(.03));
        double floor = mc.player.getY() + .03;
        int warm = lerp(colour, 0xFFC27A, .45F), light = lerp(colour, 0xFFFFFF, .45F);
        VertexConsumer c = buffers.getBuffer(MurimRenderTypes.flowMote());
        // the warm flash on the chest: up in 0.2 s, out in 0.9 s, with the belly answering a little later
        float fl = (float) (PillScenePlan.smooth((float) ((t - .1) / .2)) * (1 - PillScenePlan.smooth((float) ((t - .35) / .9))));
        mote(c, pose, cam, chest, .75 * unit * 8 * (.6 + .4 * fl), fl * .7F * visibility, warm);
        mote(c, pose, cam, chest, .4 * unit * 8 * (.6 + .4 * fl), fl * 1F * visibility, lerp(warm, 0xFFFFFF, .5F));
        float bl = (float) (PillScenePlan.smooth((float) ((t - .3) / .3)) * (1 - PillScenePlan.smooth((float) ((t - .8) / 1.4))));
        mote(c, pose, cam, belly, .4, bl * .6F * visibility, warm);
        Random r = new Random(7);
        // the push of dust: thrown out over the floor from the seat at 0.3 s
        double tau = t - .3;
        for (int i = 0; i < DUST; i++) {
            double a = r.nextDouble() * Math.PI * 2, v = 1.1 + r.nextDouble() * 1.6, size = .035 + r.nextDouble() * .05;
            if (tau <= 0) continue;
            double run = v * (1 - Math.exp(-2.4 * tau)) / 2.4 + .22, life = 1.3 + r.nextDouble() * .7;
            float k = (float) (tau / life);
            if (k >= 1) continue;
            Vec3 at = new Vec3(belly.x + Math.cos(a) * run, floor + .02 + tau * .06 * r.nextDouble(), belly.z + Math.sin(a) * run);
            mote(c, pose, cam, at, size * (1 + 1.6 * k), (1 - k) * .6F * visibility, lerp(colour, 0xFFFFFF, .3F));
        }
        // embers rise from the chest in the wind
        for (int i = 0; i < EMBERS; i++) {
            double born = .3 + r.nextDouble() * 1.3, life = 1.0 + r.nextDouble() * .8, e = t - born;
            double ox = (r.nextDouble() - .5) * 5.5, ou = 3.5 + r.nextDouble() * 6.5, size = .005 + r.nextDouble() * .006, wind = .15 + r.nextDouble() * .25, rise = .25 + r.nextDouble() * .4;
            if (e <= 0 || e >= life) continue;
            float k = (float) (e / life);
            Vec3 at = s.pt(ox, Math.min(ou, 11.0)).add(front.scale(.05)).add(s.rightVec().scale(wind * e)).add(s.upVec().scale(rise * e));
            // nothing above the collar
            if (at.subtract(s.pt(0, 0)).dot(s.upVec()) > 11.6 * unit) continue;
            float a = (float) Math.sin(Math.PI * k) * visibility;
            mote(c, pose, cam, at, size * 3.2, a * .5F, light);
            mote(c, pose, cam, at, size * 1.3, a, 0xFFFFFF);
        }
        buffers.endBatch(MurimRenderTypes.flowMote());
    }

    private static int lerp(int a, int b, float k) {
        int r = (int) Mth.lerp(k, (a >> 16) & 255, (b >> 16) & 255), g = (int) Mth.lerp(k, (a >> 8) & 255, (b >> 8) & 255), bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    private static void mote(VertexConsumer c, PoseStack.Pose pose, Vec3 cam, Vec3 centre, double h, float alpha, int rgb) {
        if (alpha <= .01F || h <= 0) return;
        Vec3 toCam = cam.subtract(centre);
        if (toCam.lengthSqr() < 1e-8) return;
        Vec3 fw = toCam.normalize();
        Vec3 ref = Math.abs(fw.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 r = fw.cross(ref).normalize().scale(h);
        Vec3 u = r.normalize().cross(fw).normalize().scale(h);
        float cr = ((rgb >> 16) & 255) / 255F, cg = ((rgb >> 8) & 255) / 255F, cb = (rgb & 255) / 255F;
        VfxDraw.vertex(c, pose, centre.subtract(r).subtract(u), fw, 0, 1, alpha, cr, cg, cb);
        VfxDraw.vertex(c, pose, centre.add(r).subtract(u), fw, 1, 1, alpha, cr, cg, cb);
        VfxDraw.vertex(c, pose, centre.add(r).add(u), fw, 1, 0, alpha, cr, cg, cb);
        VfxDraw.vertex(c, pose, centre.subtract(r).add(u), fw, 0, 0, alpha, cr, cg, cb);
    }
}
