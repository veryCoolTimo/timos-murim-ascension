package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.dev.SmokeReloadProbe;
import io.github.verycooltimo.murim.dev.SmokeWalkRoute;
import io.github.verycooltimo.murim.dev.SmokeWalkHops;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.decor.HallGrandDoorBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.tags.FluidTags;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

import java.util.List;
import java.util.Locale;

/** Real local-player input; integrated-server tasks return immutable waypoint snapshots to the client thread. */
public final class SmokeJourneyCapture {
    // Capture session belongs to one client world and is cleared on disconnect. No state enters normal play.
    private Session session;
    private boolean quitting;
    private boolean finished;

    public SmokeJourneyCapture() { }

    public static boolean ownsWorld() {
        return SmokeWalkRoute.enabled() && (System.getenv("MURIM_SMOKE_WALK") != null
                || "1".equals(System.getenv("MURIM_SMOKE_RELOAD")));
    }

    public static final class SaveQuit extends Event { }

    /** Called on the client executor after dialogue completion commands finish. */
    public static void requestSaveAndQuit() {
        if (SmokeWalkRoute.enabled()) NeoForge.EVENT_BUS.post(new SaveQuit());
    }

    @SubscribeEvent
    public void onSaveQuit(SaveQuit event) { saveAndQuit(); }

    private void saveAndQuit() {
        if (!SmokeWalkRoute.enabled() || quitting || finished) return;
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        quitting = true;
        var id = mc.player.getUUID();
        server.execute(() -> {
            try {
                var p = server.getPlayerList().getPlayer(id);
                if (p == null) throw new java.io.IOException("player_missing_before_save");
                SmokeReloadProbe.beforeQuit(p);
                mc.execute(() -> {
                    release(mc);
                    // Same lifecycle as PauseScreen.onDisconnect, verified in the 1.21.1 sources.
                    // disconnect waits for integrated-server shutdown (including playerdata and level.dat save).
                    mc.level.disconnect();
                    mc.disconnect(new TitleScreen());
                    mc.setScreen(new TitleScreen());
                    MurimMod.LOGGER.info("SMOKE-SAVED-QUIT");
                    if ("1".equals(System.getenv("MURIM_SMOKE_SAVE_QUIT")))
                        MurimMod.LOGGER.info("[CHAT] SMOKE-MARK dialogue_saved");
                    finished = true;
                    quitting = false;
                });
            } catch (java.io.IOException e) {
                mc.execute(() -> {
                    MurimMod.LOGGER.info("SMOKE-RELOAD-FAIL {}", e.getMessage());
                    finished = true;
                    quitting = false;
                });
            }
        });
    }

    @SubscribeEvent
    public void tick(ClientTickEvent.Post event) {
        if (!SmokeWalkRoute.enabled() || finished || quitting) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { session = null; return; }
        if (!ownsWorld()) return;
        if (session == null) session = new Session();
        session.tick(mc);
    }

    @SubscribeEvent
    public void input(MovementInputUpdateEvent event) {
        if (!ownsWorld() || quitting || finished || session == null) return;
        var mc = Minecraft.getInstance();
        var in = event.getInput();
        in.forwardImpulse = session.forward;
        in.leftImpulse = session.strafe;
        in.up = session.forward > 0;
        in.down = false;
        in.left = session.strafe > 0;
        in.right = session.strafe < 0;
        in.jumping = session.jump;
        in.shiftKeyDown = false;
        mc.options.keySprint.setDown(session.sprint);
    }

    private void release(Minecraft mc) {
        if (session != null) { session.forward = 0; session.strafe = 0; session.jump = false; session.sprint = false; }
        mc.options.keySprint.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyAttack.setDown(false);
        if (mc.player != null) {
            mc.player.input.forwardImpulse = 0;
            mc.player.input.leftImpulse = 0;
            mc.player.input.jumping = false;
        }
    }

    private static String pos(Vec3 p) { return String.format(Locale.ROOT, "%.3f %.3f %.3f", p.x, p.y, p.z); }

    private final class Session {
        final String walkStart = System.getenv().getOrDefault("MURIM_SMOKE_WALK_START", "sect_gate");
        final String[] steps = System.getenv().getOrDefault("MURIM_SMOKE_WALK", "").split(";");
        long fallbackAt = -1, descendSampleAt = -20, stepUpAt = -20, walkProgressAt;
        SmokeWalkHops.DescentProgress descentProgress;
        Vec3 descentBlockedAt, destination;
        Vec3 steeringStart;
        boolean descending;
        double descentPeak, descentFall;
        double groundSurface = Double.NaN, surfaceX, surfaceZ;
        int hopFailures;
        int trailLeg = -1;
        int index, node, ticks, repaths, lastRequest = -100, frames;
        boolean pending, failed, sprint, jump, clockPending, firstProbe, finalProbe;
        long gameTime = -1, clockOrigin = -1, journeyStart = -1, phaseStart, progressAt, sampleAt, waitingAt = -1;
        float forward, strafe;
        Vec3 progress;
        SmokeWalkRoute.Route route;
        String phase, target, lastError = "";
        DialogueCaptureAnswerGate answerGate;
        boolean answerClosing;
        int answerOption;
        boolean reception, joined;
        final SmokeGateReception gateReception = new SmokeGateReception();
        long receptionAt;
        BlockPos knockDoor;
        int knockAimAt;
        boolean reloadFailed;
        boolean setupStarted, setupComplete;
        int setupReadyAt;

        void fail(Minecraft mc, String reason) {
            if (failed) return;
            failed = true;
            release(mc);
            MurimMod.LOGGER.info("SMOKE-WALK-FAIL {} {} {}", reception ? "gate_reception" : phase == null ? "walk_setup" : phase, pos(mc.player.position()), reason);
        }

        void tick(Minecraft mc) {
            ticks++;
            pollClock(mc);
            release(mc);
            if (failed) return;
            if (ticks % 100 == 0) {
                Screenshot.grab(mc.gameDirectory, String.format("murim_walk_%04d.png", frames++), mc.getMainRenderTarget(), m -> { });
            }
            if ("1".equals(System.getenv("MURIM_SMOKE_RELOAD"))) { reload(mc); return; }
            if (ticks < 80 || gameTime < 0) return; // Wait for real spawn chunks, without capture setup or commands.
            if (!setupStarted) {
                setupStarted = true;
                MurimMod.LOGGER.info("SMOKE-WALK-PLAN {}", String.join(";", steps));
                if ("1".equals(System.getenv("WALK_ROAD"))) setupComplete = true;
                else {
                    var server = mc.getSingleplayerServer();
                    var id = mc.player.getUUID();
                    // API: 1.21.1 Minecraft#getSingleplayerServer, BlockableEventLoop#execute,
                    // MinecraftServer#getPlayerList/getCommands, PlayerList#getPlayer, Entity#createCommandSourceStack,
                    // CommandSourceStack#withPermission, Commands#getDispatcher (read-only /tmp/mr-ref-1121).
                    server.execute(() -> {
                        try {
                            var player = server.getPlayerList().getPlayer(id);
                            if (player == null || server.getCommands().getDispatcher().execute(
                                    "murim dev hua tp " + walkStart, player.createCommandSourceStack().withPermission(4)) <= 0)
                                throw new IllegalStateException("start_teleport_failed");
                            MurimMod.LOGGER.info("SMOKE-MARK fastfwd_{}", walkStart);
                            mc.execute(() -> { setupComplete = true; setupReadyAt = ticks + 40; });
                        } catch (Exception e) {
                            mc.execute(() -> fail(mc, "start_teleport_failed"));
                        }
                    });
                }
            }
            if (!setupComplete || ticks < setupReadyAt) return;
            if (reception) {
                reception(mc);
                return;
            }
            if (phase == null) {
                if (index >= steps.length) {
                    MurimMod.LOGGER.info("SMOKE-JOURNEY-DONE seconds={}", (gameTime - journeyStart) / 20.0);
                    saveAndQuit(); return;
                }
                int split = steps[index].indexOf(':');
                if (split < 1) { fail(mc, "invalid_phase_target"); return; }
                phase = steps[index].substring(0, split);
                target = steps[index].substring(split + 1);
                if (journeyStart < 0) journeyStart = gameTime;
                phaseStart = gameTime; walkProgressAt = gameTime; destination = null; progressAt = gameTime; sampleAt = gameTime - 20;
                progress = mc.player.position(); repaths = 0;
                route = null; trailLeg = -1; lastError = ""; fallbackAt = -1; hopFailures = 0;
                MurimMod.LOGGER.info("SMOKE-MARK {}", phase);
            }
            if (gameTime - sampleAt >= 20) {
                sampleAt = gameTime;
                MurimMod.LOGGER.info("SMOKE-WALK {} {}", phase, pos(mc.player.position()));
            }
            if (destination != null && gameTime - walkProgressAt >= 200) {
                walkProgressAt = gameTime;
                MurimMod.LOGGER.info("SMOKE-WALK-PROGRESS {} {} {} {}", phase, mc.player.getX(), mc.player.getZ(),
                        Math.hypot(destination.x - mc.player.getX(), destination.z - mc.player.getZ()));
            }
            if (!mc.player.isAlive()) { fail(mc, "death"); return; }
            if (gameTime - journeyStart > 36000) { fail(mc, "journey_over_30_minutes"); return; }
            if (descend(mc)) return;
            String fallFailure = SmokeWalkHops.fallFailure(false, mc.player.fallDistance, mc.player.getHealth(), mc.player.getMaxHealth());
            if (!fallFailure.isEmpty()) { fail(mc, fallFailure); return; }
            // Arrival is horizontal: the closed leaf must not consume the stuck retry budget.
            if ("gate".equals(target) && route != null
                    && (Math.hypot(mc.player.getX() - route.target().x, mc.player.getZ() - route.target().z) <= 3
                    || touchingGate(mc))) {
                fallbackAt = -1;
                done(mc);
                reception = true; receptionAt = gameTime;
                MurimMod.LOGGER.info("SMOKE-MARK gate_reception");
                return;
            }
            if (answerGate != null || mc.screen instanceof DialogueScreen) {
                progressAt = gameTime;
                dialogue(mc);
                return;
            }
            if ("npc:guide".equals(target) && route != null && route.member() && !joined) {
                joined = true;
                MurimMod.LOGGER.info("SMOKE-JOINED");
            }
            if (fallbackAt < 0 && Math.hypot(mc.player.getX() - progress.x, mc.player.getZ() - progress.z) > 1) { progress = mc.player.position(); progressAt = gameTime; repaths = 0; }
            if (fallbackAt < 0 && gameTime - progressAt >= 60) {
                progressAt = gameTime;
                if (++repaths >= SmokeWalkHops.FAILURE_LIMIT) { fail(mc, lastError.isEmpty() ? "stuck_retries_exhausted" : lastError + "_retries_exhausted"); return; }
                MurimMod.LOGGER.info("SMOKE-WALK-REPATH {} attempt={}", phase, repaths);
                route = null;
            }
            if (fallbackAt >= 0 && gameTime - fallbackAt >= 100) {
                finishSteering(mc);
                if (failed) return;
            }
            if (!pending && (route == null || fallbackAt < 0 && route.hop() == null
                    && ticks - lastRequest >= (target.startsWith("npc:") || route.trailSteering() ? 20 : 100))) request(mc);
            if (route == null) return;
            if (fallbackAt < 0 && !route.error().isEmpty()) { if ("gate".equals(target)) interactNear(mc); return; } // Retry timers above also cover routes with no safe steering candidate.
            if (mc.screen != null) return;
            double gap = mc.player.position().distanceToSqr(route.target());
            if (gap < 6.25 && !route.escort()) {
                if ("npc:guide".equals(target) && !route.member()) { interactNear(mc); return; }
                done(mc); return;
            }
            if (route.escort() && gap < 9) {
                if (waitingAt < 0) waitingAt = gameTime;
                progressAt = gameTime; // An intentional escort pause is not a three-second path failure.
                if (gameTime - waitingAt >= 600) fail(mc, "escort_stopped_30_seconds");
                return;
            }
            waitingAt = -1;
            List<Vec3> points = fallbackAt >= 0 && route.hop() == null ? List.of(route.target()) : route.points();
            while (node < points.size() && reached(mc.player.position(), points.get(node))) node++;
            if (node >= points.size()) {
                if (fallbackAt >= 0) {
                    finishSteering(mc);
                    if (failed) return;
                }
                if (!pending) request(mc);
                interactNear(mc); return;
            }
            Vec3 next = points.get(node);
            face(mc, next.add(0, 1, 0));
            forward = 1;
            // Small lateral corrections use the real strafe axis; no velocity or player position writes.
            double yaw = Math.toRadians(mc.player.getYRot());
            Vec3 delta = next.subtract(mc.player.position());
            strafe = (float) Math.max(-.25, Math.min(.25, delta.x * Math.cos(yaw) + delta.z * Math.sin(yaw)));
            jump = fallbackAt >= 0 || mc.player.isInWater() || mc.player.onGround() && (next.y > mc.player.getY() + .55 || mc.player.horizontalCollision);
            sprint = !target.startsWith("npc:") && Math.abs(delta.y) < .4 && !jump;
            forestStep(mc, next);
            if (!"gate".equals(target)) useDoor(mc);
        }

        // API verified in 1.21.1 ClipContext, BlockGetter#clip, BlockHitResult#getBlockPos/getType,
        // Entity#getEyePosition and Minecraft#continueAttack: normal held attack mines the crosshair block.
        void forestStep(Minecraft mc, Vec3 next) {
            var p = mc.player;
            double dx = next.x - p.getX(), dz = next.z - p.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance < .01) return;
            dx /= distance; dz /= distance;
            // Never steer blindly across an unloaded edge, lava or an unsupported drop.
            var foot = BlockPos.containing(p.getX() + dx, p.getY(), p.getZ() + dz);
            if (!mc.level.hasChunkAt(foot)) { forward = 0; jump = false; return; }
            // Report the actual blocking column, not the height of a coarse switchback target.
            if ("walk_trail".equals(phase) && p.onGround() && p.horizontalCollision && gameTime - stepUpAt >= 20) {
                int rise = 0;
                for (int up = 0; up <= 3; up++) {
                    var at = BlockPos.containing(p.getX() + dx, p.getY() + up, p.getZ() + dz);
                    if (mc.level.getBlockState(at).getCollisionShape(mc.level, at).isEmpty()) break;
                    rise = up + 1;
                }
                if (rise > 1) {
                    stepUpAt = gameTime;
                    MurimMod.LOGGER.info("SMOKE-WALK-STEP-UP {}", rise);
                }
            }
            boolean supported = false;
            for (int down = 0; down <= 3; down++) {
                var at = BlockPos.containing(p.getX() + dx, p.getY() - down - .05, p.getZ() + dz);
                if (mc.level.getFluidState(at).is(FluidTags.LAVA)) { forward = 0; jump = false; return; }
                if (!mc.level.getBlockState(at).getCollisionShape(mc.level, at).isEmpty()) supported = true;
            }
            if (!supported && !p.isInWater()) { forward = 0; jump = false; return; }
            for (double height : new double[]{1, .25, 1.6}) {
                Vec3 aim = new Vec3(p.getX() + dx * 1.4, p.getY() + height, p.getZ() + dz * 1.4);
                var hit = mc.level.clip(new ClipContext(p.getEyePosition(), aim,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (hit.getType() != HitResult.Type.BLOCK) continue;
                var state = mc.level.getBlockState(hit.getBlockPos());
                var obstacle = state.is(BlockTags.LOGS) ? SmokeWalkHops.Block.TRUNK
                        : state.is(BlockTags.LEAVES) ? SmokeWalkHops.Block.LEAVES : SmokeWalkHops.Block.SOLID;
                if (SmokeWalkHops.cutLeaves(obstacle)) {
                    face(mc, Vec3.atCenterOf(hit.getBlockPos()));
                    mc.options.keyAttack.setDown(true);
                    forward = 0; strafe = 0; jump = false;
                } else if (obstacle == SmokeWalkHops.Block.TRUNK) {
                    // Alternate sides between retries, preserving the trunk and using ordinary player input.
                    forward = .2f; strafe = hopFailures % 2 == 0 ? 1 : -1; jump = false;
                }
                return;
            }
        }

        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/tags/BlockTags.java#LEAVES;
        // world/level/BlockGetter.java#getBlockState; BlockBehaviour.BlockStateBase#is(TagKey).
        // Client-only input state; the server supplies only a surface snapshot, never block edits.
        boolean descend(Minecraft mc) {
            var p = mc.player;
            var below = BlockPos.containing(p.getX(), p.getY() - .05, p.getZ());
            boolean leavesBelow = mc.level.getBlockState(below).is(BlockTags.LEAVES);
            boolean leavesInside = mc.level.getBlockState(p.blockPosition()).is(BlockTags.LEAVES)
                    || mc.level.getBlockState(BlockPos.containing(p.getX(), p.getY() + 1, p.getZ())).is(BlockTags.LEAVES);
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/core/BlockPos.java#below;
            // LevelReader#hasChunkAt; BlockBehaviour.BlockStateBase#getCollisionShape; VoxelShape#isEmpty.
            var underLeaf = below.below();
            boolean passableUnderLeaf = mc.level.hasChunkAt(underLeaf)
                    && mc.level.getBlockState(underLeaf).getCollisionShape(mc.level, underLeaf).isEmpty();
            if (descentBlockedAt != null && (SmokeWalkHops.steeringProgress(descentBlockedAt.x, descentBlockedAt.z,
                    p.getX(), p.getZ()) || p.getY() < descentBlockedAt.y - .5)) descentBlockedAt = null;
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/LivingEntity.java#getHealth/getMaxHealth;
            // Entity.java#onGround/fallDistance; BlockBehaviour.java#BlockStateBase.getCollisionShape.
            boolean solidGround = p.onGround() && !leavesBelow && !leavesInside
                    && !mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty();
            boolean active = SmokeWalkHops.descending(descending,
                    descentBlockedAt == null && SmokeWalkHops.canopy(p.onGround(), leavesBelow, passableUnderLeaf), solidGround);
            if (active && !descending) {
                descentPeak = p.getY(); descentFall = 0; descendSampleAt = gameTime - 20;
                descentProgress = new SmokeWalkHops.DescentProgress(p.getY(), gameTime);
            }
            if (descending || active) {
                descentFall = Math.max(descentFall, Math.max(p.fallDistance, descentPeak - p.getY()));
                String failure = SmokeWalkHops.fallFailure(true, descentFall, p.getHealth(), p.getMaxHealth());
                if (!failure.isEmpty()) { fail(mc, failure); return true; }
                if (solidGround) {
                    MurimMod.LOGGER.info("SMOKE-WALK-LANDED {} fall={} health={}", pos(p.position()), descentFall, p.getHealth());
                }
                // Each supported leaf layer starts a new fall, while the descent window stays open.
                if (p.onGround()) descentPeak = p.getY();
                else descentPeak = Math.max(descentPeak, p.getY());
            }
            descending = active;
            if (!descending) return false;
            descentProgress = descentProgress.sample(p.getY(), gameTime);
            if (descentProgress.stalled(gameTime)) {
                MurimMod.LOGGER.info("SMOKE-WALK-DESCEND-EXIT {} no_downward_progress", pos(p.position()));
                descentBlockedAt = p.position(); descending = false;
                route = null; fallbackAt = -1; progressAt = gameTime; progress = p.position();
                return false; // Repath and resume forestStep's forward leaf cutting, without re-entering here.
            }
            if (mc.screen != null) return true;
            route = null; fallbackAt = -1; progressAt = gameTime; progress = p.position();
            if (gameTime - descendSampleAt >= 20) {
                descendSampleAt = gameTime;
                MurimMod.LOGGER.info("SMOKE-WALK-DESCEND {}", pos(p.position()));
            }
            // API verified: Entity#setXRot, Options#keyAttack, KeyMapping#setDown;
            // Minecraft#continueAttack uses the held key and the normal crosshair ray to mine.
            // Looking straight down also clears leaves enclosing the feet before the supporting layer.
            p.setXRot(90);
            mc.options.keyAttack.setDown(SmokeWalkHops.descend(true, p.onGround(), leavesInside || leavesBelow));
            return true;
        }

        void finishSteering(Minecraft mc) {
            double distance = Math.hypot(mc.player.getX() - steeringStart.x, mc.player.getZ() - steeringStart.z);
            boolean moved = SmokeWalkHops.steeringProgress(steeringStart.x, steeringStart.z, mc.player.getX(), mc.player.getZ());
            MurimMod.LOGGER.info("SMOKE-WALK-STEER-RESULT {} progress={} distance={}", phase, moved, distance);
            fallbackAt = -1; route = null; progressAt = gameTime; progress = mc.player.position();
            if (moved) { hopFailures = 0; repaths = 0; }
            else if (SmokeWalkHops.exhausted(++hopFailures)) fail(mc, "no_hop_path_retries_exhausted");
        }

        void pollClock(Minecraft mc) {
            if (clockPending || mc.getSingleplayerServer() == null) return;
            var server = mc.getSingleplayerServer();
            clockPending = true;
            var playerId = mc.player.getUUID();
            server.execute(() -> {
                long now = server.overworld().getGameTime();
                var p = server.getPlayerList().getPlayer(playerId);
                // NO_LEAVES is LIVE_WORLD and is not sent in chunk packets (Heightmap.Types).
                double sx = p == null ? Double.NaN : Math.floor(p.getX());
                double sz = p == null ? Double.NaN : Math.floor(p.getZ());
                double sy = p == null ? Double.NaN : p.serverLevel().getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) sx, (int) sz);
                mc.execute(() -> {
                    gameTime = now; groundSurface = sy; surfaceX = sx; surfaceZ = sz;
                    if (clockOrigin < 0) clockOrigin = now;
                    clockPending = false;
                });
            });
        }

        boolean reached(Vec3 at, Vec3 next) { return Math.hypot(at.x - next.x, at.z - next.z) < .55 && Math.abs(at.y - next.y) < 1.1; }

        void request(Minecraft mc) {
            var server = mc.getSingleplayerServer();
            if (server == null) { fail(mc, "integrated_server_required"); return; }
            if (SmokeGateReception.requestPhase(phase, reception) == null) return;
            pending = true; lastRequest = ticks;
            var id = mc.player.getUUID();
            String requested = target;
            String requestedPhase = SmokeGateReception.requestPhase(phase, reception);
            int requestedLeg = trailLeg;
            server.execute(() -> {
                var p = server.getPlayerList().getPlayer(id);
                if (p == null) {
                    mc.execute(() -> { pending = false; fail(mc, "route_player_missing"); });
                    return;
                }
                final SmokeWalkRoute.Route result;
                try {
                    result = SmokeWalkRoute.compute(p, requested, requestedLeg);
                } catch (RuntimeException e) {
                    mc.execute(() -> { pending = false; fail(mc, "route_compute_failed"); });
                    return;
                }
                mc.execute(() -> {
                    pending = false;
                    if (session != this || mc.player == null || failed || !SmokeGateReception.accepts(requested, requestedPhase, target, phase, reception) || descending) return;
                    route = result; destination = result.target(); lastError = result.error(); trailLeg = result.trailLeg(); node = 0;
                    MurimMod.LOGGER.info("SMOKE-WALK-LEG {} {} {} {} dist={} nodes={} reached={}", requestedPhase,
                            result.leg().x, result.leg().y, result.leg().z,
                            mc.player.position().distanceTo(result.leg()), result.points().size(), result.reached());
                    if (result.points().isEmpty() && result.reached()) {
                        progressAt = gameTime; progress = mc.player.position(); repaths = 0;
                    }
                    if (result.fallback()) {
                        fallbackAt = gameTime; steeringStart = mc.player.position();
                        MurimMod.LOGGER.info("SMOKE-WALK-STEER {} {} {}", phase, result.hop() == null ? result.target().x : result.hop().x, result.hop() == null ? result.target().z : result.hop().z);
                    }
                    else {
                        fallbackAt = -1;
                        hopFailures = 0;
                    }
                    if (result.hop() != null) MurimMod.LOGGER.info("SMOKE-WALK-HOP {} {} {} {}", phase,
                            result.hop().x, result.hop().z, Math.hypot(result.target().x - result.hop().x, result.target().z - result.hop().z));
                });
            });
        }

        void done(Minecraft mc) {
            if (fallbackAt >= 0) {
                finishSteering(mc);
                if (failed) return;
            }
            MurimMod.LOGGER.info("SMOKE-WALK-DONE {} {}", phase, (gameTime - phaseStart) / 20.0);
            index++; phase = null; route = null;
        }

        // Uses the same answer@key readiness/acknowledgement gate as DialogueCapture.
        // arrival.json: learn invites and orders a guide; mentor.json: accept actually admits.
        void dialogue(Minecraft mc) {
            DialogueScreen screen = mc.screen instanceof DialogueScreen ds ? ds : null;
            int option = -1;
            if (answerGate == null) {
                if (screen == null) return;
                for (String key : new String[]{"dialogue.murim.arrival.opt.learn", "dialogue.murim.arrival.opt.follow",
                        "dialogue.murim.arrival.opt.introduce", "dialogue.murim.arrival.opt.accept"}) {
                    option = screen.captureAnswer(key);
                    if (option >= 0) break;
                }
                answerOption = option;
                answerClosing = option < 0;
                answerGate = new DialogueCaptureAnswerGate(answerClosing);
            }
            var result = answerGate.poll(ticks, screen == null ? null : screen.captureLine(),
                    screen != null && screen.captureReady());
            if (result == DialogueCaptureAnswerGate.Result.FAIL) {
                fail(mc, answerGate.failure());
            } else if (result == DialogueCaptureAnswerGate.Result.PRESS) {
                if (answerClosing) screen.keyPressed(48, 0, 0); // Explicit normal close; keep the ordered guide.
                else screen.press(answerOption);
            } else if (result == DialogueCaptureAnswerGate.Result.DONE) answerGate = null;
        }

        // API verified in read-only 1.21.1 BlockPos#betweenClosed/offset/immutable,
        // BlockGetter#getBlockState, Entity#getEyePosition, Vec3#atCenterOf and KeyMapping#setDown.
        BlockPos gateLeaf(Minecraft mc) {
            BlockPos best = null;
            double distance = 16;
            for (BlockPos at : BlockPos.betweenClosed(mc.player.blockPosition().offset(-3, 0, -3),
                    mc.player.blockPosition().offset(3, 3, 3))) {
                if (!(mc.level.getBlockState(at).getBlock() instanceof GateDoorBlock)) continue;
                double d = Vec3.atCenterOf(at).distanceToSqr(mc.player.getEyePosition());
                if (d < distance) { distance = d; best = at.immutable(); }
            }
            return best;
        }

        boolean touchingGate(Minecraft mc) {
            BlockPos at = gateLeaf(mc);
            return at != null && mc.player.horizontalCollision
                    && Math.hypot(mc.player.getX() - (at.getX() + .5), mc.player.getZ() - (at.getZ() + .5)) < 1;
        }

        void reception(Minecraft mc) {
            if (!mc.player.isAlive()) { fail(mc, "death"); return; }
            if (gameTime - receptionAt > 2400) { fail(mc, "gate_reception_timeout"); return; }
            if (!pending && (route == null || ticks - lastRequest >= 20)) request(mc);
            if (gateReception.stage() == SmokeGateReception.Stage.AIM) {
                // Reception may open automatically before the deliberate knock. Close it normally first.
                if (answerGate != null || mc.screen instanceof DialogueScreen) {
                    if (answerGate == null) {
                        answerClosing = true;
                        answerGate = new DialogueCaptureAnswerGate(true);
                    }
                    dialogue(mc);
                    return;
                }
                if (knockDoor == null) {
                    knockDoor = gateLeaf(mc); knockAimAt = ticks;
                    if (knockDoor == null) return;
                }
                face(mc, Vec3.atCenterOf(knockDoor));
                // Let the normal crosshair ray update before a single one-tick use pulse.
                // Minecraft#handleKeybinds/startUseItem consumes held use through the ordinary interaction pipeline.
                if (ticks > knockAimAt && mc.screen == null && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(knockDoor)
                        && gateReception.knock(true)) {
                    mc.options.keyUse.setDown(true);
                    MurimMod.LOGGER.info("SMOKE-GATE-KNOCK");
                }
                return;
            }
            if (route == null) return;
            if (gateReception.stage() == SmokeGateReception.Stage.GREETER) {
                for (var e : mc.level.entitiesForRendering()) {
                    if (e instanceof SectDisciple d && d.getId() == route.person() && d.distanceToSqr(mc.player) <= 64) {
                        gateReception.greet(true);
                        MurimMod.LOGGER.info("SMOKE-GATE-GREETER {}", d.getName().getString());
                        break;
                    }
                }
                if (gateReception.stage() == SmokeGateReception.Stage.GREETER) return;
            }
            if (answerGate != null || mc.screen instanceof DialogueScreen) { dialogue(mc); return; }
            if (gateReception.finish(route.guideReady())) { reception = false; route = null; return; }
            interactNear(mc);
        }

        void interactNear(Minecraft mc) {
            if (!reception && !"gate".equals(target)) useDoor(mc);
            SectDisciple nearest = null;
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof SectDisciple d && e.distanceToSqr(mc.player) < 64
                        && (route == null || route.person() < 0 || d.getId() == route.person())
                        && (nearest == null || e.distanceToSqr(mc.player) < nearest.distanceToSqr(mc.player))) nearest = d;
            }
            if (nearest != null) {
                face(mc, nearest.getEyePosition());
                if (nearest.distanceToSqr(mc.player) > 7.84) {
                    forward = .65f;
                    jump = mc.player.isInWater() || mc.player.onGround() && mc.player.horizontalCollision;
                } else if (ticks % 20 == 0) mc.options.keyUse.setDown(true);
            }
        }

        void useDoor(Minecraft mc) {
            if (ticks % 20 != 0) return;
            BlockPos best = null;
            double distance = 16;
            for (BlockPos at : BlockPos.betweenClosed(mc.player.blockPosition().offset(-3, 0, -3), mc.player.blockPosition().offset(3, 2, 3))) {
                var state = mc.level.getBlockState(at);
                boolean closed = state.getBlock() instanceof GateDoorBlock && !state.getValue(GateDoorBlock.OPEN)
                        || state.getBlock() instanceof HallGrandDoorBlock && !state.getValue(HallGrandDoorBlock.OPEN)
                        || state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN);
                double d = Vec3.atCenterOf(at).distanceToSqr(mc.player.getEyePosition());
                if (closed && d < distance) { best = at.immutable(); distance = d; }
            }
            if (best != null) { face(mc, Vec3.atCenterOf(best)); mc.options.keyUse.setDown(true); }
        }

        void face(Minecraft mc, Vec3 to) {
            Vec3 d = to.subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
        }

        void reload(Minecraft mc) {
            if (gameTime < 0 || clockOrigin < 0) return;
            long elapsed = gameTime - clockOrigin;
            boolean end;
            if (!firstProbe && elapsed >= 100) { firstProbe = true; end = false; }
            else if (!finalProbe && elapsed >= 300) { finalProbe = true; end = true; }
            else return;
            var server = mc.getSingleplayerServer();
            if (server == null) { fail(mc, "integrated_server_required"); return; }
            var id = mc.player.getUUID();
            server.execute(() -> {
                var p = server.getPlayerList().getPlayer(id);
                String reason = p == null ? "player_missing" : SmokeReloadProbe.check(p);
                mc.execute(() -> {
                    if (!reason.isEmpty()) { reloadFailed = true; MurimMod.LOGGER.info("SMOKE-RELOAD-FAIL {}", reason); }
                    if (end) {
                        if (!reloadFailed) {
                            MurimMod.LOGGER.info("SMOKE-RELOAD-OK");
                            saveAndQuit();
                        } else finished = true;
                    }
                });
            });
        }
    }
}
