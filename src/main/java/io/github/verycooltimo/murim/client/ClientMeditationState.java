package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.client.camera.BreakthroughCamera;
import io.github.verycooltimo.murim.client.camera.FlowCamera;
import io.github.verycooltimo.murim.client.flow.FlowClient;
import io.github.verycooltimo.murim.client.vfx.preset.DantianFlow;
import io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath;
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

    /** Quiet live-world seed finale, 2.6 seconds (dantian-flow-lab-r2). */
    public static final int SEED_SCENE_TICKS = DantianFlowMath.SEED_TICKS;

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
    private static final DantianFlow creationFlow = new DantianFlow();

    public static DantianFlow creationFlow() {
        return creationFlow;
    }

    /** Тики сцены прорыва, досчитанные локально; {@code -1} — прорыва нет. */
    private static int breakthroughTicks = -1;
    // Client-owned Peak clock survives the rankup/tail hand-off (round-9 lab timing).
    private static final io.github.verycooltimo.murim.client.vfx.bt.BtSceneClock peakClock = new io.github.verycooltimo.murim.client.vfx.bt.BtSceneClock();
    public static int peakSceneAge() { return peakClock.age(); }

    /** Per-rank release window: pose and camera remain held until the lab tail ends. */
    private static int rankUpLength = 80;

    public static int rankUpLength() { return rankUpLength; }
    private static int rankUpTicks;
    private static int rankUpRank;
    /** The legacy afterglow timer after a rank-up (lab scenes own their visual tails), and the rank it glows in. */
    private static int afterglowTicks;
    /** The guardian took a blow: a short clang on the screen side of the scene. */
    private static int guardTicks;
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

    /**
     * Сколько тиков самой сцены прорыва прошло, или -1. Предупреждение перед ней
     * ({@link #warningAge}) сюда не входит.
     */
    public static int breakthroughAge() {
        return breakthroughTicks >= io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS ? breakthroughTicks - io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS : -1;
    }

    /** Сколько тиков предупреждения перед прорывом прошло, или -1. */
    public static int warningAge() {
        return breakthroughTicks >= 0 && breakthroughTicks < io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS ? breakthroughTicks : -1;
    }

    /**
     * Камера прорыва: резкая склейка на крупный план спереди в момент начала сцены
     * (решение автора 01.10: «не плавная камера, а резкая»), после предупреждения.
     */
    private static boolean orbit() {
        return breakthroughTicks >= io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS || rankUpTicks > 0;
    }

    /** Сколько тиков выхода ауры прошло после прорыва, или -1. */
    public static int rankUpAge() {
        return rankUpTicks > 0 ? rankUpLength - rankUpTicks : -1;
    }

    /** Ticks of the 30-second afterglow gone, or -1 when there is none. */
    public static int afterglowAge() {
        int age = afterglowTicks > 0 ? io.github.verycooltimo.murim.cultivation.BreakthroughScript.AFTERGLOW_TICKS - afterglowTicks : -1;
        // The lab tail ends with the scene; only the stains on the ground stay.
        return age >= rankUpLength + 20 ? -1 : age;
    }



    /** Ticks since the guardian took a blow, or -1. */
    public static int guardAge() {
        return guardTicks > 0 ? 10 - guardTicks : -1;
    }

    /** The omen and the scene are on: the birds and the wind go quiet (author 18.10). */
    public static boolean quiet() {
        return breakthroughTicks >= 0 && state.active();
    }

    /**
     * The 0.4 s of silence before the impact: the scene's own loop is cut for the last eight ticks (the server cuts the rest,
     * {@code BreakthroughWorld#silence}).
     */
    public static boolean silence() {
        if (!quiet()) {
            return false;
        }
        int scene = io.github.verycooltimo.murim.cultivation.Realm.breakthroughTicks(state.rank() + 1);
        return breakthroughTicks >= io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS
                + io.github.verycooltimo.murim.cultivation.BreakthroughScript.silenceStart(scene);
    }

    /** Volume of the sitting's own breath loop: low in the omen, gone in the silence before the impact. */
    public static float loopVolume() {
        if (!quiet()) {
            return 0.6F;
        }
        return silence() ? 0.0F : breakthroughTicks < io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS ? 0.3F : 0.45F;
    }

    /**
     * Пятна примесей на полу после очищения (замечание автора 01.10: «нету никаких пятен
     * после этого»): остаются там, где сидел игрок, полминуты и медленно тают.
     */
    public static final int STAIN_TICKS = 600;
    private static int stainTicks;
    private static net.minecraft.world.phys.Vec3 stainOrigin;

    public static int stainAge() {
        return stainTicks > 0 ? STAIN_TICKS - stainTicks : -1;
    }

    private static net.minecraft.world.phys.Vec3 stainRight = new net.minecraft.world.phys.Vec3(1,0,0);
    private static net.minecraft.world.phys.Vec3 stainNear = new net.minecraft.world.phys.Vec3(0,0,1);
    private static double stainScale = 1;
    public static net.minecraft.world.phys.Vec3 stainRight() { return stainRight; }
    public static net.minecraft.world.phys.Vec3 stainNear() { return stainNear; }
    public static double stainScale() { return stainScale; }
    public static void rememberStainFrame(net.minecraft.world.phys.Vec3 origin, net.minecraft.world.phys.Vec3 right,
                                          net.minecraft.world.phys.Vec3 near, double scale) {
        stainOrigin=origin;stainRight=right;stainNear=near;stainScale=scale;
    }
    public static net.minecraft.world.phys.Vec3 stainOrigin() {
        return stainOrigin;
    }

    public static int rankUpRank() {
        return rankUpRank;
    }

    /** В какой ранг идёт прорыв: от него зависит сцена (docs/design/19 §3е). */
    public static int breakthroughTarget() {
        return breakthroughTicks >= 0 ? state.rank() + 1 : rankUpRank;
    }

    /** Тело неподвижно: идёт сессия или сцена семени. */
    public static boolean still() {
        return io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive() || state.active() || seedSceneTicks > 0 || rankUpTicks > 0;
    }

    public static void accept(SyncMeditationPayload payload) {
        SyncMeditationPayload previous = state;
        previousRing = previous.ring();
        state = payload;
        peakClock.sync(payload.breakthrough() - io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS,
                payload.active() && payload.rank() == 3 && payload.breakthrough() >= 0,
                payload.rank() == 4 && (payload.event() == SyncMeditationPayload.Event.RANK_UP || rankUpTicks > 0));
        if (payload.active() && !previous.active()) {
            blockedHintSent = false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        // Итог такта при продолжающемся сидении: новая сессия началась без вставания.
        if (payload.active() && previous.active()
                && (payload.event() == SyncMeditationPayload.Event.SCATTER
                    || payload.event() == SyncMeditationPayload.Event.ECHO_DONE
                    || payload.event() == SyncMeditationPayload.Event.SETTLE)) {
            aftermath = payload.event() == SyncMeditationPayload.Event.SETTLE
                    || payload.event() == SyncMeditationPayload.Event.ECHO_DONE
                    ? Aftermath.SETTLE : Aftermath.SCATTER;
            aftermathBeats = previous.beats();
            aftermathTicks = AFTERMATH_TICKS;
            localTicks = payload.ticks();
            if (payload.event() == SyncMeditationPayload.Event.SETTLE && player != null) {
                // Цикл ци замкнулся — мягкий низкий тон.
                io.github.verycooltimo.murim.client.Sfx.play(player, io.github.verycooltimo.murim.registry.ModSounds.MEDITATION_CYCLE, 0.8F, 1.0F);
            }
        } else if (payload.active() && !previous.active()) {
            localTicks = payload.ticks();
            MeditationHud.markSessionStart();
            aftermath = Aftermath.NONE;
            if (player != null && seedSceneTicks <= 0) {
                MurimPlayerAnimations.play(player, MurimPlayerAnimations.LOTUS);
                breathDelay = BREATH_AFTER_TICKS;
            }
            if (player != null) {
                // Пока сидит — тихое дыхание ци (петля, гаснет, когда встал).
                io.github.verycooltimo.murim.client.Sfx.loop("meditation", player, io.github.verycooltimo.murim.registry.ModSounds.MEDITATION_LOOP, ClientMeditationState::loopVolume, 1.0F, () -> state.active(), 20);
            }
        } else if (payload.active()) {
            // Сервер главнее: локальный счёт только заполняет промежутки между пакетами.
            localTicks = Math.max(localTicks, payload.ticks());
        }

        // Прорыв идёт внутри сидения: сцена живёт, пока сервер присылает её тики.
        if (payload.active() && payload.breakthrough() >= 0) {
            if(previous.breakthrough()<0)io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.reset();
            breakthroughTicks = Math.max(breakthroughTicks, payload.breakthrough());
        } else {
            breakthroughTicks = -1;
        }
        if (payload.event() == SyncMeditationPayload.Event.GUARDED) {
            // A guardian took the first blow: a clang, the scene goes on.
            guardTicks = 10;
            CameraShakeHandler.request(0.25F);
        }
        if (previous.breakthrough() >= 0 && payload.breakthrough() < 0
                && payload.rank() > previous.rank() && payload.rank() == 4) {
            io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.trigger(4);
        }
        if (payload.event() == SyncMeditationPayload.Event.RANK_UP) {
            io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.trigger(payload.rank());
            rankUpLength = io.github.verycooltimo.murim.cultivation.Realm.releaseTicks(payload.rank());
            rankUpTicks = rankUpLength;
            rankUpRank = payload.rank();
            afterglowTicks = io.github.verycooltimo.murim.cultivation.BreakthroughScript.AFTERGLOW_TICKS;
            // Пятна — только после очищения (третий ранг): примеси выходят именно там.
            if (player != null && payload.rank() == 1) {
                stainOrigin = player.position();
                stainTicks = STAIN_TICKS;
            }
            // Выход ауры — главный удар сцены: толчок сильнее, чем у семени.
            // Higher scenes schedule their own impulse (First waits for its dome to reach the edges).
            if(payload.rank()<=2)CameraShakeHandler.request(payload.rank()==1?.32F:.7F,12);
        }

        if (!payload.active() && previous.active()) {
            holdSent = false;
            // Итог сессии одной строкой (docs/design/19b §4): сколько накоплено.
            if (previous.beats() >= 3 && payload.event() == SyncMeditationPayload.Event.NONE
                    && MeditationHud.sessionGain() >= 0.05D) {
                minecraft.gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable(
                        "murim.meditation.session_done", String.format(java.util.Locale.ROOT, "%.1f",
                                MeditationHud.sessionGain())), false);
            }
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
                    MurimPlayerAnimations.stopBlended(player, 6);
                }
            }
        }
    }

    private static void startSeedScene(Minecraft minecraft) {
        seedSceneTicks = SEED_SCENE_TICKS;
        CameraShakeHandler.request(0.10F, 5);
    }

    private static void endSeedScene(Minecraft minecraft) {
        seedSceneTicks = 0;
        if (minecraft.player != null && !state.active()) {
            MurimPlayerAnimations.stopBlended(minecraft.player, 6);
        }
    }

    /**
     * Создание даньтяня снимается крупным планом (замечание автора 30.09: «главное, что
     * должен видеть игрок крупным планом, — его персонаж»): все три такта и сцена семени.
     * Камера спереди — кольцо, жилы и семя на животе со спины не видны.
     */
    public static boolean cinematic() {
        return io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive()
                || (state.active() && state.beats() < 3) || seedSceneTicks > 0 || orbit() && BreakthroughCamera.enabled()
                || BreakthroughCamera.active()
                || FlowClient.active() || ClientPillState.absorbing() || ClientPillState.finaleAge() >= 0
                // Отдача удара: крупный план держится, пока красный выброс и кровь читаются.
                || ClientPillState.deviationAge() >= 0;
    }

    /**
     * Крупный план только из-за пилюли (поглощение и её финал): интерфейс остаётся. Камера режиссёра прорыва
     * (в том числе её возврат к камере игрока) — не пилюля: интерфейс спрятан до конца возврата.
     */
    private static boolean onlyPillScene() {
        return !((state.active() && state.beats() < 3) || seedSceneTicks > 0 || orbit() || BreakthroughCamera.active());
    }

    /**
     * The body stays put in a breakthrough even when the player turned the directed camera off: the mouse must not
     * turn the seated body, whatever camera the player looks through.
     */
    private static boolean holdsBody() {
        return cinematic() || orbit();
    }

    /** Body yaw of the running scene (the director measures shots from it); the fallback until the lock is set. */
    public static float sceneYaw(float fallback) {
        return lockedYaw != null ? lockedYaw : fallback;
    }

    /** Насколько камера уже подошла: плавный заход и выход за секунду. */
    private static int cinematicTicks;

    /** Какую долю подъёма проходит камера вслед за телом. */
    private static final float EYE_FOLLOW = 0.55F;

    /** Дистанция камеры крупного плана вместо ванильных четырёх блоков. */
    private static final float CLOSE_DISTANCE = 1.6F;
    /**
     * Поглощение пилюли снимается ближе: макет автора (1_pill_nodes_v2) держит торс и узлы на две трети кадра, а не на
     * его пятой части. Подлёт и финалы остаются на обычном крупном плане: ленты вокруг тела должны влезть.
     */
    private static final float PILL_DISTANCE = 1.55F;
    /** Камера при поглощении смотрит на грудь, а не в глаза: тогда торс с узлами в середине кадра, а не у хотбара. */
    private static final float PILL_EYE_DROP = -0.3F;

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

    /** The meditation framing, shared with the creation director's first-person/missed-ray fallback. */
    public static io.github.verycooltimo.murim.client.camera.ShotPose closeUpPose() {
        return new io.github.verycooltimo.murim.client.camera.ShotPose(
                0, CLOSE_PITCH, CLOSE_DISTANCE, SEATED_EYE, 0, 1);
    }


    /**
     * Точка глаз опускается только у ЛОКАЛЬНОГО игрока на клиенте: серверный хитбокс
     * и чужие клиенты не затрагиваются, а неподвижному телу клиентская коробка не важна.
     * API: reference/neoforge-src/net/neoforged/neoforge/event/entity/EntityEvent.java#Size
     */
    @SubscribeEvent
    static void onEntitySize(net.neoforged.neoforge.event.entity.EntityEvent.Size event) {
        if (seatedEye && event.getEntity() == Minecraft.getInstance().player) {
            // В подлёте камера поднимается вместе с телом (камера сглаживает высоту глаз сама).
            event.setNewSize(event.getNewSize().withEyeHeight(SEATED_EYE + eyeLift));
        }
    }

    private static boolean seatedEye;
    /** Насколько тело поднято подлётом: глаза (а с ними камера) следуют за ним. */
    private static float eyeLift;

    private static void updateCamera(Minecraft minecraft) {
        boolean wanted = cinematic();
        // Сидящий взгляд — во всей медитации, и после даньтяня тоже (замечание автора
        // 30.09: «игрок должен сидеть, а не стоять»), а не только в крупном плане.
        boolean seated = io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive() || state.active() || seedSceneTicks > 0 || rankUpTicks > 0;
        // Камера идёт за телом лишь частью подъёма: иначе тело стоит на месте в кадре и подлёт не читается (кадры стенда 19.10).
        float lift = ClientPillState.finaleLift() ? EYE_FOLLOW * ClientPillState.lift(0.0F)
                : ClientPillState.absorbing() && ClientPillState.finaleAge() < 0 ? PILL_EYE_DROP : 0.0F;
        if ((seated != seatedEye || Math.abs(lift - eyeLift) > 0.004F) && minecraft.player != null) {
            seatedEye = seated;
            eyeLift = lift;
            minecraft.player.refreshDimensions();
        }
        if (wanted && restoreCamera == null) {
            restoreCamera = minecraft.options.getCameraType();
        }
        // Breakthrough: the camera director (client/camera) owns the camera, including the glide back to the player's
        // own view; the stand-in third-person-front shot below is for the other scenes.
        BreakthroughCamera.tick(minecraft, orbit() || seedSceneTicks > 0, restoreCamera);
        FlowCamera.tick(minecraft, (FlowClient.active() || io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive()) && !BreakthroughCamera.active());
        wanted = cinematic();
        if (wanted) {
            CameraType shot = BreakthroughCamera.active() || FlowCamera.active() ? CameraType.THIRD_PERSON_BACK : CameraType.THIRD_PERSON_FRONT;
            if (minecraft.options.getCameraType() != shot) {
                minecraft.options.setCameraType(shot);
            }
        } else if (restoreCamera != null) {
            minecraft.options.setCameraType(restoreCamera);
            restoreCamera = null;
        }
        // Ванильный интерфейс прячется на всё создание даньтяня: хотбар и сердца ложились на живот, где
        // кольцо. Свой слой мода при этом рисуется. The pill game instead cancels individual GUI layers
        // in FlowOverlay, keeping its own hint, pressure and dissolve indicators visible.
        boolean hide = wanted && !onlyPillScene();
        if (hide && restoreHideGui == null) {
            restoreHideGui = minecraft.options.hideGui;
            minecraft.options.hideGui = true;
        } else if (!hide && restoreHideGui != null) {
            minecraft.options.hideGui = restoreHideGui;
            restoreHideGui = null;
        }
        cinematicTicks = wanted ? Math.min(20, cinematicTicks + 1) : 0;
        // Прорыв — склейкой: крупный план сразу, без наезда.
        if (wanted && orbit()) {
            cinematicTicks = 20;
        }
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
        if (BreakthroughCamera.active() || FlowCamera.active()) {
            return;
        }
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        float k = closeness(partial);
        if (k > 0.0F) {
            float close = ClientPillState.absorbing() && ClientPillState.finaleAge() < 0 ? PILL_DISTANCE : CLOSE_DISTANCE;
            event.setDistance(net.minecraft.util.Mth.lerp(k, event.getDistance(),
                    Math.min(event.getDistance(), close)));
            // Подлёт: короткий наезд камеры на подъёме и отъезд на спуске.
            event.setDistance(event.getDistance() * ClientPillState.distanceFactor(partial));
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
        if (holdsBody() && me != null) {
            if (lockedYaw == null) {
                lockedYaw = me.getYRot();
            }
            float yaw = lockedYaw;
            me.setYRot(yaw);
            me.yRotO = yaw;
            me.yBodyRot = me.yBodyRotO = yaw;
            me.yHeadRot = me.yHeadRotO = yaw;
            if (cinematic()) {
                me.setXRot(0.0F);
                me.xRotO = 0.0F;
                // Спереди ваниль разворачивает камеру на 180°, так что угол — как у игрока.
                if (!BreakthroughCamera.active() && !FlowCamera.active()) {
                    event.setYaw(yaw);
                }
            }
        } else {
            lockedYaw = null;
        }
        if (BreakthroughCamera.active() || FlowCamera.active()) {
            return;
        }
        float k = closeness(event.getPartialTick());
        if (k > 0.0F) {
            event.setPitch(net.minecraft.util.Mth.lerp(k, event.getPitch(), -CLOSE_PITCH));
        }
    }

    /** Лотос садится за 0,3 с; после этого включается цикл дыхания. */
    private static final int BREATH_AFTER_TICKS = 8;
    private static int breathDelay;

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
        peakClock.tick();
        if (state.rank() == 4 && rankUpTicks <= 0) peakClock.reset();
        if (state.active()) {
            localTicks++;
            // Сел в лотос — дальше тело дышит циклом (автор 02.10: «не просто сидит, а дышит»).
            if (breathDelay > 0 && --breathDelay == 0) {
                MurimPlayerAnimations.play(minecraft.player, MurimPlayerAnimations.MEDITATION_BREATH);
            }
        } else {
            breathDelay = 0;
        }
        if (aftermathTicks > 0 && --aftermathTicks == 0) {
            aftermath = Aftermath.NONE;
        }
        if (seedSceneTicks > 0 && --seedSceneTicks == 0) {
            endSeedScene(minecraft);
        }
        if (breakthroughTicks >= 0 && state.active()) {
            breakthroughTicks++;
            int w = io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS;
            int target = state.rank() + 1;
            int sceneEnd = io.github.verycooltimo.murim.cultivation.Realm.breakthroughTicks(target);
            int impactAge = io.github.verycooltimo.murim.client.vfx.bt.BtImpactHold.startTick(target, sceneEnd);
            if ((target == 3 || target == 4) && breakthroughTicks - w >= impactAge) {
                io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.trigger(target);
            }

            // Предупреждение: стук сердца, чаще к концу.
            if (breakthroughTicks < w && breakthroughTicks % (breakthroughTicks < w / 2 ? 20 : 12) == 1) {
                minecraft.player.playSound(net.minecraft.sounds.SoundEvents.WARDEN_HEARTBEAT, 1.0F, 0.9F);
            }
            if (breakthroughTicks == w) {
                io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.reset();
                CameraShakeHandler.request(0.25F);
                // Сам прорыв: ци рвётся наружу (сервер добавит «прорыв» при подъёме ранга).
                io.github.verycooltimo.murim.client.Sfx.play(minecraft.player, io.github.verycooltimo.murim.registry.ModSounds.QI_CHARGE, 0.8F, 0.85F);
            }
        }
        if (stainTicks > 0) {
            stainTicks--;
        }
        if (afterglowTicks > 0) {
            afterglowTicks--;
        }
        if (guardTicks > 0) {
            guardTicks--;
        }
        // Lab finale B starts 0.12s after the blow; nearest 20Hz tick, own-view overlay only.
        if (rankUpRank == 1 && rankUpTicks > 0 && rankUpLength - rankUpTicks == 2)
            io.github.verycooltimo.murim.client.vfx.SpeedLines.radial(.5F,.5F,.75F,15,0xF0FFFF);
        if (rankUpTicks > 0 && --rankUpTicks == 0 && !state.active()) {
            MurimPlayerAnimations.stopBlended(minecraft.player, 6);
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
        // While the pill game is on the screen, the strafe keys are its fork keys (25.10: no new keybinds): they do not stand the player up.
        boolean strafeIsFlow = io.github.verycooltimo.murim.client.flow.FlowClient.active();
        boolean exitKeys = minecraft.options.keyShift.isDown() || minecraft.options.keyUp.isDown()
                || minecraft.options.keyDown.isDown() || !strafeIsFlow && (minecraft.options.keyLeft.isDown()
                || minecraft.options.keyRight.isDown());
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

    private static boolean blockedHintSent;

    private static void blockedAction() {
        if (!blockedHintSent) {
            blockedHintSent = true;
            PacketDistributor.sendToServer(new MeditationInputPayload(MeditationInputPayload.Action.BLOCKED_ACTION, true));
        }
    }

    // API: NeoForge 1.21.1 InputEvent.MouseScrollingEvent; cancellation precedes vanilla hotbar scrolling.
    @SubscribeEvent
    static void onScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (state.active() && mc.player != null && mc.screen == null) {
            event.setCanceled(true);
            blockedAction();
        }
    }

    /** Drain slot key clicks before Minecraft#handleKeybinds, including rebound number keys. */
    @SubscribeEvent
    static void beforeInput(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!state.active() || mc.player == null) {
            return;
        }
        for (var key : mc.options.keyHotbarSlots) {
            while (key.consumeClick()) {
                blockedAction();
            }
        }
        if (mc.player.isUsingItem()) {
            mc.player.stopUsingItem();
        }
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    static void onItemInput(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        // Absorption owns these buttons: ClientPillState must still receive its choices.
        if (!state.active() || mc.player == null
                || (event.isUseItem() && ClientPillState.state().active())) {
            return;
        }
        if (event.isPickBlock() || event.isUseItem()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            blockedAction();
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
        io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.reset();
        Minecraft minecraft = Minecraft.getInstance();
        if (restoreHideGui != null) {
            minecraft.options.hideGui = restoreHideGui;
            restoreHideGui = null;
        }
        if (restoreCamera != null) {
            minecraft.options.setCameraType(restoreCamera);
            restoreCamera = null;
        }
        BreakthroughCamera.reset();
        FlowCamera.reset();
        seatedEye = false;
        lockedYaw = null;
        creationFlow.reset();
        state = IDLE;
        blockedHintSent = false;
        previousRing = SyncMeditationPayload.Ring.NONE;
        localTicks = 0;
        holdSent = false;
        aftermath = Aftermath.NONE;
        aftermathTicks = 0;
        seedSceneTicks = 0;
        breakthroughTicks = -1;
        peakClock.reset();
        rankUpTicks = 0;
        afterglowTicks = 0;
        guardTicks = 0;
        stainTicks = 0;
    }

    private ClientMeditationState() {
    }
}
