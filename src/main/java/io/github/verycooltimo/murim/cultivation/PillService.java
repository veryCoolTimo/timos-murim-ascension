package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.flow.FlowCharacter;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import io.github.verycooltimo.murim.cultivation.flow.FlowSpec;
import io.github.verycooltimo.murim.network.PillPayloads;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import io.github.verycooltimo.murim.registry.ModSounds;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Пилюли на сервере: окно «сразу», игра «Провести поток» в медитации, награда и подлёт
 * (docs/design/19b §1–2 и §10; the numbers and the game are the author's of 25.10, mockup plan-site/extra/mockups/pill-flow).
 *
 * <p>Правила - в {@link PillRules}, поток - в {@link FlowGame}; здесь время, мир и последствия. The client plays the game from the
 * server's seed and reports how it ended ({@link #report}); the server derives the quality from the report and applies the qi,
 * the damage and the tolerance. Звуки - только хуки ({@link #sound}): их подбирает отдельная задача.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PillService {

    /** One running game: the pills, what they are worth, and the last thing the client reported. */
    static final class Session {
        final int id;
        final List<PillKind> kinds;
        final PillRules.Plan plan;
        final long startTick;
        /** A client that never reports (it closed, or crashed) is paid as a timeout after this tick. */
        final long deadline;
        /** The earliest tick a run can honestly end: a clean one takes about half of the dissolve time. */
        final long earliest;
        boolean automatic;
        int progress;
        int lost;

        Session(int id, List<PillKind> kinds, PillRules.Plan plan, long start, int timeTicks) {
            this.id = id;
            this.kinds = kinds;
            this.plan = plan;
            this.startTick = start;
            this.deadline = start + timeTicks + WATCHDOG_TICKS;
            this.earliest = start + (long) (EARLIEST_SHARE * timeTicks);
        }
    }

    /** Идущее поглощение игрока; живёт только в сессии. */
    public static final class Slot {
        Session flow;
        int sessions;
        /** Тик, до которого техники дешевле (Пилюля Тысячи Ядов). Не сохраняется. */
        long discountUntil;
        /** Урон прямо сейчас наносит волна места силы: медитацию он не прерывает. */
        boolean waveHit;
        /** Камень жилы рядом с медитирующим (docs/design/19b §3); {@code null} — места силы нет. */
        public io.github.verycooltimo.murim.world.Place place;
        /** Подлёт при безупречном усвоении высшей пилюли: возраст сцены в тиках, −1 — не летит (docs/design/19b §10). */
        int liftAge = -1;
        float liftHeight;
        boolean liftBump;
        /** Удар сорвал поглощение: искажение ци выплачивается на ближайшем тике (после самого удара). */
        boolean deviation;
        /** Идёт сам удар-искажение: повторно не срабатывает. */
        boolean deviating;
        /** Game time when a finished absorption stands the player up by himself; -1 - none. */
        long standAt = -1L;
        /** What the stand-up says: the pill is absorbed, or the body was torn. */
        String standKey = "murim.pill.standup";
        /** The pill that was refused at the first try as «the body will not endure», and when (the second try confirms). */
        PillKind warnKind;
        long warnAt = -1L;
    }

    /** Травма непоглощённой слезы: слабость 2 минуты (только за «не сел», поглощение никого не ранит). */
    static final int INJURY_TICKS = 2400;
    /** Скидка Пилюли Тысячи Ядов на цену техник: 20 минут, −10 %. */
    static final int DISCOUNT_TICKS = 24000;
    static final double DISCOUNT = 0.9D;
    /** The client has this long after the dissolve time to report before the server pays the game as a timeout. */
    static final int WATCHDOG_TICKS = 160;
    /** A run cannot honestly end before this share of the dissolve time (the stream never goes faster than 2.1 / time). */
    static final double EARLIEST_SHARE = 0.40D;

    private PillService() {
    }

    // ------------------------------------------------------------------ еда

    /** Можно ли съесть пилюлю сейчас (проверка и на клиенте — до начала поедания). */
    public static PillRules.Refusal check(Player player, PillKind kind, DantianProfile profile, PillState pills) {
        if (!profile.isAwakened()) {
            return PillRules.Refusal.NO_DANTIAN;
        }
        if (player.getData(ModAttachments.MEDITATION).active()) {
            return PillRules.Refusal.MEDITATING;
        }
        if (player instanceof ServerPlayer server && absorbing(server)) {
            return PillRules.Refusal.ABSORBING;
        }
        // Several pills eaten inside the window go into ONE stream (up to three, all different).
        long now = player.level().getGameTime();
        if (pills.windowOpen(now)) {
            if (player instanceof ServerPlayer server && GreatElixirs.assisted(server, pills)) return PillRules.Refusal.FULL;
            return PillRules.canAdd(pills.pending(), kind);
        }
        return PillRules.Refusal.NONE;
    }

    public static Component refusalText(PillRules.Refusal r) {
        return Component.translatable(r == PillRules.Refusal.MEDITATING ? "murim.meditation.leave_first"
                : "murim.pill.refuse." + r.name().toLowerCase(java.util.Locale.ROOT))
                .withStyle(ChatFormatting.GRAY);
    }

    /**
     * Пилюля съедена стоя: открывается (или продлевается) окно до посадки (ADR-0008, 21.10).
     *
     * <p>A pill (or a load) that would tear the body is not eaten at the first try: «тело чувствует, что не выдержит». The same pill
     * again within {@link PillRules#WARN_TICKS} confirms.
     *
     * @return {@code true} if the pill was eaten (the caller takes the item), {@code false} if it was refused or only warned about
     */
    public static boolean eat(ServerPlayer player, PillKind kind) {
        if (kind.utility()) {
            return false; // everyday utility pills never open the window: PillEffects.use
        }
        PillRules.Refusal refusal = check(player, kind, player.getData(ModAttachments.PROFILE),
                player.getData(ModAttachments.PILLS));
        if (refusal != PillRules.Refusal.NONE) {
            PlayerJournal.refuse(player, refusalText(refusal));
            return false;
        }
        expireWindow(player);
        PillState pills = player.getData(ModAttachments.PILLS);
        long now = player.level().getGameTime();
        Slot slot = player.getData(ModAttachments.ABSORB);
        List<PillKind> together = new ArrayList<>(pills.pending());
        together.add(kind);
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        PillRules.Plan plan = PillRules.plan(together, profile.rank(), Realm.wall(profile), PillRules.tolerance(pills, together, now));
        boolean confirmed = slot.warnKind == kind && slot.warnAt >= 0L && now - slot.warnAt <= PillRules.WARN_TICKS;
        boolean full = profile.pool() >= Realm.wall(profile);
        if ((plan.torn() || full) && !confirmed) {
            slot.warnKind = kind;
            slot.warnAt = now;
            String warning = plan.torn() ? (plan.tooHigh() ? "murim.pill.warn" : "murim.pill.warn.load")
                    : "murim.pill.warn.full";
            PlayerJournal.record(player, Component.translatable(warning).withStyle(ChatFormatting.RED));
            sound(player, "absorb_overflow");
            return false;
        }
        slot.warnKind = null;
        slot.warnAt = -1L;
        player.setData(ModAttachments.PILLS, pills.withEaten(kind, now));
        PillEffects.onEaten(player, kind);
        sound(player, "pill_eat");
        PlayerJournal.record(player, Component.translatable("murim.pill.window", Component.translatable(
                "item.murim." + kind.itemId())).withStyle(ChatFormatting.AQUA));
        sync(player, PillPayloads.Event.NONE);
        return true;
    }

    /**
     * Окно истекло, а игрок не сел (игра только для сидящего): срабатывает пятая часть ци ({@link PillRules#STANDING_SHARE}),
     * остальное теряется. Пилюля, которую тело не выдержит, рвёт его так же, как за игрой.
     */
    static void expireWindow(ServerPlayer player) {
        PillState pills = player.getData(ModAttachments.PILLS);
        if (pills.pending().isEmpty() || pills.windowOpen(player.level().getGameTime())) {
            return;
        }
        long now = player.level().getGameTime();
        List<PillKind> eaten = pills.pending();
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        PillRules.Plan plan = PillRules.plan(eaten, profile.rank(), Realm.wall(profile), PillRules.tolerance(pills, eaten, now));
        player.setData(ModAttachments.PILLS, pills.closed());
        if (plan.torn()) {
            tear(player, plan);
            sync(player, PillPayloads.Event.EXHALE);
            return;
        }
        Award award = award(player, plan, PillRules.STANDING_SHARE, false);
        player.setData(ModAttachments.PILLS, player.getData(ModAttachments.PILLS).afterIntake(eaten, now));
        for (PillKind k : eaten) {
            if (k == PillKind.THOUSAND_POISON && !natureIsPoison(player)) {
                player.addEffect(new MobEffectInstance(MobEffects.POISON, 300, 0));
            }
            if (k == PillKind.BEAUTY_TEAR && !player.isCreative()) {
                tearUnabsorbed(player);
            }
        }
        PlayerJournal.record(player, resultText("murim.pill.standing", PillRules.STANDING_SHARE, award));
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

    static boolean natureIsPoison(ServerPlayer player) {
        return "poison".equals(player.getData(ModAttachments.PROFILE).nature());
    }

    // ------------------------------------------------------------------ игра

    /**
     * Медитация началась: если окно открыто - начинается игра «Провести поток». Вызывается из
     * {@link MeditationService#start} после посадки. Ranks, load and tolerance are decided here, once: a body that does not endure
     * is torn at once (no game), otherwise the client gets the seed and plays.
     */
    public static void onMeditationStart(ServerPlayer player) {
        if (!player.getData(ModAttachments.CULTIVATION).seeded()) {
            return;
        }
        expireWindow(player);
        PillState pills = player.getData(ModAttachments.PILLS);
        long now = player.level().getGameTime();
        if (!meditating(player) || !pills.windowOpen(now) || absorbing(player)) {
            return;
        }
        Slot slot = player.getData(ModAttachments.ABSORB);
        slot.liftAge = -1;
        slot.warnKind = null;
        List<PillKind> kinds = pills.pending();
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        PillRules.Plan plan = PillRules.plan(kinds, profile.rank(), Realm.wall(profile), PillRules.tolerance(pills, kinds, now), GreatElixirs.assisted(player, pills));
        player.setData(ModAttachments.PILLS, pills.closed());
        int id = ++slot.sessions;
        long seed = player.getRandom().nextLong();
        int[] ordinals = kinds.stream().mapToInt(Enum::ordinal).toArray();
        if (plan.torn()) {
            sendStart(player, new PillPayloads.FlowStart(id, ordinals, seed, false, true, !plan.tooHigh(), false));
            tear(player, plan);
            scheduleStand(player, slot, "murim.pill.standup.torn");
            return;
        }
        FlowCharacter stream = character(kinds);
        slot.flow = new Session(id, kinds, plan, now, (int) Math.round(stream.time() * 20.0D));
        slot.flow.automatic = plan.assisted() || shortAbsorption(kinds);
        sendStart(player, new PillPayloads.FlowStart(id, ordinals, seed, plan.hard(), false, false, natureIsPoison(player), slot.flow.automatic));
        // UX #32: the explanation is repeated for every game by the hint lines of the game screen (no chat spam over the picture).
        sound(player, "absorb_start");
        sync(player, PillPayloads.Event.NONE);
    }

    /** Ordinary qi pills use the complete scene in 2.5 seconds, without steering. */
    public static boolean shortAbsorption(List<PillKind> kinds) {
        return !kinds.isEmpty() && kinds.stream().allMatch(k -> !k.utility() && k.tier() != PillTier.HIGH);
    }

    /** The merged stream of these pills: the same thing the client builds from the ordinals. */
    public static FlowCharacter character(List<PillKind> kinds) {
        List<FlowSpec> parts = new ArrayList<>();
        for (PillKind k : kinds) {
            parts.add(k.flow());
        }
        return FlowCharacter.of(parts);
    }

    private static void sendStart(ServerPlayer player, PillPayloads.FlowStart start) {
        // GameTest mock players have no client channel for our payloads.
        if (player.connection != null && player.connection.hasChannel(start)) {
            PacketDistributor.sendToPlayer(player, start);
        }
    }

    /** Сидит ли игрок в медитации прямо сейчас (после семени, не в прорыве): тогда пилюля усваивается сразу. */
    static boolean meditating(ServerPlayer player) {
        MeditationState med = player.getData(ModAttachments.MEDITATION);
        return med.active() && !med.breakingThrough() && player.getData(ModAttachments.CULTIVATION).seeded();
    }

    /** Урон прямо сейчас — от волны места силы: медитацию не срывает. */
    public static boolean waveHit(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).waveHit;
    }

    public static void setWaveHit(ServerPlayer player, boolean value) {
        player.getData(ModAttachments.ABSORB).waveHit = value;
    }

    /** Идёт ли игра сейчас. */
    public static boolean absorbing(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).flow != null;
    }

    /**
     * The client's report of the game (see {@link PillPayloads.FlowReport}). A report of another session is ignored. While the game
     * runs the reports only move the progress (an interrupted sitting is paid by it); the end is worked out here from the outcome,
     * the progress and the lost share, never taken as a quality.
     */
    public static void report(ServerPlayer player, int id, PillPayloads.Outcome outcome, int progress, int lost) {
        report(player, id, outcome, progress, lost, player.level().getGameTime());
    }

    /** {@link #report} at a given game time (the tests cannot let the world's clock run inside one call). */
    static void report(ServerPlayer player, int id, PillPayloads.Outcome outcome, int progress, int lost, long now) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        Session flow = slot.flow;
        if (flow == null || flow.id != id) {
            return;
        }
        if (flow.automatic) return; // the server clock pays guided and ordinary absorption
        flow.progress = Math.max(flow.progress, Math.max(0, Math.min(1000, progress)));
        flow.lost = Math.max(flow.lost, Math.max(0, Math.min(1000, lost)));
        if (outcome != PillPayloads.Outcome.RUNNING) {
            resolve(player, slot, outcome, now);
        }
    }

    /**
     * Тик поглощения внутри медитации.
     *
     * @return {@code true}, если игра идёт и обычный прирост медитации на этом тике не нужен
     */
    public static boolean tickAbsorb(ServerPlayer player) {
        return tickAbsorb(player, player.level().getGameTime());
    }

    static boolean tickAbsorb(ServerPlayer player, long now) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        Session flow = slot.flow;
        if (flow == null) {
            return false;
        }
        if (flow.automatic && now >= flow.startTick + 50) {
            resolve(player, slot, PillPayloads.Outcome.DONE, now);
        } else if (now > flow.deadline) {
            resolve(player, slot, PillPayloads.Outcome.TIMEOUT, now);
        }
        return true;
    }

    /** The quality (0..1) the server pays for a report; the same function the tests use. */
    static double quality(Session flow, long now, PillPayloads.Outcome outcome) {
        if (flow.automatic) {
            return Math.max(PillRules.STANDING_SHARE, Math.min(1.0D, (now - flow.startTick) / 50.0D));
        }
        double lost = flow.lost / 10.0D;
        double progress = flow.progress / 1000.0D;
        return switch (outcome) {
            // A run cannot end before the stream could honestly have crossed: an early «done» is paid as the floor.
            case DONE -> now < flow.earliest ? PillRules.MIN_SAT_QUALITY
                    : FlowGame.quality(FlowGame.State.DONE, 1.0D, lost) / 100.0D;
            case BURST -> PillRules.BURST_QUALITY;
            default -> Math.max(PillRules.STANDING_SHARE, FlowGame.quality(FlowGame.State.TIMEOUT, progress, lost) / 100.0D);
        };
    }

    /** The game is over: pay it. */
    private static void resolve(ServerPlayer player, Slot slot, PillPayloads.Outcome outcome, long now) {
        Session flow = slot.flow;
        slot.flow = null;
        double q = quality(flow, now, outcome);
        Award award = award(player, flow.plan, q, true);
        player.setData(ModAttachments.PILLS, player.getData(ModAttachments.PILLS).afterIntake(flow.kinds, now));
        boolean burst = outcome == PillPayloads.Outcome.BURST;
        boolean flawless = outcome == PillPayloads.Outcome.DONE && q >= PillRules.FLAWLESS_QUALITY;
        boolean lift = PillRules.lifts(flow.kinds, flawless);
        PerfectLift.Plan lifting = lift ? PerfectLift.plan(headroom(player)) : PerfectLift.Plan.NONE;
        if (lift) {
            slot.liftAge = 0;
            slot.liftHeight = lifting.height();
            slot.liftBump = lifting.bump();
        }
        sound(player, lift ? "absorb_perfect" : burst ? "absorb_overflow" : "absorb_finish");
        String key = burst ? "murim.pill.result.burst" : outcome == PillPayloads.Outcome.DONE ? "murim.pill.result"
                : "murim.pill.result.timeout";
        PlayerJournal.record(player, resultText(key, q, award).copy().withStyle(burst ? ChatFormatting.RED
                : flawless ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        send(player, burst ? PillPayloads.Event.BURST : PillPayloads.Event.FINISH, flow.kinds, q, flawless, lifting);
        if (burst) {
            backlash(player, flow.kinds, "murim.pill.burst");
        }
        scheduleStand(player, slot);
        // Медитация продолжается: если запас упёрся в стену и условия ранга выполнены,
        // MeditationService сам переведёт сидение в принятую сцену прорыва.
    }

    /** The absorption is over: the player stands up by himself a moment later (author 22.10). */
    private static void scheduleStand(ServerPlayer player, Slot slot) {
        scheduleStand(player, slot, "murim.pill.standup");
    }

    private static void scheduleStand(ServerPlayer player, Slot slot, String key) {
        slot.standKey = key;
        slot.standAt = player.level().getGameTime() + STAND_DELAY;
    }

    static final int STAND_DELAY = 50;

    /** The line the stand-up says (the pill is absorbed, or the torn body pulls itself up). */
    public static String standUpKey(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).standKey;
    }

    /** Ends the sitting once the delay after a finished absorption has passed; false while the player keeps sitting. */
    public static boolean standUpDue(ServerPlayer player) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        if (slot.standAt < 0L || player.level().getGameTime() < slot.standAt) {
            return false;
        }
        slot.standAt = -1L;
        return !player.getData(ModAttachments.MEDITATION).breakingThrough();
    }

    /**
     * A pill that burst the stream tears the channels (author 22.10): health, the circulating qi and a weakness. Not deadly by
     * itself - one heart stays.
     */
    static void backlash(ServerPlayer player, List<PillKind> kinds, String key) {
        if (player.isCreative() || kinds.isEmpty()) {
            return;
        }
        PillTier top = PillRules.top(kinds);
        float lose = (float) Math.max(0.0D, Math.min(PillRules.backlashLoss(top) * player.getMaxHealth(), player.getHealth() - 1.0F));
        player.setHealth(player.getHealth() - lose);
        DantianProfile p = player.getData(ModAttachments.PROFILE);
        player.setData(ModAttachments.PROFILE, p.withCirculating(0.0D));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, INJURY_TICKS, 1));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, INJURY_TICKS / 2, 0));
        sound(player, "absorb_deviation");
        PlayerJournal.record(player, Component.translatable(key).withStyle(ChatFormatting.DARK_RED));
        ProfileNetwork.sync(player);
    }

    /**
     * The body does not endure the pills (a pill two ranks above, or a load beyond {@link PillRules#TORN_LOAD} walls): the pills are
     * wasted, half of the maximal health goes (never the last heart), a part of the pool is knocked out and the circulating qi is gone.
     */
    static void tear(ServerPlayer player, PillRules.Plan plan) {
        if (!player.isCreative()) io.github.verycooltimo.murim.sect.PlumBlossomCare.torn(player);
        io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(player, "torn_meridians");
        DantianProfile p = player.getData(ModAttachments.PROFILE);
        if (p.isAwakened()) {
            player.setData(ModAttachments.PROFILE, p.withPool(p.pool() * (1.0D - PillRules.TORN_POOL_LOSS)).withCirculating(0.0D));
            ProfileNetwork.sync(player);
        }
        if (!player.isCreative()) {
            halveHealth(player);
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, INJURY_TICKS, 1));
        }
        sound(player, "absorb_deviation");
        PlayerJournal.record(player, Component.translatable(plan.tooHigh() ? "murim.pill.torn" : "murim.pill.torn.load")
                .withStyle(ChatFormatting.DARK_RED));
        sync(player, PillPayloads.Event.DEVIATION);
    }

    // ------------------------------------------------------------------ награда

    /** What a sitting paid: the qi that went into the pool and the qi the body could not hold. */
    record Award(double gain, double stored, double wasted) {
        static final Award NONE = new Award(0.0D, 0.0D, 0.0D);
    }

    /**
     * Pays the pills at quality {@code q} (0..1 of their fixed qi, already multiplied by the rank rule and the tolerance in the
     * plan). The qi beyond the wall is lost («тело не вместило»). The side effects of every pill grow with what it was worth:
     * {@code mult × tolerance × q}.
     *
     * @param sat true after the game: a clean stream with several pills gives +10 % per extra pill
     */
    static Award award(ServerPlayer player, PillRules.Plan plan, double q, boolean sat) {
        DantianProfile p = player.getData(ModAttachments.PROFILE);
        if (!p.isAwakened()) {
            return Award.NONE;
        }
        double gain = sat ? plan.gain(q) : plan.absorbedLoad() * q;
        double capacity = p.capacity();
        double meridians = p.meridians();
        double purity = p.purity();
        double circulating = p.circulating();
        boolean flawless = sat && q >= PillRules.FLAWLESS_QUALITY;
        for (int i = 0; i < plan.kinds().size(); i++) {
            PillKind k = plan.kinds().get(i);
            double worth = plan.mult()[i] * plan.tolerance();
            double potency = worth * q * plan.share(i);
            double capped = Math.min(1.0D, worth);
            switch (k) {
                case SNOW_PLUM -> circulating += p.maxCirculating() * Math.min(1.0D, potency);
                case ORIGIN_ENERGY -> {
                    capacity *= 1.0D + 0.04D * potency;
                    meridians += 0.03D * potency;
                }
                case THOUSAND_POISON -> {
                    if (q >= 0.5D) {
                        player.setData(ModAttachments.PILLS, player.getData(ModAttachments.PILLS).withPoisonResistant());
                        player.getData(ModAttachments.ABSORB).discountUntil = player.level().getGameTime()
                                + Math.max(1L, Math.round(DISCOUNT_TICKS * capped));
                        io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
                    }
                }
                case BLUE -> {
                    // A calm elixir, stronger than Origin Energy: widens the dantian and clears the channels.
                    capacity *= 1.0D + 0.05D * potency;
                    meridians += 0.04D * potency;
                }
                case BLOOD -> {
                    // The blood pill: a great dose of raw qi (a large dantian gain), a little channel clearing.
                    capacity *= 1.0D + 0.06D * potency;
                    meridians += 0.02D * potency;
                }
                case BEAUTY_TEAR -> purity += 0.05D * potency;
                default -> {
                }
            }
            if (flawless && k.good()) {
                meridians += PillRules.PERFECT_MERIDIANS * capped;
            }
        }
        if (sat && q > 0.0D && plan.kinds().stream().anyMatch(PillKind::greatElixir)) {
            GreatElixirs.absorbed(player);
        }
        if (gain > 0.0D) {
            purity += PillRules.comboPurity(plan.kinds()) * q;
        }
        DantianProfile next = p.withAxes(capacity, purity, meridians);
        double room = Math.max(0.0D, Realm.wall(next) - next.pool());
        double stored = Math.min(gain, room);
        next = next.withPool(next.pool() + stored).withCirculating(circulating);
        player.setData(ModAttachments.PROFILE, next);
        ProfileNetwork.sync(player);
        if (stored > 0.0D) {
            io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(player, "first_pill");
            if (PillRules.top(plan.kinds()) == PillTier.HIGH) {
                io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(player, "great_elixir");
            }
        }
        return new Award(gain, stored, gain - stored);
    }

    private static Component resultText(String key, double q, Award award) {
        var text = Component.translatable(key, Math.round(q * 100.0D), Math.round(award.stored()));
        if (award.wasted() >= 1.0D) {
            text.append(" ").append(Component.translatable("murim.pill.wasted", Math.round(award.wasted()))
                    .withStyle(ChatFormatting.GRAY));
        }
        return text;
    }

    /**
     * Сессия прервана (встал, сдвинулся, удар). То, что усвоено, остаётся: платится по последнему отчёту клиента, но не меньше
     * доли «не сел». Травм нет: песочница.
     */
    public static void onMeditationStop(ServerPlayer player, boolean hurt) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        boolean wasLifting = slot.liftAge >= 0;
        slot.liftAge = -1;
        Session flow = slot.flow;
        if (flow == null) {
            if (wasLifting) {
                sync(player, PillPayloads.Event.LANDED);
            }
            return;
        }
        slot.flow = null;
        double q = quality(flow, player.level().getGameTime(), PillPayloads.Outcome.RUNNING);
        award(player, flow.plan, q, false);
        player.setData(ModAttachments.PILLS, player.getData(ModAttachments.PILLS).afterIntake(flow.kinds, player.level().getGameTime()));
        send(player, PillPayloads.Event.INTERRUPTED, flow.kinds, q, false, PerfectLift.Plan.NONE);
        // Любой удар по сидящему за поглощением (автор 19.10: «если тебя хоть кто-то коснётся, то у тебя пол хп») —
        // искажение ци; само списание — на ближайшем тике, когда удар уже применён.
        if (hurt && !slot.deviating) {
            slot.deviation = true;
        }
    }

    /** Halves the maximal health (armour ignored), never below one health; the same for the qi deviation and the torn body. */
    private static void halveHealth(ServerPlayer player) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        if (!player.isAlive()) {
            return;
        }
        float lose = (float) Math.max(0.0D, Math.min(PillRules.DEVIATION_LOSS * player.getMaxHealth(), player.getHealth() - 1.0F));
        float target = player.getHealth() - lose;
        boolean outer = slot.deviating;
        slot.deviating = true;
        try {
            if (lose > 0.0F) {
                player.hurt(io.github.verycooltimo.murim.registry.ModDamageTypes.qiDeviation(player), lose);
                // Золотые сердца и чужие обработчики не должны смягчать удар: итог ровно половина.
                if (player.isAlive() && player.getHealth() > target) {
                    player.setHealth(target);
                }
            }
        } finally {
            slot.deviating = outer;
        }
    }

    /**
     * Искажение ци: поток сорван ударом. Игрок мгновенно теряет половину максимального здоровья, мимо брони
     * (особый тип урона {@code murim:qi_deviation}, теги обхода брони, чар, сопротивления, щита и кадров неуязвимости).
     *
     * <p>Не ниже одного здоровья: искажение само не убивает — добить может только тот, кто ударил (правило
     * песочницы: наказание не должно быть смертельным само по себе). Усвоенное остаётся.
     */
    static void deviate(ServerPlayer player) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        if (!slot.deviation) {
            return;
        }
        slot.deviation = false;
        if (!player.isAlive()) {
            return;
        }
        halveHealth(player);
        sound(player, "absorb_deviation");
        PlayerJournal.record(player, Component.translatable("murim.pill.deviation").withStyle(ChatFormatting.RED));
        sync(player, PillPayloads.Event.DEVIATION);
    }

    // ------------------------------------------------------------------ подлёт

    /** Идёт ли сейчас подлёт (сцена полного усвоения хорошей пилюли). */
    public static boolean lifting(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).liftAge >= 0;
    }

    /** Высота подлёта сейчас, блоки: ту же формулу рисует клиент. */
    public static float liftHeight(ServerPlayer player) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        return slot.liftAge < 0 ? 0.0F : PerfectLift.height(slot.liftAge, slot.liftHeight);
    }

    public static boolean liftBumps(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).liftBump;
    }

    /**
     * Свободное место над головой сидящего, блоки (не больше {@link PerfectLift#MAX_HEIGHT} + 0,1).
     * Коробка головы шагает вверх, пока не упрётся во что-то твёрдое. API:
     * reference/minecraft-src/net/minecraft/world/level/CollisionGetter.java#noCollision(Entity, AABB)
     */
    static float headroom(ServerPlayer player) {
        double top = player.getY() + PerfectLift.SEATED_HEAD_TOP;
        net.minecraft.world.phys.AABB head = new net.minecraft.world.phys.AABB(player.getX() - 0.3D, top - 0.5D,
                player.getZ() - 0.3D, player.getX() + 0.3D, top, player.getZ() + 0.3D);
        float limit = PerfectLift.MAX_HEIGHT + 0.1F;
        for (float h = 0.0F; h <= limit; h += 0.05F) {
            if (!player.level().noCollision(player, head.move(0.0D, h, 0.0D))) {
                return Math.max(0.0F, h - 0.05F);
            }
        }
        return limit;
    }

    /** Тик сцены подлёта: стук о потолок, выброс ци, посадка. */
    static void tickLift(ServerPlayer player, Slot slot) {
        MeditationState med = player.getData(ModAttachments.MEDITATION);
        if (!med.active() || med.breakingThrough() || player.hurtTime > 0) {
            slot.liftAge = -1;
            sync(player, PillPayloads.Event.LANDED);
            return;
        }
        slot.liftAge++;
        if (slot.liftBump && slot.liftAge == PerfectLift.RISE_END - 4) {
            // Хуашань, гл. 249: «ударился головой о потолок». Без урона: только стук и строка.
            player.level().playSound(null, player.getX(), player.getY() + 1.4D, player.getZ(),
                    net.minecraft.sounds.SoundEvents.WOOD_HIT, SoundSource.PLAYERS, 0.9F, 0.7F);
            PlayerJournal.record(player, Component.translatable("murim.pill.bump").withStyle(ChatFormatting.YELLOW));
        }
        if (slot.liftAge == PerfectLift.BURST) {
            sound(player, "absorb_burst");
        }
        if (slot.liftAge >= PerfectLift.TICKS) {
            slot.liftAge = -1;
        }
    }

    // ------------------------------------------------------------------ резерв

    /**
     * Резерв усваивается за десять минут игры без сидения: раз в {@link PillRules#RESERVE_STEP} тиков
     * выплачивается пропорциональная часть. Запас не выше стены ранга: что не влезло, ждёт в резерве
     * (до прорыва), а не сгорает.
     */
    static void tickReserve(ServerPlayer player) {
        PillState pills = player.getData(ModAttachments.PILLS);
        PillState.Reserve r = pills.reserve();
        if (!r.active()) {
            if (r != PillState.Reserve.NONE) {
                player.setData(ModAttachments.PILLS, pills.withReserve(PillState.Reserve.NONE));
            }
            return;
        }
        DantianProfile p = player.getData(ModAttachments.PROFILE);
        if (!p.isAwakened()) {
            return;
        }
        int step = PillRules.RESERVE_STEP;
        double f = r.fraction(step);
        DantianProfile next = p.withAxes(p.capacity() * (1.0D + r.capacity() * f), p.purity() + r.purity() * f,
                p.meridians() + r.meridians() * f);
        double room = Math.max(0.0D, Realm.wall(next) - next.pool());
        double due = r.pool() * f;
        double pay = Math.min(due, room);
        next = next.withPool(next.pool() + pay);
        player.setData(ModAttachments.PROFILE, next);
        PillState.Reserve rest = r.after(f, step);
        double waiting = due - pay;
        PillState.Reserve updated = waiting > 1.0E-9D
                ? new PillState.Reserve(rest.pool() + waiting, rest.capacity(), rest.meridians(), rest.purity(),
                Math.max(rest.ticks(), step))
                : rest;
        player.setData(ModAttachments.PILLS, pills.withReserve(updated));
        ProfileNetwork.sync(player);
        sync(player, PillPayloads.Event.NONE);
    }

    /** Резерв долей стены запаса — для видимой янтарной нити у шкалы ци. */
    static float reserveFraction(ServerPlayer player) {
        PillState.Reserve r = player.getData(ModAttachments.PILLS).reserve();
        if (!r.active()) {
            return 0.0F;
        }
        DantianProfile p = player.getData(ModAttachments.PROFILE);
        return (float) Math.max(0.02D, Math.min(1.0D, r.pool() / Math.max(1.0D, Realm.wall(p))));
    }

    /** Expiry sent to the HUD, so its displayed costs revert without another practice event. */
    public static long costDiscountUntil(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).discountUntil;
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
        if (slot.deviation) {
            deviate(player);
        }
        if (slot.liftAge >= 0) {
            tickLift(player, slot);
        }
        // Резерв усваивается за десять минут игры: раз в секунду выплачивается часть.
        if (player.tickCount % PillRules.RESERVE_STEP == 0) {
            tickReserve(player);
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

    /**
     * Шанс выпадения с бандита, убитого игроком (docs/design/19b §1 «Где взять сейчас»). 24.10: обычные пилюли часты, средние
     * (Снежная Слива теперь средняя) реже, высшая — по-прежнему редкость.
     */
    static final float BANDIT_VITALITY = 0.20F;
    static final float BANDIT_FASTING = 0.20F;
    static final float BANDIT_ANTIDOTE = 0.12F;
    static final float BANDIT_SNOW_PLUM = 0.15F;
    static final float BANDIT_BLOOD_REPLENISHING = 0.05F;
    static final float BANDIT_QUICK_RECOVERY = 0.05F;
    static final float BANDIT_ORIGIN = 0.03F;

    /**
     * Лут бандитов: обычные часто, сливовая реже, остальное редко. Через событие, а не правкой класса
     * бандита. API: reference/neoforge-src/net/neoforged/neoforge/event/entity/living/LivingDropsEvent.java
     */
    @SubscribeEvent
    static void onDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (!(event.getEntity() instanceof io.github.verycooltimo.murim.entity.Bandit bandit)
                || bandit instanceof io.github.verycooltimo.murim.npc.GuestFighter || !event.isRecentlyHit()
                || !(event.getSource().getEntity() instanceof Player)) {
            return;
        }
        var random = bandit.getRandom();
        float[] chance = {BANDIT_VITALITY, BANDIT_FASTING, BANDIT_ANTIDOTE, BANDIT_SNOW_PLUM, BANDIT_BLOOD_REPLENISHING,
                BANDIT_QUICK_RECOVERY, BANDIT_ORIGIN};
        net.minecraft.world.item.Item[] item = {
                io.github.verycooltimo.murim.registry.ModItems.PILL_VITALITY.get(),
                io.github.verycooltimo.murim.registry.ModItems.PILL_FASTING.get(),
                io.github.verycooltimo.murim.registry.ModItems.PILL_ANTIDOTE.get(),
                io.github.verycooltimo.murim.registry.ModItems.PILL_SNOW_PLUM.get(),
                io.github.verycooltimo.murim.registry.ModItems.PILL_BLOOD_REPLENISHING.get(),
                io.github.verycooltimo.murim.registry.ModItems.PILL_QUICK_RECOVERY.get(),
                io.github.verycooltimo.murim.registry.ModItems.PILL_ORIGIN_ENERGY.get()};
        for (int i = 0; i < chance.length; i++) {
            if (random.nextFloat() < chance[i]) {
                drop(event, bandit, item[i]);
            }
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
                || !(player.getData(ModAttachments.PILLS).poisonResistant() || PillEffects.antidoteActive(player))) {
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

    /**
     * Звуковой хук (05.10, ElevenLabs — art/sounds-src/prompts.tsv): глоток, шаги мини-игры, итог.
     * «Пять цветов» — тот же звук успеха выше тоном; удар «дикой» стороны — тихая отдача.
     */
    public static void sound(ServerPlayer player, String hook) {
        record S(net.minecraft.sounds.SoundEvent event, float volume, float pitch) {
        }
        S s = switch (hook) {
            case "pill_eat" -> new S(ModSounds.PILL_EAT.get(), 0.9F, 1.0F);
            case "absorb_start" -> new S(ModSounds.ABSORB_PULSE.get(), 0.6F, 0.8F);
            case "absorb_finish" -> new S(ModSounds.ABSORB_SUCCESS.get(), 0.8F, 1.0F);
            case "absorb_overflow" -> new S(ModSounds.ABSORB_FAIL.get(), 0.6F, 0.9F);
            case "absorb_perfect" -> new S(ModSounds.ABSORB_SUCCESS.get(), 1.0F, 1.4F);
            case "absorb_deviation" -> new S(ModSounds.ABSORB_FAIL.get(), 1.0F, 0.7F);
            case "absorb_burst" -> new S(ModSounds.RANK_UP.get(), 0.5F, 1.3F);
            default -> null;
        };
        if (s != null) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), s.event(), SoundSource.PLAYERS,
                    s.volume(), s.pitch());
        }
    }

    // ------------------------------------------------------------------ синхронизация

    public static void sync(ServerPlayer player, PillPayloads.Event event) {
        Slot slot = player.getData(ModAttachments.ABSORB);
        send(player, event, slot.flow == null ? List.of() : slot.flow.kinds, 0.0D, false, PerfectLift.Plan.NONE);
    }

    private static void send(ServerPlayer player, PillPayloads.Event event, List<PillKind> clots, double quality, boolean perfect,
                             PerfectLift.Plan plan) {
        PillState pills = player.getData(ModAttachments.PILLS);
        long now = player.level().getGameTime();
        int[] pending = pills.windowOpen(now) ? pills.pending().stream().mapToInt(Enum::ordinal).toArray() : new int[0];
        int left = pills.windowOpen(now) ? (int) (pills.deadline() - now) : 0;
        int[] taken = new int[PillKind.values().length];
        for (PillKind k : PillKind.values()) {
            taken[k.ordinal()] = pills.taken(k);
        }
        int streakKind = -1;
        int streakCount = 0;
        for (PillKind k : PillKind.values()) {
            if (!k.utility() && pills.streakOf(k, now) > 0) {
                streakKind = k.ordinal();
                streakCount = pills.streakOf(k, now);
            }
        }
        // Pairs by kind: effective steps and ticks until the next recovery. Client advances them while unpaused.
        int[] tolerance = new int[PillKind.values().length * 2];
        for (PillKind k : PillKind.values()) {
            PillState.Tolerance t = pills.tolerances().get(k.name());
            tolerance[k.ordinal() * 2] = pills.streakOf(k, now);
            tolerance[k.ordinal() * 2 + 1] = t == null ? 0 : (int) (PillRules.TOLERANCE_RECOVERY_TICKS
                    - Math.max(0L, now - t.at()) % PillRules.TOLERANCE_RECOVERY_TICKS);
        }
        PillPayloads.Sync payload = new PillPayloads.Sync(pending, left, player.getData(ModAttachments.ABSORB).flow != null,
                clots.stream().mapToInt(Enum::ordinal).toArray(), event, PillRules.canonTriple(clots), (float) quality, perfect,
                plan.height(), plan.bump(), reserveFraction(player), taken, streakKind, streakCount, tolerance);
        // GameTest mock players have no client channel for our payloads.
        if (player.connection != null && player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
