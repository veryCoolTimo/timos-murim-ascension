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
 * Смотр учеников (docs/design/23-mount-hua-sect.md §6.1): раз в 7 дней секты на тренировочной площади — сетка из
 * четырёх мест (полуфиналы в двух рингах одновременно, затем финал) или из восьми (если записались двое и больше
 * игроков: четвертьфиналы в четырёх рингах, полуфиналы, финал; docs/design/39 п. 4). Ученики не на посту и
 * старшие смотрят ({@link #spectator}). Игроки записываются у старшего (флаг {@link #SIGNED}, нужно положение
 * «ученик третьего класса») и встают в сетку вместе с учениками; бой двух игроков — дуэль {@link SectDuel} без отдельного
 * согласия (запись и есть согласие).
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
    /** Окно смотра в тиках суток: после доклада Тэ Гюна (2900) до совета старейшин (6000). */
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
    static final String PLAYER_PREFIX = "p:";
    /** Мест в сетке не больше. */
    public static final int MAX_PLACES = 8;
    /** Ринги первого круга восьмёрки (номера в {@link SectSchedule#RINGS}). */
    static final int[] QUARTER_RINGS = {1, 4, 0, 3};
    /** Бойцов ищут в стольких блоках от тренировочной площади (лагерь, общежития — вся полка секты). */
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
     * Место зрителя на смотре: глава и наставник — на помосте, Тэ Рок и Тэ Сон — у края площадки, лекарь — на дежурстве у
     * края; ученики (второе и третье поколение) — двумя рядами вдоль северного и южного края лицом к рингам. Остальные
     * (Гён Так у ворот, Тэ Гюн у казны, слуги) — своим делом: null.
     */
    static SectSchedule.Task spectator(SectRoster m) {
        switch (m.key()) {
            case "tae_hwi" -> {
                return new SectSchedule.Task(SectSchedule.Kind.WATCH, "mentor", -1.0D, 2.0D, 1.0D, 0.0D);
            }
            case "gyeong_pil" -> {
                return new SectSchedule.Task(SectSchedule.Kind.WATCH, "mentor", -1.0D, -1.0D, 1.0D, 0.0D);
            }
            case "tae_rok" -> {
                return new SectSchedule.Task(SectSchedule.Kind.WATCH, "training", 12.0D, -8.5D, -0.6D, 1.0D);
            }
            case "tae_seong" -> {
                return new SectSchedule.Task(SectSchedule.Kind.WATCH, "training", 12.0D, 8.5D, -0.6D, -1.0D);
            }
            case "gyeong_cho" -> {
                return new SectSchedule.Task(SectSchedule.Kind.HEAL_POST, "training", -12.0D, -8.5D, 0.6D, 1.0D);
            }
            default -> {
            }
        }
        if (m.firstRank()) {
            // Рядовые первого поколения — у западного края площадки, лицом к рингам: смотрят и поправляют.
            int f = SectRoster.firstRanks().indexOf(m);
            return new SectSchedule.Task(SectSchedule.Kind.WATCH, "training", -15.0D, -4.5D + f * 3.0D, 1.0D, 0.0D);
        }
        List<SectRoster> crowd = crowd();
        int i = crowd.indexOf(m);
        if (i < 0) {
            return null;
        }
        int side = i % 2;
        int col = i / 2;
        double du = -13.5D + col * 2.25D;
        return new SectSchedule.Task(SectSchedule.Kind.WATCH, "training", du, side == 0 ? -10.0D : 10.0D, 0.0D, side == 0 ? 1.0D : -1.0D);
    }

    /** Зрители-ученики: второе и третье поколение по списку. */
    static List<SectRoster> crowd() {
        List<SectRoster> out = new ArrayList<>(SectRoster.generation(2));
        out.addAll(SectRoster.generation(3));
        return out;
    }

    /**
     * Сетка смотра дня {@code day} без игроков: четверо учеников третьего поколения по кругу списка (каждую неделю —
     * другие). С игроком — {@code "p:"}-ключ первым (старый вызов стенда и тестов).
     */
    public static List<String> bracket(long day, boolean withPlayer) {
        return bracket(day, withPlayer ? List.of(PLAYER_PREFIX) : List.of());
    }

    /** Сколько мест в сетке: восемь, если записались двое и больше игроков, иначе четыре. */
    public static int places(int players) {
        return players >= 2 ? MAX_PLACES : 4;
    }

    /**
     * Сетка до восьми мест: игроки (по ключам {@link #playerKey}) и ученики третьего поколения вместе. Игроки стоят
     * первыми в парах первого круга, чтобы встретиться с учениками, а не друг с другом (если игроков больше половины
     * мест — остальные попадают второй стороной пары). Ученики берутся по кругу списка, каждую неделю другие.
     */
    public static List<String> bracket(long day, List<String> playerKeys) {
        int size = places(playerKeys.size());
        List<String> players = playerKeys.size() > size ? playerKeys.subList(0, size) : playerKeys;
        List<SectRoster> gen = SectRoster.generation(3);
        int start = (int) Math.floorMod(Math.floorDiv(day, (long) EVERY) * 4L, (long) gen.size());
        String[] slots = new String[size];
        int next = 0;
        for (int pair = 0; pair < size / 2 && next < players.size(); pair++) {
            slots[pair * 2] = players.get(next++);
        }
        for (int i = 1; i < size && next < players.size(); i += 2) {
            slots[i] = players.get(next++);
        }
        int npc = 0;
        for (int i = 0; i < size; i++) {
            if (slots[i] == null) {
                slots[i] = gen.get((start + npc++) % gen.size()).key();
            }
        }
        return new ArrayList<>(List.of(slots));
    }

    /** Ключ игрока в сетке. */
    public static String playerKey(ServerPlayer p) {
        return PLAYER_PREFIX + p.getUUID();
    }

    static boolean isPlayerKey(String key) {
        return key.startsWith(PLAYER_PREFIX);
    }

    /** Ринги круга: первый круг восьмерки — четыре ринга, полуфиналы — два, финал — один. */
    static int ringFor(int size, int round, int index) {
        int[] rings = round == 0 && size == 8 ? QUARTER_RINGS : SEMI_RINGS;
        return rings[Math.min(index, rings.length - 1)];
    }

    /** Место бойца: ринг и сторона (0 — запад, 1 — восток). */
    static SectSchedule.Task ringSpot(int ring, int side) {
        return SectSchedule.showcaseRing(ring, side, "");
    }

    // ------------------------------------------------------------------ состояние

    /** Бой сетки. */
    public static final class Bout {
        String a;
        String b;
        final int ring;
        /** Круг: 0 — первый. */
        final int round;
        String winner = "";
        long called = -1L;
        long started = -1L;

        Bout(String a, String b, int ring, int round) {
            this.a = a;
            this.b = b;
            this.ring = ring;
            this.round = round;
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
        /** Игроки в сетке (ключи {@link #playerKey}). */
        final List<UUID> players;
        /** Мест в сетке: 4 или 8. */
        final int size;
        /** Где искать бойцов: центр тренировочной площади и радиус. */
        final Vec3 center;
        final double radius;
        final List<Bout> bouts = new ArrayList<>();
        String champion = "";

        State(long day, List<UUID> players, int size, Vec3 center, double radius) {
            this.day = day;
            this.players = players;
            this.size = size;
            this.center = center;
            this.radius = radius;
        }

        public List<Bout> bouts() {
            return bouts;
        }

        public int size() {
            return size;
        }

        public List<UUID> players() {
            return players;
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

        /** Номер круга, который идёт (по числу созданных боёв). */
        int round() {
            return bouts.isEmpty() ? 0 : bouts.get(bouts.size() - 1).round;
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
        remind(level, data, time, day);
        if (data.review == null && window(time) && data.lastReview() != day) {
            Vec3 c = layout.at("training", 0.0D, 0.0D);
            // Смотр идёт, только если рядом кто-то есть: иначе люди спят (дормант), бои не сыграть — день просто проходит.
            if (c != null && level.getNearestPlayer(c.x, c.y, c.z, 96.0D, false) != null) {
                begin(level, layout, day, signedPlayers(level, c), SEARCH);
            }
        }
        if (data.review != null) {
            step(level, layout);
            if (data.review != null && !window(time) && !data.review.done()) {
                abort(level, "murim.sect.review.closed", layout);
            }
        }
    }

    /** In the morning of the review day the signed players are reminded once. */
    static void remind(ServerLevel level, SectSiteData data, long time, long day) {
        int t = (int) Math.floorMod(time, (long) SectSchedule.DAY);
        if (!reviewDay(day) || t >= FROM || data.session.remindedDay == day || data.lastReview() == day) {
            return;
        }
        data.session.remindedDay = day;
        for (ServerPlayer p : level.players()) {
            if (p.getData(ModAttachments.SECT).has(SIGNED)) {
                SectNotice.chat(p, Component.translatable("murim.sect.review.reminder").withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /** Записанные игроки рядом с площадкой (до восьми, в порядке списка игроков). */
    private static List<ServerPlayer> signedPlayers(ServerLevel level, Vec3 c) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (out.size() < MAX_PLACES && !p.isSpectator() && p.getData(ModAttachments.SECT).has(SIGNED)
                    && p.position().distanceToSqr(c) < 96.0D * 96.0D) {
                out.add(p);
            }
        }
        return out;
    }

    /** Начать смотр с одним игроком или без (стенд и прежние GameTest зовут так). */
    public static State begin(ServerLevel level, SectLayout layout, long day, ServerPlayer player, double radius) {
        return begin(level, layout, day, player == null ? List.of() : List.of(player), radius);
    }

    /**
     * Начать смотр: сетка из четырёх или восьми мест, игроки и ученики вместе. Запись на смотр — согласие на бой
     * (в том числе против другого игрока).
     */
    public static State begin(ServerLevel level, SectLayout layout, long day, List<ServerPlayer> players, double radius) {
        List<String> playerKeys = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (ServerPlayer p : players) {
            if (playerKeys.size() < MAX_PLACES) {
                playerKeys.add(playerKey(p));
                ids.add(p.getUUID());
            }
        }
        List<String> keys = bracket(day, playerKeys);
        Vec3 center = layout.at("training", 0.0D, 0.0D);
        State s = new State(day, ids, keys.size(), center == null ? Vec3.ZERO : center, radius);
        for (int i = 0; i < keys.size(); i += 2) {
            s.bouts.add(new Bout(keys.get(i), keys.get(i + 1), ringFor(keys.size(), 0, i / 2), 0));
        }
        SectLife.data(level).review = s;
        MurimMod.LOGGER.info("Секта: смотр учеников, день {}: {}", day, keys);
        for (ServerPlayer p : players) {
            if (!ids.contains(p.getUUID())) {
                continue;
            }
            p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without(SIGNED));
            SectService.contribute(p, ENTRY);
            Bout mine = s.current(playerKey(p));
            SectNotice.chat(p, Component.translatable("murim.sect.review.your_bout", name(level, s, mine.other(playerKey(p))))
                    .withStyle(ChatFormatting.GOLD));
        }
        Component list = Component.empty();
        for (int i = 0; i < keys.size(); i++) {
            list = list.copy().append(i == 0 ? Component.empty() : Component.literal(", ")).append(name(level, s, keys.get(i)));
        }
        announce(level, layout, Component.translatable("murim.sect.review.begin_list", keys.size(), list));
        return s;
    }

    /** Шаг смотра (раз в 10 тиков): позвать бойцов, начать бой, следить за исходом, собрать следующий круг. */
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
        List<Bout> round = new ArrayList<>();
        for (Bout b : s.bouts) {
            if (b.round == s.round()) {
                round.add(b);
            }
        }
        if (!round.stream().allMatch(b -> !b.winner.isEmpty())) {
            return;
        }
        if (round.size() == 1) {
            finish(level, layout, s);
            return;
        }
        int next = s.round() + 1;
        for (int i = 0; i < round.size(); i += 2) {
            Bout fin = new Bout(round.get(i).winner, round.get(i + 1).winner, ringFor(s.size, next, i / 2), next);
            s.bouts.add(fin);
            announce(level, layout, Component.translatable(round.size() == 2 ? "murim.sect.review.final" : "murim.sect.review.semi",
                    name(level, s, fin.a), name(level, s, fin.b)));
        }
        for (ServerPlayer p : players(level, s)) {
            Bout mine = s.current(playerKey(p));
            if (mine != null && mine.round == next) {
                SectNotice.chat(p, Component.translatable("murim.sect.review.your_bout", name(level, s, mine.other(playerKey(p))))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /** Игроки смотра, которые сейчас на сервере. */
    private static List<ServerPlayer> players(ServerLevel level, State s) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : s.players) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private static void stepBout(ServerLevel level, SectLayout layout, State s, Bout b, long now) {
        LivingEntity ea = fighter(level, s, b.a);
        LivingEntity eb = fighter(level, s, b.b);
        if (b.called < 0) {
            b.called = now;
        }
        if (b.started < 0) {
            if (ea == null || eb == null) {
                // Бойца ещё нет (ученик: чанк не загружен, не встал на гору; игрок вышел) — ждём, как опоздавшего; через
                // минуту — победа другому, нет обоих — первому по сетке.
                if (now - b.called >= SHOW_UP) {
                    decide(level, s, b, ea == null && eb != null ? b.b : b.a, "absent");
                }
                return;
            }
            boolean readyA = ready(layout, b, 0, ea);
            boolean readyB = ready(layout, b, 1, eb);
            if (!(readyA && readyB) && now - b.called >= SHOW_UP) {
                // Опоздавший игрок получает неявку; ученики продолжают идти к рингу.
                boolean lateA = isPlayerKey(b.a) && !readyA;
                boolean lateB = isPlayerKey(b.b) && !readyB;
                if (lateA || lateB) {
                    // Late for his own bout: a disciple takes his place, no penalty.
                    if (lateA) {
                        b.a = substitute(level, s, b, b.a);
                    }
                    if (lateB) {
                        b.b = substitute(level, s, b, b.b);
                    }
                    b.called = now;
                    return;
                }
                place(layout, b, 0, ea);
                place(layout, b, 1, eb);
                return;
            }
            if (readyA && readyB && busyFree(ea) && busyFree(eb)) {
                start(level, layout, s, b, ea, eb, now);
            }
            return;
        }
        if (ea instanceof ServerPlayer && eb instanceof ServerPlayer) {
            // Бой двух игроков: исход принесёт SectDuel; затянувшийся и оставленный закончит сама дуэль.
            if (!ea.isAlive() || !eb.isAlive()) {
                decide(level, s, b, ea.isAlive() ? b.a : b.b, "absent");
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

    /** A third-generation disciple not yet in the bracket takes the place of a player who is late. */
    static String substitute(ServerLevel level, State s, Bout b, String lateKey) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Bout other : s.bouts) {
            used.add(other.a);
            used.add(other.b);
        }
        String pick = null;
        for (SectRoster m : SectRoster.generation(3)) {
            if (!used.contains(m.key())) {
                pick = m.key();
                break;
            }
        }
        if (pick == null) {
            pick = SectRoster.generation(3).get(0).key();
        }
        for (ServerPlayer p : players(level, s)) {
            if (lateKey.equals(playerKey(p))) {
                SectNotice.chat(p, Component.translatable("murim.sect.review.replaced", name(level, s, pick)).withStyle(ChatFormatting.GRAY));
            }
        }
        MurimMod.LOGGER.info("Секта: смотр — опоздал {}, вместо него {}", lateKey, pick);
        return pick;
    }

    private static boolean busyFree(LivingEntity e) {
        return !(e instanceof SectDisciple d) || d.spar() == SectDisciple.Spar.NONE && !d.defending();
    }

    private static void start(ServerLevel level, SectLayout layout, State s, Bout b, LivingEntity ea, LivingEntity eb, long now) {
        b.started = now;
        if (ea instanceof SectDisciple a && eb instanceof SectDisciple bb) {
            a.setWounded(false);
            bb.setWounded(false);
            a.sparWith(bb, 20);
        } else if (ea instanceof ServerPlayer pa && eb instanceof ServerPlayer pb) {
            Vec3 c = layout.at("training", SectSchedule.RINGS[b.ring][0], SectSchedule.RINGS[b.ring][1]);
            SectDuel.start(pa, pb, c == null ? s.center : c, winner -> {
                if (winner == null) {
                    // Дуэль оборвана без победителя: по остатку сил.
                    decide(level, s, b, frac(pa) >= frac(pb) ? b.a : b.b, "stopped");
                } else {
                    decide(level, s, b, winner.equals(pa.getUUID()) ? b.a : b.b, "bout");
                }
            });
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
            d.walking().walk(spot, 0.9D);
        }
    }

    /** Боец: игрок смотра или ученик по ключу (в радиусе поиска от площади — ближайший экземпляр). */
    static LivingEntity fighter(ServerLevel level, State s, String key) {
        if (isPlayerKey(key)) {
            for (UUID id : s.players) {
                if (key.equals(PLAYER_PREFIX + id)) {
                    Entity e = level.getEntity(id);
                    return e instanceof ServerPlayer p && p.isAlive() ? p : null;
                }
            }
            return null;
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
        boolean partnerIsOther = isPlayerKey(other) ? partner instanceof ServerPlayer p && other.equals(playerKey(p))
                : partner instanceof SectDisciple d && other.equals(d.memberKey());
        if (!partnerIsOther) {
            return;
        }
        decide(level, s, b, partnerWon ? other : npc.memberKey(), "bout");
    }

    /** Решить бой (исход, неявка, обрыв); игрокам — строка итога и заслуги за победу. Тесты зовут напрямую. */
    static void decide(ServerLevel level, State s, Bout b, String winner, String why) {
        decide(level, s, b, winner, why, players(level, s));
    }

    /** Explicit audience for isolated server fixtures; production resolves signed-in players above. */
    static void decide(ServerLevel level, State s, Bout b, String winner, String why, List<ServerPlayer> audience) {
        if (!b.winner.isEmpty()) {
            return;
        }
        b.winner = winner;
        MurimMod.LOGGER.info("Секта: смотр — {} против {}: победил {} ({})", b.a, b.b, winner, why);
        for (ServerPlayer p : audience) {
            String me = playerKey(p);
            if (!b.has(me)) {
                continue;
            }
            boolean won = me.equals(winner);
            if (won) {
                SectService.contribute(p, WIN);
                if (b.started >= 0 && !"absent".equals(why) && !isPlayerKey(b.other(me)) && SectRoster.of(b.other(me)).isPresent()) {
                    p.setData(ModAttachments.SECT, SectService.state(p).with("mentor.partner." + b.other(me)));
                }
            }
            SectNotice.chat(p, Component.translatable(won ? "murim.sect.review.won" : "no_show".equals(why)
                    ? "murim.sect.review.no_show" : "murim.sect.review.lost", name(level, s, b.other(me)))
                    .withStyle(won ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        }
    }

    private static void finish(ServerLevel level, SectLayout layout, State s) {
        s.champion = s.bouts.get(s.bouts.size() - 1).winner;
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
            SectSpeechWitnesses.record(p, "review");
            SectRequests.reviewWon(p);
        }
        // О победителе говорят ученики (SectTalk): ключ ученика или имя игрока.
        SectLife.data(level).crown(champ instanceof ServerPlayer p ? "@" + p.getName().getString() : s.champion);
        MurimMod.LOGGER.info("Секта: смотр дня {} окончен, победил {}", s.day, s.champion);
        SectLife.data(level).review = null;
        SectBladeExam.call(level, layout);
    }

    /** Смотр прерван (окно кончилось): бои останавливаются без победителя. */
    static void abort(ServerLevel level, String key) {
        MountHuaSite site = MountHuaSites.get(level.getServer());
        abort(level, key, site == null ? null : SectLayout.hua(site));
    }

    static void abort(ServerLevel level, String key, SectLayout layout) {
        State s = state(level);
        if (s == null) {
            return;
        }
        for (Bout b : s.bouts) {
            if (!b.winner.isEmpty()) {
                continue;
            }
            for (String side : new String[] {b.a, b.b}) {
                LivingEntity e = fighter(level, s, side);
                if (e instanceof SectDisciple d && d.inBout()) {
                    d.stopBout();
                } else if (e instanceof ServerPlayer p) {
                    SectDuel.cancel(level, p.getUUID());
                }
            }
        }
        SectLife.data(level).finishReview(s.day);
        SectLife.data(level).review = null;
        if (layout != null) SectBladeExam.call(level, layout);
        MurimMod.LOGGER.info("Секта: смотр дня {} прерван", s.day);
        for (ServerPlayer p : level.players()) {
            if (SectService.state(p).member()) {
                SectNotice.chat(p, Component.translatable(key).withStyle(ChatFormatting.GRAY));
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
        SectNotice.chat(p, Component.translatable("murim.sect.approval." + why).withStyle(ChatFormatting.LIGHT_PURPLE));
        SectService.announceStanding(p, before);
        MurimMod.LOGGER.info("Секта: {} — одобрение главы ({})", p.getName().getString(), why);
    }

    /** Стенд (client/sect/SectLifeCapture): смотр начинается заново к съёмке — без игрока, в день текущего времени. */
    public static void restartForCapture(ServerLevel level, SectLayout layout) {
        if (state(level) != null) {
            abort(level, "murim.sect.review.closed");
        }
        begin(level, layout, SectSchedule.day(level.getDayTime()), (ServerPlayer) null, SEARCH);
    }

    /** Sign-up is open: the review is today or tomorrow, or it is already running. The tablet's button follows this. */
    public static boolean signupOpen(ServerPlayer p) {
        return daysUntil(p.level().getDayTime()) <= 1 || state(p.serverLevel()) != null;
    }

    /** Записаться на смотр (диалог старшего Со Рана): положение «ученик третьего класса» и выше. */
    public static void signUp(ServerPlayer p) {
        SectState st = p.getData(ModAttachments.SECT);
        if (!SectService.standing(p).atLeast(SectStanding.DISCIPLE)) {
            PlayerJournal.refuse(p, Component.translatable("murim.sect.review.too_young").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!signupOpen(p)) {
            PlayerJournal.refuse(p, Component.translatable("murim.sect.review.too_early", daysUntil(p.level().getDayTime()) - 1)
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        p.setData(ModAttachments.SECT, st.with(SIGNED));
        SectTablet.touch(p);
        long days = daysUntil(p.level().getDayTime());
        boolean running = state(p.serverLevel()) != null;
        SectNotice.bar(p, Component.translatable(days == 0 && !running ? "murim.sect.review.signed_today" : "murim.sect.review.signed",
                running && days == 0 ? EVERY : days).withStyle(ChatFormatting.GOLD));
    }

    /** Имя бойца для сообщений. */
    static Component name(ServerLevel level, State s, String key) {
        if (isPlayerKey(key)) {
            for (UUID id : s.players) {
                if (key.equals(PLAYER_PREFIX + id)) {
                    ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                    return p == null ? Component.literal("?") : p.getName();
                }
            }
            return Component.literal("?");
        }
        return SectNames.of(key);
    }

    /** Сообщение всем членам секты у тренировочной площади (в 96 блоках). */
    private static void announce(ServerLevel level, SectLayout layout, Component msg) {
        Vec3 c = layout.at("training", 0.0D, 0.0D);
        for (ServerPlayer p : level.players()) {
            if (c == null || p.position().distanceToSqr(c) < 96.0D * 96.0D) {
                SectNotice.chat(p, msg.copy().withStyle(ChatFormatting.AQUA));
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
        // Выбыл или ждёт следующего круга — зритель, как все (место по распорядку смотра).
        return b == null ? null : ringSpot(b.ring, b.a.equals(key) ? 0 : 1);
    }
}
