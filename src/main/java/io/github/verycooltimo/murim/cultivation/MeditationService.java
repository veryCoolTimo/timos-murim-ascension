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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Медитация: до семени — три такта создания даньтяня, после — ускоритель культивации.
 *
 * <p>docs/design/19-dantian-qi-meditation.md §3, §3а; мини-игра — решение автора 30.09.
 * <ol>
 *   <li>первое ощущение — 15 секунд, играть нечего: тепло появляется и рассеивается;</li>
 *   <li>удержание — мини-игра {@link RingMinigame}: удержать кольцо в полосе;</li>
 *   <li>сжатие в семя — та же мини-игра, полоса ползёт к центру.</li>
 * </ol>
 * Проигрыш мини-игры целиком — искажение ци: здоровье до полсердца и тяжёлая слабость.
 *
 * <p>До семени такты идут ПОДРЯД, пока игрок сидит: поза лотоса не перезапускается
 * между тактами (замечание автора 29.09).
 *
 * <p>Правила тактов — в {@link SeedLogic}, мини-игры — в {@link RingMinigame}; здесь
 * только время, прерывания, последствия и сообщения.
 */
public final class MeditationService {

    /** Длина первого такта: 15 секунд — показать тепло, не заставляя ждать. */
    public static final int FIRST_FEELING_TICKS = 300;

    /** Прирост запаса за тик в начале медитации после семени. */
    static final double GAIN_START = 0.05D;

    /** За сколько тиков прирост падает в e раз: «усталость ума». */
    static final double GAIN_DECAY_TICKS = 1200.0D;

    /** Запас после семени ограничен ёмкостью с этим множителем, пока нет рангов. */
    public static final double POOL_CAP = 4.0D;

    /** Во время медитации циркулирующая ци наполняется быстрее пассивной. */
    static final int MEDITATION_CIRCULATION_STEPS = 3;

    /** Искажение ци: две минуты слабости. */
    static final int BACKLASH_TICKS = 2400;

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
        player.setData(ModAttachments.MEDITATION, MeditationState.started(filter, cultivation.beats()));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.3F, 1.5F);
        player.displayClientMessage(Component.translatable(cultivation.seeded()
                ? "murim.meditation.begin_seeded" : "murim.meditation.begin." + cultivation.beats()), true);
        sync(player, SyncMeditationPayload.Event.NONE);
        return true;
    }

    /** Прерывает сессию. До семени незаконченный такт не засчитывается. */
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

    /** Клиент сообщает, держит ли игрок клавишу сжатия. Что из этого следует — решает сервер. */
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
        // по пакетам клиента и deltaMovement при ходьбе не выставляет (урок старого ритуала).
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

        if (state.ring() == null) {
            MeditationState next = state.tick(null);
            player.setData(ModAttachments.MEDITATION, next);
            if (next.ticks() >= FIRST_FEELING_TICKS) {
                finishSeedSession(player, cultivation, next, true);
            } else if (next.ticks() % 5 == 0) {
                sync(player, SyncMeditationPayload.Event.NONE);
            }
            return;
        }

        RingMinigame ring = state.ring().step(state.holding(), cultivation.beats(),
                player.getRandom()::nextDouble);
        MeditationState next = state.tick(ring);
        player.setData(ModAttachments.MEDITATION, next);
        switch (ring.result()) {
            case PASSED -> finishSeedSession(player, cultivation, next, true);
            case BACKLASH -> backlash(player);
            // Мини-игра требует отклика каждый тик: реже — и кольцо на экране прыгает.
            default -> sync(player, SyncMeditationPayload.Event.NONE);
        }
    }

    private static void finishSeedSession(ServerPlayer player, CultivationState cultivation,
                                          MeditationState state, boolean passed) {
        SeedLogic.SessionResult result = SeedLogic.finishSession(cultivation, passed);
        player.setData(ModAttachments.CULTIVATION, result.state());
        // Следующий такт начинается сразу, без вставания. Семя — конец сидения: дальше
        // идёт сцена «внутреннего взгляда», и медитация с семенем начинается отдельно.
        player.setData(ModAttachments.MEDITATION, result.outcome() == SeedLogic.Outcome.SEED
                ? MeditationState.IDLE : MeditationState.started(state.filter(), result.state().beats()));
        SyncMeditationPayload.Event event = result.outcome() == SeedLogic.Outcome.HELD
                ? SyncMeditationPayload.Event.SETTLE : SyncMeditationPayload.Event.SCATTER;

        switch (result.outcome()) {
            case FIRST_FEELING -> player.displayClientMessage(
                    Component.translatable("murim.meditation.first_feeling").withStyle(ChatFormatting.GRAY), false);
            case HELD -> player.displayClientMessage(
                    Component.translatable("murim.meditation.held").withStyle(ChatFormatting.GRAY), false);
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
                        .withStyle(ChatFormatting.AQUA), false);
                event = SyncMeditationPayload.Event.SEED;
            }
            default -> {
            }
        }
        sync(player, event);
    }

    /**
     * Искажение ци: мини-игра проиграна целиком (решение автора 30.09 — «огромный дебаф
     * и потеря всех сердец до половинки»). Пройденные такты не отнимаются: наказание —
     * тело, а не прогресс.
     */
    private static void backlash(ServerPlayer player) {
        player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        // Здоровье ставится напрямую, а не уроном: урон с бронёй и чарами дал бы
        // непредсказуемый итог, а по замыслу это ровно половина сердца, без смерти.
        if (!player.isCreative()) {
            player.setHealth(Math.min(player.getHealth(), 1.0F));
            // API: reference/minecraft-src/net/minecraft/world/effect/MobEffects.java
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, BACKLASH_TICKS, 1));
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, BACKLASH_TICKS, 1));
            player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, BACKLASH_TICKS, 1));
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0));
            // Без этого сердца отрастали за секунды на сытости, и дебафф гас сам (кадры
            // стенда 30.09). Еда 17 — ровно ниже порога естественной регенерации (18):
            // голодом не убивает, но пока не поешь, тело не восстанавливается.
            // API: reference/minecraft-src/net/minecraft/world/food/FoodData.java
            player.getFoodData().setSaturation(0.0F);
            player.getFoodData().setFoodLevel(Math.min(player.getFoodData().getFoodLevel(), 17));
        }
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.6F);
        player.displayClientMessage(Component.translatable("murim.meditation.backlash")
                .withStyle(ChatFormatting.DARK_RED), false);
        sync(player, SyncMeditationPayload.Event.BACKLASH);
    }

    /** После семени: запас растёт с падающей отдачей, циркулирующая наполняется быстрее. */
    private static void tickSeeded(ServerPlayer player, MeditationState state) {
        MeditationState next = state.tick(null);
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
    public static double gainAt(int ticks) {
        return GAIN_START * Math.exp(-ticks / GAIN_DECAY_TICKS);
    }

    public static void sync(ServerPlayer player, SyncMeditationPayload.Event event) {
        MeditationState state = player.getData(ModAttachments.MEDITATION);
        CultivationState cultivation = player.getData(ModAttachments.CULTIVATION);
        SyncMeditationPayload.Ring ring = SyncMeditationPayload.Ring.NONE;
        RingMinigame game = state.ring();
        if (state.active() && game != null) {
            int beat = cultivation.beats();
            ring = new SyncMeditationPayload.Ring(true, (float) game.radius(),
                    (float) RingMinigame.bandCentre(beat, game.ticks()),
                    (float) RingMinigame.bandHalfWidth(beat, game.ticks()),
                    (float) game.stability(), (float) game.strain());
        }
        PacketDistributor.sendToPlayer(player, new SyncMeditationPayload(state.active(),
                cultivation.beats(), state.ticks(), event, ring));
    }

    private MeditationService() {
    }
}
