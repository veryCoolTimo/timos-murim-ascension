package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/** Crisp ground wave; existing cel dust/ImpactFlash, no postprocessing or blur. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class QiShockwaveVfx implements VfxBackend {
    private static final int LIFE = 18;
    private static final int SEGMENTS = 32;
    private static final int DUST_COUNT = 16;
    private static final VfxColour CORE = new VfxColour(0.96F, 1.0F, 1.0F);
    private static final VfxColour EDGE = new VfxColour(0.54F, 0.94F, 1.0F);
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            MurimMod.MODID, "textures/vfx/qi_shockwave.png");
    private static final List<Wave> WAVES = new ArrayList<>();
    private static ClientLevel lastLevel;

    private static final class Wave {
        final Vec3 ground;
        int age;
        Wave(Vec3 ground) { this.ground = ground; }
    }

    @Override public String id() { return "ours"; }

    @Override public boolean shockwave(ClientLevel level, Vec3 ground) {
        changeLevel(level);
        if (WAVES.size() >= 24) { WAVES.remove(0); }
        WAVES.add(new Wave(ground));
        return true;
    }

    @Override public boolean rainfall(ClientLevel level, Vec3 ground) {
        PlumRainfallVfx.emit(level, ground);
        return true;
    }

    /** Collision surface, including slabs; never place on a distant heightmap/roof. */
    public static Vec3 ground(ClientLevel level, Vec3 at) {
        var hit = level.clip(new ClipContext(at.add(0, 2, 0), at.add(0, -6, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, Minecraft.getInstance().player));
        return (hit.getType() == HitResult.Type.MISS ? at : hit.getLocation()).add(0, 0.035D, 0);
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        changeLevel(mc.level);
        if (mc.level == null || mc.isPaused()) { return; }
        WAVES.removeIf(wave -> ++wave.age >= LIFE);
    }

    private static void changeLevel(ClientLevel level) {
        if (lastLevel != level) { WAVES.clear(); lastLevel = level; }
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        WAVES.clear();
        lastLevel = null;
        VfxBackends.reset();
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || WAVES.isEmpty()) { return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != lastLevel) { return; }
        var buffers = mc.renderBuffers().bufferSource();
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        try {
            stack.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = stack.last();
            RenderType ringType = RenderType.entityTranslucent(TEXTURE);
            VertexConsumer ring = buffers.getBuffer(ringType);
            for (Wave wave : WAVES) {
                float age = wave.age + partial;
                float t = Mth.clamp(age / LIFE, 0.0F, 1.0F);
                double radius = 0.25D + 3.0D * (1.0D - Math.pow(1.0D - t, 2));
                float fade = 1.0F - t;
                ring(ring, pose, wave.ground, radius, 0.12D * fade + 0.02D, fade, EDGE);
                ring(ring, pose, wave.ground.add(0, 0.004D, 0), radius, 0.045D, fade, CORE);
                if (age < 3) {
                    ImpactFlash.draw(ring, pose, wave.ground.add(0, 0.15D, 0), camera,
                            age / 3.0F, 0.14D, CORE, EDGE);
                }
            }
            buffers.endBatch(ringType);
            RenderType dustType = MurimRenderTypes.dustPuffs();
            VertexConsumer dust = buffers.getBuffer(dustType);
            for (Wave wave : WAVES) {
                float age = wave.age + partial;
                float t = Mth.clamp(age / LIFE, 0.0F, 1.0F);
                double travel = 0.3D + 2.7D * (1 - Math.pow(1 - t, 3));
                for (int i = 0; i < DUST_COUNT; i++) {
                    double angle = 2 * Math.PI * i / DUST_COUNT + (i % 3) * 0.07D;
                    double distance = travel * (0.85D + (i % 3) * 0.075D);
                    Vec3 at = wave.ground.add(Math.cos(angle) * distance,
                            0.1D + Math.sin(Math.PI * t) * (0.3D + (i % 4) * 0.07D), Math.sin(angle) * distance);
                    PlumVfx.puff(dust, pose, camera, at, (0.14D + (i % 4) * 0.035D) * (1 + t),
                            i, Mth.clamp((1 - t) * 2, 0, 1), 0.72F + (i % 3) * 0.06F);
                }
            }
            buffers.endBatch(dustType);
        } finally {
            stack.popPose();
        }
    }

    private static void ring(VertexConsumer v, PoseStack.Pose pose, Vec3 centre, double radius,
                             double width, float alpha, VfxColour colour) {
        Vec3 up = new Vec3(0, 1, 0);
        for (int i = 0; i < SEGMENTS; i++) {
            double a = 2 * Math.PI * i / SEGMENTS, b = 2 * Math.PI * (i + 1) / SEGMENTS;
            Vec3[] points = {
                    centre.add(Math.cos(a) * (radius - width), 0, Math.sin(a) * (radius - width)),
                    centre.add(Math.cos(b) * (radius - width), 0, Math.sin(b) * (radius - width)),
                    centre.add(Math.cos(b) * (radius + width), 0, Math.sin(b) * (radius + width)),
                    centre.add(Math.cos(a) * (radius + width), 0, Math.sin(a) * (radius + width))};
            for (Vec3 point : points) {
                VfxDraw.vertex(v, pose, point, up, 0.5F, 0.5F, alpha,
                        colour.red(), colour.green(), colour.blue());
            }
        }
    }
}
