package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Медитация: до семени — три такта создания даньтяня, после — ускоритель культивации.
 *
 * <p>docs/design/19-dantian-qi-meditation.md §3, §3а. Сессия до семени фиксированной длины:
 * первый такт — ци рассеивается, второй — кольцо у пупка надо удержать клавишей в окне
 * удержания, третий — рождается семя. После семени длину выбирает игрок: встал в любой
 * момент — получил пропорционально, первая минута самая выгодная, дальше отдача падает.
 *
 * <p>До семени сессии идут ПОДРЯД, пока игрок сидит: закончился такт — сразу начинается
 * следующий. Раньше каждая сессия завершала медитацию, и поза лотоса перезапускалась
 * между тактами — персонаж вставал и садился заново (замечание автора 29.09).
 *
 * <p>Правила тактов — в {@link SeedLogic}; здесь только время, прерывания и сообщения.
 */
public final class MeditationService {

    /** Длина сессии до семени: 30 секунд. */
    public static final int SESSION_TICKS = 600;

    /** Окно удержания кольца во втором такте: с 10-й по 20-ю секунду. */
    public static final int RING_FROM = 200;
    public static final int RING_TO = 400;

    /** Сколько тиков окна нужно удержать: три четверти, без требования идеала. */
    public static final int HOLD_REQUIRED = 150;

    /** Прирост запаса за тик в начале медитации после семени. */
    static final double GAIN_START = 0.05D;

    /** За сколько тиков прирост падает в e раз: «усталость ума». */
    static final double GAIN_DECAY_TICKS = 1200.0D;

    /** Запас после семени ограничен ёмкостью с этим множителем, пока нет рангов. */
    static final double POOL_CAP = 4.0D;

    /** Во время медитации циркулирующая ци наполняется быстрее пассивной. */
    static final int MEDITATION_CIRCULATION_STEPS = 3;

    public static boolean isRingWindow(int ticks) {
        return ticks >= RING_FROM && ticks < RING_TO;
    }

    public static void toggle(ServerPlayer player, boolean filter) {
        if (player.getData(ModAttachments.MEDITATION).active()) {
            stop(player, "murim.meditation.stopped");
        } else {
            start(player, filter);
        }
    }

    public static boolean start(ServerPlayer player, boolean filter) {
        if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
            return false;
        }
        if (player.getData(ModAttachments.MEDITATION).active()) {
            return false;
        }
        CultivationState cultivation = player.getData(ModAttachments.CULTIVATION);
        CultivationMethod method = cultivation.method().map(MethodLoader::get).orElse(null);
        if (method == null) {
            player.displayClientMessage(Component.translatable("murim.meditation.no_method")
                    .withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        if (method.nightOnly() && player.level().isDay()) {
            player.displayClientMessage(Component.translatable("murim.meditation.night_only")
                    .withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        if (!player.onGround()) {
            return false;
        }
        player.setData(ModAttachments.MEDITATION, MeditationState.started(filter));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.3F, 1.5F);
        player.displayClientMessage(Component.translatable(cultivation.seeded()
                ? "murim.meditation.begin_seeded" : "murim.meditation.begin." + cultivation.beats()), true);
        sync(player, SyncMeditationPayload.Event.NONE);
        return true;
    }

    /** Прерывает или заканчивает сессию. До семени незаконченная сессия не засчитывается. */
    public static void stop(ServerPlayer player, String messageKey) {
        if (!player.getData(ModAttachments.MEDITATION).active()) {
            return;
        }
        player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        if (messageKey != null) {
            player.displayClientMessage(Component.translatable(messageKey).withStyle(ChatFormatting.GRAY), true);
        }
        sync(player, SyncMeditationPayload.Event.NONE);
    }

    /** Клиент сообщает, держит ли игрок клавишу удержания. Засчитывает сервер, в своём окне. */
    public static void setHolding(ServerPlayer player, boolean holding) {
        MeditationState state = player.getData(ModAttachments.MEDITATION);
        if (state.active()) {
            player.setData(ModAttachments.MEDITATION, state.withHolding(holding));
        }
    }

    /** Один тик. Вызывается из тика игрока. */
    public static void tick(ServerPlayer player) {
        MeditationState state = player.getData(ModAttachments.MEDITATION);
        if (!state.active()) {
            return;
        }
        // Неподвижность — по смещению позиции, а не по скорости: сервер двигает игрока
        // по пакетам клиента и deltaMovement при ходьбе не выставляет (урок RitualService).
        double dx = player.getX() - player.xOld;
        double dz = player.getZ() - player.zOld;
        if (dx * dx + dz * dz > 1.0E-6D || !player.onGround()) {
            stop(player, "murim.meditation.broken.moved");
            return;
        }

        CultivationState cultivation = player.getData(ModAttachments.CULTIVATION);
        if (cultivation.seeded()) {
            tickSeeded(player, state);
            return;
        }

        boolean countHold = cultivation.beats() == 1 && state.holding() && isRingWindow(state.ticks());
        MeditationState next = state.tick(countHold);
        player.setData(ModAttachments.MEDITATION, next);
        if (next.ticks() >= SESSION_TICKS) {
            finishSeedSession(player, cultivation, next);
        } else if (next.ticks() % 5 == 0) {
            sync(player, SyncMeditationPayload.Event.NONE);
        }
    }

    private static void finishSeedSession(ServerPlayer player, CultivationState cultivation,
                                          MeditationState state) {
        SeedLogic.SessionResult result = SeedLogic.finishSession(cultivation,
                state.holdTicks() >= HOLD_REQUIRED);
        player.setData(ModAttachments.CULTIVATION, result.state());
        // Следующий такт начинается сразу, без вставания. Семя — конец сидения: дальше
        // идёт сцена «внутреннего взгляда», и медитация с семенем начинается отдельно.
        player.setData(ModAttachments.MEDITATION, result.outcome() == SeedLogic.Outcome.SEED
                ? MeditationState.IDLE : MeditationState.started(state.filter()));
        SyncMeditationPayload.Event event = result.outcome() == SeedLogic.Outcome.HELD
                ? SyncMeditationPayload.Event.SETTLE : SyncMeditationPayload.Event.SCATTER;

        switch (result.outcome()) {
            case FIRST_FEELING -> player.displayClientMessage(
                    Component.translatable("murim.meditation.first_feeling").withStyle(ChatFormatting.GRAY), false);
            case HELD -> player.displayClientMessage(
                    Component.translatable("murim.meditation.held").withStyle(ChatFormatting.GRAY), false);
            case SLIPPED -> player.displayClientMessage(
                    Component.translatable("murim.meditation.slipped").withStyle(ChatFormatting.GRAY), false);
            case SEED -> {
                CultivationMethod method = result.state().method().map(MethodLoader::get).orElse(null);
                if (method == null) {
                    MurimMod.LOGGER.error("Семя у {} без загруженного метода — такт откатан",
                            player.getGameProfile().getName());
                    player.setData(ModAttachments.CULTIVATION, cultivation);
                    player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
                    event = SyncMeditationPayload.Event.NONE;
                    break;
                }
                DantianProfile seeded = SeedLogic.seedProfile(player.getData(ModAttachments.PROFILE),
                        method, state.filter() ? 1.0D : 0.0D);
                player.setData(ModAttachments.PROFILE, seeded);
                ProfileNetwork.sync(player);
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.6F, 1.2F);
                player.displayClientMessage(Component.translatable("murim.meditation.seed")
                        .withStyle(ChatFormatting.GREEN), false);
                event = SyncMeditationPayload.Event.SEED;
            }
            default -> {
            }
        }
        if (player.getData(ModAttachments.MEDITATION).active()) {
            // Подсказка следующего такта: раньше она приходила только при посадке.
            player.displayClientMessage(Component.translatable(
                    "murim.meditation.begin." + result.state().beats()), true);
        }
        sync(player, event);
    }

    /** После семени: запас растёт с падающей отдачей, циркулирующая наполняется быстрее. */
    private static void tickSeeded(ServerPlayer player, MeditationState state) {
        MeditationState next = state.tick(false);
        player.setData(ModAttachments.MEDITATION, next);
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        double gain = gainAt(next.ticks()) * profile.efficiency();
        double cap = profile.capacity() * POOL_CAP;
        DantianProfile updated = profile.withPool(Math.min(cap, profile.pool() + gain));
        for (int i = 0; i < MEDITATION_CIRCULATION_STEPS; i++) {
            updated = updated.circulateOnce();
        }
        player.setData(ModAttachments.PROFILE, updated);
        if (next.ticks() % 20 == 0) {
            ProfileNetwork.sync(player);
            sync(player, SyncMeditationPayload.Event.NONE);
        }
    }

    /** Прирост запаса за тик на данной секунде медитации: первая минута самая выгодная. */
    static double gainAt(int ticks) {
        return GAIN_START * Math.exp(-ticks / GAIN_DECAY_TICKS);
    }

    public static void sync(ServerPlayer player, SyncMeditationPayload.Event event) {
        MeditationState state = player.getData(ModAttachments.MEDITATION);
        CultivationState cultivation = player.getData(ModAttachments.CULTIVATION);
        PacketDistributor.sendToPlayer(player, new SyncMeditationPayload(state.active(),
                cultivation.beats(), state.ticks(), state.holdTicks(), event));
    }

    private MeditationService() {
    }
}
