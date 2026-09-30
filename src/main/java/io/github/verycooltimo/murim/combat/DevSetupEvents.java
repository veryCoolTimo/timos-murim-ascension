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

    private static final String CAPTURE_PROPERTY = "murim.capture";

    /** Съёмочная площадка: над кронами, чтобы в кадр не лезли ветки. */
    private static final int STAGE_X = 8;

    /** Имя невидимой стойки, глазами которой снимает камера «сбоку». */
    public static final String CAMERA_STAND_NAME = "murim_camera";
    private static final int STAGE_Y = 120;
    private static final int STAGE_Z = 8;
    private static final int PLATFORM_RADIUS = 12;

    /** Метка мишеней съёмки: по ней они снимаются перед следующим прогоном. */
    private static final String TARGET_TAG = "murim_capture_target";

    /** Через сколько тиков после входа доводить сцену. Хватает на загрузку чанков. */
    private static final int SETUP_DELAY_TICKS = 20;

    /** Обратный отсчёт до отложенной доводки сцены; ноль означает «делать нечего». */
    private static int pendingSetup;

    @SubscribeEvent
    static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // Два барьера, а не один. Системного свойства мало: класс уезжает в релизный jar,
        // и сервер, запущенный со скопированным набором JVM-аргументов, стирал бы предмет
        // в руке и перестраивал мир каждому входящему игроку.
        if (net.neoforged.fml.loading.FMLEnvironment.production
                || !Boolean.getBoolean(CAPTURE_PROPERTY)) {
            return;
        }
        // Меч нужен для съёмки техник, но в церемонии он торчит из сложенных рук и
        // перекрывает то самое тело, ради которого сцена и снимается.
        boolean ceremony = "awakening".equals(System.getProperty("murim.capture.technique"))
                || "meditation".equals(System.getProperty("murim.capture.technique"));
        event.getEntity().setItemInHand(InteractionHand.MAIN_HAND,
                ceremony ? ItemStack.EMPTY : new ItemStack(Items.NETHERITE_SWORD));

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
                        Blocks.SMOOTH_STONE.defaultBlockState());
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
        for (double dx : new double[] {-1.6D, 1.6D}) {
            net.minecraft.world.entity.decoration.ArmorStand target =
                    new net.minecraft.world.entity.decoration.ArmorStand(
                            serverLevel, STAGE_X + 0.5D + dx, STAGE_Y, STAGE_Z + 6.0D);
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
            meditating.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                    io.github.verycooltimo.murim.profile.DantianProfile.INITIAL);
            meditating.setData(io.github.verycooltimo.murim.registry.ModAttachments.CULTIVATION,
                    io.github.verycooltimo.murim.cultivation.CultivationState.NONE
                            .withMethod(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                    io.github.verycooltimo.murim.MurimMod.MODID, "six_harmonies"))
                            .withBeats(beats));
            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(meditating);
            // Искажение ци из прошлого прогона не должно попадать в кадры следующего.
            meditating.removeAllEffects();
            meditating.setHealth(meditating.getMaxHealth());
            meditating.getFoodData().setFoodLevel(20);
            return;
        }
        // Съёмка САМОЙ церемонии требует обратного: даньтянь должен быть НЕ создан,
        // иначе церемония откажется начинаться, и в кадры попадёт неподвижный игрок.
        // Молчаливый отказ выглядел бы как поломка визуала, поэтому режим разделён явно.
        if ("awakening".equals(System.getProperty("murim.capture.technique"))) {
            event.getEntity().setData(
                    io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                    io.github.verycooltimo.murim.profile.DantianProfile.INITIAL);
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer fresh) {
                io.github.verycooltimo.murim.profile.ProfileNetwork.sync(fresh);
            }
            return;
        }
        // Техники теперь стоят ци и требуют сформированного центра. Без этого съёмочный
        // стенд молча перестал бы запускать техники, и это выглядело бы как поломка визуала.
        io.github.verycooltimo.murim.profile.DantianProfile awakened =
                io.github.verycooltimo.murim.profile.DantianProfile.INITIAL
                        .withTags("clear", "debug")
                        .withAxes(60.0D, 0.8D, 0.8D);
        event.getEntity().setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                awakened.withPool(500.0D).withCirculating(awakened.maxCirculating()));
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            io.github.verycooltimo.murim.profile.ProfileNetwork.sync(serverPlayer);
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
                STAGE_X + 32.0D, STAGE_Y + 8.0D, STAGE_Z + 32.0D);
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
        double aim = yaw + Math.toRadians(28.0D);
        double lookX = -Math.sin(aim);
        double lookZ = Math.cos(aim);
        // Сбоку манекен стоит на вытянутую руку: иначе ладонь до него не достаёт, и всплеск
        // «под ладонью» висит в воздухе в метре от кисти (кадры 2026-09-24).
        double dummyDistance = "side".equalsIgnoreCase(
                System.getProperty("murim.capture.camera", "back")) ? 1.15D : 1.7D;
        dummy.setPos(STAGE_X + 0.5D + lookX * dummyDistance, STAGE_Y,
                     STAGE_Z + 0.5D + lookZ * dummyDistance);
        level.addFreshEntity(dummy);

        // Камера сбоку для съёмки удара: со спины кисть и шлейф закрывает корпус, спереди
        // манекен встаёт перед камерой. Клиент смотрит глазами этой стойки
        // (DevCaptureHandler), сама она невидима и висит без гравитации.
        if ("side".equalsIgnoreCase(System.getProperty("murim.capture.camera", "back"))) {
            double midX = STAGE_X + 0.5D + lookX * dummyDistance * 0.5D;
            double midZ = STAGE_Z + 0.5D + lookZ * dummyDistance * 0.5D;
            // Правая сторона игрока: при взгляде (−sin, cos) правая рука смотрит в (−cos, −sin).
            double rightX = -Math.cos(yaw);
            double rightZ = -Math.sin(yaw);
            double camX = midX + rightX * 3.2D;
            double camZ = midZ + rightZ * 3.2D;
            net.minecraft.world.entity.decoration.ArmorStand stand =
                    new net.minecraft.world.entity.decoration.ArmorStand(level, camX, STAGE_Y - 0.45D, camZ);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setCustomName(net.minecraft.network.chat.Component.literal(CAMERA_STAND_NAME));
            float standYaw = (float) Math.toDegrees(Math.atan2(-(midX - camX), midZ - camZ));
            stand.setYRot(standYaw);
            stand.setYHeadRot(standYaw);
            stand.setXRot(6.0F);
            level.addFreshEntity(stand);
        }
        MurimMod.LOGGER.info("Съёмка: манекенов убрано {}, поставлен новый", removed);
    }

    private DevSetupEvents() {
    }
}
