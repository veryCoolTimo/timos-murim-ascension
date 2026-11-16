package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.TechniqueLight;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import io.github.verycooltimo.murim.client.vfx.fx.FxQuality;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Draws the pill absorption scene (version 3, 14.11) on the seated body, everything on the body's centre line and inside its silhouette:
 * <ol>
 *   <li>the pill is a small bead of light that drops down the centre of the chest to the belly (refs pill-sequence 1, pills-ar melting);</li>
 *   <li>it dissolves: light motes spread over the torso under the robe, the belly glows (melting.png);</li>
 *   <li>the frame darkens (the overlay vignette) and the meridians grow: ONE branching, jagged tree from the dantian up the centre line,
 *       to the shoulders, ribs and hips, with clots of light flowing out along it (absorbing pill.png; {@link PillVeinTree});</li>
 *   <li>thin bright ribbons loop round the body, tighten on the core, then fly outward as air waves (snowy pill, heavenly pill; {@link PillWaves});</li>
 *   <li>the core flashes in white-hot light with rays, a shock ring and sparks; plum pills also throw petals; then a warm ember fades.</li>
 * </ol>
 * Everything is additive light drawn with depth, so the far half of a loop hides behind the body. One hue (the pill's) and white; no dark
 * discs, no haze boxes, no bone-glued clouds. Cost: the number of particles and wave loops follows the quality governor ({@link FxQuality}),
 * which watches the frame time and lowers itself when frames get slow.
 */
public final class PillSceneRenderer {
    private static final int GRIT = 0x74747C;
    private static final int WARM = 0xD08A4E;
    private static final int SPARK = 0, EMBER = 1, LIGHT = 2, PETAL = 3, RISE = 4, DUST = 5;
    /** Ticks after the core appears when it bursts (the camera has finished its pull-back). */
    static final int BURST_AFTER = PillScenePlan.BURST_AFTER;
    private static final double LIFT = 0.015D;
    private static final int MAX_PARTICLES = 420;

    private static final class P {
        Vec3 pos, vel;
        float age, life, size, phase, alpha;
        int rgb, kind;
        double tx, tu, vx, vu, depth;
        double drag, lift;
    }

    private static final List<P> PARTICLES = new ArrayList<>();
    private static final Random RNG = new Random(1114);
    private static float lastAge = -1;
    private static long lastNanos;
    private static final FxQuality QUALITY = new FxQuality();
    private static List<PillBodyVeins.Branch> veins;
    private static boolean veinsFinal;
    private static double floorY;
    /** Continuous additive lines (the air-band texture): veins, waves, rings. The mote texture on a stretched quad reads as a string of beads. */
    private static boolean linePass;

    /** Read-only live simulation count; no rendering or timeline advancement. */
    public static int liveParticles() { return PARTICLES.size(); }

    private PillSceneRenderer() { }

    static void clear() {
        PARTICLES.clear();
        lastAge = -1;
        lastNanos = 0;
        cpuSum = cpuMax = 0; cpuFrames = particlesMax = tierMax = 0; cpuLogged = false;
        RNG.setSeed(1114);
        veins = null; veinsFinal = false;
    }

    /** The quality governor's tier now (0 = full, 3 = lowest). */
    static int qualityTier() { return QUALITY.tier(); }

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
        floorY = mc.player.getY() + .03;
        long now = System.nanoTime();
        if (lastNanos != 0) QUALITY.update((now - lastNanos) / 1e6, (now - lastNanos) / 1e9);
        lastNanos = now;
        Frame f = new Frame(mc, pose, cam, front, up, right, face, span.normalize(), span.length() / 12, age, strength, T, PillScene.colour(), PillScene.plum());

        ensureVeins(mc, f);
        float dtTicks = lastAge < 0 ? 0 : Mth.clamp(age - lastAge, 0, 3);
        if (lastAge >= 0 && age > lastAge) {
            for (int t = (int) Math.floor(lastAge) + 1; t <= (int) Math.floor(age); t++) spawn(f, t);
        }
        lastAge = age;
        int burstTick = burst(T);
        step(f, dtTicks / 20F, PillScenePlan.smooth((age - (PillScenePlan.core(T) - 2)) / 8) * (age < burstTick ? 1 : 0));

        long t0 = System.nanoTime();
        // Two passes over the same pieces, never interleaved (a BufferSource keeps one open buffer): the continuous lines first, then the soft lights.
        for (int pass = 0; pass < 2; pass++) {
            linePass = pass == 0;
            var type = linePass ? MurimRenderTypes.flowLine() : MurimRenderTypes.flowMote();
            VertexConsumer glow = buffers.getBuffer(type);
            drawTorsoLight(glow, f);
            drawVeins(glow, f);
            drawBead(glow, f);
            drawParticles(glow, f);
            drawCore(glow, f);
            buffers.endBatch(type);
        }
        perf(System.nanoTime() - t0, age, T);

        if (f.plum) {
            VertexConsumer petals = buffers.getBuffer(MurimRenderTypes.petalsFlat());
            drawPetals(petals, f);
            buffers.endBatch(MurimRenderTypes.petalsFlat());
        }
    }

    private static double cpuSum, cpuMax;
    private static int cpuFrames, particlesMax, tierMax;
    private static boolean cpuLogged;

    /** Dev telemetry (MURIM_PERF=1): CPU time of building the scene's quads, logged once at the end of the scene. */
    private static void perf(long nanos, float age, int T) {
        if (!"1".equals(System.getenv("MURIM_PERF"))) return;
        cpuSum += nanos / 1e6; cpuMax = Math.max(cpuMax, nanos / 1e6); cpuFrames++;
        particlesMax = Math.max(particlesMax, PARTICLES.size()); tierMax = Math.max(tierMax, QUALITY.tier());
        if (!cpuLogged && age >= PillScenePlan.total(T) - 2 && cpuFrames > 0) {
            cpuLogged = true;
            io.github.verycooltimo.murim.MurimMod.LOGGER.info(String.format("PILLSCENE perf frames=%d cpuAvg=%.3fms cpuMax=%.3fms particlesMax=%d tierMax=%d",
                    cpuFrames, cpuSum / cpuFrames, cpuMax, particlesMax, tierMax));
        }
    }

    static int burst(int ticks) { return PillScenePlan.core(ticks) + BURST_AFTER; }

    private record Frame(Minecraft mc, PoseStack.Pose pose, Vec3 cam, Vec3 front, Vec3 up, Vec3 right,
                         Vec3 base, Vec3 upUnit, double unit, float age, float strength, int T, int colour, boolean plum) {
        /** Point of the torso front in torso pixels (x across, u up from the bottom of the torso, 12 = collar). */
        Vec3 pt(double x, double u) { return base.add(right.scale(x * unit)).add(upUnit.scale(u * unit)).add(front.scale(LIFT)); }
        Vec3 pt(double[] p) { return pt(p[0], p[1]); }
        /** A skeleton point (a across, v up, z out of the chest, torso pixels) with a small lift towards the lens. */
        Vec3 pt3(double[] p) { return base.add(right.scale(p[0] * unit)).add(upUnit.scale(p[1] * unit)).add(front.scale(LIFT + p[2] * unit)); }
        double[] local(Vec3 w) {
            Vec3 d = w.subtract(base);
            return new double[] {d.dot(right) / unit, d.dot(upUnit) / unit, d.dot(front) / unit};
        }
        Vec3 sternum() { return pt(0, 8.5); }
        Vec3 belly() { return pt(0, PillVeinTree.BELLY_U); }
        /** The body's own axis at the belly: the point the waves turn about (0.1 block behind the torso front). */
        Vec3 axis() { return belly().subtract(front.scale(.11)); }
        int light() { return lerpRgb(colour, 0xFFFFFF, .4F); }
        int hot() { return lerpRgb(colour, 0xFFFFFF, .6F); }
    }

    private static int lerpRgb(int a, int b, float k) {
        int r = (int) Mth.lerp(k, (a >> 16) & 255, (b >> 16) & 255), g = (int) Mth.lerp(k, (a >> 8) & 255, (b >> 8) & 255),
                bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    private static boolean full(Frame f) { return PillScenePlan.isFull(f.T); }
    /** The pill is in the chest at the start of the scene; it lands on the belly here. */
    private static int arrive(Frame f) { return full(f) ? PillScenePlan.melt(f.T) + 6 : 12; }
    private static int treeStart(Frame f) { return full(f) ? 54 : 10; }
    private static int treeSpan(Frame f) { return full(f) ? 34 : 16; }
    private static int waveStart(Frame f) { return full(f) ? 40 : 6; }

    // ------------------------------------------------------------------ particles

    private static P particle(int kind, Vec3 pos, Vec3 vel, float life, float size, int rgb, float alpha) {
        if (PARTICLES.size() >= MAX_PARTICLES) return null;
        P p = new P();
        p.kind = kind; p.pos = pos; p.vel = vel; p.life = life; p.size = size; p.rgb = rgb; p.alpha = alpha;
        p.phase = RNG.nextFloat() * 6.283F;
        PARTICLES.add(p);
        return p;
    }

    private static double rnd(double a, double b) { return a + RNG.nextDouble() * (b - a); }

    private static Vec3 sphere() {
        double z = rnd(-1, 1), a = rnd(0, Math.PI * 2), r = Math.sqrt(1 - z * z);
        return new Vec3(Math.cos(a) * r, z, Math.sin(a) * r);
    }

    private static int n(double base) { return Math.max(0, (int) Math.round(base * QUALITY.particleScale())); }

    private static Vec3 waveWorld(Frame f, int i, double a, double gather, double spread) {
        double[] p = PillWaves.point(i, a, gather, spread);
        return f.axis().add(f.right.scale(p[0])).add(f.upUnit.scale(p[1])).add(f.front.scale(p[2]));
    }

    private static double gather(Frame f) {
        return PillScenePlan.smooth((f.age - (PillScenePlan.core(f.T) - 6)) / (BURST_AFTER + 6)) * (f.age < burst(f.T) ? 1 : 0);
    }

    private static double spread(Frame f) { return PillScenePlan.smooth((f.age - burst(f.T)) / 9F); }

    /** Spawns what happens on plan tick {@code t}. */
    private static void spawn(Frame f, int t) {
        int mS = PillScenePlan.melt(f.T), core = PillScenePlan.core(f.T), burst = burst(f.T), arrive = arrive(f);
        // 1: a short trail of fine sparks behind the falling bead.
        if (t >= 3 && t < arrive) {
            Vec3 at = beadAt(f, t);
            for (int k = 0; k < n(2); k++) particle(SPARK, at.add(rv(.012)), rv(.06), .45F, (float) rnd(.006, .011), f.light(), .8F);
        }
        // 2: the bead dissolves: light spreads over the torso under the robe (kept inside its silhouette).
        if (t >= arrive && t < arrive + 22) {
            for (int k = 0; k < n(4); k++) {
                P p = particle(LIGHT, f.belly(), Vec3.ZERO, (float) rnd(.9, 1.6), (float) rnd(.03, .07), RNG.nextInt(3) == 0 ? f.light() : f.colour, .5F);
                if (p == null) continue;
                p.tx = rnd(-.6, .6); p.tu = PillVeinTree.BELLY_U + rnd(-.5, .5);
                double a = rnd(0, Math.PI * 2), v = rnd(1.0, 3.0);
                p.vx = Math.cos(a) * v; p.vu = Math.sin(a) * v * .8 + .5; p.depth = rnd(.01, .04); p.drag = 1.4;
            }
        }
        // 3: the waves shed fine specks that fly free (nothing is glued to the body).
        if (t >= waveStart(f) && t < burst) {
            double sec = t / 20.0;
            for (int k = 0; k < n(3); k++) {
                int i = RNG.nextInt(PillWaves.MAX_LOOPS);
                double a = PillWaves.head(i, sec) - Math.signum(PillWaves.omega(i)) * rnd(0, .8);
                Vec3 at = waveWorld(f, i, a, 0, 0), ahead = waveWorld(f, i, a + .05 * Math.signum(PillWaves.omega(i)), 0, 0).subtract(at).normalize();
                P p = particle(EMBER, at, ahead.scale(rnd(.3, .6)).add(0, rnd(.1, .35), 0), (float) rnd(.7, 1.3), (float) rnd(.006, .013), RNG.nextInt(3) == 0 ? 0xFFFFFF : f.light(), .9F);
                if (p != null) p.drag = .8;
            }
        }
        // 4: rising embers and dust, carried by a steady wind (refs: embers and dust everywhere, cloth and hair streaming).
        if (t >= treeStart(f) && t < PillScenePlan.total(f.T) - 14 && veins != null) {
            double wind = .22;
            for (int k = 0; k < n(t < burst ? 2 : 3); k++) {
                PillBodyVeins.Branch b = veins.get(RNG.nextInt(veins.size()));
                Vec3 at = f.pt3(b.at(RNG.nextDouble()));
                P p = particle(RISE, at, new Vec3(wind + rnd(-.05, .2), rnd(.3, .8), rnd(-.05, .1)), (float) rnd(1.3, 2.4), (float) rnd(.005, .011),
                        RNG.nextInt(3) == 0 ? 0xFFFFFF : f.light(), .9F);
                if (p != null) { p.drag = .35; p.lift = .12; }
            }
            if (t % 2 == 0) for (int k = 0; k < n(2); k++) {
                double a = rnd(0, Math.PI * 2), r = rnd(.28, 1.1);
                Vec3 at = new Vec3(f.axis().x + Math.cos(a) * r, floorY + rnd(0, .05), f.axis().z + Math.sin(a) * r);
                P p = particle(DUST, at, new Vec3(Math.cos(a) * .25 + wind * .6, rnd(.04, .14), Math.sin(a) * .25), (float) rnd(1.4, 2.6), (float) rnd(.02, .04),
                        lerpRgb(f.colour, 0xFFFFFF, .35F), .4F);
                if (p != null) p.drag = .6;
            }
        }
        if (t == treeStart(f)) TechniqueLight.add(f.belly(), f.colour, 3.0F, .45F, 14, Math.max(1, core - treeStart(f) + 6), 14);
        // 5: the burst. Sparks leave the core in every direction; plum pills also throw plum petals.
        if (t == burst) {
            Vec3 c = f.belly().add(f.front.scale(.06));
            // the push of dust: a ring of low dust is thrown out over the floor
            for (int i = 0; i < n(70); i++) {
                double a = rnd(0, Math.PI * 2), sp = rnd(1.2, 3.0);
                P p = particle(DUST, new Vec3(f.axis().x, floorY + rnd(0, .04), f.axis().z).add(Math.cos(a) * .25, 0, Math.sin(a) * .25),
                        new Vec3(Math.cos(a) * sp, rnd(.05, .3), Math.sin(a) * sp), (float) rnd(1.2, 2.2), (float) rnd(.02, .045), lerpRgb(f.colour, 0xFFFFFF, .3F), .45F);
                if (p != null) p.drag = 2.2;
            }
            for (int i = 0; i < n(220); i++) {
                Vec3 d = sphere();
                P p = particle(SPARK, c, d.scale(rnd(1.6, 5.0)), (float) rnd(.45, .95), (float) rnd(.008, .02), i % 3 == 0 ? 0xFFFFFF : i % 3 == 1 ? f.hot() : f.colour, 1F);
                if (p != null) p.drag = 3.0;
            }
            // Other pills throw sparks of their own colour in the petals' place; only plum pills throw petals.
            if (!f.plum) for (int i = 0; i < n(60); i++) {
                Vec3 d = sphere();
                P p = particle(SPARK, c, d.scale(rnd(.8, 2.4)), (float) rnd(1.0, 1.8), (float) rnd(.008, .016), i % 4 == 0 ? f.hot() : f.colour, .95F);
                if (p != null) { p.drag = 2.2; p.lift = .15; }
            }
            if (f.plum) for (int i = 0; i < n(55); i++) {
                Vec3 d = sphere(); d = new Vec3(d.x, d.y * .3, d.z).add(f.front.scale(.5)).normalize();
                P p = particle(PETAL, c.add(d.scale(rnd(.02, .1))), d.scale(rnd(.7, 1.8)), (float) rnd(1.1, 1.8), (float) rnd(.012, .024),
                        i % 5 == 0 ? 0xFFFFFF : i % 3 == 0 ? 0xE63D70 : 0xF19BC5, 1F);
                if (p != null) { p.drag = 2.8; p.lift = -.7; }
            }
            TechniqueLight.add(c, f.hot(), 9F, 1F, 1, 4, 22);
        }
    }

    private static Vec3 rv(double a) {
        return new Vec3((RNG.nextDouble() - .5) * a, (RNG.nextDouble() - .5) * a, (RNG.nextDouble() - .5) * a);
    }

    private static void step(Frame f, float dt, float pull) {
        if (dt <= 0) return;
        Vec3 core = f.belly();
        for (int i = PARTICLES.size() - 1; i >= 0; i--) {
            P p = PARTICLES.get(i);
            p.age += dt;
            if (p.age >= p.life) { PARTICLES.set(i, PARTICLES.get(PARTICLES.size() - 1)); PARTICLES.remove(PARTICLES.size() - 1); continue; }
            if (p.kind == LIGHT) {
                double k = Math.exp(-p.drag * dt);
                p.vx *= k; p.vu *= k;
                p.tx += p.vx * dt; p.tu += p.vu * dt;
                p.tu = Math.max(PillVeinTree.U_MIN, Math.min(PillVeinTree.U_MAX, p.tu));
                p.tx = PillVeinTree.clampX(p.tx, p.tu);
                continue;
            }
            double sx = Math.sin(p.age * 7 + p.phase) * .22, sz = Math.cos(p.age * 6 + p.phase * 1.7) * .12;
            p.vel = p.vel.add(sx * dt * 2, p.lift * dt, sz * dt * 2).scale(Math.exp(-p.drag * dt));
            if (pull > 0 && p.kind == EMBER) {
                Vec3 to = core.subtract(p.pos);
                p.vel = p.vel.scale(1 - .5 * pull).add(to.scale(pull * 9 * dt * 20 / Math.max(.25, to.length() * 4)));
                if (to.lengthSqr() < .0016) { PARTICLES.set(i, PARTICLES.get(PARTICLES.size() - 1)); PARTICLES.remove(PARTICLES.size() - 1); continue; }
            }
            p.pos = p.pos.add(p.vel.scale(dt));
        }
    }

    private static float fade(P p, Frame f) {
        float k = p.age / p.life;
        return p.alpha * (1 - k * k) * Mth.clamp(p.age / .05F, 0, 1) * f.strength;
    }

    private static void drawParticles(VertexConsumer c, Frame f) {
        for (P p : PARTICLES) {
            if (p.kind == LIGHT) {
                float k = p.age / p.life;
                mote(c, f, f.pt(p.tx, p.tu).add(f.front.scale(p.depth)), p.size * (1 + 1.2 * k), fade(p, f) * .55F, p.rgb);
                continue;
            }
            if (p.kind == DUST) {
                float k = p.age / p.life;
                mote(c, f, p.pos, p.size * (1 + 1.5 * k), fade(p, f), p.rgb);
                continue;
            }
            if (p.kind != SPARK && p.kind != EMBER && p.kind != RISE) continue;
            float twinkle = .75F + .25F * Mth.sin(p.age * 23 + p.phase);
            float a = fade(p, f) * twinkle;
            mote(c, f, p.pos, p.size * 2.6, a * .4F, p.rgb);
            mote(c, f, p.pos, p.size, a, lerpRgb(p.rgb, 0xFFFFFF, .6F));
        }
    }

    private static void drawPetals(VertexConsumer c, Frame f) {
        for (P p : PARTICLES) {
            if (p.kind != PETAL) continue;
            float[] k = rgbf(p.rgb);
            float a = fade(p, f), spin = p.phase + p.age * (2 + p.phase);
            int cell = (int) (p.phase * 10) & 3;
            petal(c, f, p.pos, p.size, cell, spin, a, k[0] * .65F, k[1] * .45F, k[2] * .55F);
            petal(c, f, p.pos.add(f.cam.subtract(p.pos).normalize().scale(.002)), p.size * .86, cell, spin, a, k[0], k[1], k[2]);
        }
    }

    // ------------------------------------------------------------------ the bead and the light under the robe

    /** Where the bead is at plan time {@code a}: from the chest down the centre line to the belly, easing in and out. */
    private static Vec3 beadAt(Frame f, float a) {
        int land = arrive(f);
        float k = PillScenePlan.smooth((a - 2) / (land - 2));
        double u = 8.6 - (8.6 - PillVeinTree.BELLY_U) * k;
        return f.pt(0, u).add(f.front.scale(.012));
    }

    private static void drawBead(VertexConsumer c, Frame f) {
        int land = arrive(f);
        float in = PillScenePlan.smooth(f.age / 3);
        float melt = 1 - PillScenePlan.smooth((f.age - land) / 14);
        if (melt <= .01F || in <= 0) return;
        Vec3 at = beadAt(f, f.age);
        float pulse = 1 + .08F * Mth.sin(f.age * .7F);
        float a = f.strength * in;
        mote(c, f, at, .12 * pulse * (.4 + .6 * melt), a * .38F, f.colour);
        mote(c, f, at, .06 * melt, a, f.light());
        mote(c, f, at, .03 * melt, a, 0xFFFFFF);
        // The landing: one ring of light runs over the belly inside the torso.
        float r = (f.age - land) / 9F;
        if (r > 0 && r < 1) ring(c, f, f.belly().add(f.front.scale(.01)), 3.2 * r * f.unit, .03 * (1 - r), a * (1 - r) * .8F, f.light(), true);
    }

    /** The belly and chest glow under the robe, as the pink light of melting.png behind the torso: a few soft lights inside the silhouette. */
    private static void drawTorsoLight(VertexConsumer c, Frame f) {
        int land = arrive(f), core = PillScenePlan.core(f.T);
        float in = PillScenePlan.smooth((f.age - land) / 10), out = 1 - PillScenePlan.smooth((f.age - (core + 12)) / 14);
        if (in <= 0 || out <= 0) return;
        float a = f.strength * in * out;
        float breath = 1 + .06F * Mth.sin(f.age * .5F);
        Vec3 z = f.front.scale(.025);
        mote(c, f, f.pt(0, 3.6).add(z), .17 * breath, a * .38F, f.colour);
        mote(c, f, f.pt(0, 5.8).add(z), .15, a * .22F * PillScenePlan.smooth((f.age - land - 6) / 12), f.colour);
        mote(c, f, f.pt(0, 7.6).add(z), .30, a * .10F * PillScenePlan.smooth((f.age - land - 12) / 16), f.colour);
        mote(c, f, f.pt(-1.8, 4.4).add(z), .11, a * .16F, f.colour);
        mote(c, f, f.pt(1.8, 4.4).add(z), .11, a * .16F, f.colour);
    }

    // ------------------------------------------------------------------ the meridians

    private static float veinAlpha(Frame f) {
        int burst = burst(f.T);
        return f.strength * (1 - PillScenePlan.smooth((f.age - (burst + 4)) / 16F));
    }

    private static double[] bone(Minecraft mc, Frame f, BoneAnchorLayer.Bone b, double lift) {
        Vec3 w = BoneAnchorLayer.position(mc.player, b);
        if (w == null) return null;
        double[] l = f.local(w);
        l[1] = Math.max(PillBodyVeins.FLOOR_V, l[1]);
        l[2] += lift;
        return l;
    }

    /** Builds the veins from the live skeleton: once at the start, once more just before they grow (the pose has settled by then). */
    private static void ensureVeins(Minecraft mc, Frame f) {
        if (veins != null && (veinsFinal || f.age < treeStart(f) - 2)) return;
        double[] rs = bone(mc, f, BoneAnchorLayer.Bone.RIGHT_SHOULDER, 0), ls = bone(mc, f, BoneAnchorLayer.Bone.LEFT_SHOULDER, 0),
                re = bone(mc, f, BoneAnchorLayer.Bone.RIGHT_ELBOW, 2.2), le = bone(mc, f, BoneAnchorLayer.Bone.LEFT_ELBOW, 2.2),
                rh = bone(mc, f, BoneAnchorLayer.Bone.RIGHT_HAND, 2.2), lh = bone(mc, f, BoneAnchorLayer.Bone.LEFT_HAND, 2.2);
        PillBodyVeins.Joints std = PillBodyVeins.standard();
        if (rs == null || ls == null || re == null || le == null || rh == null || lh == null) {
            veins = PillBodyVeins.build(std, 1114, QUALITY.tier() >= 2 ? 1 : 2);
        } else {
            // The bones are named for the player; the screen side is what counts (screen right = +a).
            boolean swap = rs[0] < ls[0];
            double[] shL = swap ? rs : ls, shR = swap ? ls : rs, elL = swap ? re : le, elR = swap ? le : re, haL = swap ? rh : lh, haR = swap ? lh : rh;
            shL[1] = Math.min(shL[1], 9.6); shR[1] = Math.min(shR[1], 9.6);
            veins = PillBodyVeins.build(new PillBodyVeins.Joints(std.dantian(), std.collar(), shL, shR, elL, elR, haL, haR), 1114, QUALITY.tier() >= 2 ? 1 : 2);
        }
        veinsFinal = f.age >= treeStart(f) - 2;
    }

    private static void drawVeins(VertexConsumer c, Frame f) {
        float va = veinAlpha(f);
        float reach = PillScenePlan.smooth((f.age - treeStart(f)) / treeSpan(f));
        if (va <= .01F || reach <= 0 || veins == null) return;
        double sec = f.age / 20.0, pxw = f.unit;
        int rgbMid = f.colour, rgbCore = lerpRgb(f.colour, 0xFFFFFF, .35F);
        float[] kS = rgb(f.colour), kC = rgb(rgbCore);
        for (int bi = 0; bi < veins.size(); bi++) {
            PillBodyVeins.Branch b = veins.get(bi);
            double g = Math.max(0, Math.min(1, (reach - b.start()) / b.dur()));
            if (g <= 0) continue;
            double w = b.width() * pxw * 1.4;
            double run = 0;
            double[] prev = b.pts()[0];
            float gain = b.arm() ? 1.1F : 1.0F;
            for (int i = 1; i < b.pts().length; i++) {
                double[] cur = b.pts()[i];
                double seg = PillBodyVeins.dist(prev, cur);
                double s0 = run / b.length(), s1 = (run + seg) / b.length();
                if (s0 >= g) break;
                double[] end = cur;
                if (s1 > g) { double k = (g - s0) / (s1 - s0); end = new double[] {prev[0] + (cur[0] - prev[0]) * k, prev[1] + (cur[1] - prev[1]) * k, prev[2] + (cur[2] - prev[2]) * k}; }
                Vec3 p0 = f.pt3(prev), p1 = f.pt3(end);
                float taper = (float) (1 - .45 * s0) * gain;
                seg(c, f.pose, p0, p1, f.cam, w * 1.0, va * .15F * taper, kS[0], kS[1], kS[2]);
                seg(c, f.pose, p0, p1, f.cam, w * .52, va * .3F * taper, kS[0], kS[1], kS[2]);
                seg(c, f.pose, p0, p1, f.cam, w * .22, va * .42F * taper, kC[0], kC[1], kC[2]);
                run += seg; prev = cur;
            }
            if (g < 1) {
                mote(c, f, f.pt3(b.at(g)), w * 1.8 + .008, va * .65F, 0xFFFFFF);
            } else {
                // A finished vein keeps carrying clots of light outward from the dantian; the glow gathers at its end.
                int clots = b.depth() == 0 ? 3 : b.depth() == 1 ? 2 : 1;
                for (int k = 0; k < clots; k++) {
                    double s = (sec * (.5 + .12 * (bi % 4)) + k / (double) clots + bi * .173) % 1.0;
                    float edge = (float) Math.min(1, Math.min(s, 1 - s) * 6);
                    Vec3 at = f.pt3(b.at(s));
                    mote(c, f, at, w * 2.4 + .008, va * edge * .5F, rgbMid);
                    mote(c, f, at, w * .9 + .003, va * edge * .8F, 0xFFFFFF);
                }
                if (b.depth() == 1 && b.arm()) mote(c, f, f.pt3(b.at(1)), .05 + .01 * Mth.sin((float) (sec * 5 + bi)), va * .35F, rgbCore);
            }
        }
        // The chest swirl (pills-ar absorbing pill): concentric arcs turning round the sternum where the branches fork.
        double sw = PillScenePlan.smooth((reach - .12F) / .25F) * va;
        if (sw > .01F) {
            Vec3 sc = f.pt(0, 8.3).add(f.front.scale(.004));
            for (int k = 0; k < 3; k++) {
                double rad = (1.0 + .75 * k) * pxw, a0 = sec * (k % 2 == 0 ? 2.2 : -1.7) + k * 2.1, span = 4.2 - .5 * k;
                arcTorso(c, f, sc, rad, pxw * .12, (float) sw * (.55F - .1F * k), a0, span);
            }
            mote(c, f, sc, pxw * 2.2, (float) sw * .45F, rgbMid);
        }
        // The bright axis beam from the dantian up the centre line; it stops at the collar (nothing on the chin or the face).
        double top = PillBodyVeins.BELLY_V + (PillBodyVeins.COLLAR_V - PillBodyVeins.BELLY_V) * reach;
        float beam = va * (.15F + .05F * Mth.sin(f.age * .6F));
        Vec3 lift = f.front.scale(.004);
        seg(c, f.pose, f.pt(0, PillBodyVeins.BELLY_V).add(lift), f.pt(0, top).add(lift), f.cam, pxw * .55, beam * .55F, kS[0], kS[1], kS[2]);
        seg(c, f.pose, f.pt(0, PillBodyVeins.BELLY_V).add(lift), f.pt(0, top).add(lift), f.cam, pxw * .13, beam * 2.2F, 1, 1, 1);
    }

    private static float[] rgb(int v) { return rgbf(v); }

    /** A continuous line segment; drawn only in the line pass. */
    private static void seg(VertexConsumer c, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 cam, double half, float alpha, float r, float g, float bl) {
        if (linePass) VfxDraw.segment(c, pose, a, b, cam, half, alpha, r, g, bl);
    }

    /** An arc of light on the torso plane (an open ring, so it reads as a swirl and not as a hoop). */
    private static void arcTorso(VertexConsumer c, Frame f, Vec3 centre, double radius, double half, float alpha, double a0, double span) {
        int n = 18;
        Vec3 prev = null;
        float[] k = rgbf(f.light());
        for (int i = 0; i <= n; i++) {
            double a = a0 + span * i / n;
            Vec3 p = centre.add(f.right.scale(Math.cos(a) * radius)).add(f.upUnit.scale(Math.sin(a) * radius * .9));
            if (prev != null) {
                double fadeK = Math.sin(Math.PI * i / n);
                seg(c, f.pose, prev, p, f.cam, half * 3, (float) (alpha * .3F * fadeK), k[0], k[1], k[2]);
                seg(c, f.pose, prev, p, f.cam, half, (float) (alpha * fadeK), 1, 1, 1);
            }
            prev = p;
        }
    }

    // ------------------------------------------------------------------ air waves

    private static void drawWaves(VertexConsumer c, Frame f) {
        int ws = waveStart(f), bst = burst(f.T);
        float in = PillScenePlan.smooth((f.age - ws) / 16F);
        float out = f.age < bst ? 1 : 1 - PillScenePlan.smooth((f.age - bst) / 12F);
        if (in <= 0 || out <= 0) return;
        int tier = QUALITY.tier();
        int loops = Math.max(3, PillWaves.MAX_LOOPS - tier * 1);
        int samples = tier >= 2 ? 14 : 22;
        double gather = gather(f), spread = spread(f), sec = f.age / 20.0;
        float boost = 1 + .8F * (float) gather;
        for (int i = 0; i < loops; i++) {
            double sign = Math.signum(PillWaves.omega(i)), head = PillWaves.head(i, sec * (1 + 1.5 * gather));
            double arc = 2.1 * (1 + .5 * gather);
            Vec3 prev = null;
            for (int k = 0; k <= samples; k++) {
                double u = k / (double) samples;
                Vec3 p = waveWorld(f, i, head - sign * arc * u, gather, spread);
                if (prev != null) {
                    double taper = Math.pow(1 - u, .8);
                    float a = f.strength * in * out * (float) Math.pow(1 - u, 1.3) * boost;
                    double w = .0085 * taper * (1 + .5 * gather);
                    seg(c, f.pose, prev, p, f.cam, w * 2.6, a * .26F, rgb(f.colour)[0], rgb(f.colour)[1], rgb(f.colour)[2]);
                    seg(c, f.pose, prev, p, f.cam, w, a * .85F, rgb(f.hot())[0], rgb(f.hot())[1], rgb(f.hot())[2]);
                }
                prev = p;
            }
            Vec3 h = waveWorld(f, i, head, gather, spread);
            mote(c, f, h, .03, f.strength * in * out * boost * .9F, 0xFFFFFF);
        }
    }

    // ------------------------------------------------------------------ the core

    private static double coreRadius(Frame f) {
        // The stored qi share (QiHud's ratio) scales the core from pill to pill.
        return .062 * (.7 + .9 * PillScene.qiShare()) * PillScenePlan.coreScale(f.T, f.age);
    }

    /** White-hot light at the dantian with turning rays (heavenly pill refs), the flash and its shock ring, then a warm ember. */
    private static void drawCore(VertexConsumer c, Frame f) {
        float scale = PillScenePlan.coreScale(f.T, f.age), alpha = PillScenePlan.coreAlpha(f.T, f.age) * f.strength;
        if (scale <= 0 || alpha <= 0) return;
        Vec3 pos = f.belly().add(f.front.scale(.05));
        double radius = coreRadius(f);
        float b = f.age - burst(f.T);
        boolean flashes = io.github.verycooltimo.murim.client.ClientConfig.SCREEN_FLASHES.get();
        if (b < 0) {
            float build = PillScenePlan.smooth((f.age - PillScenePlan.core(f.T)) / BURST_AFTER);
            float pulse = 1 + .1F * Mth.sin(f.age * 1.9F);
            mote(c, f, pos, radius * (4.0 + 2.6 * build) * pulse, alpha * (.3F + .3F * build), f.colour);
            mote(c, f, pos, radius * 2.4 * pulse, alpha * .8F, f.light());
            mote(c, f, pos, radius * 1.2 * pulse, alpha, 0xFFFFFF);
            for (int i = 0; i < 10; i++) {
                double a = i * Math.PI * 2 / 10 + f.age * .04 + (i % 2) * .15;
                ray(c, f, pos, a, radius * .9, radius * (2.8 + 2.6 * build + (i % 3) * .6), radius * .32, alpha * (.25F + .4F * build), i % 2 == 0 ? 0xFFFFFF : f.light());
            }
            return;
        }
        float flash = 1 - PillScenePlan.smooth(b / 9F), tint = 1 - PillScenePlan.smooth(b / 18F);
        float cap = flashes ? 1F : .5F;
        double grow = .3 + .9 * PillScenePlan.smooth(b / 4F);
        mote(c, f, pos, grow * 1.5, f.strength * tint * .5F * cap, f.colour);
        mote(c, f, pos, grow, f.strength * flash * cap, 0xFFFFFF);
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24 + (i % 3) * .13;
            double len = (.7 + (i * 7 % 5) * .2) * (.4 + .6 * PillScenePlan.smooth(b / 3F));
            ray(c, f, pos, a, .04, len, .035 + (i % 2) * .02, f.strength * flash * .7F * cap, i % 2 == 0 ? 0xFFFFFF : f.light());
        }
        // The shock ring: an air wave running outward round the belly, level, hugging nothing.
        float r = b / 9F;
        if (r < 1) ring(c, f, f.axis(), .2 + 1.3 * r, .06 * (1 - .5 * r), f.strength * (1 - r) * .7F * cap, f.hot(), false);
        int e = PillScenePlan.start(f.T, PillScenePlan.Beat.EXHALE);
        float warm = PillScenePlan.smooth((f.age - e) / (PillScenePlan.total(f.T) - e));
        int hue = lerpRgb(f.light(), WARM, warm);
        float breath = 1 + .08F * Mth.sin(f.age * .5F);
        mote(c, f, pos, radius * 3.2 * breath, alpha * .4F, hue);
        mote(c, f, pos, radius * 1.3 * breath, alpha * .9F, lerpRgb(hue, 0xFFFFFF, .6F));
    }

    /** A ring of light round {@code centre}: horizontal (the air wave) or on the torso plane (the landing ripple). */
    private static void ring(VertexConsumer c, Frame f, Vec3 centre, double radius, double half, float alpha, int rgb, boolean onTorso) {
        if (alpha <= .01F || radius <= 0) return;
        float[] k = rgbf(rgb);
        Vec3 ax = onTorso ? f.right : new Vec3(1, 0, 0), ay = onTorso ? f.upUnit : new Vec3(0, 0, 1);
        int n = 28;
        Vec3 prev = null;
        for (int i = 0; i <= n; i++) {
            double a = i * Math.PI * 2 / n;
            double x = Math.cos(a) * radius, y = Math.sin(a) * radius * (onTorso ? .9 : 1);
            if (onTorso) { x = Mth.clamp(x, -3.5 * f.unit, 3.5 * f.unit); }
            Vec3 p = centre.add(ax.scale(x)).add(ay.scale(y));
            if (prev != null) {
                seg(c, f.pose, prev, p, f.cam, half * 2.4, alpha * .25F, k[0], k[1], k[2]);
                seg(c, f.pose, prev, p, f.cam, half * .8, alpha, 1, 1, 1);
            }
            prev = p;
        }
    }

    /** One cell of the painted plum-petal atlas (2x2), turned by {@code spin}; {@code size} is the half width. */
    private static void petal(VertexConsumer c, Frame f, Vec3 centre, double size, int cell, float spin, float alpha, float r, float g, float b) {
        if (alpha <= 0.01F) return;
        Vec3 fw = f.cam.subtract(centre);
        if (fw.lengthSqr() < 1e-6) return;
        fw = fw.normalize();
        Vec3 ref = Math.abs(fw.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 r0 = fw.cross(ref).normalize(), u0 = r0.cross(fw).normalize();
        double cs = Math.cos(spin), sn = Math.sin(spin);
        Vec3 right = r0.scale(cs).add(u0.scale(sn)).scale(size), up = u0.scale(cs).subtract(r0.scale(sn)).scale(size * .6);
        float tu = (cell % 2) / 2F, tv = (cell / 2) / 2F;
        VfxDraw.vertex(c, f.pose, centre.subtract(right).subtract(up), fw, tu, tv + .5F, alpha, r, g, b);
        VfxDraw.vertex(c, f.pose, centre.add(right).subtract(up), fw, tu + .5F, tv + .5F, alpha, r, g, b);
        VfxDraw.vertex(c, f.pose, centre.add(right).add(up), fw, tu + .5F, tv, alpha, r, g, b);
        VfxDraw.vertex(c, f.pose, centre.subtract(right).add(up), fw, tu, tv, alpha, r, g, b);
    }

    /** A soft light streak from {@code from} to {@code to} radii at screen angle {@code a} (the mote texture stretched). */
    private static void ray(VertexConsumer c, Frame f, Vec3 centre, double a, double from, double to, double half, float alpha, int rgb) {
        if (linePass) return;
        if (alpha <= 0.01F || to <= from) return;
        Vec3 fw = f.cam.subtract(centre).normalize();
        Vec3 ref = Math.abs(fw.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 r = fw.cross(ref).normalize(), u = r.cross(fw).normalize();
        Vec3 dir = r.scale(Math.cos(a)).add(u.scale(Math.sin(a))), side = fw.cross(dir).normalize().scale(half);
        Vec3 p0 = centre.add(dir.scale(from)), p1 = centre.add(dir.scale(to));
        float[] k = rgbf(rgb);
        VfxDraw.vertex(c, f.pose, p0.subtract(side), fw, 0, 1, alpha, k[0], k[1], k[2]);
        VfxDraw.vertex(c, f.pose, p0.add(side), fw, 1, 1, alpha, k[0], k[1], k[2]);
        VfxDraw.vertex(c, f.pose, p1.add(side), fw, 1, 0, alpha, k[0], k[1], k[2]);
        VfxDraw.vertex(c, f.pose, p1.subtract(side), fw, 0, 0, alpha, k[0], k[1], k[2]);
    }

    /** Camera-facing soft light spot of half size {@code h} (the mote texture, additive). */
    private static void mote(VertexConsumer c, Frame f, Vec3 centre, double h, float alpha, int rgb) {
        if (linePass) return;
        if (alpha <= 0.01F || h <= 0) return;
        Vec3 toCam = f.cam.subtract(centre);
        if (toCam.lengthSqr() < 1e-8) return;
        Vec3 fw = toCam.normalize();
        Vec3 ref = Math.abs(fw.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 r = fw.cross(ref).normalize().scale(h);
        Vec3 u = r.normalize().cross(fw).normalize().scale(h);
        float[] k = rgbf(rgb);
        VfxDraw.vertex(c, f.pose, centre.subtract(r).subtract(u), fw, 0, 1, alpha, k[0], k[1], k[2]);
        VfxDraw.vertex(c, f.pose, centre.add(r).subtract(u), fw, 1, 1, alpha, k[0], k[1], k[2]);
        VfxDraw.vertex(c, f.pose, centre.add(r).add(u), fw, 1, 0, alpha, k[0], k[1], k[2]);
        VfxDraw.vertex(c, f.pose, centre.subtract(r).add(u), fw, 0, 0, alpha, k[0], k[1], k[2]);
    }

    // ------------------------------------------------------------------ primitives

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
