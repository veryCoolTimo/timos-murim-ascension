package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.PillPayloads;
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
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Пилюли на сервере: окно «сразу», поглощение в медитации, награда и травма
 * (docs/design/19b §1–2, решения автора 03.10).
 *
 * <p>Правила — в {@link PillRules} и {@link AbsorbGame}; здесь время, мир и последствия.
 * Звуки — только хуки ({@link #sound}): их подбирает отдельная задача.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PillService {

    /** Идущее поглощение игрока; живёт только в сессии. */
    public static final class Slot {
        AbsorbGame game;
        /** Сколько каждой пилюли было съедено ДО этой сессии: повтор считается от них. */
        int[] takenBefore = new int[PillKind.values().length];
        /** Тик, до которого техники дешевле (Пилюля Тысячи Ядов). Не сохраняется. */
        long discountUntil;
        /** Урон прямо сейчас наносит волна места силы: медитацию он не прерывает. */
        boolean waveHit;
        int lastChoice;
        /** Камень жилы рядом с медитирующим (docs/design/19b §3); {@code null} — места силы нет. */
        public io.github.verycooltimo.murim.world.Place place;
        /** Тик взрыва урагана после полного успеха; 0 — нет. */
        long stormAt;
    }

    /** Травма после искажения ци: слабость 2 минуты. */
    static final int INJURY_TICKS = 2400;
    /** Встал при напряжении выше этой доли — лёгкая травма остаётся. */
    static final double STAND_INJURY = 0.7D;
    /** Скидка Пилюли Тысячи Ядов на цену техник: 20 минут, −10 %. */
    static final int DISCOUNT_TICKS = 24000;
    static final double DISCOUNT = 0.9D;

    private PillService() {
    }

    // ------------------------------------------------------------------ еда

    /** Можно ли съесть пилюлю сейчас (проверка и на клиенте — до начала поедания). */
    public static PillRules.Refusal check(Player player, PillKind kind, DantianProfile profile, PillState pills) {
        if (!profile.isAwakened()) {
            return PillRules.Refusal.NO_DANTIAN;
        }
        if (player.getLastHurtByMob() != null
                && player.tickCount - player.getLastHurtByMobTimestamp() < PillRules.COMBAT_LOCK_TICKS) {
            return PillRules.Refusal.IN_COMBAT;
        }
        long now = player.level().getGameTime();
        List<PillKind> pending = pills.windowOpen(now) ? pills.pending() : List.of();
        return PillRules.canAdd(pending, kind);
    }

    public static Component refusalText(PillRules.Refusal r) {
        return Component.translatable("murim.pill.refuse." + r.name().toLowerCase(java.util.Locale.ROOT))
                .withStyle(ChatFormatting.GRAY);
    }

    /** Пилюля съедена: открывается или продлевается окно «сразу». */
    public static void eat(ServerPlayer player, PillKind kind) {
        expireWindow(player);
        PillState pills = player.getData(ModAttachments.PILLS);
        long now = player.level().getGameTime();
        player.setData(ModAttachments.PILLS, pills.withEaten(kind, now));
        sound(player, "pill_eat");
        player.displayClientMessage(Component.translatable("murim.pill.window", Component.translatable(
                "item.murim." + kind.itemId())).withStyle(ChatFormatting.AQUA), true);
        sync(player, PillPayloads.Event.NONE);
    }

    /** Окно истекло, а игрок не сел — срабатывает десятая часть, остальное уходит паром. */
    private static void expireWindow(ServerPlayer player) {
        PillState pills = player.getData(ModAttachments.PILLS);
        if (pills.pending().isEmpty() || pills.windowOpen(player.level().getGameTime())) {
            return;
        }
        List<PillKind> eaten = pills.pending();
        double[] none = new double[eaten.size()];
        int[] taken = takenSnapshot(pills);
        player.setData(ModAttachments.PILLS, pills.closed());
        reward(player, eaten, none, taken);
        for (PillKind k : eaten) {
            if (k == PillKind.THOUSAND_POISON && !natureIsPoison(player)) {
                player.addEffect(new MobEffectInstance(MobEffects.POISON, 300, 0));
            }
            if (k == PillKind.BEAUTY_TEAR && !player.isCreative()) {
                tearUnabsorbed(player);
            }
        }
        player.displayClientMessage(Component.translatable("murim.pill.wasted").withStyle(ChatFormatting.GRAY), true);
        sync(player, PillPayloads.Event.EXHALE);
    }

    /** Слеза без поглощения — почти смерть (канон: «другие умерли бы, едва яд коснулся языка», гл. 228). */
    private static void tearUnabsorbed(ServerPlayer player) {
        // «Почти смерть», не смерть: яд в Minecraft не добивает с последнего сердца, иссушение
        // добило бы (кадры стенда 03.10 — игрок умер), поэтому его нет.
        player.setHealth(Math.min(player.getHealth(), 1.0F));
        player.addEffect(new MobEffectInstance(MobEffects.POISON, 400, 1));
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, INJURY_TICKS, 1));
    }

    private static int[] takenSnapshot(PillState pills) {
        int[] t = new int[PillKind.values().length];
        for (PillKind k : PillKind.values()) {
            t[k.ordinal()] = pills.taken(k);
        }
        return t;
    }

    static boolean natureIsPoison(ServerPlayer player) {
        return "poison".equals(player.getData(ModAttachments.PROFILE).nature());
    }

    // ------------------------------------------------------------------ поглощение

    /**
     * Медитация началась: если окно открыто — начинается поглощение. Вызывается из
     * {@link MeditationService#start} после посадки.
     */
    public static void onMeditationStart(ServerPlayer player) {
        if (!player.getData(ModAttachments.CULTIVATION).seeded()) {
            return;
        }
        PillState pills = player.getData(ModAttachments.PILLS);
        if (!pills.windowOpen(player.level().getGameTime())) {
            return;
        }
        Slot slot = player.getData(ModAttachments.ABSORB);
        slot.takenBefore = takenSnapshot(pills);
        slot.game = AbsorbGame.start(pills.pending(), natureIsPoison(player), player.getRandom()::nextDouble);
        slot.lastChoice = 0;
        player.setData(ModAttachments.PILLS, pills.closed());
        sound(player, "absorb_start");
        sync(player, PillPayloads.Event.NONE);
    }

    /** Урон прямо сейчас — от волны места силы: медитацию не срывает. */
    public static boolean waveHit(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).waveHit;
    }

    public static void setWaveHit(ServerPlayer player, boolean value) {
        player.getData(ModAttachments.ABSORB).waveHit = value;
    }

    public static boolean absorbing(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).game != null;
    }

    public static void choose(ServerPlayer player, int side) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        if (slot.game != null) {
            slot.game.choose(side);
            slot.lastChoice = side;
        }
    }

    /**
     * Тик поглощения внутри медитации.
     *
     * @return {@code true}, если поглощение идёт и обычный прирост медитации на этом тике не нужен
     */
    public static boolean tickAbsorb(ServerPlayer player) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        AbsorbGame game = slot.game;
        if (game == null) {
            return false;
        }
        game.tick(player.getRandom()::nextDouble);
        switch (game.lastOutcome()) {
            case WILD_SHORT -> sound(player, "absorb_strike");
            case CALM_SHORT -> sound(player, "absorb_settle");
            case WILD_LONG, CALM_LONG -> sound(player, "absorb_cool");
            default -> {
            }
        }
        if (game.result() == AbsorbGame.Result.FINISHED) {
            finish(player, slot);
            return true;
        }
        if (game.result() == AbsorbGame.Result.BACKLASH) {
            backlash(player, slot);
            return true;
        }
        PillPayloads.Event event = game.phase() == AbsorbGame.Phase.SETTLE && game.phaseTicks() == 1
                ? PillPayloads.Event.SETTLED : PillPayloads.Event.NONE;
        sync(player, event);
        return true;
    }

    private static double[] settled(AbsorbGame game) {
        double[] s = new double[game.clots().size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = game.settled(i);
        }
        return s;
    }

    private static void finish(ServerPlayer player, Slot slot) {
        AbsorbGame game = slot.game;
        slot.game = null;
        reward(player, game.clots(), settled(game), slot.takenBefore);
        boolean rare = game.clots().stream().anyMatch(PillKind::rare);
        sound(player, rare ? "absorb_five_colours" : "absorb_finish");
        player.displayClientMessage(Component.translatable(rare ? "murim.pill.done.rare" : "murim.pill.done")
                .withStyle(ChatFormatting.AQUA), true);
        sync(player, PillPayloads.Event.FINISH, game);
        if (storm(game)) {
            slot.stormAt = player.level().getGameTime() + STORM_DELAY;
        }
        // Медитация продолжается: если запас упёрся в стену и условия ранга выполнены,
        // MeditationService сам переведёт сидение в принятую сцену прорыва.
    }

    /** Искажение ци: напряжение до конца. Остаток сгорает, тело травмировано. */
    private static void backlash(ServerPlayer player, Slot slot) {
        AbsorbGame game = slot.game;
        slot.game = null;
        // То, что успело осесть до срыва, остаётся: наказание — тело, а не уже усвоенное.
        reward(player, game.clots(), settled(game), slot.takenBefore);
        injure(player, game.clots().size() > 1 ? 1.0D / 3.0D : 0.2D, game.clots().size() > 1);
        if (game.clots().contains(PillKind.BEAUTY_TEAR) && PillRules.shield(game.clots()) >= 1.0D && !player.isCreative()) {
            tearUnabsorbed(player);
        }
        player.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        player.displayClientMessage(Component.translatable("murim.pill.backlash").withStyle(ChatFormatting.DARK_RED), false);
        sound(player, "absorb_backlash");
        sync(player, PillPayloads.Event.BACKLASH, game);
        MeditationService.sync(player, SyncMeditationPayload.Event.BACKLASH);
    }

    /**
     * Сессия прервана (встал, сдвинулся, удар). То, что осело, остаётся; остаток уходит.
     * Встал при напряжении выше 70 % или от удара — лёгкая травма.
     */
    public static void onMeditationStop(ServerPlayer player, boolean hurt) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        AbsorbGame game = slot.game;
        if (game == null) {
            return;
        }
        slot.game = null;
        reward(player, game.clots(), settled(game), slot.takenBefore);
        if (hurt || game.strain() >= STAND_INJURY) {
            injure(player, 0.1D, false);
        }
        sync(player, PillPayloads.Event.INTERRUPTED, game);
    }

    private static void injure(ServerPlayer player, double poolLoss, boolean heavy) {
        DantianProfile p = player.getData(ModAttachments.PROFILE);
        player.setData(ModAttachments.PROFILE, p.withPool(p.pool() * (1.0D - poolLoss)));
        ProfileNetwork.sync(player);
        if (player.isCreative()) {
            return;
        }
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, INJURY_TICKS, heavy ? 1 : 0));
        if (heavy) {
            player.setHealth(Math.min(player.getHealth(), player.getMaxHealth() * 0.5F));
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0));
        }
    }

    // ------------------------------------------------------------------ награда

    /**
     * Награда за пилюли: запас (доля стены, не выше стены), постоянные прибавки и особые
     * эффекты. {@code settled} — осевшая доля каждого сгустка; ноль — пилюля не поглощалась.
     */
    static void reward(ServerPlayer player, List<PillKind> kinds, double[] settled, int[] takenBefore) {
        DantianProfile p = player.getData(ModAttachments.PROFILE);
        if (!p.isAwakened()) {
            return;
        }
        double wall = Realm.wall(p);
        double mult = PillRules.comboMultiplier(kinds);
        double gain = 0.0D;
        double capacity = p.capacity();
        double meridians = p.meridians();
        double purity = p.purity();
        boolean refill = false;
        double circulating = p.circulating();
        for (int i = 0; i < kinds.size(); i++) {
            PillKind k = kinds.get(i);
            int taken = takenBefore[k.ordinal()];
            double s = settled[i];
            gain += PillRules.poolGain(k, wall, s, taken, mult);
            switch (k) {
                case SNOW_PLUM -> {
                    if (s >= 0.5D) {
                        refill = true;
                    } else {
                        circulating += p.maxCirculating() * 0.5D;
                    }
                }
                case ORIGIN_ENERGY -> {
                    capacity *= 1.0D + PillRules.permanent(0.04D, s, taken);
                    meridians += PillRules.permanent(0.03D, s, taken);
                }
                case THOUSAND_POISON -> {
                    if (s >= 0.5D) {
                        player.setData(ModAttachments.PILLS, player.getData(ModAttachments.PILLS).withPoisonResistant());
                        player.getData(ModAttachments.ABSORB).discountUntil = player.level().getGameTime() + DISCOUNT_TICKS;
                    }
                }
                case BEAUTY_TEAR -> purity += PillRules.permanent(0.05D, s, taken);
            }
        }
        if (anyAbsorbed(settled)) {
            purity += PillRules.comboPurity(kinds) * PillRules.repeatFactor(takenBefore[PillKind.ORIGIN_ENERGY.ordinal()]);
        }
        DantianProfile next = p.withAxes(capacity, purity, meridians);
        next = next.withPool(Math.min(Realm.wall(next), next.pool() + gain));
        next = next.withCirculating(refill ? next.maxCirculating() : circulating);
        player.setData(ModAttachments.PROFILE, next);
        ProfileNetwork.sync(player);
    }

    private static boolean anyAbsorbed(double[] settled) {
        for (double s : settled) {
            if (s > 0.0D) {
                return true;
            }
        }
        return false;
    }

    /** Множитель цены техник: Пилюля Тысячи Ядов делает их дешевле на 20 минут. */
    public static double costFactor(ServerPlayer player) {
        return player.level().getGameTime() < player.getData(ModAttachments.ABSORB).discountUntil ? DISCOUNT : 1.0D;
    }

    // ------------------------------------------------------------------ тик и события

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Slot slot = player.getData(ModAttachments.ABSORB);
        if (slot.stormAt > 0 && player.level().getGameTime() >= slot.stormAt) {
            slot.stormAt = 0;
            stormBlast(player);
        }
        PillState pills = player.getData(ModAttachments.PILLS);
        if (pills.pending().isEmpty()) {
            return;
        }
        if (!pills.windowOpen(player.level().getGameTime())) {
            expireWindow(player);
        } else if (player.tickCount % 10 == 0) {
            sync(player, PillPayloads.Event.NONE);
        }
    }

    /** Шанс выпадения с бандита, убитого игроком (docs/design/19b §1 «Где взять сейчас»). */
    static final float BANDIT_SNOW_PLUM = 0.25F;
    static final float BANDIT_ORIGIN = 0.03F;

    /**
     * Лут бандитов: сливовая часто, изначальная редко. Через событие, а не правкой класса
     * бандита. API: reference/neoforge-src/net/neoforged/neoforge/event/entity/living/LivingDropsEvent.java
     */
    @SubscribeEvent
    static void onDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (!(event.getEntity() instanceof io.github.verycooltimo.murim.entity.Bandit bandit) || !event.isRecentlyHit()
                || !(event.getSource().getEntity() instanceof Player)) {
            return;
        }
        var random = bandit.getRandom();
        if (random.nextFloat() < BANDIT_SNOW_PLUM) {
            drop(event, bandit, io.github.verycooltimo.murim.registry.ModItems.PILL_SNOW_PLUM.get());
        }
        if (random.nextFloat() < BANDIT_ORIGIN) {
            drop(event, bandit, io.github.verycooltimo.murim.registry.ModItems.PILL_ORIGIN_ENERGY.get());
        }
    }

    private static void drop(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event,
                             net.minecraft.world.entity.LivingEntity from, net.minecraft.world.item.Item item) {
        event.getDrops().add(new net.minecraft.world.entity.item.ItemEntity(from.level(), from.getX(), from.getY() + 0.5D,
                from.getZ(), new net.minecraft.world.item.ItemStack(item)));
    }

    /** Стойкость к ядам (Пилюля Тысячи Ядов): яд действует вдвое короче. */
    @SubscribeEvent
    static void onEffect(MobEffectEvent.Applicable event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MobEffectInstance e = event.getEffectInstance();
        if (e == null || !e.is(MobEffects.POISON) || e.getDuration() < 40
                || !player.getData(ModAttachments.PILLS).poisonResistant()) {
            return;
        }
        // Нечётная длительность — метка уже укороченного: без неё повторное применение
        // укорачивало бы бесконечно. API: reference/neoforge-src/.../MobEffectEvent.java#Applicable
        if (e.getDuration() % 2 == 1) {
            return;
        }
        event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        player.addEffect(new MobEffectInstance(MobEffects.POISON, e.getDuration() / 2 | 1, e.getAmplifier()));
    }

    /** Взрыв — в миг выброса финала (тик 65 из 110). */
    static final int STORM_DELAY = 65;
    /** Средняя осевшая доля для «полного успеха». */
    static final double STORM_QUALITY = 0.85D;

    /**
     * Полный успех с сильной пилюлей (автор 03.10: «если прям полный успех вместе с очень хорошей
     * пилюлей — как ураган и взрыв от ТНТ»): в составе Слеза Красоты или канонический состав,
     * осело в среднем не меньше 85 %.
     */
    static boolean storm(AbsorbGame game) {
        boolean strong = game.clots().contains(PillKind.BEAUTY_TEAR) || PillRules.canonTriple(game.clots());
        if (!strong || game.result() != AbsorbGame.Result.FINISHED) {
            return false;
        }
        double sum = 0.0D;
        for (int i = 0; i < game.clots().size(); i++) {
            sum += Math.min(1.0D, game.settled(i));
        }
        return sum / game.clots().size() >= STORM_QUALITY;
    }

    /** Ударная волна взрыва: отбрасывает и ранит существ вокруг, блоки не трогает. */
    private static void stormBlast(ServerPlayer player) {
        var level = player.serverLevel();
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                player.getBoundingBox().inflate(7.0D), e -> e != player && e.isAlive())) {
            var d = e.position().subtract(player.position());
            double dist = Math.max(0.5D, d.length());
            double k = 1.0D - dist / 8.0D;
            e.knockback(1.6D * k, -d.x / dist, -d.z / dist);
            e.hurt(level.damageSources().explosion(player, player), (float) (8.0D * k));
        }
    }

    /** Звуковой хук: звуки подбирает отдельная задача; пока — тихие ванильные заглушки. */
    static void sound(ServerPlayer player, String hook) {
        var sound = switch (hook) {
            case "pill_eat" -> SoundEvents.GENERIC_EAT;
            case "absorb_strike" -> SoundEvents.PLAYER_HURT;
            case "absorb_five_colours", "absorb_finish" -> SoundEvents.BEACON_POWER_SELECT;
            case "absorb_backlash" -> SoundEvents.PLAYER_HURT;
            default -> null;
        };
        if (sound != null) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 0.5F, 1.0F);
        }
    }

    // ------------------------------------------------------------------ синхронизация

    public static void sync(ServerPlayer player, PillPayloads.Event event) {
        sync(player, event, player.getData(ModAttachments.ABSORB).game);
    }

    private static void sync(ServerPlayer player, PillPayloads.Event event, AbsorbGame game) {
        PillState pills = player.getData(ModAttachments.PILLS);
        long now = player.level().getGameTime();
        int[] pending = pills.windowOpen(now) ? pills.pending().stream().mapToInt(Enum::ordinal).toArray() : new int[0];
        int left = pills.windowOpen(now) ? (int) (pills.deadline() - now) : 0;
        boolean active = player.getData(ModAttachments.ABSORB).game != null;
        PillPayloads.Sync payload;
        if (game == null) {
            payload = new PillPayloads.Sync(pending, left, false, new int[0], 0, 0, 0, 0, 0, 0, false, 0, 0.0F,
                    0, event, false, false);
        } else {
            int temper = game.showsTemper() || game.phase() == AbsorbGame.Phase.BRANCH ? (game.wildNow() ? 2 : 1) : 0;
            payload = new PillPayloads.Sync(pending, left, active,
                    game.clots().stream().mapToInt(Enum::ordinal).toArray(), game.clot(), game.fork(),
                    game.phase().ordinal(), game.phaseTicks(), game.choice(), game.currentFork().shortSide(),
                    game.tookShort(), temper, (float) game.strain(), game.lastOutcome().ordinal(), event,
                    PillRules.canonTriple(game.clots()), event == PillPayloads.Event.FINISH && storm(game));
        }
        PacketDistributor.sendToPlayer(player, payload);
    }
}
