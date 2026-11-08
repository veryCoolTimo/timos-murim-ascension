package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.flow.FlowClient;
import io.github.verycooltimo.murim.cultivation.PerfectLift;
import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.network.PillPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Клиент пилюль: окно «сразу», отдача удара, подлёт и то, что сервер знает об игре «Провести поток»
 * (docs/design/19b §2 и §10). The game itself - the stream, the hands, the picture - lives in {@link FlowClient}; here is what the
 * server says about it: the snapshot, the window, the tolerance, and the lift scene after a flawless absorption of a high pill.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientPillState {

    private static final PillPayloads.Sync IDLE = new PillPayloads.Sync(new int[0], 0, false, new int[0], PillPayloads.Event.NONE,
            false, 0.0F, false, 0.0F, false, 0.0F, new int[0], -1, 0);

    public static final int LIFT_TICKS = PerfectLift.TICKS;
    /** Посадка при прерывании: за столько тиков тело опускается. */
    private static final int CUT_TICKS = 16;

    private static final java.util.function.Supplier<io.github.verycooltimo.murim.profile.DantianProfile> PROFILE =
            ClientProfileState::profile;

    private static PillPayloads.Sync state = IDLE;

    private static int finale;
    private static int finaleTotal;
    private static boolean finalePerfect;
    private static boolean finaleFull;
    private static float finaleLift;
    private static boolean finaleBump;
    private static boolean finaleCut;
    private static int[] finaleClots = new int[0];

    private static int activeTicks;
    private static long toleranceAge;
    /** Искажение ци (удар сорвал поглощение): красная вспышка экрана и частицы длятся столько тиков. */
    public static final int DEVIATION_TICKS = 26;
    private static int deviationAge = DEVIATION_TICKS;

    public static PillPayloads.Sync state() {
        return state;
    }

    /** Идёт поглощение: игра «Провести поток» (её картинка ещё на экране) или сервер держит сессию. */
    public static boolean absorbing() {
        return state.active() || FlowClient.active();
    }

    /** Тиков с начала игры; 0, пока её нет. */
    public static int activeTicks() {
        return activeTicks;
    }

    public static int finaleAge() {
        return finale > 0 ? finaleTotal - finale : -1;
    }

    /** Подлёт: безупречное усвоение высшей пилюли. */
    public static boolean finaleLift() {
        return finale > 0;
    }

    public static boolean finalePerfect() {
        return finalePerfect;
    }

    public static boolean finaleBump() {
        return finaleBump;
    }

    public static boolean finaleFull() {
        return finaleFull;
    }

    public static int[] finaleClots() {
        return finaleClots;
    }

    /** Возраст искажения ци в тиках, −1 — нет. */
    public static int deviationAge() {
        return deviationAge < DEVIATION_TICKS ? deviationAge : -1;
    }

    /** Старый резерв неусвоенной доли, долей стены запаса (видимая янтарная нить у шкалы ци). */
    public static float reserve() {
        return state.reserve();
    }

    /** Возраст стука о потолок, тиков; −1 — стука нет. */
    public static int bumpAge() {
        if (!finaleLift() || !finaleBump) {
            return -1;
        }
        int age = finaleAge() - (PerfectLift.RISE_END - 4);
        return age >= 0 && age < 24 ? age : -1;
    }

    /** Тело приподнимается в сцене подлёта: та же формула, что считает сервер. */
    public static float lift(float partial) {
        if (finale <= 0) {
            return 0.0F;
        }
        float age = finaleTotal - finale + partial;
        float h = PerfectLift.height(age, finaleLift);
        if (finaleCut) {
            float k = Mth.clamp((finale - partial) / CUT_TICKS, 0.0F, 1.0F);
            h *= k * k * (3.0F - 2.0F * k);
        }
        return h;
    }

    /**
     * Множитель дистанции камеры в сцене подлёта: на подъёме камера чуть отъезжает, чтобы в кадр влезли поднятое
     * тело и ленты вокруг него, а на выбросе ци делает короткий наезд («быстрый наезд камеры», 19.10).
     */
    public static float distanceFactor(float partial) {
        if (finale <= 0) {
            return 1.0F;
        }
        float age = finaleTotal - finale + partial;
        float up = Mth.clamp((age - 14.0F) / 30.0F, 0.0F, 1.0F);
        float down = Mth.clamp((finaleTotal - age) / 26.0F, 0.0F, 1.0F);
        float k = Math.min(up, down);
        float hold = k * k * (3.0F - 2.0F * k);
        float x = (age - PerfectLift.BURST) / 5.0F;
        float punch = (float) Math.exp(-x * x);
        return 1.0F + 0.24F * hold - 0.2F * punch;
    }

    public static void accept(PillPayloads.Sync payload) {
        state = payload;
        toleranceAge = 0L;
        if (!payload.active() && !FlowClient.active()) {
            activeTicks = 0;
        }
        switch (payload.event()) {
            case FINISH -> {
                // Подлёт (19.10): безупречное усвоение высшей пилюли; сервер прислал высоту, если подлёт есть.
                if (payload.liftHeight() > 0.0F || payload.bump()) {
                    startLift(payload);
                }
            }
            case DEVIATION -> {
                // Отдача удара (автор 19.10): красный выброс у даньтяня, короткая тряска и красная кайма.
                deviationAge = 0;
                CameraShakeHandler.request(0.9F);
                CameraShakeHandler.quake(0.5F, 12);
            }
            case INTERRUPTED -> {
                FlowClient.interrupted();
                if (finale > CUT_TICKS) {
                    finale = CUT_TICKS;
                    finaleCut = true;
                }
            }
            case LANDED -> {
                if (finale > CUT_TICKS) {
                    finale = CUT_TICKS;
                    finaleCut = true;
                }
            }
            default -> {
            }
        }
    }

    private static void startLift(PillPayloads.Sync payload) {
        finaleClots = payload.clots();
        finalePerfect = payload.perfect();
        finaleFull = payload.fullFive();
        finaleBump = payload.bump();
        finaleLift = payload.liftHeight();
        finaleCut = false;
        finaleTotal = LIFT_TICKS;
        finale = finaleTotal;
        CameraShakeHandler.request(finaleFull ? 0.5F : 0.35F);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        // Клиентский профиль для проверки «можно ли съесть» (общий код не видит client/).
        io.github.verycooltimo.murim.item.PillItem.clientProfile = PROFILE;
        io.github.verycooltimo.murim.item.PillItem.clientStreak = ClientPillState::toleranceSteps;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.isPaused()) {
            return;
        }
        toleranceAge++;
        if (absorbing()) {
            activeTicks++;
        }
        if (deviationAge < DEVIATION_TICKS) {
            deviationAge++;
        }
        if (finale > 0) {
            finale--;
            int age = finaleTotal - finale;
            if (!finaleCut) {
                // Гул земли нарастает при подъёме, удар камеры на выбросе (ползунок тряски в настройках действует).
                if (age >= 4 && age < PerfectLift.BURST && age % 8 == 4) {
                    float grow = (age - 4) / (float) (PerfectLift.BURST - 4);
                    CameraShakeHandler.quake(0.10F + 0.18F * grow, 14);
                }
                if (age == PerfectLift.BURST) {
                    CameraShakeHandler.request(0.5F);
                    CameraShakeHandler.quake(0.5F, 22);
                }
                if (finaleBump && age == PerfectLift.RISE_END - 4) {
                    CameraShakeHandler.request(0.7F);
                }
            }
        }
        if (state.windowLeft() > 0 && !state.active()) {
            state = new PillPayloads.Sync(state.pending(), state.windowLeft() - 1, false, state.clots(), PillPayloads.Event.NONE,
                    state.fullFive(), state.quality(), false, 0.0F, false, state.reserve(), state.taken(), state.streakKind(),
                    state.streakCount(), state.tolerance());
        }
    }

    public static int toleranceSteps(PillKind kind) {
        int i = kind.ordinal() * 2;
        if (i + 1 >= state.tolerance().length) return 0;
        long until = state.tolerance()[i + 1];
        long recovered = toleranceAge < until ? 0L : 1L + (toleranceAge - until)
                / io.github.verycooltimo.murim.cultivation.PillRules.TOLERANCE_RECOVERY_TICKS;
        return (int) Math.max(0L, state.tolerance()[i] - recovered);
    }

    public static void reset() {
        state = IDLE;
        toleranceAge = 0L;
        finale = 0;
        deviationAge = DEVIATION_TICKS;
        activeTicks = 0;
        FlowClient.reset();
    }

    /** The first pill of a stream: its colour tints the HUD of the window. */
    public static PillKind firstPending() {
        return state.pending().length == 0 ? null : PillKind.byId(state.pending()[0]);
    }

    private ClientPillState() {
    }
}
