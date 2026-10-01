package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.network.MeditationInputPayload;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Клиентская сторона медитации: клавиши, поза лотоса, блокировка движения, ход сцены.
 *
 * <p>Клиент присылает только нажатия; время, окно удержания и засчитанные тики считает
 * сервер (docs/design/19 §3а). Здесь живёт последнее присланное состояние и то, что
 * нужно только для картинки: локальный счёт тиков между пакетами, послесвечение
 * законченной сессии и сцена рождения семени.
 */
@EventBusSubscriber(modid = io.github.verycooltimo.murim.MurimMod.MODID, value = Dist.CLIENT)
public final class ClientMeditationState {

    /** Чем закончилась последняя сессия — для короткого послесвечения в мире. */
    public enum Aftermath {
        NONE,
        /** Ци рассеялась: первый такт или кольцо не удержано. */
        SCATTER,
        /** Кольцо удержано и осело внизу живота. */
        SETTLE,
        /** Искажение ци: кольцо лопнуло, всё вспыхивает красным. */
        BACKLASH
    }

    /** Сцена «внутреннего взгляда» при рождении семени: 12 секунд (автор: 10–15). */
    public static final int SEED_SCENE_TICKS = 240;

    /** Сколько длится послесвечение законченной сессии. */
    public static final int AFTERMATH_TICKS = 40;

    /** Искажение ци: красная вспышка и осколки кольца. */
    public static final int BACKLASH_TICKS = 50;

    private static final SyncMeditationPayload IDLE =
            new SyncMeditationPayload(false, 0, 0, SyncMeditationPayload.Event.NONE,
                    SyncMeditationPayload.Ring.NONE, -1, 0);

    /** Прошлый снимок кольца: между пакетами сервера кольцо интерполируется. */
    private static SyncMeditationPayload.Ring previousRing = SyncMeditationPayload.Ring.NONE;

    private static SyncMeditationPayload state = IDLE;

    /** Тики сессии, досчитанные локально: сервер шлёт состояние раз в пять тиков. */
    private static int localTicks;

    /** Просьба встать уже отправлена: повтор до ответа сервера сел бы обратно. */
    private static boolean standSent;
    /** Клавиши выхода были отпущены после посадки — следующее нажатие поднимает. */
    private static boolean exitArmed;

    /** Последнее, что клиент сообщил серверу про клавишу удержания. */
    private static boolean holdSent;

    private static Aftermath aftermath = Aftermath.NONE;
    private static int aftermathTicks;
    /** Такт, на котором шла закончившаяся сессия: послесвечение зависит от него. */
    private static int aftermathBeats;

    private static int seedSceneTicks;

    /** Тики сцены прорыва, досчитанные локально; {@code -1} — прорыва нет. */
    private static int breakthroughTicks = -1;

    /** Выход ауры после прорыва: 3 секунды, титр нового ранга. */
    public static final int RANK_UP_TICKS = 80;
    private static int rankUpTicks;
    private static int rankUpRank;
    private static CameraType restoreCamera;
    /** Был ли интерфейс скрыт игроком до сцены: возвращаем как было. */
    private static Boolean restoreHideGui;

    public static SyncMeditationPayload state() {
        return state;
    }

    /** Мини-игра идёт прямо сейчас. */
    public static boolean minigame() {
        return state.active() && state.ring().active();
    }

    /** Кольцо, сглаженное между тиками сервера. */
    public static SyncMeditationPayload.Ring ring(float partial) {
        SyncMeditationPayload.Ring now = state.ring();
        SyncMeditationPayload.Ring was = previousRing.active() ? previousRing : now;
        float k = net.minecraft.util.Mth.clamp(partial, 0.0F, 1.0F);
        return new SyncMeditationPayload.Ring(now.active(),
                net.minecraft.util.Mth.lerp(k, was.radius(), now.radius()),
                net.minecraft.util.Mth.lerp(k, was.centre(), now.centre()),
                net.minecraft.util.Mth.lerp(k, was.halfWidth(), now.halfWidth()),
                now.stability(), now.strain());
    }

    /** Тик сессии для картинки: между пакетами сервера досчитывается локально. */
    public static int sessionTicks() {
        return localTicks;
    }

    public static boolean holding() {
        return holdSent;
    }

    public static Aftermath aftermath() {
        return aftermath;
    }

    public static int aftermathTicks() {
        return aftermathTicks;
    }

    public static int aftermathBeats() {
        return aftermathBeats;
    }

    /** Сколько тиков сцены семени уже прошло, или -1, если сцены нет. */
    public static int seedSceneAge() {
        return seedSceneTicks > 0 ? SEED_SCENE_TICKS - seedSceneTicks : -1;
    }

    /** Сколько тиков сцены прорыва прошло, или -1. */
    public static int breakthroughAge() {
        return breakthroughTicks;
    }

    /** Сколько тиков выхода ауры прошло после прорыва, или -1. */
    public static int rankUpAge() {
        return rankUpTicks > 0 ? RANK_UP_TICKS - rankUpTicks : -1;
    }

    public static int rankUpRank() {
        return rankUpRank;
    }

    /** Тело неподвижно: идёт сессия или сцена семени. */
    public static boolean still() {
        return state.active() || seedSceneTicks > 0 || rankUpTicks > 0;
    }

    public static void accept(SyncMeditationPayload payload) {
        SyncMeditationPayload previous = state;
        previousRing = previous.ring();
        state = payload;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        // Итог такта при продолжающемся сидении: новая сессия началась без вставания.
        if (payload.active() && previous.active()
                && (payload.event() == SyncMeditationPayload.Event.SCATTER
                    || payload.event() == SyncMeditationPayload.Event.SETTLE)) {
            aftermath = payload.event() == SyncMeditationPayload.Event.SETTLE
                    ? Aftermath.SETTLE : Aftermath.SCATTER;
            aftermathBeats = previous.beats();
            aftermathTicks = AFTERMATH_TICKS;
            localTicks = payload.ticks();
        } else if (payload.active() && !previous.active()) {
            localTicks = payload.ticks();
            aftermath = Aftermath.NONE;
            if (player != null && seedSceneTicks <= 0) {
                MurimPlayerAnimations.play(player, MurimPlayerAnimations.LOTUS);
            }
        } else if (payload.active()) {
            // Сервер главнее: локальный счёт только заполняет промежутки между пакетами.
            localTicks = Math.max(localTicks, payload.ticks());
        }

        // Прорыв идёт внутри сидения: сцена живёт, пока сервер присылает её тики.
        if (payload.active() && payload.breakthrough() >= 0) {
            if (breakthroughTicks < 0) {
                CameraShakeHandler.request(0.25F);
            }
            breakthroughTicks = Math.max(breakthroughTicks, payload.breakthrough());
        } else {
            breakthroughTicks = -1;
        }
        if (payload.event() == SyncMeditationPayload.Event.RANK_UP) {
            rankUpTicks = RANK_UP_TICKS;
            rankUpRank = payload.rank();
            // Выход ауры — главный удар сцены: толчок сильнее, чем у семени.
            CameraShakeHandler.request(0.7F);
        }

        if (!payload.active() && previous.active()) {
            holdSent = false;
            if (payload.event() == SyncMeditationPayload.Event.SEED) {
                startSeedScene(minecraft);
            } else if (payload.event() == SyncMeditationPayload.Event.RANK_UP) {
                // Поза держится до конца выхода ауры — её снимает тик клиента.
                aftermath = Aftermath.NONE;
            } else if (payload.event() == SyncMeditationPayload.Event.BACKLASH
                    || payload.event() == SyncMeditationPayload.Event.BROKEN) {
                aftermath = Aftermath.BACKLASH;
                aftermathBeats = previous.beats();
                aftermathTicks = BACKLASH_TICKS;
                CameraShakeHandler.request(0.8F);
                if (player != null) {
                    MurimPlayerAnimations.stop(player);
                }
            } else {
                // Такт вырос — кольцо осело; иначе ци рассеялась (первый такт по замыслу,
                // не удержанное кольцо или прерванная сессия).
                boolean settled = previous.beats() == 1 && payload.beats() == 2;
                aftermath = settled ? Aftermath.SETTLE : Aftermath.SCATTER;
                aftermathBeats = previous.beats();
                aftermathTicks = AFTERMATH_TICKS;
                // Встал сам, сдвинулся или получил удар — ци рассеивается.
                if (player != null) {
                    MurimPlayerAnimations.stop(player);
                }
            }
        }
    }

    private static void startSeedScene(Minecraft minecraft) {
        seedSceneTicks = SEED_SCENE_TICKS;
        // Взрыв семени отдаётся в камеру коротким толчком (сила — из настроек игрока).
        CameraShakeHandler.request(0.45F);
    }

    private static void endSeedScene(Minecraft minecraft) {
        seedSceneTicks = 0;
        if (minecraft.player != null && !state.active()) {
            MurimPlayerAnimations.stop(minecraft.player);
        }
    }

    /**
     * Создание даньтяня снимается крупным планом (замечание автора 30.09: «главное, что
     * должен видеть игрок крупным планом, — его персонаж»): все три такта и сцена семени.
     * Камера спереди — кольцо, жилы и семя на животе со спины не видны.
     */
    public static boolean cinematic() {
        return (state.active() && state.beats() < 3) || seedSceneTicks > 0
                || breakthroughTicks >= 0 || rankUpTicks > 0;
    }

    /** Насколько камера уже подошла: плавный заход и выход за секунду. */
    private static int cinematicTicks;

    /** Дистанция камеры крупного плана вместо ванильных четырёх блоков. */
    private static final float CLOSE_DISTANCE = 1.6F;

    /**
     * Наклон камеры вниз, градусы. Камера по построению смотрит в глаза персонажа, и
     * при пологом угле тело уходило к низу кадра под хотбар (кадры 30.09). Крутой угол
     * с близкой дистанцией подтягивает тело к центру.
     */
    private static final float CLOSE_PITCH = 18.0F;

    /**
     * Высота глаз сидящего в лотосе, блоки. Камера нацелена в точку глаз, а она
     * считалась для стоящего игрока — на 0.7 блока выше головы сидящей модели, и центр
     * кадра смотрел в пустоту над головой (кадры 30.09).
     */
    private static final float SEATED_EYE = 0.95F;

    /**
     * Точка глаз опускается только у ЛОКАЛЬНОГО игрока на клиенте: серверный хитбокс
     * и чужие клиенты не затрагиваются, а неподвижному телу клиентская коробка не важна.
     * API: reference/neoforge-src/net/neoforged/neoforge/event/entity/EntityEvent.java#Size
     */
    @SubscribeEvent
    static void onEntitySize(net.neoforged.neoforge.event.entity.EntityEvent.Size event) {
        if (seatedEye && event.getEntity() == Minecraft.getInstance().player) {
            event.setNewSize(event.getNewSize().withEyeHeight(SEATED_EYE));
        }
    }

    private static boolean seatedEye;

    private static void updateCamera(Minecraft minecraft) {
        boolean wanted = cinematic();
        // Сидящий взгляд — во всей медитации, и после даньтяня тоже (замечание автора
        // 30.09: «игрок должен сидеть, а не стоять»), а не только в крупном плане.
        boolean seated = state.active() || seedSceneTicks > 0 || rankUpTicks > 0;
        if (seated != seatedEye && minecraft.player != null) {
            seatedEye = seated;
            minecraft.player.refreshDimensions();
        }
        if (wanted && restoreCamera == null) {
            restoreCamera = minecraft.options.getCameraType();
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            // Ванильный интерфейс прячется на всё создание даньтяня: хотбар и сердца
            // ложились на живот, где кольцо. Свой слой мода при этом рисуется.
            restoreHideGui = minecraft.options.hideGui;
            minecraft.options.hideGui = true;
        } else if (!wanted && restoreCamera != null) {
            minecraft.options.setCameraType(restoreCamera);
            restoreCamera = null;
            if (restoreHideGui != null) {
                minecraft.options.hideGui = restoreHideGui;
                restoreHideGui = null;
            }
        }
        cinematicTicks = wanted ? Math.min(20, cinematicTicks + 1) : 0;
    }

    private static float closeness(double partial) {
        if (restoreCamera == null) {
            return 0.0F;
        }
        float k = net.minecraft.util.Mth.clamp((cinematicTicks + (float) partial) / 20.0F, 0.0F, 1.0F);
        return k * k * (3.0F - 2.0F * k);
    }

    /** API: reference/neoforge-src/.../client/event/CalculateDetachedCameraDistanceEvent.java */
    @SubscribeEvent
    static void onCameraDistance(net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent event) {
        float k = closeness(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        if (k > 0.0F) {
            event.setDistance(net.minecraft.util.Mth.lerp(k, event.getDistance(),
                    Math.min(event.getDistance(), CLOSE_DISTANCE)));
        }
    }

    /**
     * Наклон ставится ДО того, как камера отодвигается вдоль взгляда
     * (reference/minecraft-src/net/minecraft/client/Camera.java#setup), поэтому он
     * поднимает камеру по орбите, а не просто опускает взгляд. Спереди знак наклона
     * инвертируется ванилью, отсюда минус.
     */
    /** Поворот тела, зафиксированный при посадке: во время ритуала мышь его не крутит. */
    private static Float lockedYaw;

    @SubscribeEvent
    static void onCameraAngles(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        // Мышь поворачивает игрока прямо перед отрисовкой кадра (Minecraft#runTick:
        // handleAccumulatedMovement, затем gameRenderer.render), поэтому поворот здесь
        // возвращается каждый кадр без дрожания. Замечание автора 30.09: персонаж
        // крутился, а жилы оставались на месте.
        LocalPlayer me = Minecraft.getInstance().player;
        if (cinematic() && me != null) {
            if (lockedYaw == null) {
                lockedYaw = me.getYRot();
            }
            float yaw = lockedYaw;
            me.setYRot(yaw);
            me.yRotO = yaw;
            me.yBodyRot = me.yBodyRotO = yaw;
            me.yHeadRot = me.yHeadRotO = yaw;
            me.setXRot(0.0F);
            me.xRotO = 0.0F;
            // Спереди ваниль разворачивает камеру на 180°, так что угол — как у игрока.
            event.setYaw(yaw);
        } else {
            lockedYaw = null;
        }
        float k = closeness(event.getPartialTick());
        if (k > 0.0F) {
            event.setPitch(net.minecraft.util.Mth.lerp(k, event.getPitch(), -CLOSE_PITCH));
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        updateCamera(minecraft);
        if (minecraft.isPaused()) {
            return;
        }
        if (state.active()) {
            localTicks++;
        }
        if (aftermathTicks > 0 && --aftermathTicks == 0) {
            aftermath = Aftermath.NONE;
        }
        if (seedSceneTicks > 0 && --seedSceneTicks == 0) {
            endSeedScene(minecraft);
        }
        if (breakthroughTicks >= 0 && state.active()) {
            breakthroughTicks++;
        }
        if (rankUpTicks > 0 && --rankUpTicks == 0 && !state.active()) {
            MurimPlayerAnimations.stop(minecraft.player);
        }
        if (minecraft.screen != null) {
            return;
        }
        while (ModKeyMappings.LOADOUT.consumeClick()) {
            minecraft.setScreen(new LoadoutScreen());
        }
        while (ModKeyMappings.MEDITATION.consumeClick()) {
            if (seedSceneTicks > 0) {
                continue;
            }
            // На корточках — «брать всё»: быстрее, но мутнее.
            boolean filter = !minecraft.player.isShiftKeyDown();
            PacketDistributor.sendToServer(new MeditationInputPayload(
                    MeditationInputPayload.Action.TOGGLE, filter));
        }
        // Встать можно не только на G: Shift и клавиши ходьбы — привычный жест «слезть»
        // (замечание автора 30.09: «не можешь выйти на шифт»). Ввод движения при этом
        // погашен ниже, поэтому смотрим на сами клавиши, а не на движение.
        // Нужно именно НОВОЕ нажатие: садятся и на корточках (Shift + G — «брать всё»),
        // и зажатый при посадке Shift не должен тут же поднимать.
        boolean exitKeys = minecraft.options.keyShift.isDown() || minecraft.options.keyUp.isDown()
                || minecraft.options.keyDown.isDown() || minecraft.options.keyLeft.isDown()
                || minecraft.options.keyRight.isDown();
        if (!state.active()) {
            standSent = false;
            exitArmed = false;
        } else if (!exitKeys) {
            exitArmed = true;
        } else if (exitArmed && !standSent) {
            standSent = true;
            PacketDistributor.sendToServer(new MeditationInputPayload(
                    MeditationInputPayload.Action.TOGGLE, true));
        }
        // Кольцо держится клавишей прыжка: во время медитации она всё равно заблокирована.
        boolean holding = state.active() && minecraft.options.keyJump.isDown();
        if (holding != holdSent) {
            holdSent = holding;
            PacketDistributor.sendToServer(new MeditationInputPayload(holding
                    ? MeditationInputPayload.Action.HOLD_ON : MeditationInputPayload.Action.HOLD_OFF, true));
        }
    }

    /** Во время медитации и сцены семени тело неподвижно: ввод гасится до отправки серверу. */
    @SubscribeEvent
    static void onMovementInput(MovementInputUpdateEvent event) {
        if (!still()) {
            return;
        }
        var input = event.getInput();
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    /** Сброс при выходе из мира: иначе в новом мире тело осталось бы «сидящим». */
    public static void reset() {
        Minecraft minecraft = Minecraft.getInstance();
        if (restoreHideGui != null) {
            minecraft.options.hideGui = restoreHideGui;
            restoreHideGui = null;
        }
        if (restoreCamera != null) {
            minecraft.options.setCameraType(restoreCamera);
            restoreCamera = null;
        }
        seatedEye = false;
        lockedYaw = null;
        state = IDLE;
        previousRing = SyncMeditationPayload.Ring.NONE;
        localTicks = 0;
        holdSent = false;
        aftermath = Aftermath.NONE;
        aftermathTicks = 0;
        seedSceneTicks = 0;
        breakthroughTicks = -1;
        rankUpTicks = 0;
    }

    private ClientMeditationState() {
    }
}
