package io.github.verycooltimo.murim.client.training;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.training.Exercise;
import io.github.verycooltimo.murim.training.Routes;
import io.github.verycooltimo.murim.training.RouteRun;
import io.github.verycooltimo.murim.training.TrainingRegistry;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Stand of body training ({@code ./capture.sh … training}, only {@code -Dmurim.capture=true}). Scenes
 * ({@code MURIM_TRAIN_SCENES}, comma list, default {@code yard,player,climb}):
 *
 * <ul>
 *   <li>{@code yard} — five disciples in the training poses (squat, push-up, weighted push-up, horse stance, stone),
 *       each from 3.4 blocks, the row from 12 blocks by day and by night;</li>
 *   <li>{@code player} — the real player with real input: the crouch key is pressed by the stand
 *       ({@code keyShift.setDown}), the pitch and the items are set on the server; squats in rhythm, horse stance,
 *       weighted push-ups, push-ups, then the stone carried up a stair (player camera, third person front);</li>
 *   <li>{@code climb} — the South Peak climb on the generated mountain: the player is moved ledge to ledge in small
 *       steps (no teleports — they void the run), falls off ledge 7 once and goes on from the rest ledge, HUD on.</li>
 * </ul>
 * Frames: {@code screenshots/murim_train_<shot>_<n>.png}. Waits are counted in client ticks (CLAUDE.md §2a: no TickTask).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TrainingCapture {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "training".equals(System.getProperty("murim.capture.technique"));
    private static final String CAMERA = "murim_train_cam";

    /** A step of the stand: duration, frame interval (0 — none), start and per-tick actions (client thread). */
    private record Step(String name, int ticks, int every, Runnable start, IntConsumer tick, boolean waitRender) {
    }

    private static final List<Step> steps = new ArrayList<>();
    private static boolean setup;
    private static int index = -1;
    private static int t;
    private static int frame;
    private static int stable;
    private static int lastSections = -1;
    private static boolean rendering;
    private static BlockPos origin;
    private static final List<Integer> npcs = new ArrayList<>();
    private static List<RouteRun.Point> climb = List.of();

    private TrainingCapture() {
    }

    private static int env(String name, int fallback) {
        try {
            String raw = System.getenv(name);
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null) {
            return;
        }
        if (!setup) {
            setup = true;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.fov().set(60);
            String raw = System.getenv().getOrDefault("MURIM_TRAIN_SCENES", "yard,player,climb");
            List<String> scenes = List.of(raw.split(","));
            server.execute(() -> build(server));
            steps.add(new Step("wait", 80, 0, () -> { }, i -> { }, false));
            if (scenes.contains("yard")) {
                yard(server);
            }
            if (scenes.contains("player")) {
                player(mc, server);
            }
            if (scenes.contains("climb")) {
                climb(mc, server);
            }
            steps.add(new Step("done", 1, 0, () -> MurimMod.LOGGER.info("Стенд тренировок: снято"), i -> { }, false));
            return;
        }
        if (index >= steps.size()) {
            return;
        }
        follow(mc);
        if (index < 0 || t >= steps.get(index).ticks()) {
            next(mc);
            return;
        }
        Step s = steps.get(index);
        if (s.waitRender() && rendering) {
            int sections = mc.levelRenderer.countRenderedSections();
            stable = sections == lastSections && mc.levelRenderer.hasRenderedAllSections() ? stable + 1 : 0;
            lastSections = sections;
            t++;
            if (stable >= 20 && t > 60 || t > env("MURIM_TRAIN_RENDER_MAX", 1400)) {
                MurimMod.LOGGER.info("Стенд тренировок: {} — мир готов за {} тиков", s.name(), t);
                rendering = false;
                t = 0;
            }
            return;
        }
        s.tick().accept(t);
        if (s.every() > 0 && t % s.every() == 0) {
            Screenshot.grab(mc.gameDirectory, String.format("murim_train_%s_%02d.png", s.name(), frame++), mc.getMainRenderTarget(), m -> {
            });
        }
        t++;
    }

    private static void next(Minecraft mc) {
        index++;
        t = 0;
        frame = 0;
        stable = 0;
        lastSections = -1;
        if (index >= steps.size()) {
            return;
        }
        Step s = steps.get(index);
        rendering = s.waitRender();
        MurimMod.LOGGER.info("Стенд тренировок: шаг {}", s.name());
        s.start().run();
    }

    /** Camera: the newest stand named CAMERA, if any; otherwise the player. */
    private static void follow(Minecraft mc) {
        Entity newest = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ArmorStand && e.isAlive() && e.getCustomName() != null && CAMERA.equals(e.getCustomName().getString())
                    && (newest == null || e.getId() > newest.getId())) {
                newest = e;
            }
        }
        Entity want = newest != null ? newest : mc.player;
        if (mc.getCameraEntity() != want) {
            mc.setCameraEntity(want);
        }
    }

    // ------------------------------------------------------------------ build

    private static void build(IntegratedServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setWeatherParameters(12000, 0, false, false);
        level.setDayTime(6000L);
        BlockPos base = p.blockPosition().offset(0, 0, 8);
        int y = base.getY();
        for (int dx = -8; dx <= 34; dx++) {
            for (int dz = -16; dz <= 10; dz++) {
                level.setBlock(new BlockPos(base.getX() + dx, y - 1, base.getZ() + dz), Blocks.POLISHED_ANDESITE.defaultBlockState(), 2);
                for (int dy = 0; dy < 16; dy++) {
                    level.setBlock(new BlockPos(base.getX() + dx, y + dy, base.getZ() + dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (Entity e : level.getEntities((Entity) null, new AABB(base).inflate(48, 16, 32), e -> !(e instanceof ServerPlayer))) {
            e.discard();
        }
        origin = base;
        npcs.clear();
        // The row of disciples (scene yard), 5 blocks apart along +x.
        SectPose[] poses = {SectPose.SQUAT, SectPose.PUSHUP, SectPose.PUSHUP_WEIGHTED, SectPose.HORSE_STANCE, SectPose.CARRY_STONE};
        SectRole[] roles = {SectRole.DISCIPLE_A, SectRole.DISCIPLE_B, SectRole.SECOND, SectRole.DISCIPLE_A, SectRole.DISCIPLE_B};
        for (int i = 0; i < poses.length; i++) {
            BlockPos at = base.offset(i * 5, 0, 0);
            SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
            npc.setRole(roles[i]);
            npc.setKeepAwake(true);
            npc.setNoAi(true);
            npc.moveTo(at.getX() + 0.5D, y, at.getZ() + 0.5D, 0.0F, 0.0F);
            npc.setYHeadRot(0.0F);
            npc.setYBodyRot(0.0F);
            level.addFreshEntity(npc);
            npc.playPose(poses[i]);
            npcs.add(npc.getId());
        }
        // Training stones beside the stone carrier.
        level.setBlock(base.offset(4 * 5 + 1, 0, 1), TrainingRegistry.TRAINING_STONE.get().defaultBlockState(), 3);
        level.setBlock(base.offset(4 * 5 + 2, 0, 0), TrainingRegistry.TRAINING_STONE.get().defaultBlockState(), 3);
        // The player's corner (scene player) at z −10, and a stair of 6 steps up +x for the stone.
        BlockPos stair = base.offset(14, 0, -10);
        for (int i = 0; i < 6; i++) {
            for (int w = -1; w <= 1; w++) {
                for (int below = 0; below < i; below++) {
                    level.setBlock(stair.offset(i, below, w), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                }
                level.setBlock(stair.offset(i, i, w), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST), 2);
            }
        }
        for (int w = -1; w <= 1; w++) {
            for (int i = 6; i < 9; i++) {
                for (int below = 0; below <= 5; below++) {
                    level.setBlock(stair.offset(i, below, w), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                }
            }
        }
        p.setGameMode(GameType.SURVIVAL);
        p.getFoodData().setFoodLevel(20);
        p.getInventory().clearContent();
        MurimMod.LOGGER.info("Стенд тренировок: площадка у {}", base);
    }

    // ------------------------------------------------------------------ camera

    private static void stand(IntegratedServer server, Vec3 look, Vec3 cam) {
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (ArmorStand old : level.getEntitiesOfClass(ArmorStand.class, new AABB(BlockPos.containing(look)).inflate(512.0D),
                    a -> a.getCustomName() != null && CAMERA.equals(a.getCustomName().getString()))) {
                old.discard();
            }
            if (cam == null) {
                return;
            }
            ArmorStand stand = new ArmorStand(level, cam.x, cam.y - 1.62D, cam.z);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setCustomName(Component.literal(CAMERA));
            Vec3 d = look.subtract(cam);
            float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F;
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            stand.setYRot(yaw);
            stand.setYHeadRot(yaw);
            stand.setXRot(pitch);
            level.addFreshEntity(stand);
        });
    }

    private static void time(IntegratedServer server, long dayTime) {
        server.execute(() -> server.overworld().setDayTime(dayTime));
    }

    // ------------------------------------------------------------------ yard: disciples

    private static void yard(IntegratedServer server) {
        String[] names = {"squat", "pushup", "pushup_weighted", "horse", "carry"};
        for (int i = 0; i < names.length; i++) {
            int k = i;
            steps.add(new Step("npc_" + names[i], 48, 4, () -> {
                Minecraft.getInstance().options.hideGui = true;
                time(server, 6000L);
                Vec3 look = new Vec3(origin.getX() + k * 5 + 0.5D, origin.getY() + 0.7D, origin.getZ() + 0.5D);
                // Side-front: the push-up profile and the squat depth both read.
                stand(server, look, look.add(new Vec3(1.0D, 0.0D, 0.8D).normalize().scale(3.6D)).add(0.0D, 0.9D, 0.0D));
            }, i2 -> { }, false));
        }
        for (String when : new String[] {"day", "night"}) {
            steps.add(new Step("npc_row_" + when, 40, 10, () -> {
                Minecraft.getInstance().options.hideGui = true;
                time(server, "day".equals(when) ? 6000L : 18000L);
                Vec3 look = new Vec3(origin.getX() + 10.5D, origin.getY() + 0.7D, origin.getZ() + 0.5D);
                stand(server, look, look.add(3.0D, 3.5D, 11.0D));
            }, i -> { }, false));
        }
    }

    // ------------------------------------------------------------------ player: real input

    private static ServerPlayer sp(IntegratedServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static void pitch(IntegratedServer server, float pitch) {
        Minecraft.getInstance().player.setXRot(pitch);
        server.execute(() -> sp(server).setXRot(pitch));
    }

    private static void hand(IntegratedServer server, ItemStack stack) {
        server.execute(() -> sp(server).setItemInHand(InteractionHand.MAIN_HAND, stack));
    }

    private static void crouch(boolean down) {
        Minecraft.getInstance().options.keyShift.setDown(down);
    }

    private static void player(Minecraft mc, IntegratedServer server) {
        Vec3[] spot = new Vec3[1];
        Runnable place = () -> server.execute(() -> {
            ServerPlayer p = sp(server);
            Vec3 at = new Vec3(origin.getX() + 4.5D, origin.getY(), origin.getZ() - 9.5D);
            spot[0] = at;
            p.teleportTo(server.overworld(), at.x, at.y, at.z, 90.0F, 0.0F);
            p.removeAllEffects();
        });
        // Side view of the player's spot (the player faces −x, yaw 90): the camera stands to the player's left (+z side).
        Runnable sideCam = () -> {
            Vec3 look = new Vec3(origin.getX() + 4.5D, origin.getY() + 0.8D, origin.getZ() - 9.5D);
            stand(server, look, look.add(-1.5D, 0.9D, 4.2D));
        };
        steps.add(new Step("p_setup", 30, 0, () -> {
            mc.options.hideGui = false;
            time(server, 6000L);
            place.run();
            hand(server, ItemStack.EMPTY);
            pitch(server, 0.0F);
            sideCam.run();
        }, i -> crouch(false), false));
        // Squats: double tap (ticks 0, 12), then a tap every 24 ticks (the beat), crouch held 4 ticks.
        steps.add(new Step("p_squat", 180, 3, () -> { }, i -> {
            int phase = i < 12 ? i : (i - 12) % Exercise.SQUAT.beat();
            crouch(i < 12 ? i < 4 : phase < 4);
        }, false));
        // Horse stance: one more squat, stay down.
        steps.add(new Step("p_horse", 120, 8, () -> { }, i -> crouch(true), false));
        steps.add(new Step("p_rest", 100, 0, () -> { }, i -> crouch(false), false));
        // Weighted push-ups: the slab in hand, look at the ground, tap — plank; a tap per beat; look up — stand.
        steps.add(new Step("p_pushup_weighted", 230, 4, () -> {
            hand(server, new ItemStack(TrainingRegistry.WEIGHT_SLAB.get()));
            pitch(server, 70.0F);
        }, i -> {
            int beat = Exercise.PUSHUP_WEIGHTED.beat();
            crouch(i % beat < 5 && i < 200);
            if (i == 205) {
                pitch(server, 0.0F);
            }
        }, false));
        steps.add(new Step("p_pushup", 160, 6, () -> {
            hand(server, ItemStack.EMPTY);
            pitch(server, 70.0F);
        }, i -> {
            crouch(i % Exercise.PUSHUP.beat() < 5 && i < 130);
            if (i == 135) {
                pitch(server, 0.0F);
            }
        }, false));
        // The stone up the stair: player camera from the front, walk forward up the steps.
        steps.add(new Step("p_carry", 110, 5, () -> {
            stand(server, Vec3.ZERO, null);
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            server.execute(() -> {
                ServerPlayer p = sp(server);
                p.teleportTo(server.overworld(), origin.getX() + 11.5D, origin.getY(), origin.getZ() - 9.5D, -90.0F, 10.0F);
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(TrainingRegistry.TRAINING_STONE_ITEM.get()));
            });
        }, i -> {
            mc.options.keyUp.setDown(i > 15 && i < 100);
        }, false));
        steps.add(new Step("p_carry_end", 20, 0, () -> {
            mc.options.keyUp.setDown(false);
            hand(server, ItemStack.EMPTY);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }, i -> { }, false));
    }

    // ------------------------------------------------------------------ climb: the South Peak route

    private static void climb(Minecraft mc, IntegratedServer server) {
        MountHuaSite site = MountHuaSites.get(server);
        if (site == null) {
            MurimMod.LOGGER.warn("Стенд тренировок: горы нет — подъём не снят");
            return;
        }
        climb = Routes.climb(site);
        if (climb.size() < 2) {
            return;
        }
        // Waypoints: every ledge in order; after ledge 7 a fall 6 blocks below it, back to the rest ledge's next.
        List<Vec3> path = new ArrayList<>();
        for (int i = 0; i < climb.size(); i++) {
            RouteRun.Point pt = climb.get(i);
            path.add(new Vec3(pt.x(), pt.y() + 0.05D, pt.z()));
            if (i == 6) {
                path.add(new Vec3(pt.x() + 0.5D, pt.y() - 6.0D, pt.z() + 0.5D));
                RouteRun.Point rest = climb.get(3);
                path.add(new Vec3(rest.x(), rest.y() + 0.05D, rest.z()));
                for (int j = 4; j <= 6; j++) {
                    path.add(new Vec3(climb.get(j).x(), climb.get(j).y() + 0.05D, climb.get(j).z()));
                }
            }
        }
        RouteRun.Point first = climb.get(0);
        steps.add(new Step("climb_arrive", 1, 0, () -> {
            mc.options.hideGui = false;
            stand(server, Vec3.ZERO, null);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.options.renderDistance().set(12);
            time(server, env("MURIM_TRAIN_CLIMB_NIGHT", 0) == 1 ? 18000L : 4000L);
            server.execute(() -> {
                ServerPlayer p = sp(server);
                p.setNoGravity(true);
                p.teleportTo(server.overworld(), first.x(), first.y() + 8.0D, first.z() + 6.0D, 180.0F, 20.0F);
            });
        }, i -> { }, false));
        // Wait for the face to generate and render (llvmpipe: slow), hovering above the start.
        steps.add(new Step("climb_wait", 1, 0, () -> { }, i -> { }, true));
        // Move: 0.5 block a tick, 12 ticks of rest at each waypoint; one frame every 8 ticks.
        List<double[]> ticks = new ArrayList<>();
        Vec3 from = new Vec3(first.x(), first.y() + 8.0D, first.z() + 6.0D);
        for (Vec3 to : path) {
            int n = Math.max(4, (int) Math.ceil(from.distanceTo(to) / 0.5D));
            for (int k = 1; k <= n; k++) {
                Vec3 at = from.lerp(to, k / (double) n);
                ticks.add(new double[] {at.x, at.y, at.z, to.x - from.x, to.z - from.z});
            }
            for (int k = 0; k < 12; k++) {
                ticks.add(new double[] {to.x, to.y, to.z, to.x - from.x, to.z - from.z});
            }
            from = to;
        }
        steps.add(new Step("climb", ticks.size() + 60, 8, () -> { }, i -> {
            if (i >= ticks.size()) {
                return;
            }
            double[] a = ticks.get(i);
            float yaw = (float) Math.toDegrees(Math.atan2(-a[3], a[4]));
            server.execute(() -> {
                ServerPlayer p = sp(server);
                p.setNoGravity(true);
                p.teleportTo(server.overworld(), a[0], a[1], a[2], yaw, 25.0F);
            });
        }, false));
        steps.add(new Step("climb_end", 20, 0, () -> server.execute(() -> sp(server).setNoGravity(false)), i -> { }, false));
    }
}
