package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectRole;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Стенд поз секты ({@code ./capture.sh … sectposes}, только {@code -Dmurim.capture=true}): на ровной площадке в ряд
 * стоят люди секты, каждый в своей позе ({@link SectPose}) с реквизитом и обстановкой (низкий стол, столбы,
 * кровать); камера по очереди снимает каждого с 4,5 и с 10 блоков днём и с 10 блоков ночью. Кадры —
 * {@code screenshots/murim_pose_<поза>_<ракурс>_<n>.png}. {@code MURIM_POSES} — список поз через запятую
 * (по умолчанию все).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SectPoseCapture {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "sectposes".equals(System.getProperty("murim.capture.technique"));

    private static final String CAMERA = "murim_pose_cam";
    /** Шаг между людьми в ряду, блоков. */
    private static final int SPACING = 7;

    /** Сцена галереи: имя кадра, поза, роль (облик), обстановка. */
    private record Spot(String name, SectPose pose, SectRole role) {
    }

    private static final List<Spot> ALL = List.of(
            new Spot("eat", SectPose.EAT, SectRole.DISCIPLE_A),
            new Spot("sleep", SectPose.SLEEP, SectRole.DISCIPLE_B),
            new Spot("sleep_bed", SectPose.SLEEP, SectRole.DISCIPLE_A),
            new Spot("meditate", SectPose.MEDITATE, SectRole.ELDER),
            new Spot("sit", SectPose.SIT, SectRole.DISCIPLE_B),
            new Spot("poles", SectPose.POLES, SectRole.SENIOR),
            new Spot("pole_step", SectPose.POLE_STEP, SectRole.SECOND),
            new Spot("form", SectPose.FORM, SectRole.SENIOR),
            new Spot("sweep", SectPose.SWEEP, SectRole.DISCIPLE_A),
            new Spot("carry", SectPose.CARRY, SectRole.DISCIPLE_B),
            new Spot("bow", SectPose.BOW, SectRole.LEADER),
            new Spot("guard", SectPose.GUARD, SectRole.GATEKEEPER),
            new Spot("talk", SectPose.TALK, SectRole.MENTOR),
            // Для сравнения: старый лотос (sit без позы) — клип игрока на скелете NPC.
            new Spot("lotus_legacy", SectPose.NONE, SectRole.DISCIPLE_A),
            // Только по MURIM_POSES=draw: ролик «меч в ножнах → в руке → в ножнах».
            new Spot("draw", SectPose.NONE, SectRole.DISCIPLE));

    /** Ракурс: имя, расстояние, высота камеры, ночь, кадров, тиков между кадрами. */
    private record Shot(String name, double dist, double height, boolean night, int frames, int every) {
    }

    private static final List<Shot> SHOTS = List.of(
            new Shot("near", 3.4D, 1.4D, false, 12, 5),
            new Shot("far", 10.0D, 2.0D, false, 6, 10),
            new Shot("night", 10.0D, 2.0D, true, 4, 10));

    /**
     * {@code MURIM_POSES=draw} — один ролик ученика третьего поколения: покой (рукоять в ножнах) → поединок с
     * товарищем (поклон с мечом в ножнах, бой с мечом Хуашань в руке) → колокол, снова покой. Камера идёт за парой.
     */
    private static final Shot DRAW_SHOT = new Shot("clip", 4.6D, 0.5D, false, 95, 3);
    /** Кадр ролика draw, на котором начинается поединок и на котором звонит колокол. */
    private static final int DRAW_SPAR_AT = 10;
    private static final int DRAW_STOP_AT = 75;

    private static List<Shot> shots = SHOTS;

    private static boolean setup;
    private static List<Spot> spots;
    private static BlockPos origin;
    private static int index = -1;
    private static int wait;
    private static int frame;
    private static int stable;
    private static int lastSections = -1;
    private static final List<Integer> npcIds = new ArrayList<>();

    private SectPoseCapture() {
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
            mc.options.fov().set(60);
            String raw = System.getenv("MURIM_POSES");
            spots = new ArrayList<>();
            for (Spot s : ALL) {
                if (raw == null || raw.isBlank() ? !"draw".equals(s.name()) : List.of(raw.split(",")).contains(s.name())) {
                    spots.add(s);
                }
            }
            if ("draw".equals(raw)) {
                shots = List.of(DRAW_SHOT);
            }
            server.execute(() -> build(server));
            wait = -60;
            return;
        }
        if (origin == null || index >= spots.size() * shots.size()) {
            return;
        }
        follow(mc);
        // Разовые позы (поклон, прыжок) и формы строя повторяются, чтобы попасть в кадры.
        if (mc.level.getGameTime() % 50 == 0) {
            server.execute(() -> replay(server.overworld()));
        }
        wait++;
        if (index < 0) {
            if (wait >= 0) {
                next(server);
            }
            return;
        }
        Shot shot = shots.get(index % shots.size());
        Spot spot = spots.get(index / shots.size());
        if (frame == 0) {
            int sections = mc.levelRenderer.countRenderedSections();
            stable = sections == lastSections && mc.levelRenderer.hasRenderedAllSections() ? stable + 1 : 0;
            lastSections = sections;
            if (!((wait >= 30 && stable >= 10) || wait >= 400)) {
                return;
            }
            if (wait % shot.every() != 0) {
                return;
            }
        } else if (wait % shot.every() != 0) {
            return;
        }
        if ("draw".equals(spot.name())) {
            int at = frame;
            server.execute(() -> drawTimeline(server, at));
        }
        String name = String.format("murim_pose_%s_%s_%02d.png", spot.name(), shot.name(), frame++);
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), m -> {
        });
        if (frame >= shot.frames()) {
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
        if (index >= spots.size() * shots.size()) {
            MurimMod.LOGGER.info("Стенд поз секты: снято {} поз", spots.size());
            return;
        }
        Shot shot = shots.get(index % shots.size());
        int i = index / shots.size();
        server.execute(() -> camera(server, i, shot));
    }

    /** Сервер: ровная площадка, люди в позах, обстановка. */
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
        int width = spots.size() * SPACING + 8;
        // Площадка: трава, над ней воздух — ровный фон и тень под ногами одинаковы у всех.
        for (int dx = -6; dx < width; dx++) {
            for (int dz = -14; dz <= 6; dz++) {
                level.setBlock(new BlockPos(base.getX() + dx, y - 1, base.getZ() + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int dy = 0; dy < 24; dy++) {
                    level.setBlock(new BlockPos(base.getX() + dx, y + dy, base.getZ() + dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (Entity e : level.getEntities((Entity) null, new AABB(base).inflate(width + 16, 16, 24),
                e -> !(e instanceof ServerPlayer))) {
            e.discard();
        }
        origin = base;
        npcIds.clear();
        for (int i = 0; i < spots.size(); i++) {
            Spot s = spots.get(i);
            BlockPos at = base.offset(i * SPACING, 0, 0);
            int standY = y;
            switch (s.name()) {
                case "eat" -> {
                    // Низкий столик (закрытый люк, 3 px) перед сидящим: не заслоняет чашку.
                    level.setBlock(at.south(), Blocks.SPRUCE_TRAPDOOR.defaultBlockState(), 2);
                    level.setBlock(at.south().west(), Blocks.SPRUCE_TRAPDOOR.defaultBlockState(), 2);
                }
                case "poles", "pole_step" -> {
                    // Столб в два блока и соседние столбы: «стоит на столбе».
                    for (int[] d : new int[][] {{0, 0}, {-2, -1}, {2, -1}, {0, -2}, {-1, 2}, {1, 1}}) {
                        int h = d[0] == 0 && d[1] == 0 ? 2 : 1 + Math.floorMod(d[0] + d[1], 2);
                        for (int dy = 0; dy < h; dy++) {
                            level.setBlock(at.offset(d[0], dy, d[1]), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), 2);
                        }
                    }
                    standY = y + 2;
                }
                case "sleep_bed" -> {
                    BlockState foot = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
                    level.setBlock(at, foot, 3);
                    level.setBlock(at.north(), foot.setValue(BedBlock.PART, BedPart.HEAD), 3);
                }
                default -> {
                }
            }
            SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
            npc.setRole(s.role());
            npc.setKeepAwake(true);
            // Без ИИ: стоит ровно лицом на юг, не поворачивается к камере (поза видна вполоборота).
            npc.setNoAi(true);
            npc.moveTo(at.getX() + 0.5D, standY, at.getZ() + 0.5D, 0.0F, 0.0F);
            npc.setYHeadRot(0.0F);
            npc.setYBodyRot(0.0F);
            level.addFreshEntity(npc);
            if (s.pose().seated() && s.pose() != SectPose.SLEEP || "sleep".equals(s.name()) || "lotus_legacy".equals(s.name())) {
                npc.sit(true);
            }
            if ("sleep_bed".equals(s.name())) {
                npc.startSleeping(at.north());
            }
            if ("draw".equals(s.name())) {
                // Ролик: двое учеников с ИИ (поединку нужен бой), лицом друг к другу; без ключа — без распорядка.
                npc.setNoAi(false);
                npc.moveTo(at.getX() - 1.0D, standY, at.getZ() + 0.5D, -90.0F, 0.0F);
                SectDisciple mate = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                mate.setRole(SectRole.DISCIPLE);
                mate.setKeepAwake(true);
                mate.moveTo(at.getX() + 2.0D, standY, at.getZ() + 0.5D, 90.0F, 0.0F);
                level.addFreshEntity(mate);
                npcIds.add(npc.getId());
                npcIds.add(mate.getId());
                continue;
            }
            npc.playPose(s.pose());
            npcIds.add(npc.getId());
        }
        p.setGameMode(GameType.CREATIVE);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 1200, 0, false, false, false));
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        MurimMod.LOGGER.info("Стенд поз секты: {} человек на площадке у {}", spots.size(), base);
    }

    /** Сервер: разовые позы — заново, строй — форма основы (клип техники six_form_*), как в такт строя. */
    private static void replay(ServerLevel level) {
        // Чужие сущности мира (манекены прошлых стендов) не лезут в кадр.
        if (origin != null) {
            for (Entity e : level.getEntities((Entity) null, new AABB(origin).inflate(spots.size() * SPACING + 24, 16, 32),
                    e -> !(e instanceof ServerPlayer) && !(e instanceof SectDisciple) && !(e instanceof ArmorStand a
                            && a.getCustomName() != null && CAMERA.equals(a.getCustomName().getString())))) {
                e.discard();
            }
        }
        for (int i = 0; i < npcIds.size() && i < spots.size(); i++) {
            if ("draw".equals(spots.get(i).name()) || !(level.getEntity(npcIds.get(i)) instanceof SectDisciple npc)) {
                continue;
            }
            SectPose pose = spots.get(i).pose();
            if (!pose.loop()) {
                npc.playPose(pose);
            } else if (pose == SectPose.FORM && level.getGameTime() % 100 == 0) {
                npc.drillForm((int) (level.getGameTime() / 100) % 3, 8);
            }
        }
    }

    /** Сервер, ролик draw: поединок на кадре {@link #DRAW_SPAR_AT}, колокол на {@link #DRAW_STOP_AT}; камера за парой. */
    private static void drawTimeline(IntegratedServer server, int at) {
        ServerLevel level = server.overworld();
        if (npcIds.size() < 2 || !(level.getEntity(npcIds.get(0)) instanceof SectDisciple a)
                || !(level.getEntity(npcIds.get(1)) instanceof SectDisciple b)) {
            return;
        }
        if (at == DRAW_SPAR_AT) {
            a.sparWith(b, 0);
        } else if (at == DRAW_STOP_AT) {
            a.stopBout();
        }
        if (at % 4 == 0) {
            MurimMod.LOGGER.info("Стенд draw: кадр {} — {} drawn={} / {} drawn={}", at, a.spar(), a.drawn(), b.spar(), b.drawn());
        }
        // Камера: сбоку от середины пары, на том же расстоянии — оба в кадре, пока бьются.
        Vec3 mid = a.position().add(b.position()).scale(0.5D).add(0.0D, 1.0D, 0.0D);
        Vec3 cam = mid.add(new Vec3(0.35D, 0.0D, 1.0D).normalize().scale(DRAW_SHOT.dist())).add(0.0D, DRAW_SHOT.height(), 0.0D);
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, new AABB(origin).inflate(256.0D),
                s -> s.getCustomName() != null && CAMERA.equals(s.getCustomName().getString()))) {
            Vec3 d = mid.subtract(cam);
            float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F;
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            stand.moveTo(cam.x, cam.y - 1.62D, cam.z, yaw, pitch);
            stand.setYHeadRot(yaw);
        }
    }

    /** Сервер: стойка-камера перед человеком {@code i}, время суток ракурса. */
    private static void camera(IntegratedServer server, int i, Shot shot) {
        ServerLevel level = server.overworld();
        level.setDayTime(shot.night() ? 18000L : 6000L);
        Entity target = level.getEntity(npcIds.get(i));
        if (target == null) {
            MurimMod.LOGGER.warn("Стенд поз секты: нет человека {}", spots.get(i).name());
            return;
        }
        Vec3 look = target.position().add(0.0D, spots.get(i).pose().seated() ? 0.5D : 1.0D, 0.0D);
        // Человек смотрит на юг; камера с юга и на 35° к востоку: лицо и профиль позы видны вместе.
        Vec3 dir = new Vec3(0.7D, 0.0D, 1.0D).normalize();
        Vec3 cam = look.add(dir.scale(shot.dist())).add(0.0D, shot.height(), 0.0D);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.teleportTo(level, cam.x, cam.y + 1.0D, cam.z, 0.0F, 0.0F);
        for (ArmorStand old : level.getEntitiesOfClass(ArmorStand.class, new AABB(origin).inflate(256.0D),
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
