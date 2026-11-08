package io.github.verycooltimo.murim.cultivation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Пилюли игрока: сколько каких уже съедено, стойкость к ядам, открытое окно «сразу», привыкание (одна и та же пилюля подряд,
 * Myst гл. 148) и старый резерв неусвоенной доли (docs/design/19b §1–2, §10).
 *
 * <p>Сохраняется и переживает смерть. Окно сохраняется вместе со всем, оно короткое и сверяется по игровому времени.
 *
 * @param taken          съедено пилюль каждого вида за всю игру (ключ — имя {@link PillKind})
 * @param poisonResistant поглотил Пилюлю Тысячи Ядов: яды действуют вдвое короче
 * @param pending        растворяющаяся пилюля до посадки (одна; старые составы тоже читаются)
 * @param first          тик первой пилюли окна
 * @param deadline       тик конца окна
 * @param streak         the pill eaten alone last time (name of {@link PillKind}), empty - none or a combination
 * @param streakCount    how many times in a row that pill was eaten alone
 * @param streakAt       game time of the last single intake (legacy snapshot)
 * @param tolerances     independent same-kind recovery histories (optional in old saves)
 * @param reserve        старый резерв (до 25.10 неусвоенная доля уходила в него; теперь только выплачивается, новые не копятся)
 */
public record PillState(Map<String, Integer> taken, boolean poisonResistant, List<PillKind> pending,
                        long first, long deadline, String streak, int streakCount, long streakAt, Reserve reserve,
                        Map<String, Tolerance> tolerances) {

    /** Save schema marker. Future fields must be optional with defaults (ADR 0084). */
    public static final int VERSION = 1;

    public int version() { return VERSION; }

    /** Optional per-kind history; the old streak fields are migrated on read. */
    public record Tolerance(int steps, long at) {
        public static final Codec<Tolerance> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("steps").forGetter(Tolerance::steps),
                Codec.LONG.fieldOf("at").forGetter(Tolerance::at)
        ).apply(i, Tolerance::new));

        public int remaining(long now) {
            long recovered = Math.max(0L, now - at) / PillRules.TOLERANCE_RECOVERY_TICKS;
            return (int) Math.max(0L, steps - recovered);
        }
    }

    /** Compatibility constructor for old callers and saves. */
    public PillState(Map<String, Integer> taken, boolean poisonResistant, List<PillKind> pending,
                     long first, long deadline, String streak, int streakCount, long streakAt, Reserve reserve) {
        this(taken, poisonResistant, pending, first, deadline, streak, streakCount, streakAt, reserve,
                streak.isEmpty() || streakCount <= 0 ? Map.of() : Map.of(streak, new Tolerance(streakCount, streakAt)));
    }

    /**
     * Резерв: то, что осталось усвоить, в абсолютных величинах на момент приёма (ци запаса, прирост
     * ёмкости, каналов, чистоты) и сколько тиков игры осталось. Ничего не сгорает.
     */
    public record Reserve(double pool, double capacity, double meridians, double purity, int ticks) {

        public static final Reserve NONE = new Reserve(0.0D, 0.0D, 0.0D, 0.0D, 0);

        public static final Codec<Reserve> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("pool", 0.0D).forGetter(Reserve::pool),
                Codec.DOUBLE.optionalFieldOf("capacity", 0.0D).forGetter(Reserve::capacity),
                Codec.DOUBLE.optionalFieldOf("meridians", 0.0D).forGetter(Reserve::meridians),
                Codec.DOUBLE.optionalFieldOf("purity", 0.0D).forGetter(Reserve::purity),
                Codec.INT.optionalFieldOf("ticks", 0).forGetter(Reserve::ticks)
        ).apply(i, Reserve::new));

        /** Есть ли что усваивать. */
        public boolean active() {
            return ticks > 0 && (pool > 1.0E-9D || capacity > 1.0E-9D || meridians > 1.0E-9D || purity > 1.0E-9D);
        }

        /** Добавка к резерву: срок усвоения начинается заново, суммы складываются. */
        public Reserve plus(double addPool, double addCapacity, double addMeridians, double addPurity) {
            return new Reserve(pool + addPool, capacity + addCapacity, meridians + addMeridians, purity + addPurity,
                    PillRules.RESERVE_TICKS);
        }

        /** Часть резерва, которую пора выплатить за {@code step} тиков игры. */
        public double fraction(int step) {
            return ticks <= step ? 1.0D : step / (double) ticks;
        }

        /** Остаток после выплаты доли {@code f}. */
        public Reserve after(double f, int step) {
            int left = Math.max(0, ticks - step);
            if (f >= 1.0D || left == 0) {
                return NONE;
            }
            return new Reserve(pool * (1.0D - f), capacity * (1.0D - f), meridians * (1.0D - f),
                    purity * (1.0D - f), left);
        }
    }

    public static final PillState NONE = new PillState(Map.of(), false, List.of(), 0L, 0L, "", 0, 0L, Reserve.NONE);

    private static final Codec<List<PillKind>> PENDING_CODEC = Codec.STRING.listOf().xmap(
            names -> names.stream().map(name -> io.github.verycooltimo.murim.compat.SavedEnums
                    .read(PillKind.class, name)).flatMap(java.util.Optional::stream).toList(),
            kinds -> kinds.stream().map(PillKind::name).toList());

    public static final Codec<PillState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("taken", Map.of()).forGetter(PillState::taken),
            Codec.BOOL.optionalFieldOf("poison_resistant", false).forGetter(PillState::poisonResistant),
            PENDING_CODEC.optionalFieldOf("pending", List.of()).forGetter(PillState::pending),
            Codec.LONG.optionalFieldOf("first", 0L).forGetter(PillState::first),
            Codec.LONG.optionalFieldOf("deadline", 0L).forGetter(PillState::deadline),
            // Привыкание (25.10): старые сохранения читаются без него; поле «opened» узлов тела больше не читается.
            Codec.STRING.optionalFieldOf("streak", "").forGetter(PillState::streak),
            Codec.INT.optionalFieldOf("streak_count", 0).forGetter(PillState::streakCount),
            Codec.LONG.optionalFieldOf("streak_at", 0L).forGetter(PillState::streakAt),
            Reserve.CODEC.optionalFieldOf("reserve", Reserve.NONE).forGetter(PillState::reserve),
            Codec.unboundedMap(Codec.STRING, Tolerance.CODEC).optionalFieldOf("tolerances", Map.of()).forGetter(PillState::tolerances),
            // Always write the marker; an omitted legacy marker still defaults to the current schema.
            Codec.INT.optionalFieldOf("version").xmap(v -> v.orElse(VERSION), java.util.Optional::of).forGetter(PillState::version)
    ).apply(i, (taken, poisonResistant, pending, first, deadline, streak, streakCount, streakAt, reserve, tolerances, version) ->
            new PillState(taken, poisonResistant, pending, first, deadline, streak, streakCount, streakAt, reserve, tolerances)));

    public PillState {
        taken = Map.copyOf(taken);
        pending = List.copyOf(pending);
        if (tolerances.isEmpty() && !streak.isEmpty() && streakCount > 0) {
            tolerances = Map.of(streak, new Tolerance(streakCount, streakAt));
        }
        tolerances = Map.copyOf(tolerances);
    }

    public int taken(PillKind kind) {
        return taken.getOrDefault(kind.name(), 0);
    }

    /** Окно открыто прямо сейчас. */
    public boolean windowOpen(long now) {
        return !pending.isEmpty() && now < deadline;
    }

    public PillState withEaten(PillKind kind, long now) {
        boolean firstPill = pending.isEmpty();
        List<PillKind> next = new ArrayList<>(pending);
        next.add(kind);
        long start = firstPill ? now : first;
        return new PillState(taken, poisonResistant, next, start,
                PillRules.deadline(start, deadline, now, firstPill), streak, streakCount, streakAt, reserve, tolerances);
    }

    /** Окно закрыто (сел или истекло): съеденное засчитано в счётчик. */
    public PillState closed() {
        Map<String, Integer> next = new HashMap<>(taken);
        for (PillKind k : pending) {
            next.merge(k.name(), 1, Integer::sum);
        }
        return new PillState(next, poisonResistant, List.of(), 0L, 0L, streak, streakCount, streakAt, reserve, tolerances);
    }

    public PillState withPoisonResistant() {
        return new PillState(taken, true, pending, first, deadline, streak, streakCount, streakAt, reserve, tolerances);
    }

    public PillState withReserve(Reserve value) {
        return new PillState(taken, poisonResistant, pending, first, deadline, streak, streakCount, streakAt, value, tolerances);
    }

    /**
     * How many times in a row {@code kind} was eaten alone and still counts (Myst ch. 148: «after consuming one pill, the effects
     * drastically decrease from the second pill onward»). Each kind recovers one step every 6000 ticks; combinations keep their existing reset.
     */
    public int streakOf(PillKind kind, long now) {
        Tolerance t = tolerances.get(kind.name());
        return t == null ? 0 : t.remaining(now);
    }

    /** The streak after an intake: one pill alone continues or starts a streak, a combination (several pills together) ends it. */
    public PillState afterIntake(List<PillKind> kinds, long now) {
        if (kinds.size() != 1) {
            return new PillState(taken, poisonResistant, pending, first, deadline, "", 0, now, reserve);
        }
        PillKind kind = kinds.get(0);
        Map<String, Tolerance> history = new HashMap<>(tolerances);
        int steps = streakOf(kind, now) + 1;
        history.put(kind.name(), new Tolerance(steps, now));
        return new PillState(taken, poisonResistant, pending, first, deadline, kind.name(), steps, now, reserve, history);
    }
}
