package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.client.vfx.TechniqueLight;
import io.github.verycooltimo.murim.client.vfx.fx.FxQuality;
import io.github.verycooltimo.murim.client.vfx.fx.FxTuneScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;

/**
 * The calm absorption of COMMON and MIDDLE pills (no mini-game, so no board on the body), built from the layers of the high pill picture the author accepted:
 * the pill melts inside the belly, the floor vortex with arms converging into the lap, curtains of fire rising behind the figure, dark flakes, motes drawn in, the body
 * as a lamp, then a calm settle (the middle pill gives one small pulse). Common = small and quiet, middle = clearly bigger; both numbers are
 * {@code FlowLook.CALM_*[grade]} (live-tunable as {@code flow.calm.*}). The director owns a clock of its own, outlives the pill's automatic game, and
 * ends by itself: the camera eases back and nothing is cut. All the drawing is {@link FlowSpaceFx} with a grade, the body lamp is {@link FlowBodyRim}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
final class PillCalmFx {
    private static final FxQuality QUALITY = new FxQuality();
    private static boolean active, pulsed;
    private static int grade;
    private static double clock, run, floorY, nextLight;
    private static float[] pill = {1F, 1F, 1F};
    /** The picture runs on the game's own clock (ticks), so a slow machine plays it slower, not shorter: the clock is the world's game time since the start. */
    private static long startTick, lastNanos;
    private static double lastClock;

    private PillCalmFx() { }

    static boolean running() { return active; }

    /** @param g 0 common, 1 middle; @param rgb the pill's hue */
    static void begin(int g, int rgb) {
        grade = g;
        run = FlowLook.CALM_RUN[g];
        clock = 0;
        pulsed = false;
        lastClock = 0;
        lastNanos = 0;
        nextLight = 0;
        var lv = Minecraft.getInstance().level;
        startTick = lv == null ? 0 : lv.getGameTime();
        pill = new float[] {((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F};
        var pl = Minecraft.getInstance().player;
        floorY = pl == null ? 0 : pl.getY();
        FlowSpaceFx.reset();
        FlowSpaceFx.setGrade(g);
        FlowBodyRim.lampFace = false;
        active = true;
    }

    static void reset() {
        if (active || grade >= 0) {
            FlowSpaceFx.setGrade(-1);
            FlowSpaceFx.reset();
            FlowBodyRim.lampK = 0;
            FlowBodyRim.lampR = 0;
            FlowBodyRim.lampFace = true;
        }
        restoreEffectParticles();
        active = false;
    }

    private static double runEnd() { return Math.max(run - FlowLook.CALM_SETTLE, FlowLook.INTRO_LEAD + FlowLook.INTRO_DISSOLVE + FlowLook.INTRO_FLAME + 1.0); }

    private static double progress() { return FlowSpaceMath.clamp01((clock - FlowLook.INTRO_LEAD) / Math.max(.5, runEnd() - FlowLook.INTRO_LEAD)); }

    /** The front shot, slightly above: a slow push-in during the run, no orbit. -1 when the calm absorption is off. */
    static float cameraDistance() {
        if (!active) return -1F;
        // a slow push-in during the run, then the camera eases back with the picture's settle (never a cut)
        double d = FlowSpaceMath.lerp(FlowLook.CALM_CAM_DIST[grade] + .3, FlowLook.CALM_CAM_DIST[grade], FlowSpaceMath.smooth(0, 1, progress()));
        return (float) (d + FlowLook.CALM_CAM_EASE * FlowSpaceMath.smooth(runEnd(), runEnd() + FlowLook.END_OTHER, clock));
    }

    static float cameraPitch() { return active ? (float) FlowLook.CALM_CAM_PITCH[grade] : 11F; }

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { reset(); return; }
        double gt = mc.level == null ? clock : (mc.level.getGameTime() - startTick + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0;
        double dt = Math.max(0, Math.min(.25, gt - lastClock));
        long now = System.nanoTime();
        if (lastNanos != 0) QUALITY.update((now - lastNanos) / 1e6, Math.min(.25, (now - lastNanos) / 1e9));
        lastNanos = now;
        lastClock = gt;
        if (mc.isPaused()) dt = 0;
        dt = FxTuneScene.simDt(dt);
        clock += dt;
        double runEnd = runEnd(), endOther = FlowLook.END_OTHER;
        if (clock > runEnd + 1.5 + .1) { reset(); return; }
        mc.getToasts().clear(); // no advancement toast over the picture
        hideEffectParticles(mc);
        FlowMeridian.Anchors an = FlowMeridianFx.anchors(mc);
        if (an == null) return;
        Vec3 o = an.body().o();
        Vec3 core = o.add(an.body().out().scale(.05));
        Vec3 hand = BoneAnchorLayer.position(mc.player, Bone.RIGHT_FINGERS);
        double ic = clock - FlowLook.INTRO_LEAD, introEnd = FlowLook.INTRO_DISSOLVE + FlowLook.INTRO_FLAME;
        double intro = FlowSpaceMath.smooth(FlowLook.INTRO_DISSOLVE, introEnd, ic);
        int ending = clock < runEnd ? -1 : 2;
        double te = ending < 0 ? -1 : clock - runEnd;
        FlowSpaceFx.setGrade(grade);
        FlowSpaceFx.update(new FlowSpaceFx.Params(dt, clock, progress(), 0, pill, ending, te, 1 - FlowSpaceMath.smooth(runEnd, runEnd + 1.5, clock), o, floorY, core, intro, hand == null ? o : hand), QUALITY);
        // the body as a lamp: light spreads out of the dantian slowly, holds, goes out with the settle
        double lamp = FlowLook.CALM_LAMP[grade] * FlowSpaceMath.smooth(FlowLook.INTRO_LEAD + .8, FlowLook.INTRO_LEAD + introEnd + 1.2, clock) * (1 - FlowSpaceMath.smooth(runEnd, runEnd + endOther - .2, clock));
        FlowBodyRim.lampK = (float) lamp;
        FlowBodyRim.lampR = (float) (lamp * FlowLook.LAMP_REACH);
        FlowBodyRim.lampAt = o.add(0, .14, 0);
        FlowBodyRim.lampTone = FlowSpaceFx.lampColour(((int) (pill[0] * 255) << 16) | ((int) (pill[1] * 255) << 8) | (int) (pill[2] * 255), 0);
    }

    /** The swirl would show in the frames before the session starts: hide it as soon as the server says a pill is being absorbed (the picture's own start follows). */
    @SubscribeEvent
    static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || active) return;
        if (io.github.verycooltimo.murim.client.ClientPillState.absorbing()) hideEffectParticles(mc);
    }

    private static java.lang.reflect.Field effectParticlesField;
    private static java.util.List<?> savedEffectParticles;

    /** The pill's status effect swirls its own vanilla potion particles over the head: hidden while the picture runs (the entity's own list, client side, restored after). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void hideEffectParticles(Minecraft mc) {
        try {
            if (effectParticlesField == null) {
                effectParticlesField = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("DATA_EFFECT_PARTICLES");
                effectParticlesField.setAccessible(true);
            }
            var acc = (net.minecraft.network.syncher.EntityDataAccessor) effectParticlesField.get(null);
            var data = mc.player.getEntityData();
            var cur = (java.util.List<?>) data.get(acc);
            if (!cur.isEmpty()) { savedEffectParticles = cur; data.set(acc, java.util.List.of()); }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // no such field in this version: the particles stay
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restoreEffectParticles() {
        try {
            var mc = Minecraft.getInstance();
            if (savedEffectParticles != null && mc.player != null && effectParticlesField != null)
                mc.player.getEntityData().set((net.minecraft.network.syncher.EntityDataAccessor) effectParticlesField.get(null), (java.util.List) savedEffectParticles);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // nothing to restore
        }
        savedEffectParticles = null;
    }

    /** The space layers, in the scene's pass (the scene's depth: the figure stands inside the vortex and the curtains). */
    static void drawSpace(Minecraft mc, PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        if (!active || mc.player == null) return;
        FlowSpaceFx.setGrade(grade);
        FlowSpaceFx.draw(pose, cam, buffers, QUALITY.tier());
    }

    /** The pill colour on the floor, with falloff (the engine's ground light), growing with the run. */
    static void updateFloor(Minecraft mc) {
        if (!active || mc.player == null || clock < nextLight) return;
        Vec3 o = BoneAnchorLayer.position(mc.player, Bone.MERIDIAN_O);
        if (o == null) return;
        nextLight = clock + .15;
        double ic = clock - FlowLook.INTRO_LEAD, runEnd = runEnd();
        double env = FlowSpaceMath.smooth(FlowLook.INTRO_DISSOLVE, FlowLook.INTRO_DISSOLVE + FlowLook.INTRO_FLAME, ic) * (1 - FlowSpaceMath.smooth(runEnd, runEnd + FlowLook.END_OTHER, clock));
        float radius = (float) (FlowLook.CALM_R0[grade] + (FlowLook.CALM_R1[grade] - FlowLook.CALM_R0[grade]) * progress()) + .4F;
        int rgb = ((int) (pill[0] * 255) << 16) | ((int) (pill[1] * 255) << 8) | (int) (pill[2] * 255);
        TechniqueLight.add(new Vec3(o.x, mc.player.getY() + FlowLook.FLOOR_LIGHT_LIFT, o.z), rgb, radius, (float) Math.min(1, FlowLook.FLOOR_STRENGTH * env), 3, 2, 4);
    }

    /** No name plate while the picture runs. */
    @SubscribeEvent
    static void onNameTag(RenderNameTagEvent event) {
        if ((active || io.github.verycooltimo.murim.client.camera.FlowCamera.returning()) && event.getEntity() == Minecraft.getInstance().player) event.setCanRender(TriState.FALSE);
    }
}
