package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectRole;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
import java.util.List;

/**
 * Dev film stand for the animation polish round ({@code -Pmurim.technique=animfilm}, only {@code -Dmurim.capture=true}): an empty flat
 * lawn by day, clear sky, one subject at a time, a fixed or following side-on camera 3 blocks away.
 * Env {@code MURIM_FILM_SCENES=death_sword,death_archer,walk_disciple,run_disciple,walk_bandit,run_bandit,lotus} (optionally
 * {@code name:frames:every[:speed]}, a name may carry a suffix such as walk_disciple2); {@code MURIM_FILM_DIST} (3.2), {@code MURIM_FILM_FOV} (50), {@code MURIM_FILM_WALK} / {@code MURIM_FILM_RUN}
 * movement speed attribute for the walk and the run scenes (default 0.12 and 0.34).
 * Frames: {@code screenshots/murim_film_<scene>_<nnn>.png}. The chosen move clip is logged as {@code FILM-CLIP} lines (the model picks
 * {@code run} when limbSwingAmount is above 0.75, see client/bandit/BanditModel).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class AnimFilmCapture {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "animfilm".equals(System.getProperty("murim.capture.technique"));
    private static final String CAMERA = "murim_film_cam";
    private static final int KILL_TICK = 14;

    private record Scene(String name, int frames, int every, double speed) {
    }

    private static List<Scene> scenes = List.of();
    private static boolean setup;
    private static BlockPos origin;
    private static int index = -1;
    private static int wait;
    private static boolean started;
    private static int tick;
    private static int frame;
    private static int stable;
    private static int lastSections = -1;
    private static int entityId = -1;

    private AnimFilmCapture() {
    }

    private static double env(String name, double fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : Double.parseDouble(v);
    }

    private static List<Scene> parse() {
        List<Scene> out = new ArrayList<>();
        for (String raw : System.getenv().getOrDefault("MURIM_FILM_SCENES", "death_sword").split(",")) {
            if (raw.isBlank()) {
                continue;
            }
            String[] p = raw.trim().split(":");
            String name = p[0];
            int frames = p.length > 1 ? Integer.parseInt(p[1]) : name.startsWith("death") ? 52 : name.equals("lotus") ? 80 : 70;
            int every = p.length > 2 ? Integer.parseInt(p[2]) : 1;
            out.add(new Scene(name, frames, every, p.length > 3 ? Double.parseDouble(p[3]) : 0.0D));
        }
        return out;
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
            mc.options.hideGui = true;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.fov().set((int) env("MURIM_FILM_FOV", 50));
            scenes = parse();
            server.execute(() -> build(server));
            wait = -40;
            return;
        }
        if (origin == null || index >= scenes.size()) {
            return;
        }
        follow(mc);
        wait++;
        if (index < 0) {
            if (wait >= 0) {
                next(server);
            }
            return;
        }
        Scene sc = scenes.get(index);
        if (!started) {
            int sections = mc.levelRenderer.countRenderedSections();
            stable = sections == lastSections && mc.levelRenderer.hasRenderedAllSections() ? stable + 1 : 0;
            lastSections = sections;
            if (!((wait >= 30 && stable >= 10) || wait >= 400)) {
                return;
            }
            started = true;
            tick = 0;
        }
        int t = tick++;
        server.execute(() -> drive(server, sc, t));
        if (sc.name().equals("lotus") && mc.player != null) {
            // The way the game does it: the lotus entry, then the breath loop 20 ticks later (ClientMeditationState.BREATH_AFTER_TICKS).
            if (t == 10) {
                MurimPlayerAnimations.play(mc.player, MurimPlayerAnimations.LOTUS);
            } else if (t == 30) {
                MurimPlayerAnimations.play(mc.player, MurimPlayerAnimations.MEDITATION_BREATH);
            }
        }
        if ((sc.name().startsWith("walk") || sc.name().startsWith("run")) && t % 4 == 0 && mc.level.getEntity(entityId) instanceof LivingEntity le) {
            float amount = le.walkAnimation.speed(1.0F);
            MurimMod.LOGGER.info("FILM-CLIP scene={} t={} limbSwingAmount={} clip={}", sc.name(), t, String.format("%.3f", amount),
                    amount > 0.75F ? "run" : "walk");
        }
        if (t % sc.every() == 0) {
            Screenshot.grab(mc.gameDirectory, String.format("murim_film_%s_%03d.png", sc.name(), frame++), mc.getMainRenderTarget(), m -> {
            });
        }
        if (frame >= sc.frames()) {
            next(server);
        }
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
        frame = 0;
        stable = 0;
        lastSections = -1;
        started = false;
        if (index >= scenes.size()) {
            MurimMod.LOGGER.info("AnimFilm: done {} scenes", scenes.size());
            return;
        }
        Scene sc = scenes.get(index);
        server.execute(() -> start(server, sc));
    }

    /** Server: an empty lawn, day, clear sky. */
    private static void build(IntegratedServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setWeatherParameters(12000, 0, false, false);
        level.setDayTime(6000L);
        p.setGameMode(GameType.CREATIVE);
        BlockPos base = p.blockPosition().offset(0, 0, 12);
        int y = base.getY();
        for (int dx = -8; dx < 96; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                level.setBlock(new BlockPos(base.getX() + dx, y - 1, base.getZ() + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int dy = 0; dy < 14; dy++) {
                    level.setBlock(new BlockPos(base.getX() + dx, y + dy, base.getZ() + dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        origin = base;
    }

    private static void clear(ServerLevel level) {
        for (Entity e : level.getEntities((Entity) null, new AABB(origin).inflate(120, 30, 60), e -> !(e instanceof ServerPlayer))) {
            e.discard();
        }
        level.setDayTime(6000L);
        level.setWeatherParameters(12000, 0, false, false);
    }

    private static void camera(ServerLevel level, ServerPlayer p, Vec3 look, Vec3 dir, boolean hidePlayer) {
        Vec3 cam = look.add(dir.scale(env("MURIM_FILM_DIST", 3.2D))).add(0.0D, 0.15D, 0.0D);
        if (hidePlayer) {
            p.setNoGravity(true);
            p.setInvisible(true);
            p.teleportTo(level, cam.x, cam.y + 30.0D, cam.z, 0.0F, 0.0F);
        }
        ArmorStand stand = null;
        for (ArmorStand old : level.getEntitiesOfClass(ArmorStand.class, new AABB(origin).inflate(256.0D),
                a -> a.getCustomName() != null && CAMERA.equals(a.getCustomName().getString()))) {
            stand = old;
        }
        if (stand == null) {
            stand = new ArmorStand(level, cam.x, cam.y - 1.62D, cam.z);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setCustomName(Component.literal(CAMERA));
            level.addFreshEntity(stand);
        }
        Vec3 d = look.subtract(cam);
        float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        stand.moveTo(cam.x, cam.y - 1.62D, cam.z, yaw, pitch);
        stand.setYHeadRot(yaw);
    }

    private static final Vec3 SIDE = new Vec3(0.0D, 0.0D, 1.0D);

    private static void start(IntegratedServer server, Scene sc) {
        ServerLevel level = server.overworld();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        clear(level);
        for (ArmorStand old : level.getEntitiesOfClass(ArmorStand.class, new AABB(origin).inflate(256.0D))) {
            old.discard();
        }
        double ox = origin.getX() + 0.5D;
        double oz = origin.getZ() + 0.5D;
        double y = origin.getY();
        String name = sc.name();
        Entity subject = null;
        if (name.startsWith("death")) {
            Bandit b = name.contains("archer") ? ModEntities.BANDIT_ARCHER.get().create(level) : ModEntities.BANDIT_SWORDSMAN.get().create(level);
            // faces south, falls backwards (north); the camera looks from the east at the middle of the standing and the lying body
            b.moveTo(ox + 6.0D, y, oz, 0.0F, 0.0F);
            b.setNoAi(true);
            b.setPersistenceRequired();
            level.addFreshEntity(b);
            subject = b;
            camera(level, p, new Vec3(ox + 6.0D, y + 0.8D, oz - 0.2D), new Vec3(1.0D, 0.0D, 0.0D), true);
        } else if (name.contains("disciple") || name.contains("bandit")) {
            Mob m;
            if (name.contains("disciple")) {
                SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                d.setRole(SectRole.DISCIPLE_A);
                d.setKeepAwake(true);
                m = d;
            } else {
                m = ModEntities.BANDIT_SWORDSMAN.get().create(level);
            }
            m.moveTo(ox, y, oz, -90.0F, 0.0F);
            m.setYBodyRot(-90.0F);
            m.setYHeadRot(-90.0F);
            m.setNoAi(true);
            m.setPersistenceRequired();
            double speed = sc.speed() > 0.0D ? sc.speed() : name.startsWith("run") ? env("MURIM_FILM_RUN", 0.34D) : env("MURIM_FILM_WALK", 0.12D);
            m.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
            level.addFreshEntity(m);
            subject = m;
            camera(level, p, new Vec3(ox, y + 0.9D, oz), SIDE, true);
        } else if (name.equals("lotus")) {
            p.setNoGravity(false);
            p.setInvisible(false);
            p.teleportTo(level, ox + 6.0D, y, oz, 0.0F, 0.0F);
            camera(level, p, new Vec3(ox + 6.0D, y + 0.8D, oz), new Vec3(1.0D, 0.0D, 0.45D).normalize(), false);
        }
        entityId = subject == null ? -1 : subject.getId();
    }

    private static void drive(IntegratedServer server, Scene sc, int t) {
        ServerLevel level = server.overworld();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        Entity e = entityId < 0 ? null : level.getEntity(entityId);
        String name = sc.name();
        if (name.startsWith("death")) {
            if (t == KILL_TICK && e instanceof LivingEntity le) {
                le.kill();
            }
            return;
        }
        if (e instanceof Mob m) {
            if (t == 0) {
                m.setNoAi(false);
            }
            if (t % 10 == 0) {
                m.getNavigation().moveTo(origin.getX() + 90.0D, origin.getY(), m.getZ(), 1.0D);
            }
            camera(level, p, m.position().add(0.0D, 0.9D, 0.0D), SIDE, true);
        }
    }
}
