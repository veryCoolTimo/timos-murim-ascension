package io.github.verycooltimo.murim.client.sect.face;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectRoster;
import io.github.verycooltimo.murim.sect.SectRole;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stand for the student models and faces ({@code MURIM_STAND=faces MURIM_FACE_MODE=ranks|faces|moods}, technique sectposes, only
 * with {@code -Dmurim.capture=true}). A flat lawn, a row of sect people facing the camera, a camera on an invisible armor stand
 * that the stand moves shot by shot; frames {@code murim_faces_<mode>_<shot>.png}.
 * <ul>
 *   <li>{@code ranks}: a third-, a second- and a first-rate student side by side, the plain skins (front, three quarters,
 *       back, side, and the front at night); the before / after of the model change.</li>
 *   <li>{@code faces}: six third-generation people by member key ({@link #FACE_KEYS}), the face constructor forced on: the row
 *       and a close-up of each face.</li>
 *   <li>{@code moods}: one person six times, one mood each (neutral, talk, pain, angry, happy, asleep): the row and the close-ups.</li>
 * </ul>
 * The end of the film is logged as {@code StudentFaceCapture: done}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class StudentFaceCapture {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "sectposes".equals(System.getProperty("murim.capture.technique")) && "faces".equals(System.getenv("MURIM_STAND"));

    /** Six sect people chosen so that their picks differ in all four slots (tools/art/face_parts.py pick). */
    static final List<String> FACE_KEYS = List.of("disciple_a", "yul_jin", "yul_rim", "yul_bin", "yul_san", "yul_ak");
    private static final List<FaceMood> MOODS = List.of(FaceMood.NEUTRAL, FaceMood.TALK, FaceMood.PAIN, FaceMood.ANGRY, FaceMood.HAPPY,
            FaceMood.ASLEEP);

    /** Seed of the moods row (no member key): see tools/art/face_parts.py pick third_0. */
    private static final String MOODS_LOOK = "third_0";
    private static final String CAMERA = "murim_faces_cam";
    private static final double SPACING_PEOPLE = 1.7D;

    /** One frame: name, camera direction from the target (x, z), distance, height over the target, look height, night, target person or -1 = the row. */
    private record Shot(String name, double dx, double dz, double dist, double height, double lookY, boolean night, int person) {
    }

    private static String mode = "ranks";
    private static List<Shot> shots = List.of();
    private static boolean setup;
    private static BlockPos origin;
    private static int people;
    private static int index = -1;
    private static int wait;
    private static boolean applied;
    private static int calm;
    /** Entity ids of the stand's people: everything else near the lawn (wanderers, mobs, dummies) is swept away. */
    private static final java.util.Set<Integer> MINE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private StudentFaceCapture() {
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
            String m = System.getenv("MURIM_FACE_MODE");
            mode = m == null || m.isBlank() ? "ranks" : m;
            mc.options.hideGui = true;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.fov().set(50);
            people = "ranks".equals(mode) ? 3 : 6;
            shots = shotsFor(mode, people);
            server.execute(() -> build(server));
            wait = -80;
            return;
        }
        if (origin == null) {
            return;
        }
        follow(mc);
        wait++;
        if (wait % 20 == 0) {
            server.execute(() -> sweep(server.overworld()));
        }
        if (index < 0) {
            // Wait for the lawn and the people to be on the client and the terrain to be drawn, then the first shot.
            // The lawn lights up asynchronously: the first shot only after the terrain is drawn and the light engines have been
            // idle for four seconds (an unlit stripe along a chunk border was in frames taken sooner).
            calm = wait >= 0 && enough(mc) && mc.levelRenderer.hasRenderedAllSections() && !mc.level.getLightEngine().hasLightWork()
                    && !server.overworld().getLightEngine().hasLightWork() ? calm + 1 : 0;
            if (calm >= 80) {
                if (!applied) {
                    applyMoods(mc);
                    applied = true;
                }
                next(server);
            }
            return;
        }
        if (index >= shots.size()) {
            return;
        }
        Shot shot = shots.get(index);
        // The client learns the time of day from the server only every second: a night shot waits for it (the frame of a night shot
        // taken right after a day shot was still day).
        if (wait < (shot.night() ? 60 : 14)) {
            return;
        }
        // Alarm and greeting bubbles of the sect (SpeechBubbles) must not hang over the faces.
        io.github.verycooltimo.murim.client.sect.SpeechBubbles.clear();
        Screenshot.grab(mc.gameDirectory, String.format("murim_faces_%s_%s.png", mode, shot.name()), mc.getMainRenderTarget(), msg -> {
        });
        next(server);
    }

    private static boolean enough(Minecraft mc) {
        int n = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof SectDisciple) {
                n++;
            }
        }
        return n >= people;
    }

    /** Client: the moods row, one person each from left to right (the dev override of FaceConstructor). */
    private static void applyMoods(Minecraft mc) {
        if (!"moods".equals(mode)) {
            return;
        }
        List<SectDisciple> row = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof SectDisciple d) {
                row.add(d);
            }
        }
        row.sort(Comparator.comparingDouble(Entity::getX));
        for (int i = 0; i < row.size() && i < MOODS.size(); i++) {
            FaceConstructor.setMood(row.get(i), MOODS.get(i));
        }
    }

    private static List<Shot> shotsFor(String mode, int n) {
        List<Shot> out = new ArrayList<>();
        switch (mode) {
            case "ranks" -> {
                out.add(new Shot("front", 0.0D, 1.0D, 6.0D, 0.3D, 1.0D, false, -1));
                out.add(new Shot("three_quarter", 0.7D, 0.7D, 6.0D, 0.3D, 1.0D, false, -1));
                out.add(new Shot("back", 0.0D, -1.0D, 6.0D, 0.3D, 1.0D, false, -1));
                out.add(new Shot("side", -1.0D, 0.0D, 6.0D, 0.3D, 1.0D, false, -1));
                out.add(new Shot("front_night", 0.0D, 1.0D, 6.0D, 0.3D, 1.0D, true, -1));
                out.add(new Shot("three_quarter_night", 0.7D, 0.7D, 6.0D, 0.3D, 1.0D, true, -1));
                for (int i = 0; i < n; i++) {
                    out.add(new Shot("head" + i, 0.0D, 1.0D, 1.7D, 0.0D, 1.5D, false, i));
                }
            }
            default -> {
                out.add(new Shot("row", 0.0D, 1.0D, 7.5D, 0.2D, 1.3D, false, -1));
                // The six faces in one frame: the camera at head height, as close as the whole row still fits (fov 50, 16:9).
                out.add(new Shot("row_heads", 0.0D, 1.0D, 6.7D, 0.0D, 1.45D, false, -1));
                for (int i = 0; i < n; i++) {
                    out.add(new Shot("close" + i, 0.0D, 1.0D, 1.35D, 0.0D, 1.55D, false, i));
                }
            }
        }
        return out;
    }

    private static void follow(Minecraft mc) {
        Entity newest = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ArmorStand && e.isAlive() && e.getCustomName() != null && CAMERA.equals(e.getCustomName().getString())
                    && (newest == null || e.getId() > newest.getId())) {
                newest = e;
            }
        }
        if (newest != null && mc.getCameraEntity() != newest) {
            mc.setCameraEntity(newest);
        }
    }

    private static void next(IntegratedServer server) {
        index++;
        wait = 0;
        if (index >= shots.size()) {
            MurimMod.LOGGER.info("StudentFaceCapture: done {} {} shots", mode, shots.size());
            return;
        }
        Shot shot = shots.get(index);
        server.execute(() -> camera(server, shot));
    }

    /** Server: lawn and people. */
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
        for (int dx = -14; dx < 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                level.setBlock(new BlockPos(base.getX() + dx, y - 1, base.getZ() + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                for (int dy = 0; dy < 12; dy++) {
                    level.setBlock(new BlockPos(base.getX() + dx, y + dy, base.getZ() + dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        // An unlit stripe along a chunk border showed up in some frames: ask the light engine to recheck the whole lawn surface.
        for (int dx = -14; dx < 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                level.getLightEngine().checkBlock(new BlockPos(base.getX() + dx, y, base.getZ() + dz));
                level.getLightEngine().checkBlock(new BlockPos(base.getX() + dx, y - 1, base.getZ() + dz));
            }
        }
        for (Entity e : level.getEntities((Entity) null, new AABB(base).inflate(30, 16, 30), e -> !(e instanceof ServerPlayer))) {
            e.discard();
        }
        origin = base;
        for (int i = 0; i < people; i++) {
            SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
            switch (mode) {
                case "ranks" -> {
                    // Plain skins, no member key (a key would start a daily routine): third, second, first rate.
                    npc.setRole(i == 0 ? SectRole.DISCIPLE : i == 1 ? SectRole.SECOND : SectRole.FIRST);
                    if (i == 2) {
                        net.minecraft.nbt.CompoundTag look = new net.minecraft.nbt.CompoundTag();
                        look.putString("look", "first_rate_disciple");
                        npc.readAdditionalSaveData(look);
                    }
                }
                case "faces" -> SectRoster.of(FACE_KEYS.get(i)).ifPresent(npc::setMember);
                default -> {
                    // The same person six times: a member key may stand only once (the sect removes the duplicates), so the pick is
                    // seeded by the look, which every copy shares.
                    npc.setRole(SectRole.DISCIPLE);
                    net.minecraft.nbt.CompoundTag look = new net.minecraft.nbt.CompoundTag();
                    look.putString("look", MOODS_LOOK);
                    npc.readAdditionalSaveData(look);
                }
            }
            npc.setKeepAwake(true);
            npc.setNoAi(true);
            double x = base.getX() + 0.5D + (i - (people - 1) / 2.0D) * SPACING_PEOPLE;
            npc.moveTo(x, y, base.getZ() + 0.5D, 0.0F, 0.0F);
            npc.setYHeadRot(0.0F);
            npc.setYBodyRot(0.0F);
            level.addFreshEntity(npc);
            MINE.add(npc.getId());
        }
        p.setGameMode(GameType.CREATIVE);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 1200, 0, false, false, false));
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        MurimMod.LOGGER.info("StudentFaceCapture: {} people ({}) on the lawn at {}", people, mode, base);
    }

    /** Server: whatever is not the player, a stand person or the camera goes (a wanderer, mobs and dummies of the stand world load late). */
    private static void sweep(ServerLevel level) {
        for (Entity e : level.getEntities((Entity) null, new AABB(origin).inflate(40.0D, 20.0D, 40.0D),
                e -> !(e instanceof ServerPlayer) && !MINE.contains(e.getId())
                        && !(e instanceof ArmorStand a && a.getCustomName() != null && CAMERA.equals(a.getCustomName().getString())))) {
            e.discard();
        }
    }

    /** Server: the camera stand for one shot. */
    private static void camera(IntegratedServer server, Shot shot) {
        ServerLevel level = server.overworld();
        level.setDayTime(shot.night() ? 18000L : 6000L);
        sweep(level);
        double cx = origin.getX() + 0.5D;
        double tx = shot.person() < 0 ? cx : cx + (shot.person() - (people - 1) / 2.0D) * SPACING_PEOPLE;
        Vec3 look = new Vec3(tx, origin.getY() + shot.lookY(), origin.getZ() + 0.5D);
        Vec3 dir = new Vec3(shot.dx(), 0.0D, shot.dz()).normalize();
        Vec3 cam = look.add(dir.scale(shot.dist())).add(0.0D, shot.height(), 0.0D);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.teleportTo(level, cam.x, cam.y + 1.0D, cam.z, 0.0F, 0.0F);
        for (ArmorStand old : level.getEntitiesOfClass(ArmorStand.class, new AABB(origin).inflate(64.0D),
                a -> a.getCustomName() != null && CAMERA.equals(a.getCustomName().getString()))) {
            old.discard();
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
    }
}
