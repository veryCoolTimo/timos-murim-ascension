package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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

    /**
     * Прирост запаса за тик после семени (до эффективности, места силы и чая). Затухания в сессии больше нет
     * (hua-main-1810 §1, автор 18.10): раньше приток падал в e раз за минуту сидения и выгоднее всего было вставать-садиться
     * каждые 15–30 с. Постоянная 0,020/тик = 24 ци в минуту при эффективности 1 даёт не больше сидения до Пика, чем
     * прежний распорядок «сессии по минуте» (22,0 мин против 23,1), и не зависит от того, как долго человек сидит
     * (docs/design/research/hua-main-1810.md, симулятор PacingSimTest).
     */
    static final double GAIN_PER_TICK = 0.020D;

    /** Сколько Эха на искусстве обрабатывает весь его резерв: 30 секунд (автор 18.10). */
    public static final int ECHO_TICKS = 600;

    /**
     * Ручка для системы тренировки тела (параллельная ветка, 05.10): на сколько закалённое тело ускоряет
     * прирост запаса за единицу «тела». 0 — не влияет, пока та система не подключит {@link #bodyFactor}
     * в {@code tickSeeded}. Так тело кормит прорывы, не трогая их условий (docs/design/27-balance.md §4).
     */
    public static final double BODY_GAIN_PER_POINT = 0.0D;

    /** Множитель прироста запаса от тела; 1 — без влияния. */
    public static double bodyFactor(double body) {
        return 1.0D + BODY_GAIN_PER_POINT * Math.max(0.0D, body);
    }

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
            PlayerJournal.refuse(player, Component.translatable("murim.meditation.no_method")
                    .withStyle(ChatFormatting.GRAY));
            return false;
        }
        if (method.nightOnly() && player.level().isDay()) {
            PlayerJournal.refuse(player, Component.translatable("murim.meditation.night_only")
                    .withStyle(ChatFormatting.GRAY));
            return false;
        }
        if (!player.onGround()) {
            return false;
        }
        // Занятие выбирается до сидения, без новых клавиш (hua-main-1810 §1): искусство в руке — Эхо, иначе Очищение.
        ResourceLocation echo = cultivation.seeded() ? echoArt(player) : null;
        player.setData(ModAttachments.MEDITATION, MeditationState.started(filter, cultivation.beats()).withEcho(echo));
        player.getPersistentData().remove(WALL_SAID);
        player.getPersistentData().remove("murim.meditation.blocked_hint");
        // Cancel food already in progress; never release it (bows would fire on release).
        if (player.isUsingItem() && !(player.getUseItem().getItem() instanceof io.github.verycooltimo.murim.item.PillItem)) {
            player.stopUsingItem();
        }
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.MEDITATION_START.get(), SoundSource.PLAYERS, 0.8F, 1.0F);
        // The ring puzzles everybody once: one explanation the first time the circle appears (author 22.10).
        if (!player.getPersistentData().getBoolean("murim_ring_hint")) {
            player.getPersistentData().putBoolean("murim_ring_hint", true);
            PlayerJournal.record(player, Component.translatable("murim.meditation.ring_hint", Component.keybind("key.jump")));
        }
        // После даньтяня строка не нужна: виджет у хотбара сам показывает, что идёт.
        if (!cultivation.seeded()) {
            PlayerJournal.record(player, Component.translatable("murim.meditation.begin." + cultivation.beats()));
        }
        sync(player, SyncMeditationPayload.Event.NONE);
        // Съел пилюлю и сразу сел — поглощение (docs/design/19b §2, автор 03.10).
        PillService.onMeditationStart(player);
        io.github.verycooltimo.murim.quest.QuestLessons.meditated(player);
        return true;
    }

    /** Shared by server refusals and client input: one journal hint per sitting, including all seed beats. */
    public static void blockedAction(ServerPlayer player) {
        if (!player.getData(ModAttachments.MEDITATION).active()
                || player.getPersistentData().getBoolean("murim.meditation.blocked_hint")) {
            return;
        }
        player.getPersistentData().putBoolean("murim.meditation.blocked_hint", true);
        PlayerJournal.refuse(player, Component.translatable("murim.meditation.leave_first"));
    }

    /**
     * Искусство, которое игрок держит в руке: книга или страница с техникой, которую он знает, либо меч при выбранной
     * основе. Его осмысляет Эхо; пусто — Очищение. Методы дыхания (Шесть Равновесий как маршрут) сюда не входят.
     */
    public static ResourceLocation echoArt(ServerPlayer player) {
        io.github.verycooltimo.murim.mastery.MasteryState mastery = player.getData(ModAttachments.MASTERY);
        for (net.minecraft.world.item.ItemStack stack : new net.minecraft.world.item.ItemStack[] {
                player.getMainHandItem(), player.getOffhandItem()}) {
            ResourceLocation book = stack.get(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get());
            if (book != null && echoable(mastery, book)) {
                return io.github.verycooltimo.murim.mastery.Arts.masteryId(book);
            }
        }
        if (io.github.verycooltimo.murim.combat.QiSword.holdsSword(player.getMainHandItem())) {
            ResourceLocation foundation = player.getData(ModAttachments.LOADOUT).foundation().orElse(null);
            if (foundation != null && echoable(mastery, foundation)) {
                return io.github.verycooltimo.murim.mastery.Arts.masteryId(foundation);
            }
        }
        return null;
    }

    /** Знает ли игрок это боевое искусство (метод дыхания Эхом не осмысляется). */
    private static boolean echoable(io.github.verycooltimo.murim.mastery.MasteryState mastery, ResourceLocation formOrArt) {
        ResourceLocation art = io.github.verycooltimo.murim.mastery.Arts.masteryId(formOrArt);
        return !io.github.verycooltimo.murim.mastery.Arts.isMethod(art) && mastery.progress(art) != null;
    }

    /** Прерывает сессию. До семени незаконченный такт не засчитывается. */
    public static void stop(ServerPlayer player, String messageKey) {
        MeditationState was = player.getData(ModAttachments.MEDITATION);
        if (!was.active()) {
            return;
        }
        PillService.onMeditationStop(player, "murim.meditation.broken.hurt".equals(messageKey));
        if (was.breakingThrough()) {
            // UX #25: voluntary standing is a safe cancellation; external disruption still uses tickBreakthrough.
            if (was.breakthrough() >= Realm.WARNING_TICKS
                    && !"murim.meditation.stopped".equals(messageKey)) {
                breakthroughBroken(player, was.guarded());
                return;
            }
            // Встал во время предупреждения — прорыв отложен, без травмы.
            messageKey = "murim.rank.breakthrough.held";
        }
        player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.MEDITATION_STOP.get(), SoundSource.PLAYERS, 0.7F, 1.0F);
        if (messageKey != null) {
            PlayerJournal.record(player, Component.translatable(messageKey).withStyle(ChatFormatting.GRAY));
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
            idleWallHint(player);
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
        cultivation = player.getData(ModAttachments.CULTIVATION);
        if (cultivation.seeded()) {
            return;
        }
        SeedLogic.SessionResult result = SeedLogic.finishSession(cultivation, passed);
        player.setData(ModAttachments.CULTIVATION, result.state());
        // Следующий такт начинается сразу, без вставания. Семя — конец сидения: дальше
        // идёт сцена «внутреннего взгляда», и медитация с семенем начинается отдельно.
        player.setData(ModAttachments.MEDITATION, result.outcome() == SeedLogic.Outcome.SEED
                ? MeditationState.IDLE : state.nextBeat(result.state().beats()));
        SyncMeditationPayload.Event event = result.outcome() == SeedLogic.Outcome.HELD
                ? SyncMeditationPayload.Event.SETTLE : SyncMeditationPayload.Event.SCATTER;

        switch (result.outcome()) {
            case FIRST_FEELING -> {
                io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(player, "first_feeling");
                PlayerJournal.record(player,
                    Component.translatable("murim.meditation.first_feeling").withStyle(ChatFormatting.GRAY));
            }
            case HELD -> PlayerJournal.record(player,
                    Component.translatable("murim.meditation.held").withStyle(ChatFormatting.GRAY));
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
                createSeed(player, method, state.filter() ? 1.0D : 0.0D);
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        io.github.verycooltimo.murim.registry.ModSounds.QI_CHIME.get(), SoundSource.PLAYERS, 0.8F, 0.8F);
                PlayerJournal.record(player, Component.translatable("murim.meditation.seed")
                        .withStyle(ChatFormatting.AQUA));
                return;
            }
            default -> {
            }
        }
        sync(player, event);
    }

    /** Shared seed transition for the final meditation beat and debug commands (docs/design/19-dantian-qi-meditation.md).
     * The method is normally learned from a scroll before the ritual; ensure that track also exists for debug seeding.
     */
    public static void createSeed(ServerPlayer player, CultivationMethod method, double filteredShare) {
        player.setData(ModAttachments.CULTIVATION, player.getData(ModAttachments.CULTIVATION)
                .withMethod(method.id()).withBeats(CultivationState.SEEDED));
        player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        player.setData(ModAttachments.PROFILE,
                SeedLogic.seedProfile(player.getData(ModAttachments.PROFILE), method, filteredShare));
        io.github.verycooltimo.murim.mastery.MasteryService.learnMethod(player, method);
        io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
        io.github.verycooltimo.murim.mastery.LoadoutService.sync(player);
        ProfileNetwork.sync(player);
        sync(player, SyncMeditationPayload.Event.SEED);
    }

    /**
     * Искажение ци: мини-игра проиграна целиком (решение автора 30.09 — «огромный дебаф
     * и потеря всех сердец до половинки»). Пройденные такты не отнимаются: наказание —
     * тело, а не прогресс.
     */
    private static void backlash(ServerPlayer player) {
        // UX #23: the first ring is practice until it is mastered. No health or hunger penalty while learning.
        if (player.getData(ModAttachments.CULTIVATION).beats() == 1) {
            MeditationState state = player.getData(ModAttachments.MEDITATION);
            player.setData(ModAttachments.MEDITATION, MeditationState.started(state.filter(), 1));
            PlayerJournal.record(player, Component.translatable("murim.meditation.practice"));
            sync(player, SyncMeditationPayload.Event.SCATTER);
            return;
        }
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
                io.github.verycooltimo.murim.registry.ModSounds.ABSORB_FAIL.get(), SoundSource.PLAYERS, 1.0F, 0.8F);
        PlayerJournal.record(player, Component.translatable("murim.meditation.backlash")
                .withStyle(ChatFormatting.DARK_RED));
        sync(player, SyncMeditationPayload.Event.BACKLASH);
    }

    /** The method's wall bonus (Purple Sky: a larger reserve before the wall), growing with the method's stars. */
    static double methodWallFactor(ServerPlayer player) {
        CultivationMethod method = player.getData(ModAttachments.CULTIVATION).method().map(MethodLoader::get).orElse(null);
        if (method == null || method.capacityMult() == 1.0D) return 1.0D;
        var progress = player.getData(ModAttachments.MASTERY)
                .progress(io.github.verycooltimo.murim.mastery.Arts.methodArt(method.id()));
        return method.capacityFactor(progress == null ? 0 : progress.stars());
    }

    /** The visible sign of a method in meditation: a slow rise of coloured motes around the sitter. */
    static void mist(ServerPlayer player) {
        if (player.tickCount % 6 != 0) return;
        CultivationMethod method = player.getData(ModAttachments.CULTIVATION).method().map(MethodLoader::get).orElse(null);
        if (method == null || method.mist() == 0) return;
        var level = player.serverLevel();
        var colour = new org.joml.Vector3f(((method.mist() >> 16) & 255) / 255.0F, ((method.mist() >> 8) & 255) / 255.0F,
                (method.mist() & 255) / 255.0F);
        var dust = new net.minecraft.core.particles.DustParticleOptions(colour, 1.4F);
        for (int i = 0; i < 3; i++) {
            double a = level.random.nextDouble() * Math.PI * 2.0D;
            double r = 0.7D + level.random.nextDouble() * 0.5D;
            level.sendParticles(dust, player.getX() + Math.cos(a) * r, player.getY() + 0.1D, player.getZ() + Math.sin(a) * r,
                    0, 0.0D, 0.06D, 0.0D, 1.0D);
        }
    }

    /** После семени: запас растёт с падающей отдачей, циркулирующая наполняется быстрее. */
    private static void tickSeeded(ServerPlayer player, MeditationState state) {
        if (state.breakingThrough()) {
            tickBreakthrough(player, state);
            return;
        }
        // The pill is absorbed: he stands up by himself (author 22.10).
        String standKey = PillService.standUpKey(player);
        if (PillService.standUpDue(player)) {
            stop(player, standKey);
            return;
        }
        // Идёт поглощение пилюли: обычный прирост ждёт, пока сгустки осядут (docs/design/19b §2).
        if (PillService.tickAbsorb(player)) {
            player.setData(ModAttachments.MEDITATION, player.getData(ModAttachments.MEDITATION).active()
                    ? state.tick(null) : player.getData(ModAttachments.MEDITATION));
            return;
        }
        MeditationState next = state.tick(null);
        player.setData(ModAttachments.MEDITATION, next);
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        double gain = GAIN_PER_TICK * profile.efficiency()
                * io.github.verycooltimo.murim.mastery.MasteryService.practiceMethod(player)
                * io.github.verycooltimo.murim.world.PlaceService.gainFactor(player)
                // Пещера покаяния: медитация в келье ×1,5 (автор 03.10 п.7).
                * io.github.verycooltimo.murim.sect.seal.PenanceService.meditationFactor(player)
                // A cup of plum-blossom tea from the sect kitchen: a short, mild focus.
                * TeaFocus.factor(player);
        double cap = Realm.wall(profile) * methodWallFactor(player);
        DantianProfile updated = profile.withPool(Math.min(cap, profile.pool() + gain));
        mist(player);
        for (int i = 0; i < MEDITATION_CIRCULATION_STEPS; i++) {
            updated = updated.circulateOnce();
        }
        player.setData(ModAttachments.PROFILE, updated);
        // Эхо: 30 секунд на искусстве обрабатывают весь его резерв, без множителя; Очищение резерв не трогает.
        if (echoCompletion(player, next) == SyncMeditationPayload.Event.ECHO_DONE) {
            sync(player, SyncMeditationPayload.Event.ECHO_DONE);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    io.github.verycooltimo.murim.registry.ModSounds.MEDITATION_CYCLE.get(), SoundSource.PLAYERS, 0.3F, 1.15F);
            PlayerJournal.record(player, Component.translatable("murim.meditation.echo.done",
                    io.github.verycooltimo.murim.mastery.MasteryService.artName(next.echo())).withStyle(ChatFormatting.AQUA));
        }
        if (checkWall(player, updated, next)) {
            return;
        }
        if (next.ticks() % 20 == 0) {
            io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
            ProfileNetwork.sync(player);
            sync(player, SyncMeditationPayload.Event.NONE);
        }
    }

    /** The authoritative one-shot condition, shared by completion feedback and its regression test. */
    static SyncMeditationPayload.Event echoCompletion(ServerPlayer player, MeditationState next) {
        return next.echoing() && next.echoTicks() == ECHO_TICKS
                && io.github.verycooltimo.murim.mastery.MasteryService.comprehend(player, next.echo())
                ? SyncMeditationPayload.Event.ECHO_DONE : SyncMeditationPayload.Event.NONE;
    }

    /** Read-only view of the real rank/stage gates, shared with mentor advice. Null means ready. */
    public static Component readiness(ServerPlayer p) {
        var profile = p.getData(ModAttachments.PROFILE);
        boolean stage = profile.rank() == Realm.PEAK;
        Realm.Blocker blocker = stage ? Realm.checkStage(profile, p.getData(ModAttachments.MASTERY).combatStars())
                : Realm.check(profile, p.getData(ModAttachments.MASTERY).combatStars(),
                    io.github.verycooltimo.murim.sect.SectProgression.secondSparProof(p), GreatElixirs.ready(p));
        if (blocker == Realm.Blocker.NONE) {
            boolean body = stage ? io.github.verycooltimo.murim.training.BodyRules.stageReady(profile.stage() + 1,
                    io.github.verycooltimo.murim.training.TrainingService.level(p))
                    : io.github.verycooltimo.murim.training.BodyRules.breakthroughReady(profile.rank() + 1,
                    io.github.verycooltimo.murim.training.TrainingService.level(p));
            return body ? null : Component.translatable("murim.rank.wall.body");
        }
        if (blocker == Realm.Blocker.NO_DANTIAN) return Component.translatable("murim.threshold.no_dantian");
        if (blocker == Realm.Blocker.NOT_AT_WALL) return Component.translatable("murim.threshold.not_at_wall");
        if (stage && blocker == Realm.Blocker.NO_TECHNIQUE) return Component.translatable("murim.rank.wall.stage",
                io.github.verycooltimo.murim.mastery.MasteryService.requirement(p,
                    io.github.verycooltimo.murim.cultivation.RankRequirements.stageGate(profile.stage() + 1)));
        return wallReason(p, profile, blocker);
    }

    /**
     * Запас упёрся в стену: всё готово — начинается прорыв; не хватает техники — подсказка.
     *
     * @return {@code true}, если прорыв начался
     */
    static boolean checkWall(ServerPlayer player, DantianProfile profile, MeditationState state) {
        if (profile.rank() == Realm.PEAK) {
            return checkStage(player, profile, state);
        }
        Realm.Blocker blocker = Realm.check(profile, player.getData(ModAttachments.MASTERY).combatStars(),
                io.github.verycooltimo.murim.sect.SectProgression.secondSparProof(player), GreatElixirs.ready(player));
        // Author 21.10: a full qi wall still requires the body threshold.
        if (blocker == Realm.Blocker.NONE && !io.github.verycooltimo.murim.training.BodyRules.breakthroughReady(profile.rank() + 1,
                io.github.verycooltimo.murim.training.TrainingService.level(player))) {
            bodyHint(player, state);
            return false;
        }
        if (blocker == Realm.Blocker.NONE) {
            player.setData(ModAttachments.MEDITATION, state.withBreakthrough(0));
            // UX #25: the HUD explains the countdown and safe standing alongside the heartbeat.
            sync(player, SyncMeditationPayload.Event.BREAKTHROUGH);
            return true;
        }
        if (Realm.atWall(profile) && wallHintDue(player, state)) {
            wallHint(player);
        }
        return false;
    }

    /** The body is not ready for the breakthrough: said in words, no number (author 21.10). */
    static void bodyHint(ServerPlayer player, MeditationState state) {
        if (wallHintDue(player, state)) {
            wallHint(player);
        }
    }

    /** Session value in the player's data: the reason for the wall was said in this sitting (playtest 19.10). */
    static final String WALL_SAID = "murim_wall_said";

    /** Each distinct translated blocker is said once in this voluntary sitting. */
    static boolean wallHintDue(ServerPlayer player, MeditationState state) {
        Component reason = readiness(player);
        if (reason == null) return false;
        // Translation key + arguments distinguish successive gates, independently of the client's language.
        String blocker = reason.toString();
        var data = player.getPersistentData();
        var said = data.getCompound(WALL_SAID);
        if (said.getBoolean(blocker)) return false;
        said.putBoolean(blocker, true);
        data.put(WALL_SAID, said);
        return true;
    }

    private static void wallHint(ServerPlayer player) {
        Component reason = readiness(player);
        if (reason != null) sendWallHint(player, reason);
        sync(player, SyncMeditationPayload.Event.NONE);
    }

    private static void sendWallHint(ServerPlayer player, Component line) {
        // API: minecraft-src/net/minecraft/server/level/ServerPlayer.java#displayClientMessage — Component, boolean.
        player.displayClientMessage(line, true);
        io.github.verycooltimo.murim.sect.PlayerJournal.record(player, line);
    }

    static final String WALL_IDLE_DAY = "murim_wall_idle_day";

    static boolean idleWallHintDue(ServerPlayer player) {
        if (!Realm.atWall(player.getData(ModAttachments.PROFILE))) return false;
        var data = player.getPersistentData();
        long day = Math.floorDiv(player.level().getDayTime(), 24000L);
        if (data.contains(WALL_IDLE_DAY) && day <= data.getLong(WALL_IDLE_DAY)) return false;
        data.putLong(WALL_IDLE_DAY, day);
        return true;
    }

    public static void idleWallHint(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()
                || player.getData(ModAttachments.MEDITATION).active() || !idleWallHintDue(player)) return;
        Component reason = readiness(player);
        sendWallHint(player,
                reason == null ? Component.translatable("murim.rank.wall.sit") : reason);
    }

    /**
     * Why the breakthrough does not begin: the rank-two checklist, other art requirements, or the end of the road. {@code blocker} is what {@link Realm#check} said.
     */
    public static net.minecraft.network.chat.MutableComponent wallReason(ServerPlayer player, DantianProfile profile, Realm.Blocker blocker) {
        if (blocker == Realm.Blocker.NO_SECT_SKILLS) {
            var sect = player.getData(ModAttachments.SECT);
            if (!sect.member()) return Component.translatable("murim.rank.wall.join");
            if (io.github.verycooltimo.murim.sect.SectProgression.secondWins(sect) < 3)
                return Component.translatable("murim.rank.wall.spar");
            if (!sect.has(io.github.verycooltimo.murim.sect.SectProgression.SECOND_TEST))
                return Component.translatable("murim.rank.wall.exam");
            var stars = player.getData(ModAttachments.MASTERY).combatStars();
            return Component.translatable(stars.getOrDefault(io.github.verycooltimo.murim.sect.SectService.SIX, 0) < 6
                    ? "murim.rank.wall.six" : "murim.rank.wall.petal");
        }
        String key = blocker != Realm.Blocker.NO_TECHNIQUE ? "murim.rank.wall.max"
                : Realm.condition(profile.rank() + 1) == Realm.Condition.MASTER_ANY
                ? "murim.rank.wall.master_any" : "murim.rank.wall.technique";
        // The requirement is told in words, never as a number of stars (author 18.10).
        return Component.translatable(key,
                io.github.verycooltimo.murim.mastery.MasteryService.requirement(player, Realm.gate(profile.rank() + 1)));
    }

    /**
     * Подступень Пика утверждается без сцены прорыва (автор 03.10): стена запаса и искусство до звезды из таблицы
     * {@link RankRequirements} (★9 и ★11) — глаза открываются, звон, строка в чате. Травмы нет: это не стена ступени.
     *
     * @return {@code true}, если подступень утверждена и сессия закончилась
     */
    private static boolean checkStage(ServerPlayer player, DantianProfile profile, MeditationState state) {
        Realm.Blocker blocker = Realm.checkStage(profile, player.getData(ModAttachments.MASTERY).combatStars());
        if (blocker == Realm.Blocker.NONE && !io.github.verycooltimo.murim.training.BodyRules.stageReady(profile.stage() + 1,
                io.github.verycooltimo.murim.training.TrainingService.level(player))) {
            bodyHint(player, state);
            return false;
        }
        if (blocker == Realm.Blocker.NONE) {
            DantianProfile settled = Realm.settle(profile);
            io.github.verycooltimo.murim.quest.QuestLessons.advanced(player, profile.rank(), profile.stage());
            player.setData(ModAttachments.PROFILE, settled);
            player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
            ProfileNetwork.sync(player);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    io.github.verycooltimo.murim.registry.ModSounds.RANK_UP.get(), SoundSource.PLAYERS, 0.7F, 1.15F);
            PlayerJournal.record(player, Component.translatable("murim.rank.stage.done",
                    Component.translatable(Realm.nameKey(settled.rank())),
                    Component.translatable(Realm.stageKey(settled.stage()))).withStyle(ChatFormatting.GOLD));
            sync(player, SyncMeditationPayload.Event.NONE);
            return true;
        }
        if (Realm.atWall(profile) && wallHintDue(player, state)) wallHint(player);
        return false;
    }

    /**
     * Сцена прорыва идёт сама: игроку остаётся сидеть. Удар по телу прерывает её
     * (docs/design/19 §3б: «тело уязвимо»).
     */
    private static void tickBreakthrough(ServerPlayer player, MeditationState state) {
        // hurtTime выставляется при любом полученном уроне — так прерывание ловится без
        // отдельной подписки на событие урона.
        if (player.hurtTime > 0) {
            if (state.breakthrough() >= Realm.WARNING_TICKS) {
                breakthroughBroken(player, state.guarded());
            } else {
                stop(player, "murim.rank.breakthrough.held");
            }
            return;
        }
        MeditationState next = state.tick(null);
        player.setData(ModAttachments.MEDITATION, next);
        if (next.breakthrough() == Realm.WARNING_TICKS) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    io.github.verycooltimo.murim.registry.ModSounds.QI_CHARGE.get(), SoundSource.PLAYERS, 0.7F, 0.9F);
        }
        // Realm owns the scene-to-blow length; Third/Second use the lab, First/Peak retain their old scenes.
        int sceneTicks = Realm.breakthroughTicks(player.getData(ModAttachments.PROFILE).rank() + 1);
        // The layered sound: breathing, a rising hum, three crack clicks, 0.4 s of silence (BreakthroughWorld).
        BreakthroughWorld.tickSound(player, next.breakthrough(), sceneTicks);
        if (next.breakthrough() < Realm.WARNING_TICKS + sceneTicks) {
            if (next.breakthrough() % 5 == 0) {
                sync(player, SyncMeditationPayload.Event.NONE);
            }
            return;
        }
        int stateRankBeforeAdvance = player.getData(ModAttachments.PROFILE).rank();
        int stageBeforeAdvance = player.getData(ModAttachments.PROFILE).stage();
        DantianProfile risen = Realm.advance(player.getData(ModAttachments.PROFILE));
        if (risen.rank() > stateRankBeforeAdvance) io.github.verycooltimo.murim.quest.QuestLessons.advanced(player, stateRankBeforeAdvance, stageBeforeAdvance);
        player.setData(ModAttachments.PROFILE, risen);
        if (risen.rank() > stateRankBeforeAdvance) {
            GreatElixirs.consume(player);
            io.github.verycooltimo.murim.mastery.MasteryService.rankBreathingStar(player);
        }
        io.github.verycooltimo.murim.advancement.MurimAdvancements.rank(player, risen.rank());
        // The sect notices a new realm for a day (docs/design/39 «Реплики: объём и частота»).
        io.github.verycooltimo.murim.sect.SectEvents.record(player, io.github.verycooltimo.murim.sect.SectEvents.Event.REALM_UP);
        // После прорыва глаза открываются: сессия кончается. Иначе следом сразу шло
        // осмысление, и его титр ложился поверх титра ранга (кадры стенда 01.10).
        player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        RankEffects.apply(player);
        player.setHealth(player.getMaxHealth());
        ProfileNetwork.sync(player);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.BREAKTHROUGH.get(), SoundSource.PLAYERS, 0.9F, 1.0F);
        // Гонг под прорыв — тише самого прорыва, чтобы не спорить с ним (автор 03.10: «не взрывать уши»).
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.RANK_UP.get(), SoundSource.PLAYERS, 0.55F, 1.0F);
        PlayerJournal.record(player, Component.translatable("murim.rank.breakthrough.done",
                Component.translatable(Realm.nameKey(risen.rank()))).withStyle(ChatFormatting.GOLD));
        sync(player, SyncMeditationPayload.Event.RANK_UP);
        // The world answers: a shockwave knocks mobs back, mobs near run for ten seconds, the sect's people walk up and bow,
        // the echo carries 64 blocks. No weakness and no sitting after it: the new rank is at full strength at once.
        BreakthroughWorld.impact(player, risen.rank());
    }

    /**
     * The scene was interrupted. With the first disruption taken by a guardian ({@code guarded}) the stop costs no
     * progress; without one it is the injury below.
     */
    private static void breakthroughBroken(ServerPlayer player, boolean guarded) {
        if (guarded) {
            breakthroughShaken(player);
        } else {
            breakthroughBroken(player);
        }
    }

    /** A second disruption after a guardian took the first: the scene ends, the reserve is whole, no weakness. */
    private static void breakthroughShaken(ServerPlayer player) {
        player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.MEDITATION_STOP.get(), SoundSource.PLAYERS, 0.7F, 0.8F);
        PlayerJournal.record(player, Component.translatable("murim.rank.breakthrough.shaken").withStyle(ChatFormatting.GRAY));
        sync(player, SyncMeditationPayload.Event.NONE);
    }

    /** Прорыв прерван: травма меридиан — треть запаса и слабость, ранг прежний. */
    private static void breakthroughBroken(ServerPlayer player) {
        player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        player.setData(ModAttachments.PROFILE, Realm.injure(player.getData(ModAttachments.PROFILE)));
        if (!player.isCreative()) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, BACKLASH_TICKS, 1));
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0));
        }
        ProfileNetwork.sync(player);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.BREAKTHROUGH_FAIL.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        PlayerJournal.refuse(player, Component.translatable("murim.rank.breakthrough.broken")
                .withStyle(ChatFormatting.DARK_RED));
        sync(player, SyncMeditationPayload.Event.BROKEN);
    }

    /** Прирост запаса за тик до множителей: одинаков в любую секунду сессии (затухания нет). */
    public static double gainPerTick() {
        return GAIN_PER_TICK;
    }

    public static void sync(ServerPlayer player, SyncMeditationPayload.Event event) {
        SyncMeditationPayload payload = snapshot(player, event);
        // GameTest mock players have no client channel for our payloads.
        if (player.connection != null && player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** What the client is told about the sitting: the state, the ring and, for the Echo, the art and its moves. */
    public static SyncMeditationPayload snapshot(ServerPlayer player, SyncMeditationPayload.Event event) {
        MeditationState state = player.getData(ModAttachments.MEDITATION);
        CultivationState cultivation = player.getData(ModAttachments.CULTIVATION);
        SyncMeditationPayload.Ring ring = SyncMeditationPayload.Ring.NONE;
        RingMinigame game = state.ring();
        if (state.active() && game != null) {
            ring = new SyncMeditationPayload.Ring(true, (float) game.radius(),
                    (float) game.bandCentre(),
                    (float) game.bandHalfWidth(),
                    (float) game.stability(), (float) game.strain());
        }
        // The Echo tells the client which art and which moves its double repeats (the last fight with that art).
        java.util.Optional<ResourceLocation> echo = state.echoing() ? java.util.Optional.of(state.echo()) : java.util.Optional.empty();
        java.util.List<ResourceLocation> moves = state.echoing()
                ? io.github.verycooltimo.murim.mastery.EchoMoves.forArt(player, state.echo()) : java.util.List.of();
        return new SyncMeditationPayload(state.active(),
                cultivation.beats(), state.ticks(), event, ring,
                state.active() ? state.breakthrough() : -1, player.getData(ModAttachments.PROFILE).rank(), echo, moves, breathingStars(player),
                state.active() && state.breakthrough() < 0 && Realm.atWall(player.getData(ModAttachments.PROFILE))
                        ? java.util.Optional.ofNullable(readiness(player)) : java.util.Optional.empty());
    }

    /** Server source for the meditation visual multiplier; the cultivation selection IS the breathing slot. */
    public static int breathingStars(ServerPlayer player) {
        return player.getData(ModAttachments.CULTIVATION).method()
                .map(io.github.verycooltimo.murim.mastery.Arts::methodArt)
                .map(id -> player.getData(ModAttachments.MASTERY).progress(id))
                .map(io.github.verycooltimo.murim.mastery.TechniqueProgress::stars).orElse(0);
    }

    private MeditationService() {
    }
}
