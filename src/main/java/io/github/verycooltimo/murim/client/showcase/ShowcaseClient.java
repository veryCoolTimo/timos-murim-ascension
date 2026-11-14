package io.github.verycooltimo.murim.client.showcase;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.camera.CameraRig;
import io.github.verycooltimo.murim.network.ShowcasePayloads;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client half of {@code /murim dev showcase}: the server timeline sends cues, this class hides the HUD, flies a far camera and takes
 * the screenshots. The camera is an invisible client-only armor stand set as the camera entity, the same way as
 * {@code BreakthroughCamera} and {@code DialogueCamera}; its pose is computed every frame in {@code ComputeCameraAngles}, so it
 * moves smoothly between ticks. Walls pull the camera in (five rays, {@link CameraRig#allowed}). The shot is a slow orbit plus
 * a dolly around a focus point the server chose, so the whole effect and its surroundings stay in frame.
 * API: reference/minecraft-src/net/minecraft/client/Minecraft.java#setCameraEntity, client/Screenshot.java#grab,
 * reference/neoforge-src/net/neoforged/neoforge/client/event/ViewportEvent.java.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ShowcaseClient {

    private static boolean on;
    private static boolean savedHideGui;
    private static CameraType savedType = CameraType.FIRST_PERSON;
    private static ArmorStand cam;
    private static ShowcasePayloads.Cue shot;
    private static int age;
    private static float shown = -1.0F;
    /** Degrees added to the shot's yaw: the side of the focus point with the most room (a pillar or a wall behind the first choice). */
    private static float turn;
    /** Added to the shot's elevation: the angle (side, then height) that leaves the camera the most room. */
    private static float lift;
    /** Focus point after the nudge out of a solid block. */
    private static Vec3 focusNow = Vec3.ZERO;
    /** Screenshots waiting for the rank-up scene to reach their moment: label -> scene age, and the client tick they expire at. */
    private static final java.util.List<Object[]> pending = new java.util.ArrayList<>();

    private ShowcaseClient() {
    }

    /** The far camera owns the view: the breakthrough director and the stand-in third-person shot keep out. */
    public static boolean holdsCamera() {
        return cam != null;
    }

    public static boolean running() {
        return on;
    }

    public static void accept(ShowcasePayloads.Cue cue) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        switch (cue.op()) {
            case BEGIN -> {
                if (!on) {
                    savedHideGui = mc.options.hideGui;
                    savedType = mc.options.getCameraType();
                }
                on = true;
                mc.options.hideGui = true;
            }
            case SHOT -> startShot(mc, cue);
            case FREE -> release(mc);
            case SNAP -> {
                if (cue.ticks() > 0) {
                    pending.add(new Object[] {cue.label(), cue.ticks(), 400});
                    MurimMod.LOGGER.info("SHOWCASE-PENDING {} until rank-up age {} (now {})", cue.label(), cue.ticks(),
                            io.github.verycooltimo.murim.client.ClientMeditationState.rankUpAge());
                } else {
                    snap(mc, cue.label());
                }
            }
            case END -> end(mc);
        }
    }

    private static void startShot(Minecraft mc, ShowcasePayloads.Cue cue) {
        if (cam == null) {
            cam = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
            // A small stand: the camera's own eye height is then small and the smoothing has little to catch up with.
            cam.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) ArmorStand.CLIENT_FLAG_SMALL);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.setCameraEntity(cam);
        }
        shot = cue;
        age = 0;
        turn = 0.0F;
        lift = 0.0F;
        focusNow = new Vec3(cue.x(), cue.y(), cue.z());
        shown = cue.dist();
    }

    /** A focus inside a solid block (a dais, a screen) would put every ray in a wall: lift it to the first free cell, up to 3 blocks. */
    private static Vec3 freeFocus(Minecraft mc, Vec3 focus) {
        for (int i = 0; i <= 6; i++) {
            Vec3 at = focus.add(0.0D, i * 0.5D, 0.0D);
            if (mc.level.noCollision(new net.minecraft.world.phys.AABB(at.x - 0.2, at.y - 0.2, at.z - 0.2, at.x + 0.2, at.y + 0.2, at.z + 0.2))) {
                return at;
            }
        }
        return focus;
    }

    /**
     * Tries the shot from several sides and heights and keeps the one whose tightest moment leaves the camera the most room, so
     * the camera never sits in a wall or behind a pillar: the rays run from the focus outwards and stop at the first block.
     */
    private static void pickAngle(Minecraft mc, ShowcasePayloads.Cue cue) {
        Vec3 focus = freeFocus(mc, new Vec3(cue.x(), cue.y(), cue.z()));
        focusNow = focus;
        float best = 0.0F;
        float bestUp = 0.0F;
        float bestRoom = -1.0F;
        for (float up : new float[] {0.0F, 14.0F, 30.0F}) {
            for (float off : new float[] {0.0F, 30.0F, -30.0F, 60.0F, -60.0F, 90.0F, -90.0F, 120.0F, -120.0F, 150.0F, -150.0F, 180.0F}) {
                float worst = 1.0F;
                for (float t : new float[] {0.0F, 0.5F, 1.0F}) {
                    double ya = Math.toRadians(cue.yaw() + off + cue.sweep() * t);
                    double pa = Math.toRadians(cue.elev() + up);
                    Vec3 back = new Vec3(Math.sin(ya) * Math.cos(pa), Math.sin(pa), -Math.cos(ya) * Math.cos(pa));
                    float wanted = Mth.lerp(t, cue.dist(), cue.dist() * cue.dolly());
                    // The two figures stand a block or two either side of the focus: their lines of sight must be free as well.
                    Vec3 side = back.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
                    for (double lateral : new double[] {-1.6D, -0.8D, 0.0D, 0.8D, 1.6D}) {
                        for (double height : new double[] {-0.9D, 0.0D, 0.7D}) {
                            Vec3 from = focus.add(side.scale(lateral)).add(0.0D, height, 0.0D);
                            worst = Math.min(worst, CameraRig.room(from, back, wanted, clear(mc)));
                        }
                    }
                }
                // a small preference for the authored side and height keeps the 3/4 angle when it is free
                float score = worst - Math.abs(off) / 360.0F - up / 1800.0F;
                if (score > bestRoom) {
                    bestRoom = score;
                    best = off;
                    bestUp = up;
                }
            }
        }
        turn = best;
        lift = bestUp;
    }

    private static void release(Minecraft mc) {
        if (cam != null) {
            mc.setCameraEntity(mc.player);
            mc.options.setCameraType(savedType);
        }
        cam = null;
        shot = null;
    }

    private static void snap(Minecraft mc, String label) {
        String name = "showcase_" + label + ".png";
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> { });
        MurimMod.LOGGER.info("SHOWCASE-SNAP {} file=screenshots/{}", label, name);
    }

    private static void end(Minecraft mc) {
        pending.clear();
        release(mc);
        if (on) {
            mc.options.hideGui = savedHideGui;
        }
        on = false;
    }

    /** Leaving the world mid-reel: give the player's own view and the HUD back at once. */
    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft mc = Minecraft.getInstance();
        cam = null;
        shot = null;
        if (on) {
            mc.options.hideGui = savedHideGui;
            mc.options.setCameraType(savedType);
        }
        on = false;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (cam != null) {
            age++;
            // Chosen once the chunks around the shot are loaded and drawn (at the very first tick they are not).
            if (age == 24 && shot != null) {
                pickAngle(Minecraft.getInstance(), shot);
            }
        }
        // The breakthrough's own clock: the picture is taken when the rank-up scene has run this many ticks (its visual peak).
        Minecraft mc = Minecraft.getInstance();
        for (var it = pending.iterator(); it.hasNext(); ) {
            Object[] w = it.next();
            w[2] = (int) w[2] - 1;
            if (io.github.verycooltimo.murim.client.ClientMeditationState.rankUpAge() >= (int) w[1]) {
                snap(mc, (String) w[0]);
                it.remove();
            } else if ((int) w[2] <= 0) {
                // The scene's clock never reached the moment: take the picture anyway rather than lose the shot.
                MurimMod.LOGGER.info("SHOWCASE-PENDING {} expired at rank-up age {}", w[0],
                        io.github.verycooltimo.murim.client.ClientMeditationState.rankUpAge());
                snap(mc, (String) w[0]);
                it.remove();
            }
        }
    }

    private static float ease(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    private static CameraRig.Clear clear(Minecraft mc) {
        return (from, to) -> {
            HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            return hit.getType() == HitResult.Type.MISS ? from.distanceTo(to) : from.distanceTo(hit.getLocation());
        };
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (cam == null || shot == null || event.getCamera().getEntity() != cam) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        float t = ease((age + (float) event.getPartialTick()) / Math.max(1, shot.ticks()));
        float yaw = shot.yaw() + turn + shot.sweep() * t;
        float pitch = shot.elev() + lift;
        float wanted = Mth.lerp(t, shot.dist(), shot.dist() * shot.dolly());
        double ya = Math.toRadians(yaw);
        double pa = Math.toRadians(pitch);
        Vec3 look = new Vec3(-Math.sin(ya) * Math.cos(pa), -Math.sin(pa), Math.cos(ya) * Math.cos(pa));
        Vec3 focus = focusNow;
        float room = CameraRig.allowed(focus, look.scale(-1.0D), wanted, clear(mc));
        // A wall pulls the camera in at once, an opening lets it out slowly: no pops.
        shown = room < shown ? room : Math.min(room, shown + 0.15F);
        Vec3 pos = focus.subtract(look.scale(shown));
        cam.setPos(pos.x, pos.y - cam.getEyeHeight(), pos.z);
        cam.xo = cam.getX();
        cam.yo = cam.getY();
        cam.zo = cam.getZ();
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(0.0F);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onDistance(CalculateDetachedCameraDistanceEvent event) {
        if (cam != null && event.getCamera().getEntity() == cam) {
            event.setDistance(0.0F);
        }
    }

    @SubscribeEvent
    static void onFov(ViewportEvent.ComputeFov event) {
        if (cam != null && shot != null && event.getCamera().getEntity() == cam) {
            event.setFOV(event.getFOV() * shot.fov());
        }
    }
}
