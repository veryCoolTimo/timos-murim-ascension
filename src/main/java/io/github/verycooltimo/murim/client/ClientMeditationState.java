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
                    SyncMeditationPayload.Ring.NONE);

    /** Прошлый снимок кольца: между пакетами сервера кольцо интерполируется. */
    private static SyncMeditationPayload.Ring previousRing = SyncMeditationPayload.Ring.NONE;

    private static SyncMeditationPayload state = IDLE;

    /** Тики сессии, досчитанные локально: сервер шлёт состояние раз в пять тиков. */
    private static int localTicks;

    /** Последнее, что клиент сообщил серверу про клавишу удержания. */
    private static boolean holdSent;

    private static Aftermath aftermath = Aftermath.NONE;
    private static int aftermathTicks;
    /** Такт, на котором шла закончившаяся сессия: послесвечение зависит от него. */
    private static int aftermathBeats;

    private static int seedSceneTicks;
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

    /** Тело неподвижно: идёт сессия или сцена семени. */
    public static boolean still() {
        return state.active() || seedSceneTicks > 0;
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

        if (!payload.active() && previous.active()) {
            holdSent = false;
            if (payload.event() == SyncMeditationPayload.Event.SEED) {
                startSeedScene(minecraft);
            } else if (payload.event() == SyncMeditationPayload.Event.BACKLASH) {
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
        // Камера уходит вперёд: семя рождается внизу живота, со спины его не видно
        // (так же поставлена сцена церемонии по замечанию автора).
        if (restoreCamera == null) {
            restoreCamera = minecraft.options.getCameraType();
        }
        minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        // Взрыв семени отдаётся в камеру коротким толчком (сила — из настроек игрока).
        CameraShakeHandler.request(0.45F);
        // Интерфейс на время сцены прячется: сердца и хотбар ложились на живот,
        // где рождается семя. Приближение камеры пробовали — камера смотрит в голову,
        // и при зуме живот уходил за нижний край кадра.
        if (restoreHideGui == null) {
            restoreHideGui = minecraft.options.hideGui;
        }
        minecraft.options.hideGui = true;
    }

    private static void endSeedScene(Minecraft minecraft) {
        seedSceneTicks = 0;
        if (restoreHideGui != null) {
            minecraft.options.hideGui = restoreHideGui;
            restoreHideGui = null;
        }
        if (restoreCamera != null) {
            minecraft.options.setCameraType(restoreCamera);
            restoreCamera = null;
        }
        if (minecraft.player != null && !state.active()) {
            MurimPlayerAnimations.stop(minecraft.player);
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
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
        if (minecraft.screen != null) {
            return;
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
        state = IDLE;
        previousRing = SyncMeditationPayload.Ring.NONE;
        localTicks = 0;
        holdSent = false;
        aftermath = Aftermath.NONE;
        aftermathTicks = 0;
        seedSceneTicks = 0;
    }

    private ClientMeditationState() {
    }
}
