package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.IntSeating;
import io.github.verycooltimo.murim.entity.SeatStyle;
import io.github.verycooltimo.murim.sect.SectLife;
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
            && "sectposes".equals(System.getProperty("murim.capture.technique"))
            // MURIM_STAND=faces is the student model / face constructor stand (client/sect/face/StudentFaceCapture), same technique name.
            && !"faces".equals(System.getenv("MURIM_STAND"));

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
            // Cook and servants (author 18.10): blockout clips with their props, a cauldron or a table in front where they work.
            new Spot("cook_stir", SectPose.COOK_STIR, SectRole.COOK),
            new Spot("cook_chop", SectPose.COOK_CHOP, SectRole.COOK),
            new Spot("cook_taste", SectPose.COOK_TASTE, SectRole.COOK),
            new Spot("cook_ladle", SectPose.COOK_LADLE, SectRole.COOK),
            new Spot("tray", SectPose.TRAY, SectRole.WATER_CARRIER),
            new Spot("tray_set", SectPose.TRAY_SET, SectRole.WATER_CARRIER),
            new Spot("wipe", SectPose.WIPE, SectRole.WATER_CARRIER),
            new Spot("sack", SectPose.SACK, SectRole.PORTER),
            new Spot("jar", SectPose.JAR, SectRole.PORTER),
            new Spot("hoe", SectPose.HOE, SectRole.GARDENER),
            // The way of sitting follows the furniture (author 19.10): the head on the armchair (the throne), an elder reading on a
            // chair, a disciple eating at a table on a chair, a disciple meditating on a cushion. Chair sit on the first three, lotus on the last.
            new Spot("chair_head", SectPose.SIT, SectRole.LEADER),
            new Spot("chair_read", SectPose.READ, SectRole.ELDER),
            new Spot("chair_eat", SectPose.EAT, SectRole.DISCIPLE_A),
            new Spot("cushion_med", SectPose.MEDITATE, SectRole.DISCIPLE_B),
            // Only by MURIM_POSES=sleepwalk: a disciple asleep in a bed gets a path (nobody woke him): he must get up and walk, not walk lying.
            new Spot("sleepwalk", SectPose.NONE, SectRole.DISCIPLE_A),
            // Only by MURIM_POSES=seatwalk: the head sits on the armchair, a path starts: he gets up off the chair and walks.
            new Spot("seatwalk", SectPose.SIT, SectRole.LEADER),
            // Only by MURIM_POSES=point: the wandering disciple (docs/adr/0020) shows the way east, arm out, held; front = the player's view.
            new Spot("point", SectPose.NONE, SectRole.SECOND),
            // Только по MURIM_POSES=draw: ролик «меч в ножнах → в руке → в ножнах».
            new Spot("draw", SectPose.NONE, SectRole.DISCIPLE),
            // Только по MURIM_POSES=model: ученик в покое спереди, сбоку и сзади вблизи (сверка текстуры с Blockbench).
            new Spot("model", SectPose.NONE, SectRole.DISCIPLE),
            // Только по MURIM_POSES=head: глава (модель автора high_sect_people) рядом с учеником для масштаба —
            // покой спереди/сбоку/сзади, шаг вдоль площадки (полы одеяния) и разговор (поза TALK).
            new Spot("head", SectPose.NONE, SectRole.LEADER),
            // Только по MURIM_POSES=head_watch: глава на деле WATCH / GREET (смотрит строй, встречает чужака у ворот) —
            // вблизи спереди, сбоку, сзади: на поясе одеяния меча быть не должно (автор 06.10: «странный меч на поясе»).
            new Spot("head_watch", SectPose.GUARD, SectRole.LEADER),
            // Только по MURIM_POSES=first: первый тренер (наставник, модель first_rate_disciple), Гён Так первого
            // поколения, ученик второго и третьего поколения для сравнения — те же ракурсы, что у head.
            new Spot("first", SectPose.NONE, SectRole.MENTOR),
            // Только по MURIM_POSES=all: вся секта в один ряд (глава первым) — у каждой роли модель и текстура автора.
            new Spot("all", SectPose.NONE, SectRole.LEADER));

    /**
     * Ряд стенда all после главы: роль, облик (пусто — по роли и поколению), поза (стража второго поколения на посту).
     * Стража — ученик второго поколения в позе GUARD: роли GUARD у живых людей больше нет (SectLife убирает её).
     */
    private record Mate(SectRole role, String look, SectPose pose) {
    }

    private static final List<Mate> ALL_MATES = List.of(
            new Mate(SectRole.ELDER, "elder_treasury", SectPose.NONE), new Mate(SectRole.ELDER, "elder_martial", SectPose.NONE),
            new Mate(SectRole.MENTOR, "mentor", SectPose.NONE), new Mate(SectRole.ELDER, "first_gate", SectPose.NONE),
            new Mate(SectRole.ELDER, "elder_healer", SectPose.NONE), new Mate(SectRole.SECOND, "first_rate_disciple", SectPose.NONE),
            new Mate(SectRole.SENIOR, "second_0", SectPose.NONE), new Mate(SectRole.SECOND, "guard_0", SectPose.GUARD),
            new Mate(SectRole.GATEKEEPER, "third_7", SectPose.NONE), new Mate(SectRole.DISCIPLE, "third_0", SectPose.NONE),
            new Mate(SectRole.STEWARD, "lay_steward", SectPose.NONE), new Mate(SectRole.COOK, "lay_cook", SectPose.NONE),
            new Mate(SectRole.PORTER, "lay_porter_0", SectPose.NONE), new Mate(SectRole.GARDENER, "lay_herbalist", SectPose.NONE),
            new Mate(SectRole.SWEEPER, "lay_sweeper", SectPose.NONE), new Mate(SectRole.WATER_CARRIER, "lay_water", SectPose.NONE));

    /** The way the stand's wanderer points: east (Minecraft yaw -90). */
    private static final float POINT_YAW = -90.0F;

    /** Ракурс: имя, расстояние, высота камеры, ночь, кадров, тиков между кадрами. */
    private record Shot(String name, double dist, double height, boolean night, int frames, int every) {
    }

    private static final List<Shot> SHOTS = List.of(
            new Shot("near", 3.4D, 1.4D, false, 12, 5),
            new Shot("far", 10.0D, 2.0D, false, 6, 10),
            new Shot("night", 10.0D, 2.0D, true, 4, 10),
            // From the east, level with the seat: a chair sitter in profile (hips on the seat, legs forward, the robe over the thighs).
            new Shot("side", 3.4D, 0.1D, false, 4, 5));

    /**
     * {@code MURIM_POSES=draw} — один ролик ученика третьего поколения: покой (рукоять в ножнах) → поединок с
     * товарищем (поклон с мечом в ножнах, бой с мечом Хуашань в руке) → колокол, снова покой. Камера идёт за парой.
     */
    private static final Shot DRAW_SHOT = new Shot("clip", 3.3D, 0.3D, false, 95, 3);
    /** Кадр ролика draw, на котором начинается поединок и на котором звонит колокол. */
    private static final int DRAW_SPAR_AT = 10;
    private static final int DRAW_STOP_AT = 75;

    /**
     * {@code MURIM_POSES=sleepwalk} / {@code seatwalk}: a person lies in a bed (sits on the throne), at frame {@link #PATH_AT} a path
     * starts for him (nobody wakes him), the camera watches from the south-west: he must get up and walk upright.
     */
    private static final Shot PATH_SHOT = new Shot("walk", 4.2D, 0.5D, false, 56, 4);
    private static final int PATH_AT = 16;
    private static final java.util.Set<Integer> pathStarted = new java.util.HashSet<>();

    /** {@code MURIM_POSES=head}: покой с трёх сторон, шаг (туда и обратно мимо камеры), разговор. */
    private static final List<Shot> HEAD_SHOTS = List.of(new Shot("front", 5.8D, 0.2D, false, 2, 5),
            new Shot("side", 4.8D, 0.2D, false, 2, 5), new Shot("back", 5.8D, 0.2D, false, 2, 5),
            new Shot("walk", 7.0D, 0.2D, false, 40, 3), new Shot("talk", 5.8D, 0.2D, false, 6, 6));
    /** {@code MURIM_POSES=first}: те же ракурсы для ряда из шести, камера дальше. */
    private static final List<Shot> FIRST_SHOTS = List.of(new Shot("front", 8.5D, 0.2D, false, 2, 5),
            new Shot("side", 8.8D, 0.2D, false, 2, 5), new Shot("back", 8.5D, 0.2D, false, 2, 5),
            new Shot("walk", 7.5D, 0.2D, false, 40, 3), new Shot("talk", 8.5D, 0.2D, false, 6, 6));
    /**
     * {@code MURIM_POSES=all}: общий план, камера вдоль ряда спереди и сзади (кадр на человека), шаг с камерой,
     * идущей вдоль ряда.
     */
    private static final List<Shot> ALL_SHOTS = List.of(new Shot("front", 30.0D, 2.0D, false, 2, 5),
            new Shot("pan", 4.2D, 0.2D, false, ALL_MATES.size() + 1, 8), new Shot("panback", 4.2D, 0.2D, false, ALL_MATES.size() + 1, 8),
            new Shot("walk", 6.5D, 0.4D, false, 80, 3));
    /** Старейшины (казна и elder1) и ученик рядом с главой на стенде head (масштаб), шаг между ними, блоков. */
    private static final List<Integer> headMates = new ArrayList<>();
    private static final double HEAD_GAP = 1.6D;

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
                if (raw == null || raw.isBlank() ? !List.of("draw", "model", "head", "head_watch", "first", "all", "sleepwalk", "seatwalk", "point").contains(s.name()) : List.of(raw.split(",")).contains(s.name())) {
                    spots.add(s);
                }
            }
            if ("model".equals(raw) || "head_watch".equals(raw)) {
                shots = List.of(new Shot("front", 2.6D, 0.2D, false, 2, 5), new Shot("side", 2.6D, 0.2D, false, 2, 5),
                        new Shot("back", 2.6D, 0.2D, false, 2, 5));
            }
            if ("point".equals(raw)) {
                shots = List.of(new Shot("front", 4.2D, 0.5D, false, 4, 7), new Shot("side", 4.2D, 0.5D, false, 4, 7),
                        new Shot("back", 4.2D, 0.5D, false, 3, 7));
            }
            if ("draw".equals(raw)) {
                shots = List.of(DRAW_SHOT);
            }
            if (!spots.isEmpty() && spots.stream().allMatch(sp -> "sleepwalk".equals(sp.name()) || "seatwalk".equals(sp.name()))) {
                shots = List.of(PATH_SHOT);
            }
            if ("head".equals(raw)) {
                shots = HEAD_SHOTS;
            }
            if ("first".equals(raw)) {
                shots = FIRST_SHOTS;
            }
            if ("all".equals(raw)) {
                shots = ALL_SHOTS;
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
        if (mc.level.getGameTime() % 70 == 0) {
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
        if (row(spot.name())) {
            int at = frame;
            server.execute(() -> headTimeline(server, shot, at));
        }
        if ("sleepwalk".equals(spot.name()) || "seatwalk".equals(spot.name())) {
            int at = frame;
            int who = index / shots.size();
            server.execute(() -> pathTimeline(server, who, at));
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
        // Ряд всей секты (all) — 17 человек через 1,6 блока и камера общего плана за 30 блоков: площадка шире и глубже.
        boolean wide = spots.stream().anyMatch(sp -> "all".equals(sp.name()));
        int width = spots.size() * SPACING + 8 + (wide ? 40 : 0);
        int south = wide ? 34 : 6;
        // Площадка: трава, над ней воздух — ровный фон и тень под ногами одинаковы у всех.
        for (int dx = -6; dx < width; dx++) {
            for (int dz = -14; dz <= south; dz++) {
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
                case "cook_stir", "cook_taste" -> {
                    // A pot at the stove: the cauldron (rim 16 px) in front of the cook.
                    level.setBlock(at.south(), Blocks.CAULDRON.defaultBlockState(), 2);
                }
                case "cook_chop", "cook_ladle", "tray_set", "wipe" -> {
                    // A table top (a top slab, 16 px; the dining tables are 14) in front.
                    for (int dx = -1; dx <= 1; dx++) {
                        level.setBlock(at.south().offset(dx, 0, 0), Blocks.SPRUCE_SLAB.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP), 2);
                    }
                }
                case "hoe" -> {
                    // A herb bed: farmland with sprouts in front of the herbalist.
                    for (int dx = -1; dx <= 1; dx++) {
                        level.setBlock(at.south().offset(dx, -1, 0), Blocks.FARMLAND.defaultBlockState(), 2);
                        level.setBlock(at.south().offset(dx, 0, 0), Blocks.WHEAT.defaultBlockState(), 2);
                    }
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
                case "sleep_bed", "sleepwalk" -> {
                    BlockState foot = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
                    level.setBlock(at, foot, 3);
                    level.setBlock(at.north(), foot.setValue(BedBlock.PART, BedPart.HEAD), 3);
                }
                case "chair_head", "seatwalk" -> level.setBlock(at, IntSeating.ARMCHAIR.get().defaultBlockState(), 3);
                case "chair_read" -> level.setBlock(at, IntSeating.CHAIR.get().defaultBlockState(), 3);
                case "chair_eat" -> {
                    // A chair and a long table in front of it (14 px): the sitter eats at the table.
                    level.setBlock(at, IntSeating.CHAIR.get().defaultBlockState(), 3);
                    level.setBlock(at.south(), IntSeating.LONG_TABLE.get().defaultBlockState(), 3);
                    level.setBlock(at.south().east(), IntSeating.LONG_TABLE.get().defaultBlockState(), 3);
                    level.setBlock(at.south().west(), IntSeating.LONG_TABLE.get().defaultBlockState(), 3);
                }
                case "cushion_med" -> level.setBlock(at, IntSeating.FLOOR_CUSHION.get().defaultBlockState(), 3);
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
            boolean onSeat = s.name().startsWith("chair_") || "seatwalk".equals(s.name()) || "cushion_med".equals(s.name());
            if (!onSeat && (s.pose().seated() && s.pose() != SectPose.SLEEP || "sleep".equals(s.name()))) {
                npc.sit(io.github.verycooltimo.murim.entity.SeatStyle.LOTUS);
            }
            if ("sleep_bed".equals(s.name()) || "sleepwalk".equals(s.name())) {
                npc.startSleeping(at.north());
            }
            if (s.name().startsWith("chair_") || "seatwalk".equals(s.name())) {
                // Put on the seat the way the routine does it: from the cell in front of the chair, gliding onto the seat top.
                npc.moveTo(at.getX() + 0.5D, standY, at.getZ() + 1.5D, 0.0F, 0.0F);
                npc.sit(SeatStyle.CHAIR, SectLife.seatTop(level, at), 0.0F, npc.position());
            }
            if ("cushion_med".equals(s.name())) {
                npc.moveTo(at.getX() + 0.5D, standY + 2.5D / 16.0D, at.getZ() + 0.5D, 0.0F, 0.0F);
                npc.sit(SeatStyle.LOTUS);
            }
            if ("chair_read".equals(s.name())) {
                npc.hold(net.minecraft.world.item.Items.BOOK);
            }
            if ("draw".equals(s.name())) {
                // Ролик: двое учеников с ИИ (поединку нужен бой), лицом друг к другу; без ключа — без распорядка.
                npc.setNoAi(false);
                npc.moveTo(at.getX() - 1.0D, standY, at.getZ() + 0.5D, -90.0F, 0.0F);
                SectDisciple mate = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                // Товарищ — второе поколение: обе текстуры модели ученика в одном ролике.
                mate.setRole(SectRole.SECOND);
                String drawLook = System.getenv("MURIM_DRAW_LOOK");
                if (drawLook != null && !drawLook.isBlank()) {
                    // MURIM_DRAW_LOOK=first_rate_disciple: тот же ролик первым поколением (ножны модели first_rate_disciple),
                    // товарищ — Гён Так; роль не третьего поколения, иначе ножны спрятаны и в руке деревянный меч.
                    npc.setRole(SectRole.SECOND);
                    for (SectDisciple who : List.of(npc, mate)) {
                        net.minecraft.nbt.CompoundTag look = new net.minecraft.nbt.CompoundTag();
                        look.putString("look", who == npc ? drawLook : "first_gate");
                        who.readAdditionalSaveData(look);
                    }
                }
                mate.setKeepAwake(true);
                mate.moveTo(at.getX() + 2.0D, standY, at.getZ() + 0.5D, 90.0F, 0.0F);
                level.addFreshEntity(mate);
                npcIds.add(npc.getId());
                npcIds.add(mate.getId());
                continue;
            }
            if ("hoe".equals(s.name())) {
                // The herbalist's tool is a vanilla hoe in the hand (ScheduleGoal#outfit).
                npc.hold(net.minecraft.world.item.Items.WOODEN_HOE);
            }
            npc.playPose(s.pose());
            if ("point".equals(s.name())) {
                npc.makeWanderer();
                npc.pointAlong(POINT_YAW);
            }
            npcIds.add(npc.getId());
            if (row(s.name())) {
                // head: Тэ Гюн (своя модель по облику), старейшина (elder1) и ученик третьего поколения — масштаб;
                // first: второй тренер Гён Так, алхимик Гён Чхо, рядовой первого поколения, второе и третье поколение.
                // MURIM_FIRST_GEN=1: ряд first — наставник и четверо рядовых первого поколения (роль FIRST, автор 06.10),
                // последним — ученик второго поколения для сравнения.
                boolean firstGen = "first".equals(s.name()) && "1".equals(System.getenv("MURIM_FIRST_GEN"));
                List<SectRole> roles = "all".equals(s.name()) ? ALL_MATES.stream().map(Mate::role).toList() : firstGen
                        ? List.of(SectRole.FIRST, SectRole.FIRST, SectRole.FIRST, SectRole.FIRST, SectRole.SECOND)
                        : "first".equals(s.name())
                        ? List.of(SectRole.ELDER, SectRole.ELDER, SectRole.ELDER, SectRole.SECOND, SectRole.DISCIPLE)
                        : List.of(SectRole.ELDER, SectRole.ELDER, SectRole.DISCIPLE);
                List<String> looks = "all".equals(s.name()) ? ALL_MATES.stream().map(Mate::look).toList() : firstGen
                        ? List.of("first_rate_disciple", "first_rate_disciple", "first_rate_disciple", "first_rate_disciple", "")
                        : "first".equals(s.name())
                        ? List.of("first_gate", "elder_healer", "first_rate_disciple", "", "")
                        : List.of("elder_treasury", "", "");
                headMates.clear();
                for (int k = 1; k <= roles.size(); k++) {
                    SectDisciple mate = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                    mate.setRole(roles.get(k - 1));
                    if (!looks.get(k - 1).isEmpty()) {
                        // Только облик, без ключа человека: ключ включил бы распорядок, а на шаге у стенда ИИ включён.
                        net.minecraft.nbt.CompoundTag look = new net.minecraft.nbt.CompoundTag();
                        look.putString("look", looks.get(k - 1));
                        mate.readAdditionalSaveData(look);
                    }
                    mate.setKeepAwake(true);
                    mate.setNoAi(true);
                    mate.moveTo(at.getX() + 0.5D + HEAD_GAP * k, standY, at.getZ() + 0.5D, 0.0F, 0.0F);
                    mate.setYHeadRot(0.0F);
                    mate.setYBodyRot(0.0F);
                    level.addFreshEntity(mate);
                    if ("all".equals(s.name()) && ALL_MATES.get(k - 1).pose() != SectPose.NONE) {
                        mate.playPose(ALL_MATES.get(k - 1).pose());
                    }
                    headMates.add(mate.getId());
                }
            }
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
            if ("point".equals(spots.get(i).name())) {
                npc.pointAlong(POINT_YAW);
            }
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
        // Разошлись (натиск, отскок) — камера отходит, чтобы оба оставались в кадре и никто не налетал на неё.
        double dist = Math.max(DRAW_SHOT.dist(), a.distanceTo(b) * 1.1D + 1.5D);
        Vec3 side = new Vec3(b.getZ() - a.getZ(), 0.0D, a.getX() - b.getX());
        side = side.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : side.normalize();
        if (side.z < 0.0D) {
            side = side.scale(-1.0D);
        }
        Vec3 cam = mid.add(side.scale(dist)).add(0.0D, DRAW_SHOT.height(), 0.0D);
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, new AABB(origin).inflate(256.0D),
                s -> s.getCustomName() != null && CAMERA.equals(s.getCustomName().getString()))) {
            Vec3 d = mid.subtract(cam);
            float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F;
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            stand.moveTo(cam.x, cam.y - 1.62D, cam.z, yaw, pitch);
            stand.setYHeadRot(yaw);
        }
    }

    /**
     * Сервер, стенд head: на шаге все трое идут на восток и обратно (ИИ включён только на этот ракурс, ключа человека
     * нет — распорядка тоже), на разговоре глава и старейшина стоят лицом к камере в позе TALK.
     */
    private static void headTimeline(IntegratedServer server, Shot shot, int at) {
        ServerLevel level = server.overworld();
        List<SectDisciple> row = new ArrayList<>();
        for (int id : java.util.stream.Stream.concat(npcIds.stream().limit(1), headMates.stream()).toList()) {
            if (level.getEntity(id) instanceof SectDisciple n) {
                row.add(n);
            }
        }
        if (row.size() < 4) {
            return;
        }
        int last = row.size() - 1;
        // Люди секты из мира, догрузившиеся с чанками после уборки площадки, не стоят в ряду (двоились в кадре).
        for (SectDisciple stray : level.getEntitiesOfClass(SectDisciple.class, new AABB(origin).inflate(24.0D, 8.0D, 24.0D),
                d -> !row.contains(d))) {
            stray.discard();
        }
        double x0 = origin.getX() + 0.5D;
        if ("walk".equals(shot.name())) {
            boolean back = at >= shot.frames() / 2;
            for (int k = 0; k < row.size(); k++) {
                SectDisciple n = row.get(k);
                n.setNoAi(false);
                n.getNavigation().moveTo(x0 + HEAD_GAP * k + (back ? -2.0D : 6.0D), origin.getY(), n.getZ(), 0.6D);
            }
            // Камера едет за серединой ряда с юга: все трое в профиль и крупно. Ряд всей секты длинный — камера
            // идёт вдоль него, от главы к последнему слуге.
            Vec3 mid = row.size() > 8
                    ? row.get(Math.min(last, at * row.size() / shot.frames())).position().add(0.0D, 1.0D, 0.0D)
                    : row.get(0).position().add(row.get(last).position()).scale(0.5D).add(0.0D, 1.0D, 0.0D);
            Vec3 cam = mid.add(0.0D, shot.height(), shot.dist());
            for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, new AABB(origin).inflate(256.0D),
                    s -> s.getCustomName() != null && CAMERA.equals(s.getCustomName().getString()))) {
                Vec3 d = mid.subtract(cam);
                float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F;
                float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                stand.moveTo(cam.x, cam.y - 1.62D, cam.z, yaw, pitch);
                stand.setYHeadRot(yaw);
            }
            return;
        }
        if (shot.name().startsWith("pan")) {
            // Кадр снимается раньше, чем камера доезжает: на кадре at ставится камера кадра at + 1 (кадр 0 — camera()).
            SectDisciple n = row.get(Math.min(last, at + 1));
            Vec3 look = n.position().add(0.0D, 1.0D, 0.0D);
            Vec3 cam = look.add(0.0D, shot.height(), "pan".equals(shot.name()) ? shot.dist() : -shot.dist());
            for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, new AABB(origin).inflate(256.0D),
                    s -> s.getCustomName() != null && CAMERA.equals(s.getCustomName().getString()))) {
                Vec3 d = look.subtract(cam);
                float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F;
                float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                stand.moveTo(cam.x, cam.y - 1.62D, cam.z, yaw, pitch);
                stand.setYHeadRot(yaw);
            }
            return;
        }
        if ("talk".equals(shot.name()) && at == 0) {
            for (int k = 0; k < row.size(); k++) {
                SectDisciple n = row.get(k);
                n.getNavigation().stop();
                n.setNoAi(true);
                n.moveTo(x0 + HEAD_GAP * k, origin.getY(), origin.getZ() + 0.5D, 0.0F, 0.0F);
                n.setYHeadRot(0.0F);
                n.setYBodyRot(0.0F);
                if (k < last) {
                    n.playPose(SectPose.TALK);
                }
            }
        }
    }

    /** Server: the sleeper (the one on the throne) gets a path at frame {@link #PATH_AT} and nothing else; every few frames the state is logged. */
    private static void pathTimeline(IntegratedServer server, int who, int at) {
        ServerLevel level = server.overworld();
        if (who >= npcIds.size() || !(level.getEntity(npcIds.get(who)) instanceof SectDisciple n)) {
            return;
        }
        // A mob without AI is not on the ground for the pathfinder until it has ticked once with the AI on: switch it on early, then ask
        // for the path every frame until there is one (the stand's way of «a path starts and nobody calls wake or stand up»).
        if (at == PATH_AT - 3) {
            n.setNoAi(false);
        }
        if (at >= PATH_AT && !pathStarted.contains(who)) {
            n.getNavigation().moveTo(origin.getX() + who * SPACING + 6.5D, origin.getY(), origin.getZ() + 2.5D, 0.6D);
            if (!n.getNavigation().isDone()) {
                pathStarted.add(who);
                MurimMod.LOGGER.info("Pose stand path: the path starts at frame {} (sleeping={} seat={})", at, n.isSleeping(), n.seatStyle());
            }
        }
        if (at % 2 == 0) {
            MurimMod.LOGGER.info("Pose stand path: frame {} sleeping={} seat={} pose={} pos={} nav_done={}", at, n.isSleeping(), n.seatStyle(),
                    n.pose(), n.position(), n.getNavigation().isDone());
        }
    }

    /** Стенд «ряд из четырёх» (глава со старейшинами или первое поколение со вторым и третьим). */
    private static boolean row(String spot) {
        return "head".equals(spot) || "first".equals(spot) || "all".equals(spot);
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
        String sceneName = spots.get(i).name();
        if ("sleepwalk".equals(sceneName) || "seatwalk".equals(sceneName)) {
            look = look.add(1.8D, 0.0D, 0.8D);
        } else if (sceneName.startsWith("chair_") || "cushion_med".equals(sceneName)) {
            look = target.position().add(0.0D, 0.7D, 0.0D);
        }
        if (row(spots.get(i).name()) && !shot.name().startsWith("pan")) {
            // Глава и ученик справа от него: камера смотрит в середину пары (на шаге — в середину пути).
            look = look.add(0.5D * headMates.size() * HEAD_GAP + ("walk".equals(shot.name()) ? 2.0D : 0.0D), 0.0D, 0.0D);
        }
        // Человек смотрит на юг; камера с юга и на 35° к востоку: лицо и профиль позы видны вместе.
        Vec3 dir = switch (shot.name()) {
            case "front", "walk", "pan" -> new Vec3(0.0D, 0.0D, 1.0D);
            case "side" -> new Vec3(1.0D, 0.0D, 0.0D);
            case "back", "panback" -> new Vec3(0.0D, 0.0D, -1.0D);
            default -> new Vec3(0.7D, 0.0D, 1.0D).normalize();
        };
        if (row(spots.get(i).name()) && "side".equals(shot.name())) {
            // Ученик стоит восточнее главы — профиль главы снимается с запада, чтобы ученик не заслонял.
            dir = dir.scale(-1.0D);
        }
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
