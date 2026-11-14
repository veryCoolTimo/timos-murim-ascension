package io.github.verycooltimo.murim.client.dev;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.flow.PillScene;
import io.github.verycooltimo.murim.client.flow.PillScenePlan;
import io.github.verycooltimo.murim.client.flow.PillSceneRenderer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.client.vfx.PlumStormVfx;
import io.github.verycooltimo.murim.client.vfx.RiverVfx;
import io.github.verycooltimo.murim.client.vfx.bt.BtFireField;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;

/** Immutable-by-ownership snapshots of the framebuffer, also used by tick/HUD grabs. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class CaptureTelemetry {
    private static Map<String, Object> rendered;
    private static Object renderedLevel;
    private static double fov;
    private static int tongues;
    private record Geometry(Vec3 camera, Matrix4f view, Matrix4f projection, int width, int height, float partial) { }
    private static Geometry geometry;

    // API: supplied NeoForge 1.21.1 ViewportEvent.ComputeFov#usedConfiguredFov/getFOV.
    // MC GameRenderer#getFov uses this value for the world projection, before bob/nausea.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void fov(ViewportEvent.ComputeFov event) {
        if (!VfxTelemetry.ENABLED) return;
        if (event.usedConfiguredFov()) fov = event.getFOV();
    }

    // API: supplied RenderLevelStageEvent#getStage and Stage.AFTER_SKY.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void begin(RenderLevelStageEvent event) {
        if (!VfxTelemetry.ENABLED) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        rendered = null;
        tongues = 0;
        PlumStormVfx.observeElements(true);
        BtFireField.observeCounts(count -> tongues += count);
    }

    // LOW runs after the producers' NORMAL render callbacks, before DevCaptureHandler LOWEST.
    // API: supplied RenderLevelStageEvent#getModelViewMatrix/getProjectionMatrix/getCamera/
    // getPartialTick; DeltaTracker#getGameTimeDeltaPartialTick; Camera#getPosition/getYRot/getXRot.
    // MC Minecraft#getInstance/getMainRenderTarget, RenderTarget.width/height as used by Screenshot#takeScreenshot;
    // Level#getGameTime; Entity#getId. Sources: /tmp/mr-ref-1121/{minecraft-src,neoforge-src}.
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void rendered(RenderLevelStageEvent event) {
        if (!VfxTelemetry.ENABLED) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                && event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { rendered = null; return; }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && rendered != null) {
            // Producers were sampled once after their particle pass. Fire may finish later.
            Map<Object, Object> systems = new LinkedHashMap<>((Map<?, ?>) rendered.get("systems"));
            systems.put("BtFireField.tongues", tongues);
            rendered.put("systems", systems);
            if (tongues > 0) {
                java.util.Set<Object> layers = new java.util.LinkedHashSet<>((java.util.Set<?>) rendered.get("layers"));
                layers.add("BtFireField");
                rendered.put("layers", layers);
            }
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var camera = event.getCamera();
        var pos = camera.getPosition();
        int width = mc.getMainRenderTarget().width, height = mc.getMainRenderTarget().height;
        Matrix4f view = new Matrix4f(event.getModelViewMatrix());
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tick", mc.level.getGameTime()); row.put("partial", partial);
        row.put("screen", new int[]{width, height}); row.put("fov", fov);
        row.put("camera", Map.of("pos", new double[]{pos.x, pos.y, pos.z}, "yaw", camera.getYRot(), "pitch", camera.getXRot()));
        Map<String, Object> bodies = new LinkedHashMap<>();
        geometry = new Geometry(pos, view, projection, width, height, partial);
        bodies.put("player", player(mc, geometry));
        Entity target = target(mc);
        if (target != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            putBox(body, target, partial, pos, view, projection, width, height);
            bodies.put("target", body);
        }
        row.put("bodies", bodies);
        var sample = VfxTelemetry.sample(true, data -> {
            PlumStormVfx.observe(data.systems, data.layers, data.technique, partial, mc.player.getId());
            RiverVfx.observe(data.systems, data.layers, data.technique, partial, mc.player.getId());
            data.systems.put("BtFireField.tongues", tongues);
            if (tongues > 0) data.layers.add("BtFireField");
            data.systems.put("PillScene.particles", PillScene.active() ? PillSceneRenderer.liveParticles() : 0);
            if (PillScene.active()) {
                float age = PillScene.age(partial);
                data.technique.clear();
                data.technique.put("phase", PillScenePlan.segment(PillScene.ticks(), age).beat().name());
                data.technique.put("t", age / 20.0);
                data.layers.add("PillScene");
            }
        });
        row.put("systems", sample.systems); row.put("layers", sample.layers);
        if (!sample.technique.isEmpty()) row.put("technique", sample.technique);
        rendered = row;
        renderedLevel = mc.level;
    }

    // API: supplied NeoForge RenderFrameEvent.Post follows the held-item pass in GameRenderer#render.
    // HIGH precedes DevCaptureHandler's NORMAL first-person grab.
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void handRendered(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        if (!VfxTelemetry.ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (rendered == null || geometry == null || renderedLevel != mc.level || mc.player == null) return;
        Map<Object, Object> bodies = new LinkedHashMap<>((Map<?, ?>) rendered.get("bodies"));
        bodies.put("player", player(mc, geometry));
        rendered.put("bodies", bodies);
    }

    private static Map<String, Object> player(Minecraft mc, Geometry g) {
        Vec3 pos = g.camera();
        Matrix4f view = g.view(), projection = g.projection();
        int width = g.width(), height = g.height();
        float partial = g.partial();
        Map<String, Object> player = new LinkedHashMap<>();
        Map<String, Object> bones = new LinkedHashMap<>();
        for (var bone : new BoneAnchorLayer.Bone[]{BoneAnchorLayer.Bone.RIGHT_HAND, BoneAnchorLayer.Bone.LEFT_HAND,
                BoneAnchorLayer.Bone.CHEST, BoneAnchorLayer.Bone.DANTIAN, BoneAnchorLayer.Bone.HEAD}) {
            Vec3 at = BoneAnchorLayer.position(mc.player, bone);
            // API: supplied MC Entity#getEyePosition(float).
            if (at == null && bone == BoneAnchorLayer.Bone.HEAD) at = mc.player.getEyePosition(partial);
            if (at != null) bones.put(bone.name(), point(at, pos, view, projection, width, height).json());
        }
        player.put("bones", bones);
        Vec3[] blade = BoneAnchorLayer.heldBlade(mc.player);
        if (blade != null) player.put("blade", Map.of(
                "tip", point(blade[1], pos, view, projection, width, height).json(),
                "hilt", point(blade[0], pos, view, projection, width, height).json()));
        putBox(player, mc.player, partial, pos, view, projection, width, height);
        return player;
    }

    private static CaptureProjection.Point point(Vec3 at, Vec3 camera, Matrix4f view, Matrix4f projection, int w, int h) {
        return CaptureProjection.project(at.x - camera.x, at.y - camera.y, at.z - camera.z, view, projection, w, h);
    }

    // API: supplied MC Entity#getBoundingBox/getPosition(float)/position; AABB public corner fields.
    // Shift the tick AABB by the same interpolated translation used by entity rendering.
    private static void putBox(Map<String, Object> body, Entity actor, float partial, Vec3 camera,
                               Matrix4f view, Matrix4f projection, int w, int h) {
        var box = actor.getBoundingBox();
        Vec3 shift = actor.getPosition(partial).subtract(actor.position());
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1;
        for (double x : new double[]{box.minX, box.maxX})
            for (double y : new double[]{box.minY, box.maxY})
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    var p = point(new Vec3(x + shift.x, y + shift.y, z + shift.z), camera, view, projection, w, h);
                    // A corner behind the camera makes a finite projected AABB undefined.
                    if (p.depth() <= 0) return;
                    x0 = Math.min(x0, p.x()); y0 = Math.min(y0, p.y());
                    x1 = Math.max(x1, p.x()); y1 = Math.max(y1, p.y());
                }
        body.put("bbox", new double[]{x0, y0, x1, y1});
    }

    // API: supplied MC ClientLevel#entitiesForRendering; Entity#getType; EntityType.ZOMBIE; Entity#distanceToSqr.
    // The configured stand enemy is the nearest matching actor within 10 blocks of the player (the stand puts it at
    // MURIM_CAPTURE_DUMMY_DIST). First film 14.11: other bandits were rendered too, and the old «exactly one match»
    // rule dropped the target after frame 0. Two candidates at nearly the same distance stay ambiguous: omitted.
    private static Entity target(Minecraft mc) {
        String kind = System.getenv("MURIM_CAPTURE_ENEMY");
        if (kind == null || kind.isBlank() || mc.player == null) return null;
        Entity best = null;
        double bestD = 100.0, secondD = Double.MAX_VALUE;
        for (Entity actor : mc.level.entitiesForRendering()) {
            boolean match = "zombie".equals(kind) && actor.getType() == net.minecraft.world.entity.EntityType.ZOMBIE
                    || "disciple".equals(kind) && actor instanceof io.github.verycooltimo.murim.entity.SectDisciple
                    || kind.startsWith("bandit") && actor instanceof io.github.verycooltimo.murim.entity.Bandit;
            if (!match) continue;
            double d = actor.distanceToSqr(mc.player);
            if (d < bestD) { secondD = bestD; bestD = d; best = actor; }
            else if (d < secondD) secondD = d;
        }
        if (best == null) return null;
        if (secondD < 100.0 && Math.sqrt(secondD) - Math.sqrt(bestD) < 0.75) return null;
        return best;
    }

    /** One call immediately after each grab; clean/baseline images do not get rows. */
    public static void grabbed(Minecraft mc, String png, int frame, String angle) {
        if (!VfxTelemetry.ENABLED) return;
        if (!png.startsWith("murim_") || rendered == null || renderedLevel != mc.level) return;
        // API: supplied MC Screenshot#_grab saves named screenshots in gameDirectory/screenshots.
        var folder = mc.gameDirectory.toPath().resolve("screenshots");
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("vfx_telemetry_" + angle + ".jsonl"), VfxTelemetry.line(rendered, frame, png),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            MurimMod.LOGGER.warn("Capture telemetry write failed: {}", e.getMessage());
        }
    }

    private CaptureTelemetry() { }
}
