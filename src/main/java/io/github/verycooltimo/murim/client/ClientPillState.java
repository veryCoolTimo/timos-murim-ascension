package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.AbsorbGame;
import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.network.PillPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Клиент пилюль: окно «сразу», ход мини-игры поглощения, выбор ветки мышью и финал
 * (docs/design/19b §2).
 *
 * <p>Выбор — без экрана и без клавиш. Во время поглощения камера крупного плана держит
 * поворот игрока ({@link ClientMeditationState}): мышь всё равно поворачивает его, и поворот
 * возвращается каждый кадр. Здесь разница до возврата читается как сдвиг мыши: вправо —
 * правая ветка НА ЭКРАНЕ (не тела: камера спереди, тело зеркально).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientPillState {

    private static final PillPayloads.Sync IDLE = new PillPayloads.Sync(new int[0], 0, false, new int[0], 0, 0, 0, 0,
            0, 0, false, 0, 0.0F, 0, PillPayloads.Event.NONE, false, false);

    /** Финал поглощения: пятицветный выброс 3 с, у простой пилюли — короткая вспышка. */
    /**
     * Финал редких: 5,5 с (автор 03.10: «дольше — как ты подлетаешь, в тебя входит энергия»):
     * 0–30 подъём, 20–65 энергия втекает со всех сторон, 65–78 выброс, 78–110 спуск.
     */
    public static final int FINALE_TICKS = 110;
    public static final int FLASH_TICKS = 24;

    /** Порог выбора в градусах поворота мыши: мелкое дрожание выбор не меняет. */
    static final float CHOICE_THRESHOLD = 4.0F;
    static final float CURSOR_LIMIT = 10.0F;

    private static final java.util.function.Supplier<io.github.verycooltimo.murim.profile.DantianProfile> PROFILE =
            ClientProfileState::profile;

    private static PillPayloads.Sync state = IDLE;
    private static int localPhaseTicks;
    /** Накопленный сдвиг мыши с начала подхода к развилке, градусы поворота. */
    private static float cursor;
    private static float yawAfterLock = Float.NaN;
    private static int sentChoice;
    private static int lastForkKey = -1;
    private static int finale;
    private static boolean finaleRare;
    private static boolean finaleFull;
    /** Полный успех с сильной пилюлей: ураган и взрыв (автор 03.10: «как ураган и взрыв от ТНТ»). */
    private static boolean finaleStorm;

    /** Тик выброса внутри финала. */
    public static final int BURST_AGE = 65;
    private static int[] finaleClots = new int[0];
    /** Вспышка осевшей порции и удара: тики до конца и вид. */
    private static int flash;
    private static int flashOutcome;
    private static int activeTicks;

    public static PillPayloads.Sync state() {
        return state;
    }

    public static boolean absorbing() {
        return state.active();
    }

    public static float cursor() {
        return cursor;
    }

    public static int activeTicks() {
        return activeTicks;
    }

    /** Тики в фазе с досчётом между пакетами. */
    public static int phaseTicks() {
        return localPhaseTicks;
    }

    public static AbsorbGame.Phase phase() {
        return AbsorbGame.Phase.values()[Math.min(AbsorbGame.Phase.values().length - 1, state.phase())];
    }

    public static PillKind clotKind() {
        int[] c = state.clots();
        return c.length == 0 ? PillKind.SNOW_PLUM : PillKind.byId(c[Math.min(c.length - 1, state.clot())]);
    }

    public static int finaleAge() {
        return finale > 0 ? (finaleRare ? FINALE_TICKS : FLASH_TICKS) - finale : -1;
    }

    public static boolean finaleRare() {
        return finaleRare;
    }

    public static boolean finaleStorm() {
        return finaleStorm;
    }

    public static boolean finaleFull() {
        return finaleFull;
    }

    public static int[] finaleClots() {
        return finaleClots;
    }

    public static int flashAge() {
        return flash > 0 ? 14 - flash : -1;
    }

    public static AbsorbGame.Outcome flashOutcome() {
        return AbsorbGame.Outcome.values()[flashOutcome];
    }

    /** Тело приподнимается в пятицветном выбросе (канон гл. 176: «начал парить в воздухе»). */
    public static float lift(float partial) {
        if (finale <= 0 || !finaleRare) {
            return 0.0F;
        }
        float age = FINALE_TICKS - finale + partial;
        float up = net.minecraft.util.Mth.clamp(age / 30.0F, 0.0F, 1.0F);
        float down = net.minecraft.util.Mth.clamp((FINALE_TICKS - age) / 28.0F, 0.0F, 1.0F);
        float k = Math.min(up, down);
        return 0.6F * k * k * (3.0F - 2.0F * k);
    }

    public static void accept(PillPayloads.Sync payload) {
        PillPayloads.Sync previous = state;
        state = payload;
        int forkKey = payload.clot() * 16 + payload.fork();
        if (payload.active() && (forkKey != lastForkKey || !previous.active())) {
            if (payload.phase() == AbsorbGame.Phase.APPROACH.ordinal()) {
                lastForkKey = forkKey;
                cursor = 0.0F;
                sentChoice = 0;
            }
        }
        if (payload.phase() != previous.phase() || forkKey != previous.clot() * 16 + previous.fork()) {
            localPhaseTicks = payload.phaseTicks();
        } else {
            localPhaseTicks = Math.max(localPhaseTicks, payload.phaseTicks());
        }
        if (payload.outcome() != 0) {
            flash = 14;
            flashOutcome = payload.outcome();
            if (payload.outcome() == AbsorbGame.Outcome.WILD_SHORT.ordinal()) {
                CameraShakeHandler.request(0.25F);
            }
        }
        if (!payload.active()) {
            activeTicks = 0;
        }
        switch (payload.event()) {
            case FINISH -> {
                finaleClots = payload.clots();
                finaleRare = false;
                for (int c : finaleClots) {
                    finaleRare |= PillKind.byId(c).rare();
                }
                finaleFull = payload.fullFive();
                finaleStorm = payload.storm();
                finale = finaleRare ? FINALE_TICKS : FLASH_TICKS;
                // Огонь ауры (автор 03.10: «добавить огня, как у ауры»): та же симуляция пламени,
                // что у давления ауры, — голубая аура техники на время финала.
                LocalPlayer me = Minecraft.getInstance().player;
                if (finaleRare && me != null) {
                    ClientAuraState.techniqueAura(me.getId(), finaleFull ? 5 : 4, 0, FINALE_TICKS - 8);
                }
                if (finaleRare) {
                    CameraShakeHandler.request(finaleFull ? 0.6F : 0.4F);
                }
            }
            case BACKLASH -> CameraShakeHandler.request(0.8F);
            default -> {
            }
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        // Клиентский профиль для проверки «можно ли съесть» (общий код не видит client/).
        io.github.verycooltimo.murim.item.PillItem.clientProfile = PROFILE;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.isPaused()) {
            return;
        }
        if (state.active()) {
            localPhaseTicks++;
            activeTicks++;
        }
        if (finale > 0) {
            finale--;
            // Взрыв как от ТНТ в миг выброса — сильный толчок камеры.
            if (finaleStorm && finaleRare && FINALE_TICKS - finale == BURST_AGE) {
                // Сам взрыв (блоки, частицы, звук) делает сервер; здесь — только толчок камеры.
                CameraShakeHandler.request(1.0F);
            }
        }
        if (flash > 0) {
            flash--;
        }
        if (state.windowLeft() > 0 && !state.active()) {
            state = new PillPayloads.Sync(state.pending(), state.windowLeft() - 1, false, state.clots(), state.clot(),
                    state.fork(), state.phase(), state.phaseTicks(), state.choice(), state.shortSide(), state.tookShort(),
                    state.temper(), state.strain(), 0, PillPayloads.Event.NONE, state.fullFive(), false);
        }
    }

    /**
     * Сдвиг мыши читается ДО того, как {@link ClientMeditationState} вернёт поворот: высокий
     * приоритет здесь и низкий ниже — по разнице до и после возврата.
     * API: reference/neoforge-src/net/neoforged/neoforge/client/event/ViewportEvent.java#ComputeCameraAngles
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void beforeLock(ViewportEvent.ComputeCameraAngles event) {
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null || !state.active() || Float.isNaN(yawAfterLock)) {
            return;
        }
        float delta = net.minecraft.util.Mth.wrapDegrees(me.getYRot() - yawAfterLock);
        if (delta == 0.0F || phase() != AbsorbGame.Phase.APPROACH) {
            return;
        }
        cursor = net.minecraft.util.Mth.clamp(cursor + delta, -CURSOR_LIMIT, CURSOR_LIMIT);
        // Выбор «залипает»: меняется только при пересечении порога на другой стороне.
        int side = cursor > CHOICE_THRESHOLD ? 1 : cursor < -CHOICE_THRESHOLD ? -1 : sentChoice;
        if (side != sentChoice) {
            sentChoice = side;
            PacketDistributor.sendToServer(new PillPayloads.Choice(side));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void afterLock(ViewportEvent.ComputeCameraAngles event) {
        LocalPlayer me = Minecraft.getInstance().player;
        yawAfterLock = me == null || !state.active() ? Float.NaN : me.getYRot();
    }

    /** Выбор, который видит игрок сразу (до ответа сервера). */
    public static int choice() {
        return sentChoice != 0 ? sentChoice : state.choice();
    }

    public static void reset() {
        state = IDLE;
        cursor = 0.0F;
        sentChoice = 0;
        lastForkKey = -1;
        finale = 0;
        flash = 0;
        yawAfterLock = Float.NaN;
    }

    private ClientPillState() {
    }
}
