package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.client.vfx.MeridianFlow;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxColour;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Draws the pill absorption scene on the seated body: the pill, the sternum orb and the falling dust, the bands of the route
 * (belly, up the centre line, fork to the shoulders) with their running orbs and the impurities drifting out, the sphere of the
 * core with its breath, the dust ring on the floor. Everything that moves is a real particle (position, velocity, turbulence, life).
 *
 * <p>One palette: the pill's hue and its darker shade, white only as a one-pixel highlight. Bands, core, dust and orbs are drawn with
 * NORMAL alpha blending (the translucent solid layer), so they cannot add up to white; only the tiny highlights use the additive
 * layer. Sizes are in block pixels (1/16 block), as the body is.
 */
public final class PillSceneRenderer {
    private static final int GRIT = 0x74747C;
    private static final int WARM = 0xD08A4E;
    /** Half width of a band: 1.7 block pixels in all (an outer shade and a narrower inner one). */
    static final double BAND_HALF = 0.053D;
    /** Bands lie this far off the torso front (outward) so they never sink into the robe. */
    private static final double LIFT = 0.015D;
    private static final double BP = 1 / 16D;

    /** Torso pixels: x across (screen right +), u above the bottom of the torso (12 = the collar). */
    private static final double[][][] ROUTES = {
            // trunk: from the dantian up the centre line to the fork at the sternum
            {{0, 3.2}, {0, 5}, {0, 6.8}, {0, 8}},
            // the two branches to the shoulders
            {{0, 8}, {.5, 8.6}, {1.6, 9.3}, {2.8, 10}, {3.8, 10.4}},
            {{0, 8}, {-.5, 8.6}, {-1.6, 9.3}, {-2.8, 10}, {-3.8, 10.4}}};

    private static final class P {
        Vec3 pos, vel;
        float age, life, size, phase, alpha;
        int rgb;
    }

    private static final List<P> PARTICLES = new ArrayList<>();
    private static final Random RNG = new Random(1109);
    private static float lastAge = -1;

    /** Read-only live simulation count; no rendering or timeline advancement. */
    public static int liveParticles() { return PARTICLES.size(); }

    private PillSceneRenderer() { }

    static void clear() {
        PARTICLES.clear();
        lastAge = -1;
        RNG.setSeed(1109);
    }

    // ------------------------------------------------------------------ frame

    static void draw(Minecraft mc, FlowBodySurface s, PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, float partial) {
        int T = PillScene.ticks();
        float age = PillScene.age(partial);
        float strength = PillScene.strength(partial);
        Vec3 front = s.front();
        Vec3 up = s.at(.5, .2).subtract(s.at(.5, .8)).normalize();
        Vec3 right = s.at(.6, .5).subtract(s.at(.4, .5)).normalize();
        Vec3 bot = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_BOTTOM), top = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_TOP),
                face = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_FRONT);
        if (bot == null || top == null || face == null) return;
        Vec3 span = top.subtract(bot);
        Frame f = new Frame(mc, pose, cam, front, up, right, face, span.normalize(), span.length() / 12, age, strength, T, PillScene.colour());

        float dtTicks = lastAge < 0 ? 0 : Mth.clamp(age - lastAge, 0, 3);
        if (lastAge >= 0 && age > lastAge) {
            for (int t = (int) Math.floor(lastAge) + 1; t <= (int) Math.floor(age); t++) spawn(f, t);
        }
        lastAge = age;
        step(dtTicks / 20F);

        VertexConsumer solid = buffers.getBuffer(MurimRenderTypes.airBand());
        drawBands(solid, f);
        drawParticles(solid, f);
        drawOrbs(solid, f);
        drawCore(solid, f);
        drawPill(solid, f);
        buffers.endBatch(MurimRenderTypes.airBand());

        // The additive layer holds only one-pixel highlights.
        VertexConsumer glow = buffers.getBuffer(MurimRenderTypes.flowLine());
        highlights(glow, f);
        buffers.endBatch(MurimRenderTypes.flowLine());

    }

    private record Frame(Minecraft mc, PoseStack.Pose pose, Vec3 cam, Vec3 front, Vec3 up, Vec3 right,
                         Vec3 base, Vec3 upUnit, double unit, float age, float strength, int T, int colour) {
        /** The mouth from the three face-corner bones (face plane, 78 % down the face, centred). */
        Vec3 lips() {
            Vec3 rt = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FACE_RIGHT_TOP),
                    lt = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FACE_LEFT_TOP),
                    rb = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FACE_RIGHT_BOTTOM);
            if (rt == null || lt == null || rb == null) return null;
            return rt.add(lt.subtract(rt).scale(.5)).add(rb.subtract(rt).scale(.78));
        }
        /** Point of the torso front in torso pixels (the bones' own frame, no plane lift). */
        Vec3 pt(double x, double u) { return base.add(right.scale(x * unit)).add(upUnit.scale(u * unit)).add(front.scale(LIFT)); }
        Vec3 sternum() { return pt(0, 8.5); }
        Vec3 belly() { return pt(0, 3.2).subtract(front.scale(.03)); }
        int dark() { return shade(colour, .62F); }
        int light() { return lerpRgb(colour, 0xFFFFFF, .35F); }
    }

    private static int shade(int rgb, float k) {
        return ((int) (((rgb >> 16) & 255) * k) << 16) | ((int) (((rgb >> 8) & 255) * k) << 8) | (int) ((rgb & 255) * k);
    }

    private static int lerpRgb(int a, int b, float k) {
        int r = (int) Mth.lerp(k, (a >> 16) & 255, (b >> 16) & 255), g = (int) Mth.lerp(k, (a >> 8) & 255, (b >> 8) & 255),
                bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    // ------------------------------------------------------------------ particles

    private static void particle(Vec3 pos, Vec3 vel, float life, float size, int rgb, float alpha) {
        P p = new P();
        p.pos = pos; p.vel = vel; p.life = life; p.size = size; p.rgb = rgb; p.alpha = alpha;
        p.phase = RNG.nextFloat() * 6.283F;
        PARTICLES.add(p);
    }

    private static Vec3 rv(double a) {
        return new Vec3((RNG.nextDouble() - .5) * a, (RNG.nextDouble() - .5) * a, (RNG.nextDouble() - .5) * a);
    }

    /** Spawns what happens on plan tick {@code t}. */
    private static void spawn(Frame f, int t) {
        int mS = PillScenePlan.melt(f.T);
        // D: a low ring of 28 pill-coloured motes spreads from the seat (radius about a block, under 0.3 high, one second).
        if (t == PillScenePlan.core(f.T) + 8) {
            Vec3 seat = f.mc.player.position();
            for (int i = 0; i < 28; i++) {
                double a = RNG.nextDouble() * Math.PI * 2, r0 = .3 + RNG.nextDouble() * .1;
                Vec3 at = seat.add(Math.cos(a) * r0, .05 + RNG.nextDouble() * .08, Math.sin(a) * r0);
                particle(at, new Vec3(Math.cos(a), 0, Math.sin(a)).scale(.7 + RNG.nextDouble() * .1).add(0, .12 + RNG.nextDouble() * .06, 0),
                        .8F + RNG.nextFloat() * .2F, .5F + RNG.nextFloat() * .5F, f.colour, .5F);
            }
        }
        // B: 11 small motes of the pill colour fall from the sternum orb down the chest (0.6 b/s, a little turbulence).
        if (t >= mS + 14 && t < mS + 25) {
            Vec3 at = f.sternum().add(f.right.scale((RNG.nextFloat() - .5) * .12)).add(f.up.scale(RNG.nextFloat() * .03)).add(f.front.scale(.02));
            particle(at, new Vec3(0, -.6 * (.9 + RNG.nextFloat() * .2), 0), 1.0F, .5F + RNG.nextFloat() * .5F, f.colour, .85F);
        }
        if (!PillScenePlan.isFull(f.T) || t < 60 || t >= 100) return;
        // C: a short fading trail behind each running orb ...
        for (int i = 0; i < PillScenePlan.ORBS; i++) {
            float r = PillScenePlan.orbRoute(i, t);
            if (r <= 0 || r >= 1) continue;
            Vec3[] path = orbPath(f, i);
            Vec3 at = MeridianFlow.alongRoute(path, r);
            Vec3 dir = MeridianFlow.alongRoute(path, Math.min(1, r + .03F)).subtract(at).normalize();
            particle(at, dir.scale(-.1).add(rv(.03)), .3F, .6F, f.colour, .7F);
        }
        // ... and 8 dark-grey impurities that leave the bands sideways and down, then fade.
        if (t >= 66 && t < 90 && t % 3 == 0) {
            Vec3[] path = route(f, RNG.nextBoolean() ? 1 : 2);
            Vec3 pos = MeridianFlow.alongRoute(path, .15F + RNG.nextFloat() * .7F);
            Vec3 side = f.right.scale(pos.subtract(f.base).dot(f.right) >= 0 ? 1 : -1);
            particle(pos, side.scale(.12 + RNG.nextFloat() * .06).add(0, -.08, 0).add(rv(.03)), .9F, .5F + RNG.nextFloat() * .3F, GRIT, .8F);
        }
    }

    private static void step(float dt) {
        if (dt <= 0) return;
        for (int i = PARTICLES.size() - 1; i >= 0; i--) {
            P p = PARTICLES.get(i);
            p.age += dt;
            if (p.age >= p.life) { PARTICLES.remove(i); continue; }
            // Slight turbulence: a sideways swirl that changes with age, different for every particle.
            double sx = Math.sin(p.age * 7 + p.phase) * .22, sz = Math.cos(p.age * 6 + p.phase * 1.7) * .12;
            p.vel = p.vel.add(sx * dt * 4, 0, sz * dt * 4);
            p.pos = p.pos.add(p.vel.scale(dt));
        }
    }

    private static void drawParticles(VertexConsumer c, Frame f) {
        for (P p : PARTICLES) {
            float k = p.age / p.life;
            float a = p.alpha * (1 - k * k) * Mth.clamp(p.age / .05F, 0, 1) * f.strength;
            square(c, f, p.pos, p.size * .5 * BP, a, p.rgb);
        }
    }

    // ------------------------------------------------------------------ the route

    private static Vec3[] route(Frame f, int index) {
        double[][] base = ROUTES[index];
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i + 1 < base.length; i++) {
            double[] p0 = base[Math.max(0, i - 1)], p1 = base[i], p2 = base[i + 1], p3 = base[Math.min(base.length - 1, i + 2)];
            for (int k = 0; k < 6; k++) {
                double u = k / 6D, u2 = u * u, u3 = u2 * u;
                double x = .5 * (2 * p1[0] + (p2[0] - p0[0]) * u + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * u2 + (3 * p1[0] - p0[0] - 3 * p2[0] + p3[0]) * u3);
                double y = .5 * (2 * p1[1] + (p2[1] - p0[1]) * u + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * u2 + (3 * p1[1] - p0[1] - 3 * p2[1] + p3[1]) * u3);
                out.add(f.pt(x, y));
            }
        }
        out.add(f.pt(base[base.length - 1][0], base[base.length - 1][1]));
        return out.toArray(new Vec3[0]);
    }

    /** The way of orb {@code i}: the trunk, then the right or the left branch. */
    private static Vec3[] orbPath(Frame f, int i) {
        Vec3[] trunk = route(f, 0), branch = route(f, 1 + i % 2);
        Vec3[] out = new Vec3[trunk.length + branch.length];
        System.arraycopy(trunk, 0, out, 0, trunk.length);
        System.arraycopy(branch, 0, out, trunk.length, branch.length);
        return out;
    }

    private static void drawBands(VertexConsumer c, Frame f) {
        if (!PillScenePlan.isFull(f.T)) return;
        float reach = PillScenePlan.bandReach(f.age), a = PillScenePlan.bandAlpha(f.age) * f.strength;
        if (reach <= 0 || a <= 0) return;
        VfxColour outer = colour(f.dark()), inner = colour(f.colour);
        MeridianFlow.drawRoute(c, f.pose, f.cam, route(f, 0), BAND_HALF, Mth.clamp(reach * 2, 0, 1), a * .8F, outer, inner);
        float branch = Mth.clamp(reach * 2 - 1, 0, 1);
        for (int i = 1; i < 3; i++) MeridianFlow.drawRoute(c, f.pose, f.cam, route(f, i), BAND_HALF * .9, branch, a * .8F, outer, inner);
    }

    private static void drawOrbs(VertexConsumer c, Frame f) {
        int mS = PillScenePlan.melt(f.T);
        // B: the sternum orb in the pill colour with a lighter spot, growing in over six ticks.
        Vec3 st = f.sternum();
        float grow = PillScenePlan.smooth((f.age - (mS + 10)) / 6);
        float fade = 1 - PillScenePlan.smooth((f.age - (PillScenePlan.isFull(f.T) ? 58 : PillScenePlan.core(f.T) - 4)) / 6);
        if (grow > 0 && fade > 0) {
            float a = fade * f.strength;
            sphere(c, f, st, .026 * grow, a, f.colour, false);
        }
        if (!PillScenePlan.isFull(f.T)) return;
        Vec3 belly = f.belly();
        for (int i = 0; i < PillScenePlan.ORBS; i++) {
            float appear = PillScenePlan.smooth((f.age - (60 + 6 * i)) / 2);
            if (appear <= 0) continue;
            float run = PillScenePlan.orbRoute(i, f.age), gather = PillScenePlan.orbConverge(f.T, i, f.age);
            Vec3 end = MeridianFlow.alongRoute(orbPath(f, i), run);
            Vec3 pos = end.lerp(belly, gather);
            // An orb sinks into its band at the end of the route and is called back to the belly in the core beat.
            float a = appear * f.strength * (gather > 0 ? gather < 1 ? .9F * Math.min(1, gather * 4) : 0 : 1 - PillScenePlan.smooth((run - .85F) / .15F));
            sphere(c, f, pos, .032, a, f.colour, false);
        }
    }

    // ------------------------------------------------------------------ the core

    private static void drawCore(VertexConsumer c, Frame f) {
        float scale = PillScenePlan.coreScale(f.T, f.age), alpha = PillScenePlan.coreAlpha(f.T, f.age) * f.strength;
        if (scale <= 0 || alpha <= 0) return;
        Vec3 pos = f.belly();
        // About a quarter of a block across at the breath's peak; the stored qi share (QiHud's ratio) scales it from pill to pill.
        double radius = .085 * (.7 + .9 * PillScene.qiShare()) * scale;
        int e = PillScenePlan.start(f.T, PillScenePlan.Beat.EXHALE);
        float warm = PillScenePlan.smooth((f.age - e) / (PillScenePlan.total(f.T) - e));
        int hue = lerpRgb(f.colour, WARM, warm);
        disc(c, f, pos, radius * 2.1, alpha * .10F, hue, 0, 0);
        disc(c, f, pos, radius * 1.5, alpha * .20F, hue, 0, 0);
        sphere(c, f, pos, radius, alpha, hue, true);
    }

    /** A sphere from layered discs: dark rim, body, a lighter spot towards the upper left. */
    private static void sphere(VertexConsumer c, Frame f, Vec3 pos, double radius, float alpha, int rgb, boolean big) {
        if (alpha <= 0 || radius <= 0) return;
        disc(c, f, pos, radius, alpha * .95F, shade(rgb, .6F), 0, 0);
        disc(c, f, pos, radius * .84, alpha * .95F, rgb, -.06, .06);
        disc(c, f, pos, radius * .46, alpha * .85F, lerpRgb(rgb, 0xFFFFFF, .4F), -.28, .3);
    }

    // ------------------------------------------------------------------ the pill

    private static final int[][] CUBES = cubes();

    private static int[][] cubes() {
        List<int[]> out = new ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            if (Math.abs(x) + Math.abs(y) + Math.abs(z) <= 2) out.add(new int[] {x, y, z});
        return out.toArray(new int[0][]);
    }

    /** In front of the mouth towards the lens, two block pixels across. */
    private static Vec3 pillAt(Frame f, Vec3 lips) {
        int mS = PillScenePlan.melt(f.T);
        Vec3 toLens = f.cam.subtract(lips).normalize();
        // The bone mouth sits a little to the player's left of the drawn mouth on the stand films: nudge towards screen left.
        Vec3 left = toLens.cross(new Vec3(0, 1, 0)).normalize();
        // 0.27 block ahead of the mouth towards the lens, 0.05 below the mouth line.
        Vec3 hold = lips.add(toLens.scale(.27)).add(left.scale(.075)).subtract(f.up.scale(.05));
        float k = PillScenePlan.smooth((f.age - mS) / 6);
        double bob = f.age < mS + 6 ? Math.sin(f.age * .35) * .004 : 0;
        return hold.lerp(lips.add(left.scale(.075)), k).add(0, bob, 0);
    }

    private static float pillSize(Frame f) {
        return 1 - PillScenePlan.smooth((f.age - (PillScenePlan.melt(f.T) + 6)) / 8);
    }

    private static void drawPill(VertexConsumer c, Frame f) {
        float size = pillSize(f);
        Vec3 lips = f.lips();
        if (size <= 0 || lips == null) return;
        Vec3 centre = pillAt(f, lips);
        double e = .015 * size;
        double spin = f.age * .08, cs = Math.cos(spin), sn = Math.sin(spin);
        float r = ((f.colour >> 16) & 255) / 255F, g = ((f.colour >> 8) & 255) / 255F, b = (f.colour & 255) / 255F;
        for (int[] o : CUBES) {
            double ox = o[0] * cs - o[2] * sn, oz = o[0] * sn + o[2] * cs;
            cube(c, f, centre.add(ox * e * 2, o[1] * e * 2, oz * e * 2), e, r, g, b);
        }
    }

    /** Axis-aligned cube, faces shaded by direction (top light, bottom dark) so the cluster reads as a solid. */
    private static void cube(VertexConsumer c, Frame f, Vec3 m, double h, float r, float g, float b) {
        double[][] n = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        float[] shade = {1.0F, .5F, .8F, .65F, .72F, .88F};
        for (int i = 0; i < 6; i++) {
            Vec3 nv = new Vec3(n[i][0], n[i][1], n[i][2]);
            if (nv.dot(f.cam.subtract(m)) <= 0) continue;
            Vec3 u = Math.abs(n[i][1]) > .5 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 w = nv.cross(u).normalize();
            u = w.cross(nv).normalize();
            Vec3 ctr = m.add(nv.scale(h));
            Vec3 a = ctr.subtract(u.scale(h)).subtract(w.scale(h)), bq = ctr.add(u.scale(h)).subtract(w.scale(h)),
                    cq = ctr.add(u.scale(h)).add(w.scale(h)), d = ctr.subtract(u.scale(h)).add(w.scale(h));
            for (Vec3 p : new Vec3[] {a, bq, cq, d}) {
                VfxDraw.vertex(c, f.pose, p, nv, .5F, .5F, f.strength, r * shade[i], g * shade[i], b * shade[i]);
            }
        }
    }

    /** One white highlight pixel on the pill's upper left and a pinpoint on the core; the only additive pieces. */
    private static void highlights(VertexConsumer c, Frame f) {
        float size = pillSize(f);
        Vec3 lips = f.lips();
        if (size > .05F && lips != null) {
            Vec3 centre = pillAt(f, lips);
            Vec3 toCam = f.cam.subtract(centre).normalize();
            Vec3 r = toCam.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 u = r.cross(toCam).normalize();
            Vec3 at = centre.add(r.scale(.028 * size)).add(u.scale(.03 * size)).add(toCam.scale(.04 * size));
            square(c, f, at, .13 * BP, f.strength, 0xFFFFFF);
        }
        float scale = PillScenePlan.coreScale(f.T, f.age), alpha = PillScenePlan.coreAlpha(f.T, f.age) * f.strength;
        if (scale > 0 && alpha > 0) {
            Vec3 pos = f.belly();
            double radius = .085 * (.7 + .9 * PillScene.qiShare()) * scale;
            Vec3 toCam = f.cam.subtract(pos).normalize();
            Vec3 r = toCam.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 u = r.cross(toCam).normalize();
            // A small white centre (a sixth of the sphere), a little off the middle towards the light.
            square(c, f, pos.add(r.scale(-radius * .25)).add(u.scale(radius * .3)).add(toCam.scale(.02)), radius * .14, alpha * .9F, 0xFFFFFF);
        }
    }

    // ------------------------------------------------------------------ primitives

    private static VfxColour colour(int rgb) {
        return new VfxColour(((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F);
    }

    private static float[] rgbf(int rgb) {
        return new float[] {((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F};
    }

    /** Camera-facing square of half size {@code h}, one flat colour (a crisp pixel). */
    private static void square(VertexConsumer c, Frame f, Vec3 centre, double h, float alpha, int rgb) {
        if (alpha <= 0 || h <= 0) return;
        Vec3 toCam = f.cam.subtract(centre);
        if (toCam.lengthSqr() < 1e-8) return;
        Vec3 fw = toCam.normalize();
        Vec3 ref = Math.abs(fw.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 r = fw.cross(ref).normalize().scale(h);
        Vec3 u = r.normalize().cross(fw).normalize().scale(h);
        float[] k = rgbf(rgb);
        VfxDraw.vertex(c, f.pose, centre.subtract(r).subtract(u), fw, .5F, .5F, alpha, k[0], k[1], k[2]);
        VfxDraw.vertex(c, f.pose, centre.subtract(r).add(u), fw, .5F, .5F, alpha, k[0], k[1], k[2]);
        VfxDraw.vertex(c, f.pose, centre.add(r).add(u), fw, .5F, .5F, alpha, k[0], k[1], k[2]);
        VfxDraw.vertex(c, f.pose, centre.add(r).subtract(u), fw, .5F, .5F, alpha, k[0], k[1], k[2]);
    }

    /** Camera-facing disc (a 16-gon as a fan of quads), offset in units of its own radius towards screen right/up. */
    private static void disc(VertexConsumer c, Frame f, Vec3 centre, double radius, float alpha, int rgb, double offRight, double offUp) {
        if (alpha <= 0 || radius <= 0) return;
        Vec3 toCam = f.cam.subtract(centre);
        if (toCam.lengthSqr() < 1e-8) return;
        Vec3 fw = toCam.normalize();
        Vec3 ref = Math.abs(fw.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 r = fw.cross(ref).normalize();
        Vec3 u = r.cross(fw).normalize();
        // Each layer a hair nearer the lens than the one below, so they stack in draw order.
        Vec3 mid = centre.add(r.scale(offRight * radius / .84)).add(u.scale(offUp * radius / .84)).add(fw.scale(.002));
        float[] k = rgbf(rgb);
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            Vec3 p0 = mid.add(r.scale(Math.cos(a0) * radius)).add(u.scale(Math.sin(a0) * radius));
            Vec3 p1 = mid.add(r.scale(Math.cos(a1) * radius)).add(u.scale(Math.sin(a1) * radius));
            VfxDraw.vertex(c, f.pose, mid, fw, .5F, .5F, alpha, k[0], k[1], k[2]);
            VfxDraw.vertex(c, f.pose, p0, fw, .5F, .5F, alpha, k[0], k[1], k[2]);
            VfxDraw.vertex(c, f.pose, p1, fw, .5F, .5F, alpha, k[0], k[1], k[2]);
            VfxDraw.vertex(c, f.pose, p1, fw, .5F, .5F, alpha, k[0], k[1], k[2]);
        }
    }
}
