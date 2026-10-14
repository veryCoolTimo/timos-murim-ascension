package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Optional sect practice log. Roll calls are invitations; incomplete days and journeys preserve diligence.
 * Earned full days accumulate towards bonuses and the leader's approval, with no consecutive-day requirement.
 * Legacy punishment fields remain readable for old saves and are cleared by the Log constructor.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectAttendance {

    /** Что отмечается в журнале. */
    public enum Activity {
        /** Утренний строй: десять форм в такт ({@link SectLife#onPlayerForm}). */
        FORMATION,
        /** Занятие днём: поединок с учеником, столбы, урок Тэ Рока, упражнение (крючок). */
        LESSON,
        /** Трапеза со всеми: завтрак или ужин за столами. */
        MEAL,
        /** Подъём по тренировочной стене до верхнего уступа. */
        CLIMB,
        /** Наряд: вода на кухню. */
        CHORE,
        /** Вечерняя медитация (крючок для системы культивации). */
        MEDITATION;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String nameKey() {
            return "murim.sect.log.activity." + id();
        }

        public static Optional<Activity> of(String id) {
            for (Activity a : values()) {
                if (a.id().equals(id)) {
                    return Optional.of(a);
                }
            }
            return Optional.empty();
        }
    }

    /** Порядок строк в журнале. */
    static final List<Activity> SHOWN = List.of(Activity.FORMATION, Activity.LESSON, Activity.MEAL, Activity.CLIMB);

    /**
     * Перекличка строя: был на горе до этого часа строя (тиков от 23000; рассвет — 0, т. е. 1000). Запас после рассвета —
     * для проснувшегося в общежитии: сон кончается в 0, до конца строя ещё 1000 тиков, десяти форм хватает 300.
     */
    public static final int ROLL_FORMATION = 1300;
    /** Перекличка занятий: был на горе в первую половину занятий. */
    public static final int ROLL_LESSON = 3500;
    /** Трапеза засчитана: столько тиков за столами. */
    public static final int MEAL_TICKS = 200;
    /** Столбы и урок Тэ Рока: столько тиков — занятие. */
    public static final int PRACTICE_TICKS = 400;
    /** Заслуги за полный учебный день; прибавка после трёх таких дней, с любыми перерывами. */
    public static final int DAY_BONUS = 1;
    public static final int STREAK_DAYS = 3;
    /** Заслуги за занятие и за подъём (раз в день). */
    public static final int LESSON_CONTRIBUTION = 1;
    public static final int CLIMB_CONTRIBUTION = 1;
    /** Наряд: что принести повару. */
    public static final String CHORE_ITEM = "minecraft:water_bucket*1";

    private SectAttendance() {
    }

    // ------------------------------------------------------------------ данные

    /** Один день секты: что ждали (перекличка) и что сделано. */
    public record Day(long day, Set<String> expected, Set<String> done) {
        public static final Day NONE = new Day(Long.MIN_VALUE, Set.of(), Set.of());

        public static final Codec<Day> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("day").forGetter(Day::day),
                Codec.STRING.listOf().xmap(l -> (Set<String>) new HashSet<>(l), s -> s.stream().sorted().toList())
                        .optionalFieldOf("expected", Set.of()).forGetter(Day::expected),
                Codec.STRING.listOf().xmap(l -> (Set<String>) new HashSet<>(l), s -> s.stream().sorted().toList())
                        .optionalFieldOf("done", Set.of()).forGetter(Day::done)
        ).apply(i, Day::new));

        public Day {
            expected = Set.copyOf(expected);
            done = Set.copyOf(done);
        }

        public boolean did(Activity a) {
            return done.contains(a.id());
        }

        public boolean expects(Activity a) {
            return expected.contains(a.id());
        }

        /** Legacy dialogue condition: optional invitations never count as a missed obligation. */
        public boolean missed(Activity a) {
            return false;
        }

        /** Был ли игрок в этот день при секте (ждали или сделал хоть что-то). */
        public boolean present() {
            return !expected.isEmpty() || !done.isEmpty();
        }

        /** Всё, чего ждали, сделано (и ждали хоть что-то). */
        public boolean full() {
            return !expected.isEmpty() && done.containsAll(expected);
        }

        Day with(Activity a, boolean expect) {
            Set<String> next = new HashSet<>(expect ? expected : done);
            next.add(a.id());
            return expect ? new Day(day, next, done) : new Day(day, expected, next);
        }
    }

    /**
     * Журнал ученика: сегодня, прошлый день при секте, суммарное число полных дней.
     * missedRow/chores/scolded — поля прежних сохранений; пропуски и наказания больше не назначаются.
     */
    public record Log(Day today, Day last, int streak, int missedRow, boolean chores, long scolded) {
        public static final Log NONE = new Log(Day.NONE, Day.NONE, 0, 0, false, Long.MIN_VALUE);

        public static final Codec<Log> CODEC = RecordCodecBuilder.create(i -> i.group(
                Day.CODEC.optionalFieldOf("today", Day.NONE).forGetter(Log::today),
                Day.CODEC.optionalFieldOf("last", Day.NONE).forGetter(Log::last),
                Codec.INT.optionalFieldOf("streak", 0).forGetter(Log::streak),
                Codec.INT.optionalFieldOf("missed_row", 0).forGetter(Log::missedRow),
                Codec.BOOL.optionalFieldOf("chores", false).forGetter(Log::chores),
                Codec.LONG.optionalFieldOf("scolded", Long.MIN_VALUE).forGetter(Log::scolded)
        ).apply(i, Log::new));

        public Log {
            // Migration of old kitchen sentences is immediate, including same-day saves.
            missedRow = 0;
            chores = false;
        }

        Log today(Day d) {
            return new Log(d, last, streak, missedRow, chores, scolded);
        }

        Log choresDone() {
            return new Log(today, last, streak, 0, false, scolded);
        }
    }

    /**
     * Итог прошлого дня при смене дня.
     *
     * @param bonus         заслуги за полный день
     * @param full          всё, чего ждали, сделано
     * @param choresGiven   прежнее поле результата; теперь всегда false
     */
    public record Settled(Log log, int bonus, boolean full, boolean choresGiven) {
    }

    /**
     * Смена дня (чистая функция): сегодняшний день уходит в прошлый, если игрок в нём был при секте; считается
     * полные учебные дни. Перерывы и невыполненные приглашения не отнимают прогресс.
     */
    public static Settled roll(Log log, long day) {
        // Тот же день — или время отмотали назад (команда): журнал не закрывается и награды не повторяются.
        if (log.today().day() == day || log.today().day() != Long.MIN_VALUE && day < log.today().day()) {
            return new Settled(log, 0, false, false);
        }
        Day closed = log.today();
        Day fresh = new Day(day, Set.of(), Set.of());
        if (closed.day() == Long.MIN_VALUE || closed.expected().isEmpty()) {
            Day last = closed.present() ? closed : log.last();
            return new Settled(new Log(fresh, last, log.streak(), log.missedRow(), log.chores(), log.scolded()), 0, false, false);
        }
        if (closed.full()) {
            int streak = log.streak() + 1;
            int bonus = DAY_BONUS + (streak >= STREAK_DAYS ? 1 : 0);
            return new Settled(new Log(fresh, closed, streak, 0, false, log.scolded()), bonus, true, false);
        }
        // An invitation can be ignored, including while doing other Minecraft activities on the mountain.
        return new Settled(new Log(fresh, closed, log.streak(), 0, false, log.scolded()), 0, false, false);
    }

    /** Отметить (чистая функция): {@code expect} — перекличка, иначе — сделано. */
    static Log mark(Log log, long day, Activity a, boolean expect) {
        Log l = roll(log, day).log();
        return l.today(l.today().with(a, expect));
    }

    // ------------------------------------------------------------------ сервер: крючок и тик

    static Log log(ServerPlayer p) {
        return p.getData(ModAttachments.SECT_ATTENDANCE);
    }

    /** День секты сейчас. */
    static long today(ServerPlayer p) {
        return SectSchedule.day(p.level().getDayTime());
    }

    /** Журнал игрока с учётом смены дня (итог прошлого дня начисляется здесь же). */
    public static Log current(ServerPlayer p) {
        settle(p);
        return log(p);
    }

    /**
     * Крючок: игрок сделал занятие секты. Только для учеников секты; одно занятие — одна отметка в день.
     *
     * @return отмечено ли впервые за день
     */
    public static boolean record(ServerPlayer p, Activity a) {
        if (!p.getData(ModAttachments.SECT).member()) {
            return false;
        }
        settle(p);
        Log log = log(p);
        if (log.today().did(a)) {
            return false;
        }
        long day = today(p);
        p.setData(ModAttachments.SECT_ATTENDANCE, mark(log, day, a, false));
        p.displayClientMessage(Component.translatable("murim.sect.log.recorded", Component.translatable(a.nameKey()))
                .withStyle(ChatFormatting.GREEN), true);
        MurimMod.LOGGER.info("Журнал секты: {} — {} (день {})", p.getName().getString(), a.id(), day);
        switch (a) {
            case LESSON -> SectService.contribute(p, LESSON_CONTRIBUTION);
            case CLIMB -> SectService.contribute(p, CLIMB_CONTRIBUTION);
            case MEAL -> {
                // Злаки и орехи — «самая дешёвая диета» (гл. 76): сытно не бывает, но силы есть.
                p.getFoodData().eat(5, 0.6F);
                p.displayClientMessage(Component.translatable("murim.sect.meal.fed").withStyle(ChatFormatting.GRAY), false);
            }
            case CHORE -> {
                p.setData(ModAttachments.SECT_ATTENDANCE, log(p).choresDone());
                p.displayClientMessage(Component.translatable("murim.sect.log.chores_done").withStyle(ChatFormatting.GOLD), false);
            }
            default -> {
            }
        }
        return true;
    }

    /**
     * Крючок по id ({@code formation}, {@code lesson}, {@code meal}, {@code climb}, {@code chore}, {@code meditation});
     * незнакомый id (упражнение, испытание) засчитывается как занятие.
     */
    public static boolean record(ServerPlayer p, String activity) {
        return record(p, Activity.of(activity).orElse(Activity.LESSON));
    }

    /** Смена дня: заслуги за полный учебный день. */
    static void settle(ServerPlayer p) {
        Log previous = log(p);
        Settled s = roll(previous, today(p));
        if (s.log() == previous) {
            return;
        }
        p.setData(ModAttachments.SECT_ATTENDANCE, s.log());
        if (previous.today().day() != Long.MIN_VALUE && today(p) - previous.today().day() >= 2
                && p.getData(ModAttachments.SECT).member()) {
            p.displayClientMessage(Component.translatable("murim.sect.journey.welcome"), false);
        }
        if (s.full() && s.bonus() > 0) {
            p.displayClientMessage(Component.translatable("murim.sect.log.day_bonus", s.bonus(), s.log().streak())
                    .withStyle(ChatFormatting.GOLD), false);
            SectService.contribute(p, s.bonus());
        }
        // Семь полных дней, с любыми перерывами — одобрение главы (SectReview.APPROVAL_STREAK; второй путь — победа на смотре).
        if (s.full() && s.log().streak() >= SectReview.APPROVAL_STREAK) {
            SectReview.approve(p, "streak");
        }
    }

    private static final String PRESENCE_TAG = "murim_sect_presence";

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 7 || p.isSpectator()) {
            return;
        }
        if (!p.getData(ModAttachments.SECT).member()) {
            return;
        }
        SectLayout layout = SectLife.layout(p.serverLevel());
        if (layout != null) {
            tick(p, layout, 20);
        }
    }

    /** Раз в {@code dt} тиков: смена дня, приглашения, трапеза, столбы, урок, подъём. */
    public static void tick(ServerPlayer p, SectLayout layout, int dt) {
        settle(p);
        long day = today(p);
        CompoundTag tag = p.getPersistentData().getCompound(PRESENCE_TAG);
        if (tag.getLong("day") != day) {
            tag = new CompoundTag();
            tag.putLong("day", day);
        }
        climb(p, tag);
        p.getPersistentData().put(PRESENCE_TAG, tag);
        Vec3 pos = p.position();
        if (!onShelf(layout, pos)) {
            return;
        }
        long time = p.level().getDayTime();
        SectSchedule.Period period = SectSchedule.at(time);
        int since = SectSchedule.sincePeriodStart(time);
        Log log = log(p);
        boolean called = false;
        if (period == SectSchedule.Period.FORMATION && since <= ROLL_FORMATION && !log.today().expects(Activity.FORMATION)) {
            p.setData(ModAttachments.SECT_ATTENDANCE, mark(log, day, Activity.FORMATION, true));
            called = true;
        } else if (period == SectSchedule.Period.TRAINING && since <= ROLL_LESSON && !log.today().expects(Activity.LESSON)) {
            p.setData(ModAttachments.SECT_ATTENDANCE, mark(log, day, Activity.LESSON, true));
            called = true;
        }
        if (called) {
            explainOnce(p);
        }
        if (period.meal() && (layout.inside("dining", pos, 2.0D) || nearTable(layout, pos))) {
            tag.putInt("meal", tag.getInt("meal") + dt);
            if (tag.getInt("meal") >= MEAL_TICKS) {
                record(p, Activity.MEAL);
            }
        }
        if (period == SectSchedule.Period.TRAINING) {
            // На столбах — значит на столбе (выше площадки), а не рядом на земле.
            Vec3 pad = layout.at("poles", 0.0D, 0.0D);
            if (layout.inside("poles", pos, 0.5D) && pad != null && pos.y >= pad.y + 1.0D && p.onGround()) {
                tag.putInt("poles", tag.getInt("poles") + dt);
            }
            if (nearLecture(p)) {
                tag.putInt("lecture", tag.getInt("lecture") + dt);
            }
            if (tag.getInt("poles") >= PRACTICE_TICKS || tag.getInt("lecture") >= PRACTICE_TICKS) {
                record(p, Activity.LESSON);
            }
        }
        p.getPersistentData().put(PRESENCE_TAG, tag);
    }

    /** На полке секты: в пределах любой площадки секты с запасом. */
    static boolean onShelf(SectLayout layout, Vec3 pos) {
        for (String zone : SectLayout.SECT_ZONES) {
            Vec3 c = layout.at(zone, 0.0D, 0.0D);
            if (c != null && Math.abs(pos.y - c.y) < 16.0D && layout.inside(zone, pos, 8.0D)) {
                return true;
            }
        }
        return false;
    }

    /** Стол во дворе лагеря (трапеза второй очереди, {@code SectSchedule.eat}). */
    static boolean nearTable(SectLayout layout, Vec3 pos) {
        double[] l = layout.local("camp", pos);
        return l != null && l[0] > 1.0D && l[0] < 9.0D && l[1] > -4.0D && l[1] < 9.0D;
    }

    /** Рядом с Тэ Роком, пока он учит. */
    static boolean nearLecture(ServerPlayer p) {
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(7.0D),
                d -> "tae_rok".equals(d.memberKey()))) {
            // Урок идёт: старейшина на месте и говорит (поза разговора), а не спит вдали и не идёт.
            SectSchedule.Task t = d.member().map(m -> SectSchedule.task(m, p.level().getDayTime())).orElse(null);
            if (t != null && t.kind() == SectSchedule.Kind.LECTURE && !d.dormant()
                    && d.pose() == io.github.verycooltimo.murim.entity.SectPose.TALK) {
                return true;
            }
        }
        return false;
    }

    /**
     * Подъём: сегодня начал с нижнего уступа тренировочной стены ({@code climb_1}) и дошёл до верхнего
     * ({@code climb_16}) ногами, днём. Стоять наверху день за днём — не подъём (codex 05.10).
     */
    static void climb(ServerPlayer p, CompoundTag tag) {
        if (SectSchedule.at(p.level().getDayTime()) == SectSchedule.Period.NIGHT || p.getAbilities().flying
                || p.level().dimension() != net.minecraft.world.level.Level.OVERWORLD || log(p).today().did(Activity.CLIMB)) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(p.getServer());
        if (site == null) {
            return;
        }
        MountHuaPlan.Ledge first = null;
        MountHuaPlan.Ledge top = null;
        for (MountHuaPlan.Ledge l : MountHuaPlan.CLIMB) {
            if (l.kind() != MountHuaPlan.Kind.SIDE) {
                first = first == null ? l : first;
                top = l;
            }
        }
        if (first == null) {
            return;
        }
        if (near(p, site, first)) {
            tag.putBoolean("climb_start", true);
        } else if (tag.getBoolean("climb_start") && near(p, site, top) && p.onGround()) {
            record(p, Activity.CLIMB);
        }
    }

    private static boolean near(ServerPlayer p, MountHuaSite site, MountHuaPlan.Ledge l) {
        int[] w = site.toWorld(l.u(), l.v());
        return p.distanceToSqr(w[0] + 0.5D, site.worldY(l.y()) + 1.0D, w[1] + 0.5D) < 6.0D * 6.0D;
    }

    /**
     * Первая перекличка ученика: наставник объясняет распорядок — что, где, как засчитывается и что за это (codex 05.10:
     * «обязательства выставляются молча»). Один раз за всю жизнь в секте.
     */
    static void explainOnce(ServerPlayer p) {
        SectState s = p.getData(ModAttachments.SECT);
        if (s.has(EXPLAINED)) {
            return;
        }
        p.setData(ModAttachments.SECT, s.with(EXPLAINED));
        p.displayClientMessage(Component.translatable("murim.sect.log.rules").withStyle(ChatFormatting.GOLD), false);
    }

    /** Флаг секты: распорядок объяснён. */
    public static final String EXPLAINED = "log.explained";

    // ------------------------------------------------------------------ журнал словами

    /** День для итога: сегодня, если в нём что-то было, иначе прошлый день при секте. */
    static Day shownDay(Log log) {
        return log.today().present() ? log.today() : log.last();
    }

    /** Журнал дня строкой: «Сегодня: строй ✔ · занятия ✘ · трапеза ✔ · подъём —». */
    public static Component line(ServerPlayer p) {
        Log log = current(p);
        Day d = shownDay(log);
        if (!d.present()) {
            return Component.translatable("murim.sect.log.empty");
        }
        boolean today = d == log.today();
        MutableComponent out = Component.translatable(today ? "murim.sect.log.today" : "murim.sect.log.last").append(" ");
        boolean first = true;
        List<Activity> rows = new java.util.ArrayList<>(SHOWN);
        if (log.chores() || d.did(Activity.CHORE)) {
            rows.add(Activity.CHORE);
        }
        for (Activity a : rows) {
            if (!first) {
                out.append(" · ");
            }
            first = false;
            String mark = d.did(a) ? "murim.sect.log.mark.done" : d.expects(a) ? (today && open(p, a) ? "murim.sect.log.mark.open" : "murim.sect.log.mark.skipped")
                    : "murim.sect.log.mark.none";
            out.append(Component.translatable(mark, Component.translatable(a.nameKey())));
        }
        return out;
    }

    /** Занятие ещё можно успеть сегодня. */
    static boolean open(ServerPlayer p, Activity a) {
        SectSchedule.Period period = SectSchedule.at(p.level().getDayTime());
        return switch (a) {
            case FORMATION -> period == SectSchedule.Period.FORMATION;
            case LESSON -> period == SectSchedule.Period.TRAINING || period == SectSchedule.Period.BREAKFAST || period == SectSchedule.Period.FORMATION;
            default -> period != SectSchedule.Period.NIGHT;
        };
    }

    /**
     * Слово наставника: приветствует вернувшегося, благодарит за участие, хвалит полный учебный день.
     */
    public static Component verdict(ServerPlayer p) {
        Log log = current(p);
        Day d = shownDay(log);
        String key;
        if (!log.today().present()) {
            key = "away";
        } else if ((d.full() || d.did(Activity.FORMATION) && d.did(Activity.LESSON))
                && !(d == log.today() && !d.did(Activity.LESSON) && open(p, Activity.LESSON))) {
            key = log.streak() + (d == log.today() ? 1 : 0) >= STREAK_DAYS ? "streak" : "full";
        } else {
            key = "partial";
        }
        return Component.translatable("murim.sect.log.verdict." + key, log.streak());
    }

    /** Отчёт для команды. */
    public static String report(ServerPlayer p) {
        Log l = current(p);
        return "today=" + l.today().day() + " expected=" + l.today().expected() + " done=" + l.today().done()
                + " last=" + l.last().day() + " " + l.last().done() + "/" + l.last().expected()
                + " streak=" + l.streak() + " missedRow=" + l.missedRow() + " chores=" + l.chores();
    }
}
