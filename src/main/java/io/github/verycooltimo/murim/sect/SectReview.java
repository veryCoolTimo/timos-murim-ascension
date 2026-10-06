package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Смотр учеников (docs/design/23-mount-hua-sect.md §6.1): раз в 7 дней секты на площадке поединков — сетка из
 * четырёх учеников третьего поколения (полуфиналы в двух рингах одновременно, затем финал). Ученики не на посту и
 * старшие смотрят ({@link #spectator}). Игрок записывается у наставника (флаг {@link #SIGNED}, нужно положение
 * «ученик третьего класса») и встаёт в сетку вместо одного из учеников.
 *
 * <p>Бой — обычный спарринг ({@link SectDisciple#startSpar}, {@link SectDisciple#sparWith}): до половины сил, без
 * смерти, ученики между собой — только основа. Не дошёл до ринга за минуту — поражение неявкой; бой дольше минуты —
 * победа по остатку сил.
 *
 * <p>Награды игроку: участие {@link #ENTRY}, победа в бою {@link #WIN}, победа в смотре {@link #CHAMPION} и одобрение
 * главы ({@link SectStanding#APPROVAL}). Одобрение также даёт неделя полных дней подряд ({@link #APPROVAL_STREAK},
 * {@link SectAttendance}). Решение 05.10 (агент, автор не возражал против §6.1 «победа = путь к Мечнику»).
 *
 * <p>Состояние идущего смотра — в {@link SectSiteData} (сеанс сервера); день последнего смотра сохраняется, чтобы смотр
 * не повторялся. Сервер. Новых пакетов и клавиш нет.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectReview {

    /** Раз в столько дней секты. Смотр — в последний день недели: первый — на седьмой день мира. */
    public static final int EVERY = 7;
    /** Окно смотра в тиках суток: после доклада Хён Ёна (2900) до совета старейшин (6000). */
    public static final int FROM = 2900;
    public static final int TO = 6000;
    /** Флаг игрока: записан на ближайший смотр. */
    public static final String SIGNED = "review.signed";
    /** Заслуги: участие, победа в бою, победа в смотре. */
    public static final int ENTRY = 1;
    public static final int WIN = 2;
    public static final int CHAMPION = 5;
    /** Полных дней подряд для одобрения главы. */
    public static final int APPROVAL_STREAK = 7;
    /** Сроки боя: не явился к рингу, бой затянулся (тики). */
    static final int SHOW_UP = 1200;
    static final int BOUT_LIMIT = 1200;
    /** Рядом с местом в ринге — можно начинать. */
    static final double READY = 3.5D;
    /** Ринги смотра (номера в {@link SectSchedule#RINGS}): полуфиналы в 1 и 4, финал в 1. */
    static final int[] SEMI_RINGS = {1, 4};
    static final String PLAYER = "player";
    /** Бойцов ищут в стольких блоках от площадки поединков (лагерь, общежития — вся полка секты). */
    static final double SEARCH = 160.0D;

    private SectReview() {
    }

    // ------------------------------------------------------------------ расписание (чистые функции)

    /** День секты с смотром. */
    public static boolean reviewDay(long day) {
        return Math.floorMod(day, (long) EVERY) == EVERY - 1;
    }

    /** Сейчас идёт окно смотра. */
    public static boolean window(long dayTime) {
        int t = (int) Math.floorMod(dayTime, (long) SectSchedule.DAY);
        return SectSchedule.at(dayTime) == SectSchedule.Period.TRAINING && t >= FROM && t < TO && reviewDay(SectSchedule.day(dayTime));
    }

    /** Через сколько дней ближайший смотр (0 — сегодня). */
    public static long daysUntil(long dayTime) {
        return Math.floorMod(EVERY - 1 - SectSchedule.day(dayTime), (long) EVERY);
    }

    /**
     * Место зрителя на смотре: глава и наставник — на помосте, Хён Сан и Хён Сон — у края площадки, лекарь — на дежурстве у
     * края; ученики (второе и третье поколение) — двумя рядами вдоль северного и южного края лицом к рингам. Остальные
     * (Ун Ам у ворот, Хён Ён у казны, слуги) — своим делом: null.
     */
    static SectSchedule.Task spectator(SectRoster m) {
        switch (m.key()) {
            case "tae_hwi" -> {
                return new SectSchedule.Task(SectSchedule.Kind.WATCH, "mentor", 2.0D, 1.0D, 0.0D, 1.0D);
            }
            case "gyeong_pil" -> {
                return new SectSchedule.Task(SectSchedule.Kind.WATCH, "mentor", -1.0D, 1.0D, 0.0D, 1.0D);
            }
            case "tae_rok" -> {
                return new SectSchedule.Task(SectSchedule.Kind.WATCH, "sparring", 12.0D, -8.5D, -0.6D, 1.0D);
            }
            case "tae_seong" -> {
                return new SectSchedule.Task(SectSchedule.Kind.WATCH, "sparring", 12.0D, 8.5D, -0.6D, -1.0D);
            }
            case "gyeong_cho" -> {
                return new SectSchedule.Task(SectSchedule.Kind.HEAL_POST, "sparring", -12.0D, -8.5D, 0.6D, 1.0D);
            }
            default -> {
            }
        }
        List<SectRoster> crowd = crowd();
        int i = crowd.indexOf(m);
        if (i < 0) {
            return null;
        }
        int side = i % 2;
        int col = i / 2;
        double du = -13.5D + col * 2.25D;
        return new SectSchedule.Task(SectSchedule.Kind.WATCH, "sparring", du, side == 0 ? -10.0D : 10.0D, 0.0D, side == 0 ? 1.0D : -1.0D);
    }

    /** Зрители-ученики: второе и третье поколение по списку. */
    static List<SectRoster> crowd() {
        List<SectRoster> out = new ArrayList<>(SectRoster.generation(2));
        out.addAll(SectRoster.generation(3));
        return out;
    }

    /** Сетка смотра дня {@code day}: четверо учеников третьего поколения по кругу списка (каждую неделю — другие). */
    public static List<String> bracket(long day, boolean withPlayer) {
        List<SectRoster> gen = SectRoster.generation(3);
        int start = (int) Math.floorMod(Math.floorDiv(day, (long) EVERY) * 4L, (long) gen.size());
        List<String> out = new ArrayList<>();
        if (withPlayer) {
            out.add(PLAYER);
        }
        for (int i = 0; out.size() < 4; i++) {
            out.add(gen.get((start + i) % gen.size()).key());
        }
        return out;
    }

    /** Место бойца: ринг и сторона (0 — запад, 1 — восток). */
    static SectSchedule.Task ringSpot(int ring, int side) {
        return SectSchedule.ring(ring, side, "");
    }

    // ------------------------------------------------------------------ состояние

    /** Бой сетки. */
    public static final class Bout {
        final String a;
        final String b;
        final int ring;
        String winner = "";
        long called = -1L;
        long started = -1L;

        Bout(String a, String b, int ring) {
            this.a = a;
            this.b = b;
            this.ring = ring;
        }

        public String winner() {
            return winner;
        }

        boolean has(String key) {
            return a.equals(key) || b.equals(key);
        }

        String other(String key) {
            return a.equals(key) ? b : a;
        }
    }

    /** Идущий смотр. */
    public static final class State {
        final long day;
        final UUID player;
        /** Где искать бойцов: центр площадки поединков и радиус. */
        final Vec3 center;
        final double radius;
        final List<Bout> bouts = new ArrayList<>();
        String champion = "";

        State(long day, UUID player, Vec3 center, double radius) {
            this.day = day;
            this.player = player;
            this.center = center;
            this.radius = radius;
        }

        public List<Bout> bouts() {
            return bouts;
        }

        public String champion() {
            return champion;
        }

        public boolean done() {
            return !champion.isEmpty();
        }

        /** Бой, в котором сейчас участвует {@code key} (не решён). */
        Bout current(String key) {
            for (Bout b : bouts) {
                if (b.winner.isEmpty() && b.has(key)) {
                    return b;
                }
            }
            return null;
        }
    }

    /** Идущий смотр или null. */
    public static State state(ServerLevel level) {
        return SectLife.data(level).review;
    }

    // ------------------------------------------------------------------ сервер

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 10 != 5) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(event.getServer());
        if (site == null) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        SectLayout layout = SectLayout.hua(site);
        SectSiteData data = SectLife.data(level);
        long time = level.getDayTime();
        long day = SectSchedule.day(time);
        if (data.review == null && window(time) && data.lastReview() != day) {
            Vec3 c = layout.at("sparring", 0.0D, 0.0D);
            // Смотр идёт, только если рядом кто-то есть: иначе люди спят (дормант), бои не сыграть — день просто проходит.
            if (c != null && level.getNearestPlayer(c.x, c.y, c.z, 96.0D, false) != null) {
                begin(level, layout, day, signedPlayer(level, c), SEARCH);
            }
        }
        if (data.review != null) {
            step(level, layout);
            if (data.review != null && !window(time) && !data.review.done()) {
                abort(level, "murim.sect.review.closed");
            }
        }
    }

    /** Записанный игрок рядом с площадкой (первый). */
    private static ServerPlayer signedPlayer(ServerLevel level, Vec3 c) {
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.getData(ModAttachments.SECT).has(SIGNED) && p.position().distanceToSqr(c) < 96.0D * 96.0D) {
                return p;
            }
        }
        return null;
    }

    /** Начать смотр: сетка из четырёх (игрок — первым, если записан). Стенд и GameTest зовут напрямую. */
    public static State begin(ServerLevel level, SectLayout layout, long day, ServerPlayer player, double radius) {
        List<String> keys = bracket(day, player != null);
        Vec3 center = layout.at("sparring", 0.0D, 0.0D);
        State s = new State(day, player == null ? null : player.getUUID(), center == null ? Vec3.ZERO : center, radius);
        s.bouts.add(new Bout(keys.get(0), keys.get(1), SEMI_RINGS[0]));
        s.bouts.add(new Bout(keys.get(2), keys.get(3), SEMI_RINGS[1]));
        SectLife.data(level).review = s;
        MurimMod.LOGGER.info("Секта: смотр учеников, день {}: {}", day, keys);
        if (player != null) {
            player.setData(ModAttachments.SECT, player.getData(ModAttachments.SECT).without(SIGNED));
            SectService.contribute(player, ENTRY);
            player.displayClientMessage(Component.translatable("murim.sect.review.your_bout", name(level, s, keys.get(1)))
                    .withStyle(ChatFormatting.GOLD), false);
        }
        announce(level, layout, Component.translatable("murim.sect.review.begin",
                name(level, s, keys.get(0)), name(level, s, keys.get(1)), name(level, s, keys.get(2)), name(level, s, keys.get(3))));
        return s;
    }

    /** Шаг смотра (раз в 10 тиков): позвать бойцов, начать бой, следить за исходом, собрать финал. */
    public static void step(ServerLevel level, SectLayout layout) {
        State s = state(level);
        if (s == null || s.done()) {
            return;
        }
        long now = level.getGameTime();
        for (Bout b : s.bouts) {
            if (b.winner.isEmpty()) {
                stepBout(level, layout, s, b, now);
            }
        }
        if (s.bouts.size() == 2 && !s.bouts.get(0).winner.isEmpty() && !s.bouts.get(1).winner.isEmpty()) {
            Bout fin = new Bout(s.bouts.get(0).winner, s.bouts.get(1).winner, SEMI_RINGS[0]);
            s.bouts.add(fin);
            announce(level, layout, Component.translatable("murim.sect.review.final", name(level, s, fin.a), name(level, s, fin.b)));
        } else if (s.bouts.size() == 3 && !s.bouts.get(2).winner.isEmpty()) {
            finish(level, layout, s);
        }
    }

    private static void stepBout(ServerLevel level, SectLayout layout, State s, Bout b, long now) {
        LivingEntity ea = fighter(level, s, b.a);
        LivingEntity eb = fighter(level, s, b.b);
        if (b.called < 0) {
            b.called = now;
        }
        if (b.started < 0) {
            if (ea == null || eb == null) {
                // Ученика ещё нет (чанк не загружен, не встал на гору) — ждём, как опоздавшего; через минуту — победа
                // другому, нет обоих — первому по сетке.
                if (now - b.called >= SHOW_UP) {
                    decide(level, s, b, ea == null && eb != null ? b.b : b.a, "absent");
                }
                return;
            }
            boolean readyA = ready(layout, b, 0, ea);
            boolean readyB = ready(layout, b, 1, eb);
            if (!(readyA && readyB) && now - b.called >= SHOW_UP) {
                // Ученик опоздал — ставим его на место (его никто не ждёт); игрок не пришёл — поражение неявкой.
                boolean playerLate = PLAYER.equals(b.a) && !readyA || PLAYER.equals(b.b) && !readyB;
                if (playerLate) {
                    decide(level, s, b, PLAYER.equals(b.a) ? b.b : b.a, "no_show");
                    return;
                }
                place(layout, b, 0, ea);
                place(layout, b, 1, eb);
                readyA = readyB = true;
            }
            if (readyA && readyB && busyFree(ea) && busyFree(eb)) {
                start(b, ea, eb, now);
            }
            return;
        }
        // Бой идёт: исход ловит onBoutEnd (крючок в SectDisciple#endSpar). Затянулся — по остатку сил.
        SectDisciple npc = ea instanceof SectDisciple d ? d : eb instanceof SectDisciple d2 ? d2 : null;
        if (ea == null || eb == null || !ea.isAlive() || !eb.isAlive()) {
            decide(level, s, b, ea != null && ea.isAlive() ? b.a : b.b, "absent");
            return;
        }
        if (npc != null && npc.spar() == SectDisciple.Spar.NONE && now - b.started > 200) {
            // Бой сорвался (колокол, разговор): решить по силам.
            decide(level, s, b, frac(ea) >= frac(eb) ? b.a : b.b, "stopped");
            return;
        }
        if (now - b.started >= BOUT_LIMIT && npc != null && npc.spar() == SectDisciple.Spar.FIGHT) {
            boolean aWins = frac(ea) >= frac(eb);
            LivingEntity loserSide = aWins ? eb : ea;
            // endSpar(partnerWon): у NPC-стороны «партнёр победил», если проиграл он сам.
            npc.endSpar(npc == loserSide);
        }
    }

    private static boolean busyFree(LivingEntity e) {
        return !(e instanceof SectDisciple d) || d.spar() == SectDisciple.Spar.NONE && !d.defending();
    }

    private static void start(Bout b, LivingEntity ea, LivingEntity eb, long now) {
        b.started = now;
        if (ea instanceof SectDisciple a && eb instanceof SectDisciple bb) {
            a.setWounded(false);
            bb.setWounded(false);
            a.sparWith(bb, 20);
        } else {
            SectDisciple npc = ea instanceof SectDisciple d ? d : (SectDisciple) eb;
            ServerPlayer p = (ServerPlayer) (ea instanceof ServerPlayer ? ea : eb);
            npc.setWounded(false);
            npc.startSpar(p, 20);
        }
        MurimMod.LOGGER.info("Секта: смотр — бой {} против {} (ринг {})", b.a, b.b, b.ring);
    }

    private static double frac(LivingEntity e) {
        return e.getHealth() / Math.max(1.0F, e.getMaxHealth());
    }

    /** Боец на месте в ринге. */
    private static boolean ready(SectLayout layout, Bout b, int side, LivingEntity e) {
        SectSchedule.Task t = ringSpot(b.ring, side);
        Vec3 at = layout.at(t.zone(), t.du(), t.dv());
        return at != null && Math.hypot(e.getX() - at.x, e.getZ() - at.z) <= READY;
    }

    private static void place(SectLayout layout, Bout b, int side, LivingEntity e) {
        if (!(e instanceof SectDisciple d)) {
            return;
        }
        SectSchedule.Task t = ringSpot(b.ring, side);
        Vec3 at = layout.at(t.zone(), t.du(), t.dv());
        if (at != null) {
            Vec3 spot = SectLife.stand(d.level(), at);
            d.moveTo(spot.x, spot.y, spot.z, layout.yaw(t.faceU(), t.faceV()), 0.0F);
            d.getNavigation().stop();
        }
    }

    /** Боец: игрок смотра или ученик по ключу (в 96 блоках от любого игрока — ближайший экземпляр). */
    static LivingEntity fighter(ServerLevel level, State s, String key) {
        if (PLAYER.equals(key)) {
            Entity e = s.player == null ? null : level.getEntity(s.player);
            return e instanceof ServerPlayer p && p.isAlive() ? p : null;
        }
        for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, new net.minecraft.world.phys.AABB(s.center, s.center).inflate(s.radius),
                d -> d.isAlive() && key.equals(d.memberKey()))) {
            return d;
        }
        return null;
    }

    /**
     * Крючок из {@link SectDisciple#endSpar}: бой ученика окончен. {@code partnerWon} — победил его партнёр.
     */
    public static void onBoutEnd(SectDisciple npc, LivingEntity partner, boolean partnerWon) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        State s = state(level);
        if (s == null || s.done()) {
            return;
        }
        Bout b = s.current(npc.memberKey());
        if (b == null || b.started < 0) {
            return;
        }
        String other = b.other(npc.memberKey());
        boolean partnerIsOther = PLAYER.equals(other) ? partner instanceof ServerPlayer p && p.getUUID().equals(s.player)
                : partner instanceof SectDisciple d && other.equals(d.memberKey());
        if (!partnerIsOther) {
            return;
        }
        decide(level, s, b, partnerWon ? other : npc.memberKey(), "bout");
    }

    private static void decide(ServerLevel level, State s, Bout b, String winner, String why) {
        if (!b.winner.isEmpty()) {
            return;
        }
        b.winner = winner;
        MurimMod.LOGGER.info("Секта: смотр — {} против {}: победил {} ({})", b.a, b.b, winner, why);
        ServerPlayer p = s.player == null ? null : level.getServer().getPlayerList().getPlayer(s.player);
        if (p != null && b.has(PLAYER)) {
            boolean won = PLAYER.equals(winner);
            if (won) {
                SectService.contribute(p, WIN);
            }
            p.displayClientMessage(Component.translatable(won ? "murim.sect.review.won" : "no_show".equals(why)
                    ? "murim.sect.review.no_show" : "murim.sect.review.lost", name(level, s, b.other(PLAYER)))
                    .withStyle(won ? ChatFormatting.GOLD : ChatFormatting.GRAY), false);
        }
    }

    private static void finish(ServerLevel level, SectLayout layout, State s) {
        s.champion = s.bouts.get(2).winner;
        SectLife.data(level).finishReview(s.day);
        announce(level, layout, Component.translatable("murim.sect.review.champion", name(level, s, s.champion)));
        LivingEntity champ = fighter(level, s, s.champion);
        // Глава и старейшины кивают победителю.
        if (champ != null) {
            for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, champ.getBoundingBox().inflate(48.0D),
                    d -> d.member().map(m -> m.generation() <= 1).orElse(false))) {
                d.gesture("nod");
            }
            level.playSound(null, champ.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.0F);
        }
        if (champ instanceof ServerPlayer p) {
            SectService.contribute(p, CHAMPION);
            approve(p, "review");
            p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SectTalk.REVIEW_WON));
        }
        // О победителе говорят ученики (SectTalk): ключ ученика или имя игрока.
        SectLife.data(level).crown(champ instanceof ServerPlayer p ? "@" + p.getName().getString() : s.champion);
        MurimMod.LOGGER.info("Секта: смотр дня {} окончен, победил {}", s.day, s.champion);
        SectLife.data(level).review = null;
    }

    /** Смотр прерван (окно кончилось): бои останавливаются без победителя. */
    static void abort(ServerLevel level, String key) {
        State s = state(level);
        if (s == null) {
            return;
        }
        for (Bout b : s.bouts) {
            if (b.winner.isEmpty() && fighter(level, s, b.a) instanceof SectDisciple d && d.inBout()) {
                d.stopBout();
            }
        }
        SectLife.data(level).finishReview(s.day);
        SectLife.data(level).review = null;
        MurimMod.LOGGER.info("Секта: смотр дня {} прерван", s.day);
        for (ServerPlayer p : level.players()) {
            if (SectService.state(p).member()) {
                p.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), false);
            }
        }
    }

    /**
     * Одобрение главы (флаг {@link SectStanding#APPROVAL}): за победу в смотре ({@code review}) или неделю полных дней
     * подряд ({@code streak}). Один раз; растит положение до доверенного, если хватает заслуг.
     */
    public static void approve(ServerPlayer p, String why) {
        SectState st = p.getData(ModAttachments.SECT);
        if (!st.member() || st.has(SectStanding.APPROVAL)) {
            return;
        }
        SectStanding before = SectService.standing(p);
        p.setData(ModAttachments.SECT, st.with(SectStanding.APPROVAL));
        p.displayClientMessage(Component.translatable("murim.sect.approval." + why).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        SectService.announceStanding(p, before);
        MurimMod.LOGGER.info("Секта: {} — одобрение главы ({})", p.getName().getString(), why);
    }

    /** Стенд (client/sect/SectLifeCapture): смотр начинается заново к съёмке — без игрока, в день текущего времени. */
    public static void restartForCapture(ServerLevel level, SectLayout layout) {
        if (state(level) != null) {
            abort(level, "murim.sect.review.closed");
        }
        begin(level, layout, SectSchedule.day(level.getDayTime()), null, SEARCH);
    }

    /** Записаться на смотр (диалог старшего Пэк Чхона): положение «ученик третьего класса» и выше. */
    public static void signUp(ServerPlayer p) {
        SectState st = p.getData(ModAttachments.SECT);
        if (!SectService.standing(p).atLeast(SectStanding.DISCIPLE)) {
            p.displayClientMessage(Component.translatable("murim.sect.review.too_young").withStyle(ChatFormatting.GRAY), false);
            return;
        }
        p.setData(ModAttachments.SECT, st.with(SIGNED));
        long days = daysUntil(p.level().getDayTime());
        boolean running = state(p.serverLevel()) != null;
        p.displayClientMessage(Component.translatable(days == 0 && !running ? "murim.sect.review.signed_today" : "murim.sect.review.signed",
                running && days == 0 ? EVERY : days).withStyle(ChatFormatting.GOLD), false);
    }

    /** Имя бойца для сообщений. */
    static Component name(ServerLevel level, State s, String key) {
        if (PLAYER.equals(key)) {
            ServerPlayer p = s.player == null ? null : level.getServer().getPlayerList().getPlayer(s.player);
            return p == null ? Component.literal("?") : p.getName();
        }
        return Component.translatable("npc.murim." + key);
    }

    /** Сообщение всем членам секты у площадки поединков (в 96 блоках). */
    private static void announce(ServerLevel level, SectLayout layout, Component msg) {
        Vec3 c = layout.at("sparring", 0.0D, 0.0D);
        for (ServerPlayer p : level.players()) {
            if (c == null || p.position().distanceToSqr(c) < 96.0D * 96.0D) {
                p.displayClientMessage(msg.copy().withStyle(ChatFormatting.AQUA), false);
            }
        }
    }

    /**
     * Ученик — боец идущего смотра: его место в ринге (бой ещё не начат) — для {@link SectLife#resolve}. null — не боец.
     */
    static SectSchedule.Task fighterTask(SectDisciple npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return null;
        }
        State s = state(level);
        if (s == null || s.done()) {
            return null;
        }
        String key = npc.memberKey();
        Bout b = s.current(key);
        // Выбыл или ждёт финала — зритель, как все (место по распорядку смотра).
        return b == null ? null : ringSpot(b.ring, b.a.equals(key) ? 0 : 1);
    }
}
