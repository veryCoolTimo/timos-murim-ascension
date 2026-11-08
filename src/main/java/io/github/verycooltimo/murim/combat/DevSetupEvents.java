package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Подготовка мира для автономной съёмки кадров.
 *
 * <p>Техника — выхват клинка, и без меча в руке оценивать её анимацию не по чему.
 * Выдать предмет командой {@code /give} нельзя: в dev-мире выключены читы, и команда
 * отбивается парсером (поймано 2026-08-10). Поэтому предмет кладётся напрямую на сервере.
 *
 * <p>Класс общий, а не клиентский, намеренно: инвентарь — состояние мира, а состояние мира
 * меняется только на сервере (правило 03). Клиентских типов здесь нет.
 *
 * <p><b>Только для разработки.</b> Без системного свойства {@code murim.capture} обработчик
 * не делает ничего, поэтому в обычной игре его как будто нет.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/entity/player/PlayerEvent.java#PlayerLoggedInEvent
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DevSetupEvents {

    private static double captureYaw() {
        try {
            return Double.parseDouble(System.getProperty("murim.capture.yaw", "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final String CAPTURE_PROPERTY = "murim.capture";

    /** Съёмочная площадка: над кронами, чтобы в кадр не лезли ветки. */
    private static final int STAGE_X = 8;

    /** Имя невидимой стойки, глазами которой снимает камера «сбоку». */
    public static final String CAMERA_STAND_NAME = "murim_camera";
    private static final int STAGE_Y = 120;
    private static final int STAGE_Z = 8;
    /** Площадка 25×25; env MURIM_CAPTURE_PLATFORM — шире (дерево Сливы падает на 15 блоков). */
    private static final int PLATFORM_RADIUS = System.getenv("MURIM_CAPTURE_PLATFORM") == null ? 12
            : Integer.parseInt(System.getenv("MURIM_CAPTURE_PLATFORM").trim());

    /** Метка мишеней съёмки: по ней они снимаются перед следующим прогоном. */
    private static final String TARGET_TAG = "murim_capture_target";

    /** Через сколько тиков после входа доводить сцену. Хватает на загрузку чанков. */
    private static final int SETUP_DELAY_TICKS = 20;

    /** Обратный отсчёт до отложенной доводки сцены; ноль означает «делать нечего». */
    private static int pendingSetup;

    /**
     * Env MURIM_CAPTURE_CMDS: server commands separated by ';', run once after the stand is ready
     * (after the delayed setup, before the client starts frames). A token {@code wait N} delays the
     * commands after it by N ticks, so one run can film several views. Example:
     * {@code fill ~2 ~ ~2 ~5 ~ ~2 murim:hua_balustrade;tp @p 8 121 4 0 20;wait 40;tp @p 2 121 9 -90 20}.
     * Commands run as the player with permission 4, so {@code ~} is relative to the player.
     */
    private static final java.util.ArrayDeque<String> PENDING_COMMANDS = new java.util.ArrayDeque<>();
    private static int commandDelay;
    private static java.util.UUID commandPlayer;

    @SubscribeEvent
    static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // Два барьера, а не один. Системного свойства мало: класс уезжает в релизный jar,
        // и сервер, запущенный со скопированным набором JVM-аргументов, стирал бы предмет
        // в руке и перестраивал мир каждому входящему игроку.
        if (net.neoforged.fml.loading.FMLEnvironment.production
                || !Boolean.getBoolean(CAPTURE_PROPERTY)
                || io.github.verycooltimo.murim.dev.SmokeWalkRoute.enabled()
                    && (System.getenv("MURIM_SMOKE_WALK") != null || "1".equals(System.getenv("MURIM_SMOKE_RELOAD")))
                // Panorama of Mount Hua: no stage, no dummies (DevPanoramaHandler).
                || "mounthua".equals(System.getProperty("murim.capture.technique"))
                || "placecheck".equals(System.getProperty("murim.capture.technique"))
                // The archive walk-through travels to a generated archive (client/library/LibraryCapture).
                || "library".equals(System.getProperty("murim.capture.technique"))
                || "sectlife".equals(System.getProperty("murim.capture.technique"))
                // Sect NPC pose gallery: own stage (client/sect/SectPoseCapture).
                || "sectposes".equals(System.getProperty("murim.capture.technique"))
                // Body training: own yard and the South Peak climb (client/training/TrainingCapture).
                || "training".equals(System.getProperty("murim.capture.technique"))
                // Sect seals: own scene (client/seal/SealCapture).
                || "seals".equals(System.getProperty("murim.capture.technique"))
                // Bandit camp: a real world camp, no stage (DevBanditCampHandler).
                || "banditcamp".equals(System.getProperty("murim.capture.technique"))
                // Boss M4: a fortress built at the player, no stage (client/boss/DevBossHandler).
                || "boss".equals(System.getProperty("murim.capture.technique"))
                // Location round trip and biome: a real showcase world, no stage (client/DevLocationHandler).
                || "location".equals(System.getProperty("murim.capture.technique"))
                // The senior spar stand films in the built sect, no stage (client/SparFilmCapture).
                || "headintrofilm".equals(System.getProperty("murim.capture.technique"))
                || "bladeexamfilm".equals(System.getProperty("murim.capture.technique"))
                || "sparfilm".equals(System.getProperty("murim.capture.technique"))) {
            if (("bladeexamfilm".equals(System.getProperty("murim.capture.technique"))
                    || "sparfilm".equals(System.getProperty("murim.capture.technique"))
                    || "headintrofilm".equals(System.getProperty("murim.capture.technique"))) && Boolean.getBoolean(CAPTURE_PROPERTY)
                    && !net.neoforged.fml.loading.FMLEnvironment.production) {
                scheduleCaptureCommands(event.getEntity());
            }
            return;
        }
        scheduleCaptureCommands(event.getEntity());
        if (io.github.verycooltimo.murim.dev.SmokeWalkRoute.enabled()
                && "1".equals(System.getenv("MURIM_SMOKE_PRESERVE"))) return;
        // Меч нужен для съёмки техник, но в церемонии он торчит из сложенных рук и
        // перекрывает то самое тело, ради которого сцена и снимается.
        boolean bench = System.getProperty("murim.capture.technique", "").startsWith("bench:");
        boolean ceremony = bench || "meditation".equals(System.getProperty("murim.capture.technique"));
        // Кинжалы Тан — из рукавов, рука пустая (меч им не нужен).
        boolean tang = System.getProperty("murim.capture.technique", "").startsWith("tang_");
        // MURIM_CAPTURE_HAND=empty — пустая рука: съёмка ци-меча (03.10).
        boolean emptyHand = "empty".equals(System.getenv("MURIM_CAPTURE_HAND"));
        event.getEntity().setItemInHand(InteractionHand.MAIN_HAND,
                ceremony || tang || emptyHand ? ItemStack.EMPTY : new ItemStack(Items.NETHERITE_SWORD));
        // MURIM_CAPTURE_HAND=tang_dagger — в руке кинжал клана Тан (съёмка модели автора, 03.10).
        if ("tang_dagger".equals(System.getenv("MURIM_CAPTURE_HAND"))) {
            event.getEntity().setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(io.github.verycooltimo.murim.registry.ModItems.TANG_DAGGER.get(), 12));
        }
        // MURIM_CAPTURE_HAND=huashan_sword — Меч Хуашань (модель автора, 03.10).
        if ("huashan_sword".equals(System.getenv("MURIM_CAPTURE_HAND"))) {
            event.getEntity().setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get()));
        }
        // MURIM_CAPTURE_HAND=wooden_sword — деревянный учебный меч (04.10).
        if ("wooden_sword".equals(System.getenv("MURIM_CAPTURE_HAND"))) {
            event.getEntity().setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(io.github.verycooltimo.murim.registry.ModItems.WOODEN_SWORD.get()));
        }

        // Площадка над лесом. Оценивать светящуюся ленту на фоне листвы невозможно: контраст
        // низкий, а ветки перекрывают силуэт. Чистое небо даёт однозначный фон, на котором
        // видно и форму дуги, и её затухание. Поймано на кадрах контрольного ракурса 2026-08-10.
        Level level = event.getEntity().level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                serverLevel.setBlockAndUpdate(
                        new BlockPos(STAGE_X + dx, STAGE_Y - 1, STAGE_Z + dz),
                        // Природный пол (env MURIM_CAPTURE_FLOOR=grass): на нём видно, как
                        // техника ломает землю; гладкий камень техники не трогают.
                        "grass".equals(System.getenv("MURIM_CAPTURE_FLOOR"))
                                ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        // Полдень: в сумерках лента выглядит ярче, чем есть, и оценка получится завышенной.
        // Время суток задаётся снаружи: -Pmurim.time=night.
        //
        // Все прежние съёмки шли в полдень на светлой площадке, и это оказалось не
        // нейтральным выбором, а маскировкой. Аддитивное свечение на светлом фоне
        // выглядит скромно, а на ночном бьёт в глаза; тёмные слои днём теряются, а
        // ночью превращаются в чёрные дыры. Автор играет ночью и увидел совсем другой
        // эффект, чем я на своих кадрах.
        boolean night = "night".equalsIgnoreCase(System.getProperty("murim.capture.time", "day"));
        serverLevel.setDayTime(night ? 18000L : 6000L);
        // Only the throwaway footwork capture world: preserve the floor, remove sight blockers.
        if (!System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()) {
            // API: minecraft-src/net/minecraft/world/level/Level.java#setBlockAndUpdate
            for (int dx = -18; dx <= 18; dx++) {
                for (int dz = -18; dz <= 18; dz++) {
                    for (int dy = 0; dy < 6; dy++) {
                        serverLevel.setBlockAndUpdate(new BlockPos(STAGE_X + dx, STAGE_Y + dy,
                                STAGE_Z + dz), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            // Same axis as the enemy lineup in setupScene (28 degrees off the player's yaw).
            double enemyYaw = Math.toRadians(captureYaw()
                    + ("fp".equalsIgnoreCase(System.getProperty("murim.capture.camera", "back")) ? 0 : 28));
            double enemyX = -Math.sin(enemyYaw);
            double enemyZ = Math.cos(enemyYaw);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                BlockPos post = BlockPos.containing(STAGE_X + .5 + 15 * Math.cos(angle), STAGE_Y,
                        STAGE_Z + .5 + 15 * Math.sin(angle));
                double dx = post.getX() + .5 - (STAGE_X + .5);
                double dz = post.getZ() + .5 - (STAGE_Z + .5);
                // Keep the whole line clear, including beyond the last enemy.
                if (Math.abs(dx * enemyZ - dz * enemyX) <= 2) continue;
                // API: minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java#isFaceSturdy
                if (!serverLevel.getBlockState(post.below()).isFaceSturdy(serverLevel, post.below(),
                        net.minecraft.core.Direction.UP)) continue;
                serverLevel.setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
                serverLevel.setBlockAndUpdate(post.above(), Blocks.OAK_FENCE.defaultBlockState());
                serverLevel.setBlockAndUpdate(post.above(2), Blocks.LANTERN.defaultBlockState());
            }
        }
        // Ракурс задаётся поворотом ИГРОКА, а не камеры: камера в Minecraft жёстко привязана
        // к взгляду, а эффект строится в системе тела. Поворот игрока при неподвижной камере
        // показывает тот же эффект с другой стороны и стоит одну строку вместо своей камеры.
        float yaw = 0.0F;
        try {
            yaw = Float.parseFloat(System.getProperty("murim.capture.yaw", "0"));
        } catch (NumberFormatException ignored) {
            // Мусор в свойстве не должен ронять стенд: снимаем с нулевого угла.
        }
        event.getEntity().teleportTo(STAGE_X + 0.5D, STAGE_Y, STAGE_Z + 0.5D);
        event.getEntity().setYRot(yaw);
        event.getEntity().setYHeadRot(yaw);
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer turned) {
            turned.connection.teleport(STAGE_X + 0.5D, STAGE_Y, STAGE_Z + 0.5D, yaw, 0.0F);
        }

        // Мишени перед игроком. Без цели техника не наносит урона, событие попадания
        // не приходит, и ни hit stop, ни тряска камеры на кадрах не проявятся —
        // проверить их было бы нечем.
        // Обработчик срабатывает на каждый вход, поэтому старые мишени снимаются перед
        // спавном новых. Иначе второй прогон съёмки давал шесть стендов в трёх точках,
        // третий — девять, и результат попадания менялся от прогона к прогону.
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
                STAGE_X - 8.0D, STAGE_Y - 4.0D, STAGE_Z - 8.0D,
                STAGE_X + 8.0D, STAGE_Y + 4.0D, STAGE_Z + 8.0D);
        for (net.minecraft.world.entity.decoration.ArmorStand old
                : serverLevel.getEntitiesOfClass(
                        net.minecraft.world.entity.decoration.ArmorStand.class, area,
                        stand -> stand.getTags().contains(TARGET_TAG))) {
            old.discard();
        }
        // Мишени разведены по сторонам, центр оставлен пустым: при контрольном ракурсе
        // камера стоит спереди, и стенд по центру полностью закрывал персонажа.
        // Мишени отнесены на шесть блоков: при двух блоках снаряды долетали за пару тиков
        // и на кадрах их было не разглядеть — сцена скрывала работающую механику.
        // MURIM_CAPTURE_TARGET_Z — ближе для коротких искусств (кулак, песок: 2–3 блока), 05.10.
        double targetZ = System.getenv("MURIM_CAPTURE_TARGET_Z") == null ? 6.0D : Double.parseDouble(System.getenv("MURIM_CAPTURE_TARGET_Z"));
        // MURIM_CAPTURE_NO_DUMMY=1 — без мишеней на сцене (съёмка A/B удержания: ничего, что стоит само).
        for (double dx : bench || "1".equals(System.getenv("MURIM_CAPTURE_NO_DUMMY")) ? new double[0] : new double[] {-1.6D, 1.6D}) {
            net.minecraft.world.entity.decoration.ArmorStand target =
                    new net.minecraft.world.entity.decoration.ArmorStand(
                            serverLevel, STAGE_X + 0.5D + dx * Math.min(1.0D, targetZ / 6.0D), STAGE_Y, STAGE_Z + targetZ);
            target.addTag(TARGET_TAG);
            serverLevel.addFreshEntity(target);
        }
        // Съёмка медитации: метод выучен, даньтяня нет. С какого такта начинать — снаружи
        // (MURIM_CAPTURE_BEATS), по умолчанию со второго: там окно удержания и следом семя.
        if ("meditation".equals(System.getProperty("murim.capture.technique"))
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer meditating) {
            int beats = 1;
            String raw = System.getenv("MURIM_CAPTURE_BEATS");
            if (raw != null) {
                beats = Integer.parseInt(raw.trim());
            }
            // С семенем стенд ставит и сам даньтянь: иначе интерфейс ци честно молчит.
            io.github.verycooltimo.murim.cultivation.CultivationMethod method =
                    io.github.verycooltimo.murim.cultivation.MethodLoader.get(
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                    io.github.verycooltimo.murim.MurimMod.MODID, "six_harmonies"));
            io.github.verycooltimo.murim.profile.DantianProfile base =
                    io.github.verycooltimo.murim.profile.DantianProfile.INITIAL;
            meditating.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                    beats >= io.github.verycooltimo.murim.cultivation.CultivationState.SEEDED && method != null
                            ? io.github.verycooltimo.murim.cultivation.SeedLogic.seedProfile(base, method, 1.0D)
                                    .withPool(method.capacity() * 1.5D).withCirculating(0.0D)
                            : base);
            meditating.setData(io.github.verycooltimo.murim.registry.ModAttachments.CULTIVATION,
                    io.github.verycooltimo.murim.cultivation.CultivationState.NONE
                            .withMethod(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                    io.github.verycooltimo.murim.MurimMod.MODID, "six_harmonies"))
                            .withBeats(beats));
            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(meditating);
            // Искажение ци из прошлого прогона не должно попадать в кадры следующего.
            // MURIM_CAPTURE_SURVIVAL=1 — выживание: видны сердца, еда и шкала ци (в творческом шкалы ци нет), чтобы
            // проверить подсказки мыши и янтарную нить резерва рядом с ними.
            if ("1".equals(System.getenv("MURIM_CAPTURE_SURVIVAL"))) {
                meditating.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                // Ночью в мире стенда бродят мобы: удар прерывает медитацию, а смерть — съёмку. Неуязвим, но не творческий.
                meditating.getAbilities().invulnerable = true;
                meditating.onUpdateAbilities();
                // Мирная сложность: ночной зомби у самого игрока срывал посадку и съёмку (прогон 19.10). API:
                // reference/minecraft-src/net/minecraft/server/MinecraftServer.java#setDifficulty
                if (meditating.getServer() != null) {
                    meditating.getServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                    // Ни ночных мобов, ни бродячего торговца с ламами (лама у самого игрока срывала посадку в прогоне 19.10).
                    // API: reference/minecraft-src/net/minecraft/world/level/GameRules.java#RULE_DOMOBSPAWNING
                    meditating.serverLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING)
                            .set(false, meditating.getServer());
                    meditating.serverLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DO_TRADER_SPAWNING)
                            .set(false, meditating.getServer());
                    for (net.minecraft.world.entity.Mob m : meditating.level().getEntitiesOfClass(
                            net.minecraft.world.entity.Mob.class, meditating.getBoundingBox().inflate(48.0D))) {
                        if (!m.getTags().contains(TARGET_TAG)) {
                            m.discard();
                        }
                    }
                }
            }
            learnAll(meditating);
            // Пережитое для двойника и озарения в медитации: «Лунный взмах» у границы слоя.
            long day = meditating.level().getDayTime() / 24000L;
            primeInsight(meditating, "crescent_sweep", 1, 0.85D, 8.0D, day);
            primeInsight(meditating, "demon_palm", 2, 0.1D, 6.0D, day);
            // MURIM_CAPTURE_ECHO=<form of an art> — the Echo: a manual of that art in the hand (the stand sits down with it, so
            // the server chooses the Echo of the art), a reserve waiting and the last fight remembered.
            // MURIM_CAPTURE_ECHO_MOVES=a,b,c — the moves of that fight (default: the foundation, the first form, the foundation).
            String echoForm = System.getenv("MURIM_CAPTURE_ECHO");
            if (echoForm != null && !echoForm.isBlank()) {
                primeEcho(meditating, echoForm.trim(), System.getenv("MURIM_CAPTURE_ECHO_MOVES"), day);
            }
            // MURIM_CAPTURE_WALL=1 — запас у стены ранга и циркулирующая полна (иначе медитация
            // переливает запас в неё и стена отодвигается): прорыв начнётся с первого тика
            // медитации (у «Ладони» уже второй слой — условие прорыва в третий ранг).
            if ("1".equals(System.getenv("MURIM_CAPTURE_WALL"))) {
                io.github.verycooltimo.murim.profile.DantianProfile p =
                        meditating.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE);
                meditating.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                        wallProfile(p, System.getenv("MURIM_CAPTURE_RANK") == null ? 0
                                : Integer.parseInt(System.getenv("MURIM_CAPTURE_RANK").trim())));
                // Rank-two capture fixture: the same art thresholds and sect proof as real promotion (author 21.10).
                if (meditating.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank() == 1) {
                    meditating.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT,
                            meditating.getData(io.github.verycooltimo.murim.registry.ModAttachments.SECT).joined()
                                    .with(io.github.verycooltimo.murim.sect.SectProgression.SECOND_TEST)
                                    .with("rank2.win.seo_rang").with("rank2.win.seo_gyu").with("rank2.win.seo_ho"));
                    meditating.setData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY,
                            meditating.getData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY)
                                    .with(io.github.verycooltimo.murim.sect.SectService.SIX,
                                            io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(6, 12))
                                    .with(io.github.verycooltimo.murim.sect.SectJourney.PETAL,
                                            io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(3, 12)));
                }
                io.github.verycooltimo.murim.profile.ProfileNetwork.sync(meditating);
            }
            io.github.verycooltimo.murim.cultivation.RankEffects.apply(meditating);
            meditating.removeAllEffects();
            meditating.setHealth(meditating.getMaxHealth());
            meditating.getFoodData().setFoodLevel(20);
            // MURIM_CAPTURE_PILLS=origin_energy,thousand_poison,beauty_tear — съесть пилюли перед
            // посадкой: стенд садится в окне «сразу», и начинается поглощение (docs/design/19b).
            // MURIM_CAPTURE_PLACE=peak|water|forest|altar — камень жилы в 2 блоках сбоку и чуть позади игрока.
            String place = System.getenv("MURIM_CAPTURE_PLACE");
            // Зомби, вызванные волной «зова» лесного узла, переживают прогон — убрать.
            for (net.minecraft.world.entity.monster.Zombie z : meditating.level().getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Zombie.class, meditating.getBoundingBox().inflate(32.0D))) {
                if (!z.getTags().contains(TARGET_TAG)) {
                    z.discard();
                }
            }
            // MURIM_CAPTURE_CLEAR_MOBS=1 — no mobs or dropped items around the seated player (breakthrough films: nothing may stand in the lens).
            if ("1".equals(System.getenv("MURIM_CAPTURE_CLEAR_MOBS")) && meditating.level() instanceof net.minecraft.server.level.ServerLevel clearLevel) {
                clearLevel.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, clearLevel.getServer());
                for (net.minecraft.world.entity.Entity e : clearLevel.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                        meditating.getBoundingBox().inflate(48.0D))) {
                    if (e instanceof net.minecraft.world.entity.Mob || e instanceof net.minecraft.world.entity.item.ItemEntity) {
                        e.discard();
                    }
                }
            }
            // Камень прошлого прогона остаётся в мире стенда — убрать, чтобы не путал кадры.
            for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(
                    meditating.blockPosition().offset(-5, 0, -5), meditating.blockPosition().offset(5, 9, 5))) {
                var old = meditating.level().getBlockState(p);
                if (old.getBlock() instanceof io.github.verycooltimo.murim.world.SpiritVeinBlock
                        || old.is(net.minecraft.world.level.block.Blocks.OAK_LOG) || old.is(net.minecraft.world.level.block.Blocks.OAK_LEAVES)
                        || old.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_BRICKS)) {
                    meditating.level().setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
            // MURIM_CAPTURE_OBSTACLE=back|right|corner|room — deepslate-brick walls 2 blocks from the seated player
            // (behind, on the right, both, or a full ring with a roof gap) to film the breakthrough camera pulling in
            // and choosing the free side (client/camera/BreakthroughCamera). Removed again on the next run (above).
            String obstacle = System.getenv("MURIM_CAPTURE_OBSTACLE");
            if (obstacle != null && !obstacle.isBlank()) {
                net.minecraft.core.Direction facing = meditating.getDirection();
                net.minecraft.core.Direction rightHand = facing.getClockWise();
                net.minecraft.core.BlockPos feet = meditating.blockPosition();
                boolean back = obstacle.equals("back") || obstacle.equals("corner") || obstacle.equals("room");
                boolean right = obstacle.equals("right") || obstacle.equals("corner") || obstacle.equals("room");
                boolean ring = obstacle.equals("room");
                for (int along = -5; along <= 5; along++) {
                    for (int y = 0; y < 5; y++) {
                        if (back) {
                            wallBlock(meditating.level(), feet.relative(facing.getOpposite(), 2).relative(rightHand, along).above(y));
                        }
                        if (right) {
                            wallBlock(meditating.level(), feet.relative(rightHand, 2).relative(facing, along).above(y));
                        }
                        if (ring) {
                            wallBlock(meditating.level(), feet.relative(rightHand, -2).relative(facing, along).above(y));
                            wallBlock(meditating.level(), feet.relative(facing, 3).relative(rightHand, along).above(y));
                        }
                    }
                }
            }
            // MURIM_CAPTURE_PLACE=tree — старое дерево рядом (природное место силы, без камня).
            if ("tree".equals(place)) {
                net.minecraft.core.BlockPos trunk = meditating.blockPosition().relative(meditating.getDirection().getClockWise(), 3)
                        .relative(meditating.getDirection().getOpposite(), 1);
                for (int y = 0; y < 6; y++) {
                    for (int dx = 0; dx < 2; dx++) {
                        for (int dz = 0; dz < 2; dz++) {
                            meditating.level().setBlockAndUpdate(trunk.offset(dx, y, dz), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
                        }
                    }
                }
                for (net.minecraft.core.BlockPos l : net.minecraft.core.BlockPos.betweenClosed(trunk.offset(-2, 5, -2), trunk.offset(3, 8, 3))) {
                    if (meditating.level().getBlockState(l).isAir()) {
                        meditating.level().setBlockAndUpdate(l, net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
                    }
                }
                place = null;
            }
            if (place != null && !place.isBlank()) {
                net.minecraft.core.BlockPos at = meditating.blockPosition().relative(meditating.getDirection().getClockWise(), 2).relative(meditating.getDirection().getOpposite(), 1);
                meditating.level().setBlockAndUpdate(at, io.github.verycooltimo.murim.world.ModWorld.SPIRIT_VEIN.get()
                        .defaultBlockState().setValue(io.github.verycooltimo.murim.world.SpiritVeinBlock.KIND,
                                io.github.verycooltimo.murim.world.PlaceKind.valueOf(place.trim().toUpperCase(java.util.Locale.ROOT))));
            }
            String pills = System.getenv("MURIM_CAPTURE_PILLS");
            // MURIM_CAPTURE_CEILING=1 — низкий потолок над сидящим (стук головы при подлёте).
            meditating.setData(io.github.verycooltimo.murim.registry.ModAttachments.PILLS,
                    io.github.verycooltimo.murim.cultivation.PillState.NONE);
            // MURIM_CAPTURE_PILL_RANK=r — ранг игрока перед пилюлями (capacity grows with it; the pool is a quarter of the wall, so a
            // pill fits): the rank rule of the pills depends on it (rank - the pill's rank).
            String pillRank = System.getenv("MURIM_CAPTURE_PILL_RANK");
            if (pillRank != null && !pillRank.isBlank()) {
                // the biggest dantian of the rank ladder (capacity 60 at rank 0, x1.8 a rank): the walls of the mockup
                io.github.verycooltimo.murim.profile.DantianProfile seeded = meditating
                        .getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE);
                io.github.verycooltimo.murim.profile.DantianProfile ranked = seeded.withAxes(60.0D, seeded.purity(), seeded.meridians())
                        .withRank(Integer.parseInt(pillRank.trim()));
                meditating.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                        ranked.withPool(io.github.verycooltimo.murim.cultivation.Realm.wall(ranked) * 0.25D)
                                .withCirculating(ranked.maxCirculating()));
                io.github.verycooltimo.murim.profile.ProfileNetwork.sync(meditating);
            }
            for (net.minecraft.core.BlockPos q : net.minecraft.core.BlockPos.betweenClosed(
                    meditating.blockPosition().offset(-2, 2, -2), meditating.blockPosition().offset(2, 2, 2))) {
                var above = meditating.level().getBlockState(q);
                if (above.is(net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS)) {
                    meditating.level().setBlockAndUpdate(q, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
            if ("1".equals(System.getenv("MURIM_CAPTURE_CEILING"))) {
                for (net.minecraft.core.BlockPos q : net.minecraft.core.BlockPos.betweenClosed(
                        meditating.blockPosition().offset(-2, 2, -2), meditating.blockPosition().offset(2, 2, 2))) {
                    meditating.level().setBlockAndUpdate(q, net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS.defaultBlockState());
                }
            }
            if (pills != null && !pills.isBlank()) {
                for (String id : pills.split(",")) {
                    io.github.verycooltimo.murim.cultivation.PillKind kind =
                            io.github.verycooltimo.murim.cultivation.PillKind.valueOf(id.trim().toUpperCase(java.util.Locale.ROOT));
                    // A pill the body will not endure is only warned about at the first try; the second try confirms it.
                    if (!io.github.verycooltimo.murim.cultivation.PillService.eat(meditating, kind)) {
                        io.github.verycooltimo.murim.cultivation.PillService.eat(meditating, kind);
                    }
                }
            }
            return;
        }
        // Техники теперь стоят ци и требуют сформированного центра. Без этого съёмочный
        // стенд молча перестал бы запускать техники, и это выглядело бы как поломка визуала.
        io.github.verycooltimo.murim.profile.DantianProfile awakened =
                io.github.verycooltimo.murim.profile.DantianProfile.INITIAL
                        .withTags("clear", "debug")
                        .withAxes(60.0D, 0.8D, 0.8D);
        // MURIM_CAPTURE_PLAYER_RANK — ранг снимающего: от него зависят давление и чтение ранга.
        String playerRank = System.getenv("MURIM_CAPTURE_PLAYER_RANK");
        if (playerRank != null && !playerRank.isBlank()) {
            awakened = awakened.withRank(Integer.parseInt(playerRank.trim()));
        }
        // MURIM_CAPTURE_PLAYER_STAGE — подступень Пика (0–2): от неё зависит ци-меч.
        String playerStage = System.getenv("MURIM_CAPTURE_PLAYER_STAGE");
        if (playerStage != null && !playerStage.isBlank()) {
            awakened = awakened.withStage(Integer.parseInt(playerStage.trim()));
        }
        event.getEntity().setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                awakened.withPool(500.0D).withCirculating(awakened.maxCirculating()));
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(serverPlayer);
            // Ранг выставлен после входа: сердца и скорость ранга ставятся заново (этап M2).
            io.github.verycooltimo.murim.cultivation.RankEffects.apply(serverPlayer);
        }

        // Стенд применяет техники — значит, должен их знать (docs/design/19 §3г).
        learnAll(event.getEntity());
        // Перезарядка общая на все техники и хранится в мире стенда между прогонами: долгий
        // кулдаун (Вихрь, 600 тиков) молча отклонял запуск после прошлой съёмки. Сбрасываем.
        event.getEntity().setData(io.github.verycooltimo.murim.registry.ModAttachments.TECHNIQUE_STATE,
                io.github.verycooltimo.murim.combat.TechniqueState.IDLE);
        // Перезарядки тоже: прогон, упавший с исключением рендера, сохраняет мир вместе с ними,
        // и следующий прогон молча не запускал технику (03.10, ци-меч).
        event.getEntity().setData(io.github.verycooltimo.murim.registry.ModAttachments.COOLDOWNS, new java.util.HashMap<>());
        // Съёмка основы меча: Шесть Равновесий в ячейке основы, удары — обычной атакой.
        if ("foundation".equals(System.getProperty("murim.capture.technique"))
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer fp) {
            fp.setData(io.github.verycooltimo.murim.registry.ModAttachments.LOADOUT,
                    fp.getData(io.github.verycooltimo.murim.registry.ModAttachments.LOADOUT).withFoundation(java.util.Optional.of(
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(io.github.verycooltimo.murim.MurimMod.MODID,
                                    System.getenv().getOrDefault("MURIM_CAPTURE_FOUNDATION_ID", "six_harmonies")))));
            io.github.verycooltimo.murim.mastery.LoadoutService.sync(fp);
        }
        // Combat HUD stand (client/CombatHudCapture): Seven Plum basic on the left click, God Wind form in the first slot.
        if ("combathud".equals(System.getProperty("murim.capture.technique"))
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer hp) {
            io.github.verycooltimo.murim.mastery.Loadout loadout = hp.getData(io.github.verycooltimo.murim.registry.ModAttachments.LOADOUT)
                    .withFoundation(java.util.Optional.of(io.github.verycooltimo.murim.technique.Styles.SEVEN_PLUM.basic().orElseThrow()));
            String[] slots = {"wind_god_steps", "demon_palm", "crescent_sweep"};
            for (int i = 0; i < slots.length; i++) {
                loadout = loadout.with(i, java.util.Optional.of(net.minecraft.resources.ResourceLocation
                        .fromNamespaceAndPath(io.github.verycooltimo.murim.MurimMod.MODID, slots[i])));
            }
            hp.setData(io.github.verycooltimo.murim.registry.ModAttachments.LOADOUT, loadout);
            io.github.verycooltimo.murim.mastery.LoadoutService.sync(hp);
            // MURIM_CAPTURE_INSIGHT=1: the basic stands one breath short of its next star, so the first hit is a breakthrough.
            if ("1".equals(System.getenv("MURIM_CAPTURE_INSIGHT"))) {
                primeInsight(hp, "seven_plum_basic", 3, 0.999D, 0.0D, hp.level().getDayTime() / 24000L);
            }
        }
        // Автоматика шагов (03.10): снимаемый стиль шагов — в первом слоте, иначе автобег и
        // автотень не видят стиля в раскладке. Since 31.10 every footwork form needs its slot (selected forms only), so any
        // footwork capture mode puts the subject there.
        if (!System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer ap) {
            net.minecraft.resources.ResourceLocation subject = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    io.github.verycooltimo.murim.MurimMod.MODID, io.github.verycooltimo.murim.dev.FootworkCapturePlan.subject(
                            System.getProperty("murim.capture.technique", "wind_god_steps"),
                            System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "")));
            io.github.verycooltimo.murim.mastery.MasteryService.completeStyles(ap);
            // Same art-slot + form-selection path as the player's loadout screen and wheel.
            var art = io.github.verycooltimo.murim.technique.Styles.of(subject)
                    .map(style -> style.forms().getFirst()).orElse(subject);
            io.github.verycooltimo.murim.mastery.LoadoutService.setSlot(ap, 0, java.util.Optional.of(art));
            boolean selected = io.github.verycooltimo.murim.mastery.LoadoutService.selectForm(ap, 0, subject, true);
            MurimMod.LOGGER.info("CAPTURE-FOOTWORK setup subject={} selected={}", subject, selected);
            io.github.verycooltimo.murim.mastery.MasteryService.sync(ap);
        }
        // Съёмка книги-манускрипта (03.10): в руке манускрипт техники MURIM_CAPTURE_MANUAL.
        if ("manual".equals(System.getProperty("murim.capture.technique"))
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer mp) {
            net.minecraft.world.item.ItemStack book = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.TECHNIQUE_MANUAL.get());
            book.set(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get(), net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    io.github.verycooltimo.murim.MurimMod.MODID, System.getenv().getOrDefault("MURIM_CAPTURE_MANUAL", "six_harmonies")));
            mp.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, book);
            // MURIM_CAPTURE_MANUAL_FRESH=1 — книга ещё не изучена (видна печать «Изучить»).
            if ("1".equals(System.getenv("MURIM_CAPTURE_MANUAL_FRESH"))) {
                mp.setData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY, io.github.verycooltimo.murim.mastery.MasteryState.EMPTY);
                io.github.verycooltimo.murim.mastery.MasteryService.sync(mp);
            }
            // MURIM_CAPTURE_NO_DANTIAN=1 — игрок без даньтяня: книга читается, но не учит.
            if ("1".equals(System.getenv("MURIM_CAPTURE_NO_DANTIAN"))) {
                mp.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE, io.github.verycooltimo.murim.profile.DantianProfile.INITIAL);
                io.github.verycooltimo.murim.profile.ProfileNetwork.sync(mp);
            }
        }
        // Съёмка интерфейсов техник: шесть открытых слотов, пять техник, часть освоена наполовину.
        if ("ui".equals(System.getProperty("murim.capture.technique"))
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer uiPlayer) {
            long day = uiPlayer.level().getDayTime() / 24000L;
            primeInsight(uiPlayer, "crescent_sweep", 2, 0.6D, 0.0D, day);
            primeInsight(uiPlayer, "demon_palm", 1, 0.3D, 0.0D, day);
            primeInsight(uiPlayer, "shadow_step", 3, 0.8D, 0.0D, day);
            uiPlayer.setData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY,
                    uiPlayer.getData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY).withWisdom(16.0D));
            io.github.verycooltimo.murim.mastery.Loadout loadout = io.github.verycooltimo.murim.mastery.Loadout.EMPTY;
            // MURIM_CAPTURE_UI_STYLE=1 — в первом слоте стиль Семи Цветков: кольцо показывает его формы.
            // MURIM_CAPTURE_UI_STYLE=2 — двойное кольцо (03.10): два стиля, отдельные техники и шаг.
            String uiStyle = System.getenv().getOrDefault("MURIM_CAPTURE_UI_STYLE", "");
            if ("2".equals(uiStyle)) {
                for (String f : new String[] {"seven_plum_blossoms", "seven_plum_whirlwind", "seven_plum_execution", "seven_plum_rush",
                        "twenty_four_plum_rainfall", "falling_petal_sword", "wind_god_steps"}) {
                    primeInsight(uiPlayer, f, 3, 0.5D, 0.0D, day);
                }
            }
            String[] order = "2".equals(uiStyle)
                    ? new String[] {"seven_plum_whirlwind", "twenty_four_plum_rainfall", "falling_petal_sword", "demon_palm", "wind_god_steps"}
                    : "1".equals(uiStyle)
                    ? new String[] {"seven_plum_whirlwind", "demon_palm", "wind_god_steps"}
                    : new String[] {"crescent_sweep", "demon_palm", "wedge_fan", "shadow_step"};
            for (int i = 0; i < order.length; i++) {
                loadout = loadout.with(i, java.util.Optional.of(net.minecraft.resources.ResourceLocation
                        .fromNamespaceAndPath(io.github.verycooltimo.murim.MurimMod.MODID, order[i])));
            }
            uiPlayer.setData(io.github.verycooltimo.murim.registry.ModAttachments.LOADOUT, loadout);
            io.github.verycooltimo.murim.mastery.MasteryService.sync(uiPlayer);
        }
        // MURIM_CAPTURE_INSIGHT=1 — снимаемая техника у самой границы слоя: первое попадание
        // даёт озарение в бою. Титр озарения — слой интерфейса, поэтому DevCaptureHandler при этой
        // переменной снимает кадры с интерфейсом (иначе пара murim_/clean_ его не содержит).
        if ("1".equals(System.getenv("MURIM_CAPTURE_INSIGHT"))) {
            primeInsight(event.getEntity(), System.getProperty("murim.capture.technique", "ceremonial_draw"),
                    1, 0.995D, 0.0D, event.getEntity().level().getDayTime() / 24000L);
        }

        // При контрольном ракурсе манекен не ставится вовсе: камера спереди оказывается
        // ровно в его голове и закрывает кадр целиком. Проверять геометрию эффекта важнее,
        // чем иметь мишень, — попадания проверяются с игрового ракурса.
        boolean frontCamera = "front".equalsIgnoreCase(
                System.getProperty("murim.capture.camera", "back"));

        // Уборка и спавн манекена отложены: при входе игрока сущности чанка ещё
        // не подгружены, и убирать нечего — старые манекены появляются позже и лезут
        // в кадр. Три попытки починить это на входе не сработали именно поэтому.
        pendingSetup = SETUP_DELAY_TICKS;

        MurimMod.LOGGER.info("Съёмка: меч выдан, профиль пробуждён, игрок на площадке {} {} {}",
                STAGE_X, STAGE_Y, STAGE_Z);
    }

    /**
     * Отложенная доводка сцены: убрать чужие манекены и поставить свой.
     *
     * <p>Выполняется через два десятка тиков после входа, когда чанки вокруг площадки
     * уже загружены и сохранённые сущности прошлых прогонов существуют в мире.
     */
    /**
     * Урон игрока по цели — в лог (этап M2: таблица «урон одной техники на разных рангах»).
     * API: reference/neoforge-src/net/neoforged/neoforge/event/entity/living/LivingDamageEvent.java#Post
     */
    @SubscribeEvent
    static void onDamageDealt(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!net.neoforged.fml.loading.FMLEnvironment.production && Boolean.getBoolean(CAPTURE_PROPERTY)
                && !System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()
                && event.getEntity() instanceof net.minecraft.world.entity.monster.Zombie) {
            // Include environmental/mob damage: player-only telemetry hid warmup deaths.
            MurimMod.LOGGER.info("Footwork actor damage: id={}, tick={}, source={}, damage={}, health={}",
                    event.getEntity().getId(), event.getEntity().level().getGameTime(),
                    event.getSource().getMsgId(), event.getNewDamage(), event.getEntity().getHealth());
        }
        if (net.neoforged.fml.loading.FMLEnvironment.production || !Boolean.getBoolean(CAPTURE_PROPERTY)
                || io.github.verycooltimo.murim.dev.SmokeWalkRoute.enabled()
                    && (System.getenv("MURIM_SMOKE_WALK") != null || "1".equals(System.getenv("MURIM_SMOKE_RELOAD")))
                || !(event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        MurimMod.LOGGER.info("Съёмка: урон {} (до брони {}) по {} id={} uuid={}, tick={}, source={}, direct={}, queued={}, ранг {}, техника {}",
                String.format(java.util.Locale.ROOT, "%.2f", event.getNewDamage()),
                String.format(java.util.Locale.ROOT, "%.2f", event.getOriginalDamage()),
                event.getEntity().getType().getDescriptionId(), event.getEntity().getId(), event.getEntity().getUUID(),
                player.level().getGameTime(), event.getSource().getMsgId(),
                event.getSource().getDirectEntity() == null ? -1 : event.getSource().getDirectEntity().getId(),
                player.getData(io.github.verycooltimo.murim.registry.ModAttachments.BASIC_ATTACK).targetId(),
                player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank(),
                System.getProperty("murim.capture.technique"));
    }

    private static void scheduleCaptureCommands(net.minecraft.world.entity.player.Player player) {
        PENDING_COMMANDS.clear();
        String raw = System.getenv("MURIM_CAPTURE_CMDS");
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String cmd : raw.split(";")) {
            if (!cmd.isBlank()) {
                PENDING_COMMANDS.add(cmd.trim());
            }
        }
        // After the delayed setup (dummy, camera stand), so the commands can also remove what it spawns.
        commandDelay = SETUP_DELAY_TICKS + 5;
        commandPlayer = player.getUUID();
    }

    private static void runCaptureCommands(net.minecraft.server.MinecraftServer server) {
        if (PENDING_COMMANDS.isEmpty() || --commandDelay > 0) {
            return;
        }
        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(commandPlayer);
        net.minecraft.commands.CommandSourceStack source = (player != null
                ? player.createCommandSourceStack() : server.createCommandSourceStack()).withPermission(4).withSuppressedOutput();
        while (!PENDING_COMMANDS.isEmpty()) {
            String cmd = PENDING_COMMANDS.poll();
            if (cmd.startsWith("wait ")) {
                commandDelay = Integer.parseInt(cmd.substring(5).trim());
                return;
            }
            MurimMod.LOGGER.info("Capture command: {}", cmd);
            server.getCommands().performPrefixedCommand(source, cmd);
        }
    }

    @SubscribeEvent
    static void onCaptureCommandsTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!net.neoforged.fml.loading.FMLEnvironment.production && Boolean.getBoolean(CAPTURE_PROPERTY)) {
            runCaptureCommands(event.getServer());
        }
    }

    @SubscribeEvent
    static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (pendingSetup <= 0 || --pendingSetup > 0) {
            return;
        }
        net.minecraft.server.level.ServerLevel level =
                event.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (level == null) {
            return;
        }
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
                STAGE_X - 32.0D, STAGE_Y - 8.0D, STAGE_Z - 32.0D,
                STAGE_X + 32.0D, STAGE_Y + 24.0D, STAGE_Z + 32.0D);
        if (!System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()) {
            // The stand world is copied between takes; discard old loot rather than filming it as a new death.
            for (var loot : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area)) loot.discard();
            for (var clone : level.getEntitiesOfClass(io.github.verycooltimo.murim.entity.ShadowClone.class, area)) clone.discard();
        }
        int removed = 0;
        for (io.github.verycooltimo.murim.entity.TrainingDummy old
                : level.getEntitiesOfClass(io.github.verycooltimo.murim.entity.TrainingDummy.class, area)) {
            old.discard();
            removed++;
        }
        for (net.minecraft.world.entity.decoration.ArmorStand old
                : level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, area,
                        stand -> stand.getCustomName() != null
                                && CAMERA_STAND_NAME.equals(stand.getCustomName().getString()))) {
            old.discard();
        }

        // Спереди манекен по умолчанию не ставится (см. выше), но для съёмки удара он нужен:
        // со спины корпус закрывает и кисть, и шлейф. Смещённый на 28° манекен камеру
        // не перекрывает, поэтому его можно включить явно: MURIM_CAPTURE_DUMMY=1.
        boolean frontCamera = "front".equalsIgnoreCase(
                System.getProperty("murim.capture.camera", "back"))
                && !"1".equals(System.getenv("MURIM_CAPTURE_DUMMY"));
        if (frontCamera) {
            MurimMod.LOGGER.info("Съёмка: контрольный ракурс, манекенов убрано {}, новый не ставится",
                    removed);
            return;
        }

        io.github.verycooltimo.murim.entity.TrainingDummy dummy =
                new io.github.verycooltimo.murim.entity.TrainingDummy(
                        io.github.verycooltimo.murim.registry.ModEntities.DUMMY.get(), level);
        // Манекен ставится ПО НАПРАВЛЕНИЮ ВЗГЛЯДА и в пределах удара.
        //
        // Две ошибки стенда сразу: прежние 2.95 блока превышали дальность ладони после
        // её сокращения до 2.2, а фиксированные координаты не учитывали поворот игрока
        // при съёмке — на ракурсе с поворотом 90° цель оказывалась сбоку от конуса.
        // В обоих случаях техника промахивалась, брызг по цели не было, и на кадрах это
        // читалось как «эффект не работает».
        double yaw = Math.toRadians(Double.parseDouble(
                System.getProperty("murim.capture.yaw", "0")));
        // Цель смещена на 28° вбок от оси взгляда: конус удара 70°, то есть она
        // остаётся поражаемой, но перестаёт прятаться ЗА игроком от камеры со спины.
        // Строго по оси её не видно вовсе, и брызги по телу оценить нельзя.
        // От первого лица (давление ауры) цель — строго по оси взгляда.
        boolean fpCamera = "fp".equalsIgnoreCase(System.getProperty("murim.capture.camera", "back"));
        double aim = fpCamera ? yaw : yaw + Math.toRadians(28.0D);
        double lookX = -Math.sin(aim);
        double lookZ = Math.cos(aim);
        // Сбоку манекен стоит на вытянутую руку: иначе ладонь до него не достаёт, и всплеск
        // «под ладонью» висит в воздухе в метре от кисти (кадры 2026-09-24).
        double dummyDistance = "side".equalsIgnoreCase(
                System.getProperty("murim.capture.camera", "back")) ? 1.15D : 1.7D;
        // MURIM_CAPTURE_DUMMY_DIST — поставить цель дальше вытянутой руки: проверка рывка ладони.
        String dist = System.getenv("MURIM_CAPTURE_DUMMY_DIST");
        if (dist != null) {
            dummyDistance = Double.parseDouble(dist.trim());
        }
        if (fpCamera && dist == null) {
            dummyDistance = 4.0D;
        }
        // MURIM_CAPTURE_ENEMY=zombie — вместо манекена зомби без ИИ, лицом к игроку:
        // давление ауры проверяется на живом противнике (docs/design/19 §3ж).
        net.minecraft.world.entity.LivingEntity target = dummy;
        // MURIM_CAPTURE_ENEMY=bandit | bandit_elite | bandit_archer — живой бандит этапа M1
        // (MURIM_CAPTURE_ENEMY_AI=0 — без ИИ). Сложность «Нормально»: на «Лёгкой» урон бандита
        // делится пополам и ни один его удар не дотягивает до порога срыва техник (6).
        String enemyKind = System.getenv().getOrDefault("MURIM_CAPTURE_ENEMY", "");
        net.minecraft.server.level.ServerPlayer captureSubject = level.players().isEmpty() ? null : level.players().get(0);
        if (enemyKind.startsWith("bandit")) {
            for (io.github.verycooltimo.murim.entity.Bandit old
                    : level.getEntitiesOfClass(io.github.verycooltimo.murim.entity.Bandit.class, area)) {
                old.discard();
            }
            if (level.getServer() != null) {
                level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
                // Ночью на траве площадки сами появлялись зомби: захват цели брал их, а не бандита,
                // и один прогон «промахнулся» (03.10). На стенде с бандитом — только бандит.
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
                for (net.minecraft.world.entity.monster.Monster m : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, area.inflate(32.0D))) {
                    m.discard();
                }
            }
            io.github.verycooltimo.murim.entity.Bandit bandit = "bandit_archer".equals(enemyKind)
                    ? new io.github.verycooltimo.murim.entity.BanditArcher(io.github.verycooltimo.murim.registry.ModEntities.BANDIT_ARCHER.get(), level)
                    : new io.github.verycooltimo.murim.entity.BanditSwordsman(io.github.verycooltimo.murim.registry.ModEntities.BANDIT_SWORDSMAN.get(), level);
            if ("bandit_elite".equals(enemyKind) && bandit instanceof io.github.verycooltimo.murim.entity.BanditSwordsman sw) {
                sw.makeElite();
            }
            bandit.setNoAi("0".equals(System.getenv("MURIM_CAPTURE_ENEMY_AI")));
            // MURIM_CAPTURE_BANDIT_DELAY — тиков до первого удара: подогнать удар под замах техники.
            String delay = System.getenv("MURIM_CAPTURE_BANDIT_DELAY");
            if (delay != null && !delay.isBlank() && bandit instanceof io.github.verycooltimo.murim.entity.BanditSwordsman sd) {
                sd.delayFirstAttack(Integer.parseInt(delay.trim()));
            }
            bandit.setPersistenceRequired();
            String hp = System.getenv("MURIM_CAPTURE_ENEMY_HP");
            if (hp != null && !hp.isBlank()) {
                java.util.Objects.requireNonNull(bandit.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH))
                        .setBaseValue(Double.parseDouble(hp.trim()));
                bandit.setHealth(bandit.getMaxHealth());
            }
            float face = (float) Math.toDegrees(aim) + 180.0F;
            bandit.setYRot(face);
            bandit.setYHeadRot(face);
            bandit.setYBodyRot(face);
            target = bandit;
        }
        // MURIM_CAPTURE_ENEMY=disciple — старший ученик Хуашань вызывает игрока на спарринг (секта С0):
        // MURIM_CAPTURE_SPAR=six,slash,rush — очередь приёмов, MURIM_CAPTURE_SPAR_DELAY — тиков до поклона.
        io.github.verycooltimo.murim.entity.SectDisciple disciple = null;
        if ("disciple".equals(enemyKind)) {
            for (io.github.verycooltimo.murim.entity.SectDisciple old
                    : level.getEntitiesOfClass(io.github.verycooltimo.murim.entity.SectDisciple.class, area)) {
                old.discard();
            }
            if (level.getServer() != null) {
                level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
                for (net.minecraft.world.entity.monster.Monster m : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, area.inflate(32.0D))) {
                    m.discard();
                }
            }
            disciple = new io.github.verycooltimo.murim.entity.SectDisciple(
                    io.github.verycooltimo.murim.registry.ModEntities.SECT_DISCIPLE.get(), level);
            float face = (float) Math.toDegrees(aim) + 180.0F;
            disciple.setYRot(face);
            disciple.setYHeadRot(face);
            disciple.setYBodyRot(face);
            target = disciple;
        }
        if ("zombie".equals(System.getenv("MURIM_CAPTURE_ENEMY"))) {
            for (net.minecraft.world.entity.monster.Zombie old
                    : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class, area)) {
                old.discard();
            }
            net.minecraft.world.entity.monster.Zombie zombie =
                    new net.minecraft.world.entity.monster.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
            // MURIM_CAPTURE_ENEMY_AI=1 — живой зомби (идёт и бьёт): проверка оглушения и прохода за спину.
            zombie.setNoAi(!"1".equals(System.getenv("MURIM_CAPTURE_ENEMY_AI")));
            zombie.setPersistenceRequired();
            if (captureSubject != null && "1".equals(System.getenv("MURIM_CAPTURE_ENEMY_AI"))) {
                level.getServer().setDifficulty(net.minecraft.world.Difficulty.EASY, true);
                captureSubject.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                captureSubject.getAbilities().invulnerable = false;
                captureSubject.onUpdateAbilities();
                zombie.setTarget(captureSubject);
            }
            // MURIM_CAPTURE_ENEMY_HP — запас здоровья цели: долгая техника (Вихрь) убивала зомби
            // раньше финала, и финальный проход было не по кому снять.
            String hp = System.getenv("MURIM_CAPTURE_ENEMY_HP");
            if (hp != null && !hp.isBlank()) {
                java.util.Objects.requireNonNull(zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH))
                        .setBaseValue(Double.parseDouble(hp.trim()));
                zombie.setHealth(zombie.getMaxHealth());
            }
            zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
            float face = (float) Math.toDegrees(aim) + 180.0F;
            zombie.setYRot(face);
            zombie.setYHeadRot(face);
            zombie.setYBodyRot(face);
            target = zombie;
        }
        // MURIM_CAPTURE_ENEMY_Y — цель висит в воздухе на этой высоте (приёмы по врагу в небе, 03.10).
        double lift = Double.parseDouble(System.getenv().getOrDefault("MURIM_CAPTURE_ENEMY_Y", "0"));
        if (lift != 0.0D) {
            // Отрицательная высота — цель НИЖЕ площадки (под ней воздух): проверка «бьёт вниз».
            target.setNoGravity(true);
        }
        boolean footworkActors = !System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()
                && "zombie".equals(enemyKind) && "1".equals(System.getenv("MURIM_CAPTURE_ENEMY_AI"));
        // Stage with AI paused; the client releases pursuit exactly one second before R.
        // Warmup must not consume live targets before the first recorded frame.
        if (footworkActors && target instanceof net.minecraft.world.entity.Mob mob) mob.setNoAi(true);
        target.setPos(STAGE_X + 0.5D + lookX * dummyDistance, STAGE_Y + lift,
                      STAGE_Z + 0.5D + lookZ * dummyDistance);
        level.addFreshEntity(target);
        int enemyCount = Math.max(1, Math.min(30, Integer.parseInt(System.getenv().getOrDefault("MURIM_CAPTURE_ENEMIES", "1"))));
        if ("zombie".equals(enemyKind)) for (int i = 1; i < enemyCount; i++) {
            var extra = new net.minecraft.world.entity.monster.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
            extra.setNoAi(footworkActors || !"1".equals(System.getenv("MURIM_CAPTURE_ENEMY_AI")));
            if ("1".equals(System.getenv("MURIM_CAPTURE_ENEMY_AI"))) extra.setTarget(captureSubject);
            extra.setPersistenceRequired();
            extra.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
            extra.moveTo(target.getX() + lookX * i * 2, target.getY(), target.getZ() + lookZ * i * 2, target.getYRot(), 0);
            extra.setYHeadRot(target.getYRot());
            level.addFreshEntity(extra);
        }
        // MURIM_CAPTURE_EXTRA_ZOMBIE=1 — живой зомби рядом с целью (за целью, чуть вбок): съёмка оглушения
        // бандита и зомби одной техникой (combat/Stun, 05.10).
        if ("1".equals(System.getenv("MURIM_CAPTURE_EXTRA_ZOMBIE"))) {
            net.minecraft.world.entity.monster.Zombie extra =
                    new net.minecraft.world.entity.monster.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
            extra.setPersistenceRequired();
            java.util.Objects.requireNonNull(extra.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH))
                    .setBaseValue(400.0D);
            extra.setHealth(extra.getMaxHealth());
            extra.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
            // За целью и чуть вбок, с замедлением III (не стан: порог оглушения — IV) — иначе зомби
            // добегал до игрока раньше удара и стоял вне коридора техники.
            extra.setPos(target.getX() - lookX * 0.9D + lookZ * 0.9D, target.getY(), target.getZ() - lookZ * 0.9D - lookX * 0.9D);
            extra.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 200, 2));
            float face = (float) Math.toDegrees(aim) + 180.0F;
            extra.setYRot(face);
            extra.setYHeadRot(face);
            extra.setYBodyRot(face);
            level.addFreshEntity(extra);
        }
        // MURIM_CAPTURE_TANG_STUCK=N — за целью лежат N воткнутых кинжалов игрока (съёмка «Возврата Лезвий»).
        int stuck = (int) envDouble("MURIM_CAPTURE_TANG_STUCK", 0.0D);
        if (stuck > 0 && !level.players().isEmpty()) {
            io.github.verycooltimo.murim.technique.TangExecutor.spawnStuckForCapture(level.players().get(0), target.position(), stuck);
        }
        // MURIM_CAPTURE_DROP=tang_dagger|huashan_sword — предмет лежит на земле перед игроком (съёмка
        // модели автора в виде выброшенного предмета, 03.10). Не подбирается и не исчезает.
        String drop = System.getenv("MURIM_CAPTURE_DROP");
        if (drop != null && !drop.isBlank() && !level.players().isEmpty()) {
            net.minecraft.world.entity.player.Player p0 = level.players().get(0);
            net.minecraft.world.item.Item item = "huashan_sword".equals(drop.trim()) ? io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get()
                    : "wooden_sword".equals(drop.trim()) ? io.github.verycooltimo.murim.registry.ModItems.WOODEN_SWORD.get()
                    : io.github.verycooltimo.murim.registry.ModItems.TANG_DAGGER.get();
            double ahead = envDouble("MURIM_CAPTURE_DROP_DIST", 1.6D);
            net.minecraft.world.entity.item.ItemEntity dropped = new net.minecraft.world.entity.item.ItemEntity(level,
                    p0.getX() + lookX * ahead, STAGE_Y, p0.getZ() + lookZ * ahead, new ItemStack(item));
            dropped.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            dropped.setNeverPickUp();
            dropped.setUnlimitedLifetime();
            level.addFreshEntity(dropped);
        }
        if (disciple != null && !level.players().isEmpty()) {
            level.players().get(0).setHealth(level.players().get(0).getMaxHealth());
            disciple.startSpar(level.players().get(0), (int) envDouble("MURIM_CAPTURE_SPAR_DELAY", 40.0D));
        }
        // Цель ниже площадки: прорезать над ней яму, иначе пол закрывает её целиком и честная
        // проверка «бьёт вниз» невозможна (техники с прямой видимостью её не видят — и правильно).
        if (lift < 0.0D) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.setBlockAndUpdate(net.minecraft.core.BlockPos.containing(target.getX() + dx, STAGE_Y - 1, target.getZ() + dz),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
        }
        // MURIM_CAPTURE_STONEWALL=1 — каменная стена слева от линии броска (рикошеты Монет Тан, 04.10): вдоль
        // взгляда на 3–11 блоков, в 2,5 блока сбоку, высотой 3. Без флага полоса очищается — стена не остаётся
        // в сохранённом мире для других съёмок.
        boolean wall = "1".equals(System.getenv("MURIM_CAPTURE_STONEWALL"));
        for (int k = 3; k <= 11; k++) {
            for (int h = 0; h < 3; h++) {
                net.minecraft.core.BlockPos wp = net.minecraft.core.BlockPos.containing(
                        STAGE_X + 0.5D + lookX * k + lookZ * 2.5D, STAGE_Y + h, STAGE_Z + 0.5D + lookZ * k - lookX * 2.5D);
                level.setBlockAndUpdate(wp, wall ? net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState()
                        : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
        }
        // MURIM_CAPTURE_EXTRA=n — ещё n зомби на пути к цели (Натиск волочит встречных, 03.10).
        int extra = (int) envDouble("MURIM_CAPTURE_EXTRA", 0.0D);
        for (int i = 0; i < extra; i++) {
            net.minecraft.world.entity.monster.Zombie z =
                    new net.minecraft.world.entity.monster.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
            // Без ИИ моб не двигается вовсе (LivingEntity.travel только при isEffectiveAi) —
            // волочение и отброс видны лишь на живых: MURIM_CAPTURE_ENEMY_AI=1.
            z.setNoAi(!"1".equals(System.getenv("MURIM_CAPTURE_ENEMY_AI")));
            z.setPersistenceRequired();
            double k = dummyDistance * (i + 1) / (extra + 1.0D);
            // Снаружи от оси взгляда: захват (по углу к прицелу) берёт главную цель, а не встречных.
            double sideK = 0.9D;
            z.setPos(STAGE_X + 0.5D + lookX * k - lookZ * sideK, STAGE_Y, STAGE_Z + 0.5D + lookZ * k + lookX * sideK);
            // Шлем — чтобы днём лишние зомби не горели и не закрывали кадр огнём (съёмка 04.10).
            z.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            level.addFreshEntity(z);
        }
        // MURIM_CAPTURE_AURA=4 или 4d (демоническая) — аура цели для съёмки давления.
        String aura = System.getenv("MURIM_CAPTURE_AURA");
        if (aura != null && !aura.isBlank()) {
            String value = aura.trim();
            boolean demonicAura = value.endsWith("d");
            AuraService.set(target, new AuraState(
                    Integer.parseInt(demonicAura ? value.substring(0, value.length() - 1) : value), demonicAura));
        }

        // Камера сбоку для съёмки удара: со спины кисть и шлейф закрывает корпус, спереди
        // манекен встаёт перед камерой. Клиент смотрит глазами этой стойки
        // (DevCaptureHandler), сама она невидима и висит без гравитации.
        boolean footworkBack = !System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()
                && "back".equalsIgnoreCase(System.getProperty("murim.capture.camera", "back"));
        if (footworkBack || "front".equalsIgnoreCase(System.getProperty("murim.capture.camera", "back")) || "side".equalsIgnoreCase(System.getProperty("murim.capture.camera", "back"))) {
            // MURIM_CAPTURE_CAM_FWD — сдвинуть точку съёмки вперёд по взгляду (падающее дерево
            // и след лежат на 3–15 блоков впереди), MURIM_CAPTURE_CAM_Y и _PITCH — поднять камеру
            // и наклонить вниз: след на земле сбоку на уровне глаз не виден.
            double fwd = envDouble("MURIM_CAPTURE_CAM_FWD", dummyDistance * 0.5D);
            double midX = STAGE_X + 0.5D + lookX * fwd;
            double midZ = STAGE_Z + 0.5D + lookZ * fwd;
            // Правая сторона игрока: при взгляде (−sin, cos) правая рука смотрит в (−cos, −sin).
            // rain-09/full-19 film: orbit is relative to the target's attack axis, including
            // the stand's 28-degree target offset. Keep an orthonormal horizontal basis.
            double rightX = -lookZ;
            double rightZ = lookX;
            // MURIM_CAPTURE_CAM_DIST — отвести камеру: аура выше головы не влезала в кадр.
            double camDist = System.getenv("MURIM_CAPTURE_CAM_DIST") == null ? 3.2D
                    : Double.parseDouble(System.getenv("MURIM_CAPTURE_CAM_DIST").trim());
            // MURIM_CAPTURE_CAM_ORBIT — degrees to swing the camera from the right side toward the front
            // (45 = three-quarter view from front-right), still aimed at the same point.
            double orbit = Math.toRadians(envDouble("MURIM_CAPTURE_CAM_ORBIT", 0.0D));
            double offX = footworkBack ? -lookX : rightX * Math.cos(orbit) + lookX * Math.sin(orbit);
            double offZ = footworkBack ? -lookZ : rightZ * Math.cos(orbit) + lookZ * Math.sin(orbit);
            if (footworkBack) camDist = 6.5D;
            double camX = midX + offX * camDist;
            double camZ = midZ + offZ * camDist;
            net.minecraft.world.entity.decoration.ArmorStand stand =
                    new net.minecraft.world.entity.decoration.ArmorStand(level, camX, STAGE_Y - 0.45D + envDouble("MURIM_CAPTURE_CAM_Y", footworkBack ? .65D : 0.0D), camZ);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setCustomName(net.minecraft.network.chat.Component.literal(CAMERA_STAND_NAME));
            float standYaw = (float) Math.toDegrees(Math.atan2(-(midX - camX), midZ - camZ));
            stand.setYRot(standYaw);
            stand.setYHeadRot(standYaw);
            stand.setXRot((float) envDouble("MURIM_CAPTURE_CAM_PITCH", 6.0D));
            level.addFreshEntity(stand);
        }
        MurimMod.LOGGER.info("Съёмка: манекенов убрано {}, поставлен новый", removed);
    }

    private DevSetupEvents() {
    }

    private static double envDouble(String name, double fallback) {
        String raw = System.getenv(name);
        try {
            return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Все техники на полном освоении: стенду нужны приёмы, а не прогресс. */
    /** Stand obstacle block (see MURIM_CAPTURE_OBSTACLE). */
    private static void wallBlock(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.DEEPSLATE_BRICKS.defaultBlockState());
    }

    private static void learnAll(net.minecraft.world.entity.player.Player player) {
        var known = new java.util.HashMap<net.minecraft.resources.ResourceLocation,
                io.github.verycooltimo.murim.mastery.TechniqueProgress>();
        for (io.github.verycooltimo.murim.technique.TechniqueDefinition definition
                : io.github.verycooltimo.murim.technique.TechniqueLoader.all().values()) {
            // Default capture is full mastery (including footwork at ★12). STAR uses current mastery; the legacy capture variable preserves old table indices.
            String stars = System.getenv("MURIM_CAPTURE_STAR");
            String legacy = System.getenv("MURIM_CAPTURE_LAYER");
            int start = stars != null && !stars.isBlank() ? Integer.parseInt(stars.trim())
                    : legacy != null && !legacy.isBlank()
                    ? io.github.verycooltimo.murim.mastery.Arts.unlockStar(definition.id(), Integer.parseInt(legacy.trim()))
                    : definition.starCap();
            known.put(definition.id(), io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(
                    start, definition.starCap()));
        }
        var state = new io.github.verycooltimo.murim.mastery.MasteryState(known, 0, java.util.Set.of());
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY, state);
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            io.github.verycooltimo.murim.mastery.MasteryService.sync(serverPlayer);
        }
    }

    /**
     * Профиль у стены заданного ранга: запас равен стене, циркулирующая полна.
     *
     * <p>Стену и потолок циркулирующей считают по профилю УЖЕ с новым рангом: ёмкость растёт с рангом,
     * и по старому профилю запас оказывался ниже новой стены, а прорыв при {@code MURIM_CAPTURE_RANK >= 1}
     * не начинался (docs/design/research/meditation-pills-breakthroughs-1810.md §3.3).
     */
    static io.github.verycooltimo.murim.profile.DantianProfile wallProfile(
            io.github.verycooltimo.murim.profile.DantianProfile profile, int rank) {
        io.github.verycooltimo.murim.profile.DantianProfile ranked = profile.withRank(rank);
        return ranked.withPool(io.github.verycooltimo.murim.cultivation.Realm.wall(ranked))
                .withCirculating(ranked.maxCirculating());
    }

    /**
     * Echo scene for the stand: the art of {@code form} stands at star 5 with a reserve that carries it to star 6, its manual is in the main
     * hand and the moves of the last fight are remembered. Fights and their order are what the double repeats.
     */
    private static void primeEcho(net.minecraft.world.entity.player.Player player, String form, String movesList, long day) {
        primeInsight(player, form, 5, 0.4D, 220.0D, day);
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return;
        }
        net.minecraft.resources.ResourceLocation book = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                io.github.verycooltimo.murim.MurimMod.MODID, form);
        String[] moves = movesList == null || movesList.isBlank() ? new String[] {form} : movesList.split(",");
        for (String move : moves) {
            io.github.verycooltimo.murim.mastery.EchoMoves.record(serverPlayer, net.minecraft.resources.ResourceLocation
                    .fromNamespaceAndPath(io.github.verycooltimo.murim.MurimMod.MODID, move.trim()));
        }
        net.minecraft.world.item.ItemStack manual = new net.minecraft.world.item.ItemStack(
                io.github.verycooltimo.murim.registry.ModItems.TECHNIQUE_MANUAL.get());
        manual.set(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get(), book);
        serverPlayer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, manual);
    }

    /** Техника на слое {@code layer}, прошедшая долю {@code share} пути к следующему. */
    private static void primeInsight(net.minecraft.world.entity.player.Player player, String path, int layer,
                                     double share, double unprocessed, long day) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(io.github.verycooltimo.murim.MurimMod.MODID, path);
        io.github.verycooltimo.murim.technique.TechniqueDefinition definition =
                io.github.verycooltimo.murim.technique.TechniqueLoader.get(id);
        if (definition == null) {
            return;
        }
        io.github.verycooltimo.murim.mastery.MasteryState state =
                player.getData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY);
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.MASTERY, state.with(id,
                new io.github.verycooltimo.murim.mastery.TechniqueProgress(layer,
                        io.github.verycooltimo.murim.mastery.MasteryRules.need(layer, io.github.verycooltimo.murim.mastery.Arts.cap(id)) * share,
                        unprocessed, day, definition.starCap())));
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            io.github.verycooltimo.murim.mastery.MasteryService.sync(serverPlayer);
        }
    }
}
