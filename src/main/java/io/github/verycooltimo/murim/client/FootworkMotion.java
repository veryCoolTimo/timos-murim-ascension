package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.TraversePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Рывок шага собственной физикой игрока (автор 02.10: «от первого лица это телепорт — должно
 * быть, что ты быстро промчался, плавно до следующего расстояния доходишь»). Перед тиком игрока
 * ставим скорость, равную отрезку пути этого тика: профиль — резкий старт и торможение к концу.
 * Камера и коллизии — честные: стена останавливает, а не пропускает.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FootworkMotion {

    /** Run bypasses TechniqueService's STARTED event; play only its entry, then vanilla gait.
     * Source: FootworkService#startRun (Traverse kind 1; dirX encodes family).
     * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/{Minecraft.java#getInstance,
     * multiplayer/ClientLevel.java#getEntity}; resources/ResourceLocation.java#fromNamespaceAndPath.
     */
    public static void runAnimation(TraversePayloads.Event event) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(event.entityId()) instanceof net.minecraft.client.player.AbstractClientPlayer player)) return;
        var clip = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "dark_fragrance_trail");
        if (event.kind() == 1 && (int) event.dirX() == io.github.verycooltimo.murim.technique.FootworkFamily.HUASHAN.ordinal()) {
            MurimPlayerAnimations.play(player, clip);
        } else if (event.kind() == 0) {
            MurimPlayerAnimations.stopOwned(player, clip);
        }
    }

    private record Facing(TraversePayloads.HeldFacing pose, long until) {}
    private static final java.util.Map<Integer, Facing> FACING = new java.util.HashMap<>();
    private static net.minecraft.client.multiplayer.ClientLevel facingLevel;
    private static void checkFacingLevel() {
        var level = Minecraft.getInstance().level;
        if (facingLevel != level) { FACING.clear(); stopSevenStars(); facingLevel = level; }
    }
    public static void holdFacing(TraversePayloads.HeldFacing pose) {
        checkFacingLevel();
        if (facingLevel == null) return;
        if (pose.ticks() <= 0) FACING.remove(pose.entityId());
        else {
            FACING.put(pose.entityId(), new Facing(pose, facingLevel.getGameTime() + Math.min(30, pose.ticks())));
            if (facingLevel.getEntity(pose.entityId()) instanceof net.minecraft.world.entity.LivingEntity target) applyFacing(target, pose);
        }
    }
    /** API: MC LivingEntity yaw setters/old yaw fields; NeoForge EntityTickEvent.Post.
     * Runs after vanilla BodyRotationControl on the client, including its idle head/body alignment. */
    @SubscribeEvent
    static void heldFacingTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.LivingEntity target) || !target.level().isClientSide()) return;
        checkFacingLevel();
        Facing held = FACING.get(target.getId());
        if (held == null) return;
        if (target.level().getGameTime() >= held.until()) FACING.remove(target.getId());
        else applyFacing(target, held.pose());
    }
    private static void applyFacing(net.minecraft.world.entity.LivingEntity target, TraversePayloads.HeldFacing pose) {
        target.setYRot(pose.yaw()); target.yRotO = pose.yaw();
        target.setYBodyRot(pose.bodyYaw()); target.yBodyRotO = pose.bodyYaw();
        target.setYHeadRot(pose.headYaw()); target.yHeadRotO = pose.headYaw();
    }

    private static int sevenTicks;
    public static void startSevenStars() { startSevenStars(12); }
    public static void startSevenStars(int ticks) { sevenTicks = ticks; }
    public static void stopSevenStars() { sevenTicks = 0; sevenRoute = null; correctionEnd = null; correctionFrames = 0; }
    private static TraversePayloads.SevenRoute sevenRoute;
    private static int routeAge;
    private static Vec3 routeStart;
    private static double acceptedAge;
    private static Vec3 correctionEnd;
    private static int correctionFrames;
    public static void acceptRoute(TraversePayloads.SevenRoute route) {
        var player = Minecraft.getInstance().player;
        if (route.actor() < 0 || player != null && route.actor() == player.getId()) startSevenRoute(route);
    }
    public static void startSevenRoute(TraversePayloads.SevenRoute route) {
        checkFacingLevel();
        if (facingLevel == null) return;
        if (route.ticks() == 1 && route.points().getFirst().equals(route.points().getLast())) {
            // A collision stop is valid even when its one-tick clock has already elapsed.
            sevenRoute = null;
            correctionEnd = null;
            correctionFrames = 0;
            var player = Minecraft.getInstance().player;
            if (player != null && player.position().distanceTo(route.points().getFirst()) > .75) {
                correctionEnd = route.points().getFirst();
                correctionFrames = 3;
            }
            return;
        }
        if (facingLevel.getGameTime() >= route.startTick() + route.ticks()) return;
        correctionEnd = null;
        correctionFrames = 0;
        sevenRoute = route;
        routeStart = null;
        routeAge = (int) route.ageAt(facingLevel.getGameTime());
        sevenTicks = Math.max(sevenTicks, (int) Math.max(0, route.startTick() + route.ticks() - facingLevel.getGameTime()));
    }

    /** Same approved polyline and substeps as the server; Entity#move clips client blocks too.
     * API: /tmp/mr-ref-1121/minecraft-src Entity#move/setYRot; ClientLevel#getEntity. */
    private static void tickSevenRoute(LocalPlayer player) {
        if (sevenRoute == null) return;
        routeAge = (int) sevenRoute.ageAt(player.level().getGameTime());
        if (routeAge < 0) return;
        if (routeAge >= sevenRoute.ticks()) { sevenRoute = null; return; }
        if (routeStart == null) {
            // Start at the interpolation endpoint captured by this player tick, never the server endpoint.
            routeStart = player.position();
            acceptedAge = routeAge;
        }
        for (int sample = 1; sample <= 64; sample++) {
            var point = io.github.verycooltimo.murim.technique.SevenStarsRules.routePoint(sevenRoute.points(),
                    routeProgress(sevenRoute, (routeAge + sample / 64.0) / sevenRoute.ticks()));
            if (sevenRoute.ticks() == 3)
                point = io.github.verycooltimo.murim.technique.FootworkPaths.dipperPoint(
                        sevenRoute.points(), routeStart, acceptedAge, routeAge + sample / 64.0);
            player.move(net.minecraft.world.entity.MoverType.SELF, point.subtract(player.position()).add(0, -.001 / 64, 0));
        }
        var target = player.level().getEntity(sevenRoute.target());
        if (target != null) {
            Vec3 facing = target.position().subtract(player.position());
            float yaw = (float) Math.toDegrees(Math.atan2(-facing.x, facing.z));
            if (sevenRoute.ticks() == 6) {
                player.setYRot(io.github.verycooltimo.murim.technique.FootworkPaths.facing(player.yRotO, yaw));
                player.setYBodyRot(io.github.verycooltimo.murim.technique.FootworkPaths.facing(player.yBodyRotO, player.getYRot()));
                player.setYHeadRot(io.github.verycooltimo.murim.technique.FootworkPaths.facing(player.yHeadRotO, player.getYRot()));
            } else player.setYRot(net.minecraft.util.Mth.rotLerp(.5f, player.getYRot(), yaw));
        }
        if (++routeAge >= sevenRoute.ticks()) sevenRoute = null;
    }

    /** Exceptional stops converge in three rendered frames, with block clipping on every increment.
     * Move both interpolation endpoints equally so partial tick cannot amplify the correction.
     * API: MC Entity#move/position and public xo/yo/zo; NeoForge RenderFrameEvent.Pre (local 1.21.1 sources). */
    @SubscribeEvent
    static void correctionFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        var minecraft = Minecraft.getInstance();
        checkFacingLevel();
        var player = minecraft.player;
        if (player == null || minecraft.isPaused() || correctionEnd == null) return;
        Vec3 before = player.position();
        player.move(net.minecraft.world.entity.MoverType.SELF,
                io.github.verycooltimo.murim.technique.FootworkPaths.correctionStep(before, correctionEnd, correctionFrames));
        Vec3 moved = player.position().subtract(before);
        player.xo += moved.x;
        player.yo += moved.y;
        player.zo += moved.z;
        if (--correctionFrames == 0) correctionEnd = null;
    }

    /** LocalPlayer#getViewYRot bypasses tick interpolation; present forced route yaw per frame.
     * API: /tmp/mr-ref-1121/neoforge-src/.../ViewportEvent.java#ComputeCameraAngles;
     * MC Mth#rotLerp and Entity#yRotO. */
    @SubscribeEvent
    static void routeCamera(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        var player = Minecraft.getInstance().player;
        if (player != null && sevenTicks > 0)
            event.setYaw(net.minecraft.util.Mth.rotLerp((float) event.getPartialTick(), player.yRotO, player.getYRot())
                    + net.minecraft.util.Mth.wrapDegrees(event.getYaw() - player.getYRot()));
    }

    private static double routeProgress(TraversePayloads.SevenRoute route, double x) {
        return route.ticks() == 3 ? io.github.verycooltimo.murim.technique.SevenStarsRules.smoothstep(x) : Math.clamp(x, 0, 1);
    }

    /** API: NeoForge 21.1 MovementInputUpdateEvent#getInput; MC 1.21.1 Input fields.
     * The server owns the path; held W must not add vanilla walking to its distance.
     */
    @SubscribeEvent
    static void sevenInput(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event) {
        if (sevenTicks <= 0) return;
        var input = event.getInput();
        input.forwardImpulse = 0;
        input.leftImpulse = 0;
        input.jumping = false;
        var player = event.getEntity();
        player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
    }

    @SubscribeEvent
    static void sevenClock(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        checkFacingLevel();
        if (minecraft.level == null) stopSevenStars();
        else if (!minecraft.isPaused()) {
            // Move after the player tick has captured its old position, preserving render interpolation.
            if (minecraft.player != null) tickSevenRoute(minecraft.player);
            if (sevenTicks > 0) sevenTicks--;
        }
    }

    private static Vec3 dir = Vec3.ZERO;
    private static double distance;
    private static int ticks;
    private static int elapsed = -1;
    private static boolean slipping;
    public static void slippingPetal() { slipping = true; }
    public static void stopAirRoute() {
        if (sevenRoute != null && sevenRoute.ticks() == 20) stopSevenStars();
    }

    public static void start(TraversePayloads.Dash dash) {
        Vec3 d = new Vec3(dash.dx(), dash.dy(), dash.dz());
        if (d.lengthSqr() < 1.0E-6D || dash.ticks() <= 0) {
            return;
        }
        slipping = false;
        dir = d.normalize();
        distance = dash.distance();
        ticks = dash.ticks();
        elapsed = 0;
    }

    public static boolean active() {
        return elapsed >= 0;
    }

    /** Доля пути к доле времени: быстро срывается, плавно тормозит. */
    static double progress(double x) {
        double k = 1.0D - Math.max(0.0D, Math.min(1.0D, x));
        return 1.0D - k * k * k;
    }

    private static double dashProgress(double x) {
        return slipping ? io.github.verycooltimo.murim.technique.SevenStarsRules.smoothstep(x) : progress(x);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (elapsed < 0 || player == null || minecraft.isPaused()) {
            return;
        }
        if (elapsed >= ticks || player.isPassenger()) {
            // Последний тик: гасим остаток скорости, чтобы не скользить дальше точки.
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x * 0.2D, Math.abs(dir.y) > 0.02D ? 0.0D : v.y, v.z * 0.2D);
            elapsed = -1;
            return;
        }
        double step = distance * (dashProgress((elapsed + 1) / (double) ticks) - dashProgress(elapsed / (double) ticks));
        // Рывок в воздух ведёт и высоту; горизонтальный оставляет падение как есть.
        double vy = Math.abs(dir.y) > 0.02D ? dir.y * step + 0.08D : player.onGround() ? 0.0D : player.getDeltaMovement().y;
        player.setDeltaMovement(dir.x * step, vy, dir.z * step);
        elapsed++;
    }

    private FootworkMotion() {
    }
}
