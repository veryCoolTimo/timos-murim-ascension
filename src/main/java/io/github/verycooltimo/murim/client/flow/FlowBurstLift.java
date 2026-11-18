package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.camera.ShotPose;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.bt.BtBody;
import io.github.verycooltimo.murim.client.vfx.bt.BtCtx;
import io.github.verycooltimo.murim.client.vfx.fx.FxCultivationBench;
import io.github.verycooltimo.murim.client.vfx.fx.FxCultivationMotes;
import io.github.verycooltimo.murim.client.vfx.fx.FxEssence;
import io.github.verycooltimo.murim.client.vfx.fx.FxFlame;
import io.github.verycooltimo.murim.client.vfx.fx.FxFlameLook;
import io.github.verycooltimo.murim.client.vfx.fx.FxCultivationScene;
import io.github.verycooltimo.murim.client.vfx.fx.FxPerf;
import io.github.verycooltimo.murim.client.vfx.fx.FxQuality;
import io.github.verycooltimo.murim.client.vfx.fx.FxTune;
import io.github.verycooltimo.murim.cultivation.PillKind;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.util.TriState;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand elements of the high pill finale, round 8, rebuilt on the effect engine (client/vfx/fx) after the author's verdict of 16.11 ("cheap, why did
 * everything go back to cheap effects, why is everything the same"). TWO separate things:
 * <ul>
 * <li>{@code burstseat}: the normal end. The player stays SEATED, no lift. The red blood pill, as the reference «relasing qi» (a seated figure in a pale
 * red-white room full of light, dark red flakes and sparks).</li>
 * <li>{@code burstlift}: a flawless absorption of a HIGH pill ({@link PillKind#ORIGIN_ENERGY}, gold): anticipation, launch, burst at the top, hover,
 * descent, landing, each phase with its own picture. The body motion is {@link LiftCurve}. Reference: pill-sequence/6_final_explosion.png, its upper
 * part (the lifted figure in the burst). The lower part of that panel is another scene (two bystanders on a flower carpet).</li>
 * </ul>
 * Which engine primitive draws which layer: see {@link Frame#draw}. Everything is a pure function of the stand clock, except the dust and the motes lists,
 * which are rebuilt every frame from the clock too (no state), so a loop is exact. Rules: colour from the pill, nothing above 2 Hz, nothing pops, nothing
 * on the face, no name plate. Every number is {@code FxTune.f("flow.burst.<key>", default)}.
 */
@EventBusSubscriber(modid = "murim", value = Dist.CLIENT)
public final class FlowBurstLift {
    public static final String ELEMENT = "burstlift", SEAT = "burstseat";
    public static final double SECONDS = 8.5D, SECONDS_SEAT = 6.0D;

    private FlowBurstLift() { }

    public static boolean active() { return FlowStageClock.active() && (ELEMENT.equals(FlowStageClock.element()) || SEAT.equals(FlowStageClock.element())); }

    static boolean lifting() { return ELEMENT.equals(FlowStageClock.element()); }

    static double now(float partial) { return FlowStageClock.secondsAt(partial); }

    private static double k(String key, double def) { return FxTune.f("flow.burst." + key, (float) def); }

    /** Seconds before the lift begins (the body sits calm), and the burst time of the seated scene. */
    static double t0() { return k("t0", 1.5); }
    static double seatBurst() { return k("seat.burst", 1.9); }
    static double liftBurst() { return t0() + io.github.verycooltimo.murim.cultivation.PerfectLift.BURST / 20.0; }

    /** The pill of the scene: the lift is a flawless HIGH pill (gold Origin Energy, not the blood pill), the seated end is the red blood pill of the reference. */
    static PillKind pill() { return lifting() ? PillKind.ORIGIN_ENERGY : PillKind.BLOOD; }

    private static LiftCurve.Params curve() {
        return new LiftCurve.Params(k("lift.sink", .05), k("lift.over", .04), k("lift.bob", .04), k("lift.period", 2.5), k("lift.dip", .06), k("lift.squash", .03));
    }

    /** Height of the lifted body (blocks) at stand second {@code t}; the anticipation and the landing dip go a little below zero. */
    public static double lift(double t) {
        if (!lifting()) return 0;
        return LiftCurve.height((t - t0()) * 20.0, k("lift", 1.3), curve());
    }

    /** The stand's fixed shot; on the lift the camera follows part of the rise so the body is seen to go up in the frame. */
    public static ShotPose shot() {
        double follow = 0, dAz = 0, dPitch = 0;
        double t = now(0);
        if (Double.isFinite(t)) {
            if (lifting()) follow = k("cam.follow", .2) * Math.max(0, lift(t));
            // the damped shake: 0.35 s at the burst, a small one (0.25 s) at the landing; zero outside, eased out so it never stops short
            double b = shakeAt(t - (lifting() ? liftBurst() : seatBurst()), .35, k("shake.az", 1.1), 9);
            double l = lifting() ? shakeAt(t - (t0() + LiftCurve.TOUCH / 20.0), .25, k("shake.land", .35), 11) : 0;
            dAz = b + l; dPitch = (b + l) * .7;
        }
        // the lift scene stands further back so the figure visibly travels up in the frame (the head at the top of the hover sits about 28 per cent from the top)
        // seated scene: the figure about 45 per cent of the frame height, from the front, a little above; lift scene: about 32 per cent, so the rise fits and the head at the
        // top of the hover sits about 25 per cent from the top
        double dist = lifting() ? k("cam.dist.lift", 5.0) : k("cam.dist", 3.5), focus = lifting() ? k("cam.focus.lift", 1.34) : k("cam.focus", .75);
        return new ShotPose((float) (k("cam.az", 0) + dAz), (float) (k("cam.pitch", 11) + dPitch), (float) dist, (float) (focus + follow), 0, (float) k("cam.fov", .7));
    }

    /** A damped shake of {@code amp} degrees lasting {@code len} seconds at {@code hz}: exp decay, eased to zero at the end. */
    static double shakeAt(double s, double len, double amp, double hz) {
        if (s < 0 || s > len) return 0;
        return amp * Math.exp(-s / (len * .35)) * Math.sin(2 * Math.PI * hz * s) * (1 - sm(len * .6, len, s));
    }

    // ------------------------------------------------------------------ the body and the name plate

    private static boolean pushed;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onPlayerPre(RenderPlayerEvent.Pre event) {
        if (!active() || event.isCanceled() || event.getEntity() != Minecraft.getInstance().player) return;
        double t = now(event.getPartialTick());
        pushed = true;
        event.getPoseStack().pushPose();
        if (lifting() && Double.isFinite(t)) {
            event.getPoseStack().translate(0, lift(t), 0);
            double sq = LiftCurve.squash((t - t0()) * 20.0, curve());
            if (sq != 1) event.getPoseStack().scale(1F, (float) sq, 1F);
        }
    }

    @SubscribeEvent
    static void onPlayerPost(RenderPlayerEvent.Post event) {
        if (!pushed || event.getEntity() != Minecraft.getInstance().player) return;
        pushed = false;
        event.getPoseStack().popPose();
    }

    @SubscribeEvent
    static void onNameTag(RenderNameTagEvent event) {
        if (active() && event.getEntity() == Minecraft.getInstance().player) event.setCanRender(TriState.FALSE);
    }

    // ------------------------------------------------------------------ the frame

    private static final FxQuality QUALITY = new FxQuality();
    private static final FxCultivationMotes MOTES = new FxCultivationMotes(7, new int[] {0xFFFFFF}, new double[] {1}, new int[] {0xFFFFFF}, 0, 0);
    private static double lastT = -1;

    static {
        try { QUALITY.force(Integer.parseInt(System.getProperty("murim.fx.tier", "-1"))); } catch (NumberFormatException ignored) { }
    }

    /** The stand: the scene's pass at AFTER_LEVEL (where the flow director draws; the figure's depth is in the buffer, so the figure stands inside the light). */
    @SubscribeEvent
    static void onLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !active()) return;
        Minecraft mc = Minecraft.getInstance();
        double t = FlowStageClock.renderedSeconds();
        double end = lifting() ? SECONDS : SECONDS_SEAT;
        if (mc.player == null || !Double.isFinite(t) || t < 0 || t > end) return;
        Vec3 camera = event.getCamera().getPosition();
        BtBody body = BtBody.of(mc.player, camera);
        if (body == null) return;
        PoseStack stack = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        buffers.endBatch();
        mc.getMainRenderTarget().bindWrite(false);
        stack.pushPose();
        try {
            stack.mulPose(event.getModelViewMatrix());
            stack.translate(-camera.x, -camera.y, -camera.z);
            double dt = lastT < 0 || t < lastT ? 0 : t - lastT;
            lastT = t;
            FxPerf.frameStart();
            // the film's software renderer is slow by construction and would walk the governor down to its coarsest tier: the stand (capture env) holds tier 0
            if (System.getenv("MURIM_BENCH_FROM") == null) QUALITY.update(FxPerf.lastFrameMs(), dt);
            new Frame(new BtCtx(mc, body, camera, buffers, stack, t, 0F), lifting(), pill()).draw();
        } finally {
            stack.popPose();
        }
    }

    // ------------------------------------------------------------------ the game's finale (the scroll game drives the same frame)

    /** What the game hands over for a frame of its finale: colour, the lift height of the real lift, a gate (0..1) and the backlash flag. */
    private record Game(int hue, double lift, double gate, boolean backlash) { }

    /**
     * The finale of the high pill game on these layers, called from the game's space pass ({@link FlowSpaceFx}) after the aura. Three endings:
     * a flawless high pill (the real lift: {@code ClientPillState.finaleAge} in ticks, negative while the lift waits, the body's height is {@code ClientPillState.lift}),
     * a clean end without a lift (the seated burst, timed from the game's burst moment {@code FlowSpaceMath.burstAt}) and the backlash (the same sheets in a red-shifted,
     * darkened tone, thrown out and falling). The scene's clock is chosen so the game's moments are the scene's moments: nothing waits, nothing is faked.
     *
     * @param ending 0 clean, 1 backlash; {@code te} seconds since the end of the stream; {@code hue} the pill's colour 0xRRGGBB
     */
    static void drawGame(PoseStack.Pose pose, Vec3 camera, net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers, int ending, double te, int hue, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || ending < 0 || ending > 1) return;
        boolean lift = ending == 0 && io.github.verycooltimo.murim.client.ClientPillState.finaleLift();
        double t, gate = 1, liftH = 0;
        int rgb = hue;
        if (ending == 1) {
            // backlash: the burst of the seated scene at the moment the black ball has formed; the tone goes toward a dark red; the whole scene fades in over 0.2 s
            t = seatBurst() + (te - FlowLook.BURST_BALL);
            gate = sm(FlowLook.BURST_BALL - .25, FlowLook.BURST_BALL, te);
            rgb = mixRgb(mixRgb(hue, 0xC0201E, .7), 0x000000, k("back.dark", .35));
        } else if (lift) {
            double age = io.github.verycooltimo.murim.client.ClientPillState.finaleAge() + partial;
            t = t0() + age / 20.0;
            liftH = io.github.verycooltimo.murim.client.ClientPillState.lift(partial);
        } else {
            t = seatBurst() + (te - FlowSpaceMath.burstAt());
        }
        double end = lift ? SECONDS : SECONDS_SEAT;
        if (t < .1 || t > end || gate <= .004) return;
        // the scroll shifts the game camera sideways: the scene faces the camera's VIEW direction, not the line to the camera's position
        double[] rest = io.github.verycooltimo.murim.client.camera.FlowCamera.restPose();
        Vec3 face = camera;
        if (rest != null) { double yr = Math.toRadians(rest[3]); face = new Vec3(mc.player.getX() + Math.sin(yr) * 6, camera.y, mc.player.getZ() - Math.cos(yr) * 6); }
        BtBody body = BtBody.of(mc.player, face);
        if (body == null) return;
        PoseStack stack = new PoseStack();
        stack.last().pose().set(pose.pose());
        stack.last().normal().set(pose.normal());
        FxPerf.frameStart();
        if (!FlowTick.ON) QUALITY.update(FxPerf.lastFrameMs(), .016);   // the film's software renderer would walk the governor down to its coarsest tier: the film holds tier 0
        new Frame(new BtCtx(mc, body, camera, buffers, stack, t, partial), lift, lift ? PillKind.ORIGIN_ENERGY : PillKind.BLOOD, new Game(rgb, liftH, gate, ending == 1)).draw();
    }

    private static int warmFrames;

    /**
     * Draws one frame of the seated burst at a gate of 0.002 (invisible) a few times early in the game: the render types and shaders of these layers are compiled and the
     * lists allocated then, not in the middle of the finale (on a slow software renderer the first use stalled the picture for a second).
     */
    static void warmGame(PoseStack.Pose pose, Vec3 camera, net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers, double clock, float partial) {
        if (clock < FlowLook.INTRO_LEAD + .5) { warmFrames = 0; return; }
        if (warmFrames >= 3) return;
        warmFrames++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        BtBody body = BtBody.of(mc.player, camera);
        if (body == null) return;
        PoseStack stack = new PoseStack();
        stack.last().pose().set(pose.pose());
        stack.last().normal().set(pose.normal());
        new Frame(new BtCtx(mc, body, camera, buffers, stack, seatBurst() + .35, partial), false, PillKind.BLOOD, new Game(0xE8B040, 0, .002, false)).draw();
    }

    // ------------------------------------------------------------------ maths

    private static double sm(double a, double b, double x) {
        double q = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return q * q * (3 - 2 * q);
    }

    private static double outCubic(double x) { double q = 1 - Math.max(0, Math.min(1, x)); return 1 - q * q * q; }

    private static double hash(int i, int salt) {
        double v = Math.sin(i * 127.1 + salt * 311.7) * 43758.5453;
        return v - Math.floor(v);
    }

    private static int mixRgb(int a, int b, double q) {
        q = Math.max(0, Math.min(1, q));
        int r = (int) Math.round((a >> 16 & 255) * (1 - q) + (b >> 16 & 255) * q), g = (int) Math.round((a >> 8 & 255) * (1 - q) + (b >> 8 & 255) * q), bl = (int) Math.round((a & 255) * (1 - q) + (b & 255) * q);
        return r << 16 | g << 8 | bl;
    }

    private static final int WHITE = 0xFFFFFF;

    // ------------------------------------------------------------------ one frame

    /**
     * What draws what (author 16.11 night: essence as cloth, no sharp edges, nothing into the ground, no dust, no pile-up of layers):
     * <ul>
     * <li>light: {@link FxCultivationScene.Light} meshes and a ground disc (the aura's own light primitives);</li>
     * <li>essence as cloth: {@link FxEssence.Strip} sheets painted by {@link FxFlame} in its CLOTH mode (soft broad sheets, feathered edges; the look numbers and the
     * mode are the aura's, FlowAura) in the pill's tones: sheets thrown out from the dantian at the burst, cloth hugging the body and a tail off the feet on the lift;</li>
     * <li>soft specks: {@link FxCultivationMotes.Mote} through {@link FxCultivationBench#motes}.</li>
     * </ul>
     */
    private static final class Frame {
        final BtCtx c;
        final boolean lift;
        final double t, floorY, L, burstAt;
        final Vec3 feet, right, near, camera, cr, cu;
        /** The body's own points in the body frame (x right, y up from the floor, z towards the camera): chest, dantian, head. */
        final double[] chest, dantian, head;
        final int col, core, pale, deep, hot;
        final double scale;
        /** The game's hand-over (null on the stand): its colour, the real lift height, the gate and the backlash flag. */
        final Game game;

        Frame(BtCtx c, boolean lift, PillKind pill) { this(c, lift, pill, null); }

        Frame(BtCtx c, boolean lift, PillKind pill, Game game) {
            this.game = game;
            this.c = c; this.lift = lift; this.t = c.labSeconds();
            BtBody b = c.body();
            this.floorY = b.ground();
            this.feet = new Vec3(b.axis().x, floorY, b.axis().z);
            this.right = b.right(); this.near = b.near(); this.camera = c.camera();
            this.L = game != null ? game.lift() : lift ? lift(t) : 0;
            var cam = c.mc().gameRenderer.getMainCamera();
            var left = cam.getLeftVector();
            this.cr = new Vec3(-left.x(), -left.y(), -left.z());
            this.cu = new Vec3(cam.getUpVector());
            this.chest = frame(b.bone(Bone.CHEST)); this.dantian = frame(b.bone(Bone.DANTIAN)); this.head = frame(b.bone(Bone.HEAD));
            this.col = game != null ? game.hue() : (int) k("hue", PillScene.hue(pill.flow().colour()));
            this.core = (int) k("core.hue", pill.flow().core());
            this.pale = mixRgb(col, core, k("pale", .55));
            this.hot = mixRgb(col, WHITE, k("hot", .7));
            this.deep = mixRgb(col, 0x000000, .45);
            this.burstAt = lift ? liftBurst() : seatBurst();
            this.scale = QUALITY.particleScale();
        }

        double gate() { return game == null ? 1 : game.gate(); }

        Vec3 frameToWorld(double x, double y, double z) { return feet.add(right.scale(x)).add(0, y, 0).add(near.scale(z)); }

        double[] frame(Vec3 w) { Vec3 d = w.subtract(feet); return new double[] {d.dot(right), d.y, d.dot(near)}; }

        double s() { return t - burstAt; }

        double yc() { return Math.max(.4, chest[1]); }

        double touch() { return t0() + LiftCurve.TOUCH / 20.0; }

        /** The full soft aura stands for a second, is pulled INTO the dantian over 0.4 s (the lift scene: the anticipation; the seated scene: just before the burst), then the burst throws it out. */
        double pullEnd() { return lift ? t0() : burstAt; }
        double pull() { return sm(pullEnd() - .4, pullEnd(), t); }

        double end() { return lift ? SECONDS : SECONDS_SEAT; }

        /** The share of the draw-in on the descent (lift) or 0. */
        double absorb() { return lift ? sm(t0() + 4.0, t0() + 5.1, t) : 0; }

        void draw() {
            lights();
            essence();
            particles();
            FxPerf.draw();
            if ((int) (t * 20) % 20 == 0) io.github.verycooltimo.murim.MurimMod.LOGGER.info("FlowBurstLift cloth sheets {} light beams {}", sheets, beamCount);
            FxPerf.frameEnd(lift ? ELEMENT : SEAT, t, "fx", line -> io.github.verycooltimo.murim.MurimMod.LOGGER.info(line));
        }

        // ---------------------------------------------------------------- lights

        void put(VertexConsumer out, Vec3 p, int rgb, double a) {
            int al = (int) Math.round(Math.max(0, Math.min(1, a * gate())) * 255);
            out.addVertex(c.pose(), (float) p.x, (float) p.y, (float) p.z).setColor(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, al).setUv(0, 0).setUv1(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
        }

        /** One soft light (the aura's mesh): the body frame's z axis points at the camera, so the mesh faces it with (cx, cz) = (0, 1). */
        void light(VertexConsumer out, double x, double y, double z, double hw, double hh, int rgb, double a) {
            if (a < .004) return;
            var l = new FxCultivationScene.Light("burst", x, y, z, hw, hh, rgb, a);
            for (float[] v : FxCultivationScene.mesh(l, 0, 1, QUALITY.grid())) put(out, frameToWorld(v[0], v[1], v[2]), rgb, v[3]);
        }

        /** A disc or a ring on the floor with a filled middle, brightest at {@code ring} of the outer radius, soft edge; {@code r0} the inner radius of a ring. */
        void floor(VertexConsumer out, double r, double ring, double a, int rgb, double r0, double phase) {
            if (a < .004) return;
            int spokes = QUALITY.spokes(), rings = QUALITY.rings();
            for (int s = 0; s < spokes; s++) {
                for (int q = 0; q < rings; q++) {
                    int[][] corner = {{s, q}, {s + 1, q}, {s + 1, q + 1}, {s, q + 1}};
                    for (int[] cc : corner) {
                        double th = (cc[0] % spokes) * 2 * Math.PI / spokes, rho = cc[1] / (double) rings;
                        double rad = r0 > 0 ? r : r * (1 + .06 * Math.sin(3 * th + phase) + .04 * Math.sin(5 * th - phase * .7 + 1));
                        double prof = rho < ring ? (r0 > 0 ? sm(0, ring, rho) : .45 + .55 * sm(0, ring, rho)) : Math.pow(1 - (rho - ring) / (1 - ring), 2.3);
                        double rr = r0 + (rad - r0) * rho;
                        put(out, frameToWorld(Math.sin(th) * rr, .03, Math.cos(th) * rr), rgb, a * prof);
                    }
                }
            }
        }

        void lights() {
            var type = MurimRenderTypes.presetLight();
            VertexConsumer out = c.buffers().getBuffer(type);
            double s = s();
            double ga = sm(.1, .7, t) * (1 - .55 * pull());
            double g = t < pullEnd() ? ga : (lift ? .45 + .4 * sm(pullEnd() + .4, burstAt, t) : .45);
            // the flash: a single white-hot disc, eased in over 0.3 s, above 70 per cent for about 0.2 s, never larger than the figure's height
            double flash = sm(-.25, 0, s) * (1 - sm(0, .25, s));
            double peak = sm(-.1, .15, s) * (1 - sm(.5, 1.6, s));
            double mass = s < 0 ? 0 : sm(0, .6, s) * (1 - sm(1.6, 4.0, s));
            double rest = lift ? 1 - sm(touch(), touch() + 1.4, t) * .85 : 1 - sm(burstAt + 2.4, burstAt + 3.8, t) * .85;
            double glow = g * (.65 + .35 * mass) * rest;
            double cx = chest[0], cy = yc();
            // the lamp: a pill-coloured haze behind the torso, held round the burst and dying with it
            light(out, cx, cy + .1, -.6, 1.5, 1.6, col, k("lamp.a", .5) * glow * (1 - .5 * flash));
            light(out, cx, cy, -.35, .85, 1.0, pale, k("lamp.a2", .36) * glow * (1 - .5 * flash));
            light(out, cx, cy, -.3, k("flash.r", 1.5), k("flash.r", 1.5), mixRgb(pale, WHITE, .5), k("flash.a", .9) * flash);
            // the room: a few wide lights on all sides, symmetric round the figure (left, right, above, below), behind its plane, nothing across the face
            double room = mass * k("room.a", .2);
            double[][] spots = {{-2.3, 0, -1.2, 2.0, 1.9}, {2.3, 0, -1.2, 2.0, 1.9}, {0, 2.0, -1.6, 2.5, 1.6}, {0, -.9, -1.0, 2.6, 1.1}};
            for (int i = 0; i < spots.length; i++) light(out, cx + spots[i][0], Math.max(spots[i][4] * .5 + .1, cy + spots[i][1]), spots[i][2], spots[i][3], spots[i][4], i < 2 ? hot : col, room * (1 + .04 * Math.sin(t * .8 + i * 1.7)));
            // the light on the body, small and low (belly and rim, never the face)
            light(out, cx - .5, cy - .1, .3, .26, .8, hot, (k("rim.a", .38) + .4 * peak) * glow);
            light(out, cx + .5, cy - .1, .3, .26, .8, pale, (k("rim.a", .38) + .4 * peak) * glow);
            // the figure lit by the pill colour at the peak: a soft light in front of the torso and the lap, under the face
            light(out, cx, cy - .35, .7, 1.0, .65, col, k("lit.a", .38) * peak);
            light(out, cx, dantian[1], .5, .55, .45, hot, k("belly.a", .34) * g * rest * (1 - .5 * flash));
            // the column under the lifted body: one tall light that keeps off the floor (its foot is a hand above it)
            if (lift) {
                double hk = Math.max(0, L), e = sm(t0() + .4, t0() + 1.2, t) * (1 - sm(touch() - .3, touch() + .6, t));
                double top = hk + .65, foot = .2, mid = (top + foot) / 2, half = (top - foot) / 2;
                light(out, cx, mid, -.3, .7, Math.max(.25, half), pale, k("col.a", .3) * e);
            }
            // the floor: the light under the body, and the soft ring of the landing
            double hk = lift ? Math.max(0, L) / 1.3 : 0;
            double poolA = k("pool.a", .5) * (.25 + .75 * g) * rest * (1 + .3 * mass) * (game != null ? k("pool.game", .45) : 1);   // in the game the director's floor light adds to it: half as strong
            floor(out, k("pool.r", 4.0) + k("pool.rl", 1.0) * hk + 3.0 * mass, .3, poolA, col, 0, t * .25);
            floor(out, 2.0 + .6 * hk, .35, poolA * .7, pale, 0, -t * .2);
            if (lift) {
                double u = t - touch();
                if (u > 0 && u < 2.2) {
                    double e = sm(0, .3, u) * (1 - sm(.8, 2.2, u)), rr = .5 + 2.4 * outCubic(u / 2.0);
                    floor(out, rr + .6, .75, k("ring.a", .4) * e, hot, Math.max(0, rr - .35), 0);
                }
            }
            beams(out);
            c.buffers().endBatch(type);
            FxPerf.draw();
        }

        int beamCount;

        /** 8-12 broad soft beams from the dantian behind everything: elongated soft light meshes (the aura's mesh), turned in the camera plane, uneven lengths, 0.4-1.2 blocks wide, alpha capped near 0.35. */
        void beams(VertexConsumer out) {
            double s = s();
            beamCount = 0;
            if (s < -.05 || s > 1.4) return;
            double env = sm(-.05, .25, s) * (1 - sm(.5, 1.1, s));
            if (env < .01) return;
            double fs = lift ? 1.4 : 1;
            int n = (int) Math.round(k("beams", 13) * Math.max(.6, scale));
            for (int i = 0; i < n; i++) {
                double ang = (i + .5) / n * 2 * Math.PI + (hash(i, 1) - .5) * .7 * (2 * Math.PI / n);
                double len = (3.0 + 4.0 * hash(i, 2)) * fs * (.6 + .4 * outCubic(s / .5)), half = (.25 + .75 * Math.pow(hash(i, 3), 1.6)) * Math.min(fs, 1.2);
                double ca = Math.cos(ang), sa = Math.sin(ang), mid = len * .55;
                var l = new FxCultivationScene.Light("beam", 0, 0, 0, mid, half, i % 2 == 0 ? hot : pale, k("beam.a", .55) * env * (.75 + .25 * hash(i, 4)));
                for (float[] v : FxCultivationScene.mesh(l, 0, 1, QUALITY.grid())) {
                    double lx = v[0], ly = v[1];
                    double rx = lx + mid, ry = ly;                  // the ellipse's tip sits on the dantian
                    put(out, frameToWorld(dantian[0] + rx * ca - ry * sa, dantian[1] + rx * sa + ry * ca, dantian[2] - 1.2), i % 2 == 0 ? hot : pale, v[3]);
                }
                beamCount++;
            }
        }

        // ---------------------------------------------------------------- the essence (FxEssence: the torn cel-shaded matter the author means by "essence as cloth")

        // ---------------------------------------------------------------- the essence as cloth: FxEssence.Strip sheets painted by FxFlame's CLOTH mode (soft broad sheets, feathered edges)

        /** The cloth looks of the aura (FlowAura's numbers), in the pill's tones: its body tone capped, a deep tone, a pale core low down (at the root of a sheet). */
        FxFlameLook[] looks(double rise) {
            float[] pc = {(col >> 16 & 255) / 255F, (col >> 8 & 255) / 255F, (col & 255) / 255F};
            float m = Math.max(.01F, Math.max(pc[0], Math.max(pc[1], pc[2]))), cap = Math.min(m, (float) k("tone.cap", .72));
            float[] body = {pc[0] / m * cap, pc[1] / m * cap, pc[2] / m * cap};
            int deepC = pack(body, 0, .4), mid = pack(body, 0, 1), paleC = pack(body, k("tone.pale", .2), 1);
            int[] cols = {deepC, mid, mid, paleC, paleC};
            float r = (float) rise;
            return new FxFlameLook[] {
                    cloth(.11F, .45F * r, .55F, .28F, -.3F, -.15F, .2F, .15F, cols, .7F, .78F),
                    cloth(.15F, .55F * r, .65F, .24F, -.28F, -.12F, .2F, .15F, cols, .7F, .74F),
                    cloth(.1F, .4F * r, .45F, .38F, -.25F, -.05F, .25F, .12F, cols, .7F, .7F)};
        }

        FxFlameLook cloth(float tiles, float rise, float warp, float squash, float low, float high, float fadeFrom, float pale, int[] cols, float aDeep, float aBody) {
            return new FxFlameLook(tiles, rise, warp, squash, low, high, 1F, 0F, .2F, .38F, 0F, 0F, cols, new float[] {aDeep, aBody, aBody, aBody, aBody}, new float[] {1F, 1F, .95F, .8F},
                    new float[] {1F, .16F, fadeFrom, pale + .15F});
        }

        int pack(float[] c, double lift, double dim) {
            int r = Math.round((float) (Math.min(1, c[0] + (1 - c[0]) * lift) * dim * 255)), g = Math.round((float) (Math.min(1, c[1] + (1 - c[1]) * lift) * dim * 255)), b = Math.round((float) (Math.min(1, c[2] + (1 - c[2]) * lift) * dim * 255));
            return r << 16 | g << 8 | b;
        }

        record Drawn(FxEssence.Strip strip, FxFlameLook look, double depth) { }

        /** A strip from a root edge to a tip edge (n + 1 points each, world); the root is the shader's foot (rises out of nothing), the tip its top (melts away). Columns sorted far to near. */
        Drawn strip(Vec3[] root, Vec3[] tip, double seed, double alpha, double growth, FxFlameLook look, double depth) {
            int n = root.length - 1;
            Integer[] idx = new Integer[n];
            double[] d = new double[n];
            for (int j = 0; j < n; j++) {
                idx[j] = j;
                d[j] = -camera.distanceTo(root[j].add(root[j + 1]).add(tip[j]).add(tip[j + 1]).scale(.25));
            }
            java.util.Arrays.sort(idx, java.util.Comparator.comparingDouble(j -> d[j]));
            int[] order = new int[n];
            for (int j = 0; j < n; j++) order[j] = idx[j];
            double hw = Math.max(.1, tip[0].distanceTo(tip[n]) / 2), hh = Math.max(.1, root[n / 2].distanceTo(tip[n / 2]) / 2);
            return new Drawn(new FxEssence.Strip(tip, root, order, hw, hh, seed, growth, (float) (alpha * gate())), look.fitted(Math.max(hw, root[0].distanceTo(root[n]) / 2)), depth);
        }

        /** A sheet thrown out from the dantian: {@code rho} the distance of its root, {@code len} its length, {@code w0}/{@code w1} the half widths at the root and the tip. */
        Drawn radial(double theta, double dz, double rho, double len, double w0, double w1, double bend, double seed, double alpha, double growth, FxFlameLook look, double[] o, double yFloor) {
            double rx = cr.dot(right), rz = cr.dot(near), ux = cu.dot(right), uy = cu.y, uz = cu.dot(near);
            double ca = Math.cos(theta), sa = Math.sin(theta);
            double dx = ca * rx + sa * ux, dy = sa * uy, dzz = ca * rz + sa * uz + dz;
            double dl = Math.sqrt(dx * dx + dy * dy + dzz * dzz);
            dx /= dl; dy /= dl; dzz /= dl;
            Vec3 d = right.scale(dx).add(0, dy, 0).add(near.scale(dzz));
            Vec3 mid = frameToWorld(o[0] + dx * (rho + len / 2), o[1] + dy * (rho + len / 2), o[2] + dzz * (rho + len / 2));
            Vec3 p = d.cross(camera.subtract(mid).normalize());
            p = p.lengthSqr() < 1e-8 ? cr : p.normalize();
            int n = 4;
            Vec3[] root = new Vec3[n + 1], tip = new Vec3[n + 1];
            for (int j = 0; j <= n; j++) {
                double u = -1 + 2.0 * j / n;
                Vec3 r0 = frameToWorld(o[0] + dx * rho, o[1] + dy * rho, o[2] + dzz * rho).add(p.scale(w0 * u));
                Vec3 t0 = frameToWorld(o[0] + dx * (rho + len), o[1] + dy * (rho + len), o[2] + dzz * (rho + len)).add(p.scale(w1 * u + bend * (1 - u * u)));
                root[j] = new Vec3(r0.x, Math.max(r0.y, yFloor), r0.z);
                tip[j] = new Vec3(t0.x, Math.max(t0.y, yFloor), t0.z);
            }
            return strip(root, tip, seed, alpha, growth, look, o[2] + dzz * (rho + len / 2));
        }

        /** A partial cone round the body (azimuth {@code rel} in degrees from the camera's side, 0 = towards the camera): root edge at {@code y0}, tip edge {@code h} above it (negative: hanging down). */
        Drawn cone(double rel, double span, double r0, double flare, double y0, double h, double lean, double seed, double alpha, double growth, FxFlameLook look) {
            int n = 8;
            Vec3[] root = new Vec3[n + 1], tip = new Vec3[n + 1];
            double mid = Math.toRadians(rel), sp = Math.toRadians(span), ln = Math.toRadians(lean), rt = Math.max(.15, r0 + flare * Math.abs(h));
            for (int j = 0; j <= n; j++) {
                double a = mid - sp / 2 + sp * j / n;
                root[j] = frameToWorld(chest[0] + Math.sin(a) * r0, y0, chest[2] + Math.cos(a) * r0);
                tip[j] = frameToWorld(chest[0] + Math.sin(a + ln) * rt, y0 + h, chest[2] + Math.cos(a + ln) * rt);
            }
            return strip(root, tip, seed, alpha, growth, look, Math.cos(mid) * r0);
        }

        int sheets;

        void essence() {
            if (!FxFlame.available()) return;
            FxFlame.warm();
            double s = s(), ab = absorb(), cy = yc();
            List<Drawn> list = new ArrayList<>();
            FxFlameLook[] lk = looks(1), tail = looks(-1);
            double fs = lift ? 1.35 : 1;
            // 0. the aura at full level (the game's picture before the burst): the soft cloth stands behind and round the figure, then is pulled INTO the dantian over 0.4 s
            double pl = pull(), au = game != null ? 0 : sm(.1, .7, t) * (1 - sm(pullEnd() - .15, pullEnd(), t));
            if (au > .02) {
                double[][] aura = {
                        // rel, span, r0, flare, y0, h, lean, alpha, look
                        {180, 230, 2.0, .5, .5, 4.2, 40, .6, 0}, {180, 150, 1.45, .45, .12, 3.7, 55, .85, 0}, {138, 100, 1.05, .35, .15, 3.1, -50, .8, 1},
                        {-142, 105, 1.1, .38, .15, 3.3, 60, .78, 1}, {98, 80, 1.25, .4, .18, 2.6, 65, .7, 2}, {-100, 85, 1.3, .4, .18, 2.7, -60, .7, 2},
                        {30, 70, .98, .2, .1, 1.25, 50, .7, 2}, {-38, 64, 1.12, .2, .1, 1.15, -55, .66, 2}};
                for (int i = 0; i < aura.length; i++) {
                    double[] r = aura[i];
                    double h = r[5] * (1 - .6 * pl), y0 = r[4] + (dantian[1] - r[4]) * .55 * pl;
                    list.add(cone(r[0], r[1], r[2] * (1 - .9 * pl), r[3] * (1 - pl), y0, h, r[6] * (h / 3.5), 81 + i, r[7] * au * (1 - .4 * pl), 1, lk[(int) r[8]]));
                }
            }
            // 1. the burst: 14-18 broad cloth sheets (3-7 blocks long, 0.8-2.4 half-width) thrown OUT from the chest in all directions, also down and forward over the floor in front
            //    (they fade before it); in three depths: behind the figure (dense), sideways at its depth (they start at the edge of the silhouette), in front below the chest (lighter).
            //    Each grows out over 0.35 s, then keeps travelling outward while it thins (the pattern is part of the sheet) and melts away.
            if (s > -.05 && s < 2.4) {
                double[] o = {chest[0], chest[1], chest[2] - .1};
                double yFloor = floorY + .25;
                int n = (int) Math.round(16 * Math.max(.75, scale));
                for (int i = 0; i < n; i++) {
                    double deg = -50 + 280.0 * (i + .5) / n + (hash(i, 1) - .5) * 8, theta = Math.toRadians(deg), ca = Math.cos(theta), sa = Math.sin(theta);
                    boolean side = Math.abs(ca) > .9, down = sa < -.2;
                    double delay = hash(i, 2) * .12, ss = s - delay;
                    double e = outCubic(ss / .35), drift = sm(.35, 1.6, ss);
                    double len = (3.0 + 4.0 * hash(i, 3)) * fs * (down ? .6 : 1), rho = (side ? .32 : .25) + 2.4 * fs * drift;
                    double w0 = .3 + .2 * hash(i, 4), w1 = (.8 + 1.6 * hash(i, 5)) * Math.min(fs, 1.25);
                    double dz = down ? .45 + .3 * hash(i, 6) : side ? -.05 : -(.6 + .4 * hash(i, 6));
                    double a = sm(0, .25, ss) * (1 - sm(.85, 1.75, ss)) * k("burst.a", 1.0) * (down ? .6 : 1);
                    if (a < .02) continue;
                    double[] oo = game != null && game.backlash() ? new double[] {o[0], o[1] - 1.1 * Math.pow(Math.max(0, ss - .3), 2) * (down ? 1.4 : 1), o[2]} : o;   // the backlash: the thrown sheets sink as they go
                    list.add(radial(theta, dz, rho, len * (.2 + .8 * e), w0, w1 * (.4 + .6 * e), (hash(i, 7) - .5) * .5 * len * .4, 3 + 2 * i, a, 1 - .5 * sm(.6, 1.6, ss), lk[i % 3], oo, yFloor));
                }
            }
            // 2. the wrap: cloth hugging the body (within about 0.6 block), the face kept free; on the lift a tail of the same cloth streams down off the feet
            double we = lift ? sm(t0() + .1, t0() + 1.0, t) * (1 - sm(touch() - .3, touch() + .6, t)) : sm(burstAt + .3, burstAt + 1.3, t) * (1 - sm(burstAt + 1.8, burstAt + 3.4, t));
            if (we > .02) {
                double shrink = 1 - .5 * ab, ga = Math.min(1, we * (1 - .85 * ab) * 1.15), y0 = (lift ? Math.max(0, L) : 0) + .1, hh = (lift ? 1.55 : 1.45) * (1 - .45 * ab), r = k("wrap.r", .8) * shrink;
                list.add(cone(180, 210, r, .12, y0, hh, 40, 61, .95 * ga, 1, lk[0]));
                list.add(cone(100, 70, r, .1, y0, hh * .85, 50, 62, .95 * ga, 1, lk[1]));
                list.add(cone(-100, 70, r, .1, y0, hh * .85, -50, 63, .95 * ga, 1, lk[1]));
                list.add(cone(32, 52, r * 1.05, .1, y0, hh * .5, 40, 64, .85 * ga, 1, lk[2]));     // low, in front of the knees: under the chest, never the face
                list.add(cone(-32, 52, r * 1.05, .1, y0, hh * .5, -40, 65, .85 * ga, 1, lk[2]));
                // the second layer: twice as dense
                list.add(cone(150, 120, r * .92, .1, y0, hh * .9, -45, 66, .9 * ga, 1, lk[1]));
                list.add(cone(-150, 120, r * .92, .1, y0, hh * .9, 45, 67, .9 * ga, 1, lk[0]));
                list.add(cone(180, 170, r * 1.1, .14, y0, hh, 30, 68, .8 * ga, 1, lk[2]));
                if (lift) {
                    double ce = sm(t0() + .25, t0() + .9, t) * (1 - sm(touch() - .4, touch() + .3, t)) * (1 - .85 * ab);
                    double top = Math.max(0, L) + .25, len = Math.min(1.5, top - .15) * (1 - .3 * ab);
                    if (ce > .02 && len > .35) {
                        list.add(cone(180, 300, .5, -.1, top, -len, 0, 71, .95 * ce, 1, tail[2]));
                        list.add(cone(90, 110, .55, -.08, top, -len * .85, 25, 72, .9 * ce, 1, tail[1]));
                        list.add(cone(-90, 110, .55, -.08, top, -len * .85, -25, 73, .9 * ce, 1, tail[1]));
                        list.add(cone(140, 100, .6, -.08, top, -len * .7, -20, 74, .85 * ce, 1, tail[0]));
                        list.add(cone(-140, 100, .6, -.08, top, -len * .7, 20, 75, .85 * ce, 1, tail[0]));
                    }
                }
            }
            list.sort(java.util.Comparator.comparingDouble(Drawn::depth));
            for (Drawn d : list) FxFlame.draw(d.look(), d.strip(), c.pose(), c.buffers(), t);
            sheets = list.size();
        }

        // ---------------------------------------------------------------- particles: the engine's motes (FxCultivationMotes' Mote, drawn by FxCultivationBench#motes)

        void particles() {
            List<FxCultivationMotes.Mote> out = MOTES.motes;
            out.clear();
            double s = s();
            double[] o = {chest[0], chest[1], chest[2] - .15};
            double rx = cr.dot(right), rz = cr.dot(near), ux = cu.dot(right), uy = cu.y, uz = cu.dot(near);
            // gather: a few soft specks drawn in towards the dantian before the burst (before the launch on the lift)
            double g = lift ? sm(t0() - .7, t0() - .4, t) * (1 - sm(t0() + 1.4, t0() + 2.2, t)) : sm(burstAt - .8, burstAt - .5, t) * (1 - sm(burstAt - .1, burstAt, t));
            int ng = (int) Math.round(k("gather.n", 14) * Math.max(.4, scale));
            for (int i = 0; i < ng && g > .02; i++) {
                double q = ((t * .5 + hash(i, 72)) % 1 + 1) % 1;
                double ang = hash(i, 73) * 2 * Math.PI + q * 2.5, rr = 1.8 * (1 - Math.pow(q, 1.5)) + .12;
                double hh = dantian[1] + (hash(i, 74) - .35) * 1.4 * (1 - q);
                double e = g * sm(0, .2, q) * (1 - sm(.8, 1, q));
                if (rr < .3 && Math.abs(hh - head[1]) < .3) continue;
                out.add(mote(false, dantian[0] + Math.sin(ang) * rr, Math.max(.2, hh), Math.cos(ang) * rr * .9, .02 + .025 * hash(i, 75), i % 3 == 0 ? hot : pale, e, 0, 0, 0));
            }
            if (s > 0) {
                int ns = (int) Math.round(k("motes.out", 60) * Math.max(.5, scale));
                for (int i = 0; i < ns; i++) {
                    double life = 3.0 * (.7 + .5 * hash(i, 81)), a = s - hash(i, 82) * .4;
                    if (a < 0 || a > life) continue;
                    double ang = hash(i, 84) * 2 * Math.PI, sp = 1.0 + 3.6 * hash(i, 85) * hash(i, 85), ca = Math.cos(ang), sa = Math.sin(ang);
                    double dx = ca * rx + sa * ux, dyy = sa * uy, dz = ca * rz + sa * uz - .2 - .4 * hash(i, 86);
                    double dl = Math.sqrt(dx * dx + dyy * dyy + dz * dz), dist = sp * (1 - Math.exp(-a * 1.5)) / 1.5, v = sp * Math.exp(-a * 1.5);
                    double x = o[0] + dx / dl * (.3 + dist), y = o[1] + dyy / dl * (.3 + dist) + .2 * a * (.5 + hash(i, 87)), z = o[2] + dz / dl * (.3 + dist);
                    double q = a / life, e = sm(0, .3, a) * (1 - sm(.5, 1, q)) * sm(.2, .6, y);
                    // the first 0.3 s each mote has a short soft streak tail (the engine's spark streak along its velocity), then it is a round soft mote
                    boolean streak = a < .3;
                    out.add(mote(streak, x, Math.max(.35, y), z, streak ? .018 + .016 * hash(i, 88) : .022 + .03 * hash(i, 88), hash(i, 89) < .5 ? hot : pale, e * .9,
                            dx / dl * v, dyy / dl * v + .2, dz / dl * v));
                }
            }
            // the calm after: slow specks hanging round the body, rising on the hover and settling after the landing (a slow fall, no dust)
            int nm = (int) Math.round(k("motes", 12) * Math.max(.4, scale));
            double calm = sm(burstAt + .6, burstAt + 1.8, t) * (1 - sm(end() - .9, end() - .05, t));
            double settleFrom = lift ? touch() : burstAt + 2.6;
            double fall = sm(settleFrom, settleFrom + 1.2, t);
            for (int i = 0; i < nm && calm > .02; i++) {
                double life = 3.4 + 1.6 * hash(i, 91), q = ((t / life + hash(i, 92)) % 1 + 1) % 1, age = q * life;
                double ang = hash(i, 93) * 2 * Math.PI + .25 * age * (hash(i, 94) - .5), rr = .45 + 1.7 * hash(i, 95);
                double y = (lift ? Math.max(0, L) : 0) + .35 + 1.3 * hash(i, 96) + (1 - fall) * .25 * age * (.4 + hash(i, 97)) - fall * .18 * age * (.4 + hash(i, 97));
                double e = calm * sm(0, .15, q) * (1 - sm(.7, 1, q)) * sm(.25, .6, y);
                out.add(mote(false, chest[0] + Math.sin(ang) * rr, Math.max(.3, y), Math.cos(ang) * rr * .8 - .3, .02 + .03 * hash(i, 98) * hash(i, 98), hash(i, 99) < .3 ? hot : pale, e, 0, 0, 0));
            }
            FxCultivationBench.motes(c, MOTES, feet, right, near);
        }

        /** A mote with its envelope {@code e} (0..1) set through its age: the engine's envelope is min(in, out) and the out part is (life - age) / (0.35 life). Brightness stays under 0.6: no flicker. */
        FxCultivationMotes.Mote mote(boolean spark, double x, double y, double z, double size, int rgb, double e, double vx, double vy, double vz) {
            FxCultivationMotes.Mote m = new FxCultivationMotes.Mote();
            m.spark = spark; m.x = x; m.y = y; m.z = z; m.size = size; m.rgb = rgb; m.bright = spark ? .55F : .45F;
            m.vx = vx; m.vy = vy; m.vz = vz;
            m.life = 1;
            m.age = 1 - .35 * Math.max(0, Math.min(1, e * gate()));
            return m;
        }
    }
}
