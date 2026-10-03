package io.github.verycooltimo.murim.cultivation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Мини-игра поглощения пилюли «Направить поток» (docs/design/19b §2).
 *
 * <p>Сгусток ци пилюли сам бежит по жилам. Перед каждой развилкой он показывает норов —
 * спокойный или бурный, — и игрок мышью выбирает ветку: короткую (прямо в даньтянь) или
 * обходную (петля через руку/колено). Канон: у ци пилюли «своя воля» (гл. 175, 248) —
 * не выбрал, и сгусток идёт сам, наугад.
 *
 * <p>Это не удержание в зоне, как кольцо даньтяня, а чтение и решение о риске: бурный поток
 * можно пустить коротко ради 130 % порции ценой удара по напряжению.
 *
 * <p>Без мира и сети: считается на сервере, проверяется юнит-тестами. Изменяемый объект —
 * живёт только в идущей сессии и не сохраняется.
 */
public final class AbsorbGame {

    public enum Phase { APPROACH, BRANCH, SETTLE, DONE }

    public enum Result { PLAYING, FINISHED, BACKLASH }

    /** Исход развилки — для вспышек на теле. */
    public enum Outcome { NONE, CALM_SHORT, CALM_LONG, WILD_LONG, WILD_SHORT }

    /** Подход к развилке; последние {@link #READ_TICKS} сгусток показывает норов. */
    public static final int APPROACH_TICKS = 40;
    public static final int READ_TICKS = 20;
    /** Слеза «переворачивается» в бурную за 0,4 с до развилки (её ореол предупреждал заранее). */
    public static final int FLIP_TICKS = 8;
    public static final int SHORT_TICKS = 14;
    public static final int LONG_TICKS = 30;
    /** Осевший сгусток вспыхивает у пупка, потом идёт следующий. */
    public static final int SETTLE_TICKS = 12;

    /** Порции за развилку (доля порции сгустка): §2 «Исходы развилки». */
    public static final double CALM_SHORT = 1.0D;
    public static final double CALM_LONG = 0.6D;
    public static final double WILD_LONG = 0.6D;
    public static final double WILD_SHORT = 1.3D;

    /** Удар бурного сгустка коротким путём и остывание обходом. */
    public static final double WILD_SHORT_STRAIN = 0.35D;
    public static final double WILD_LONG_RELIEF = 0.15D;
    public static final double CALM_RELIEF = 0.05D;

    /** Метод с природой «яд» усваивает ядовитые пилюли легче: удар слабее, бурных меньше. */
    public static final double POISON_NATURE_STRAIN = 0.6D;
    public static final double POISON_NATURE_WILD = 0.5D;

    /** Одна развилка: норов, переворот слезы и сторона экрана короткой ветки. */
    public record Fork(boolean wild, boolean flips, int shortSide) {
    }

    private final List<PillKind> clots;
    private final List<List<Fork>> plan;
    private final double shield;
    private final boolean poisonNature;
    private final double[] settled;

    private int clot;
    private int fork;
    private Phase phase = Phase.APPROACH;
    private int phaseTicks;
    /** Выбор игрока: −1 — левая ветка экрана, +1 — правая, 0 — нет выбора. */
    private int choice;
    /** Ветка, по которой идёт сгусток в фазе BRANCH: true — короткая. */
    private boolean tookShort;
    private double strain;
    private Outcome lastOutcome = Outcome.NONE;
    private int ticks;
    private Result result = Result.PLAYING;

    private AbsorbGame(List<PillKind> clots, List<List<Fork>> plan, double shield, boolean poisonNature) {
        this.clots = List.copyOf(clots);
        this.plan = plan;
        this.shield = shield;
        this.poisonNature = poisonNature;
        this.settled = new double[clots.size()];
    }

    /**
     * Новая игра: сгустки идут друг за другом (сначала щитовые, слеза — последней,
     * как у Чхон Мёна: «ци двух таблеток окружил даньтянь», гл. 228).
     */
    public static AbsorbGame start(List<PillKind> pills, boolean poisonNature, DoubleSupplier random) {
        List<PillKind> order = new ArrayList<>(pills);
        Collections.sort(order);
        List<List<Fork>> plan = new ArrayList<>();
        for (PillKind kind : order) {
            List<Fork> forks = new ArrayList<>();
            double wildChance = kind.wild() * (poisonNature && kind.poisonous() ? POISON_NATURE_WILD : 1.0D);
            for (int i = 0; i < kind.forks(); i++) {
                boolean wild = random.getAsDouble() < wildChance;
                // Слеза: спокойный сгусток иногда вспыхивает бурным в последний миг —
                // её ореол мерцает с самого начала, так что это подтверждение, а не подлость.
                boolean flips = !wild && kind == PillKind.BEAUTY_TEAR && random.getAsDouble() < 0.5D;
                int side = random.getAsDouble() < 0.5D ? -1 : 1;
                forks.add(new Fork(wild, flips, side));
            }
            plan.add(List.copyOf(forks));
        }
        return new AbsorbGame(order, plan, PillRules.shield(order), poisonNature);
    }

    /** Выбор игрока: −1, 0 или +1 (сторона экрана). Действует до входа сгустка в развилку. */
    public void choose(int side) {
        if (phase == Phase.APPROACH) {
            choice = Integer.signum(side);
        }
    }

    /** Один тик. */
    public void tick(DoubleSupplier random) {
        if (result != Result.PLAYING) {
            return;
        }
        ticks++;
        phaseTicks++;
        lastOutcome = Outcome.NONE;
        switch (phase) {
            case APPROACH -> {
                if (phaseTicks >= APPROACH_TICKS) {
                    resolveFork(random);
                }
            }
            case BRANCH -> {
                if (phaseTicks >= (tookShort ? SHORT_TICKS : LONG_TICKS)) {
                    phaseTicks = 0;
                    if (fork + 1 < plan.get(clot).size()) {
                        fork++;
                        phase = Phase.APPROACH;
                        choice = 0;
                    } else {
                        phase = Phase.SETTLE;
                    }
                }
            }
            case SETTLE -> {
                if (phaseTicks >= SETTLE_TICKS) {
                    phaseTicks = 0;
                    if (clot + 1 < clots.size()) {
                        clot++;
                        fork = 0;
                        choice = 0;
                        phase = Phase.APPROACH;
                    } else {
                        phase = Phase.DONE;
                        result = Result.FINISHED;
                    }
                }
            }
            case DONE -> {
            }
        }
    }

    private void resolveFork(DoubleSupplier random) {
        Fork f = currentFork();
        boolean wild = f.wild() || f.flips();
        int side = choice != 0 ? choice : (random.getAsDouble() < 0.5D ? -1 : 1);
        tookShort = side == f.shortSide();
        PillKind kind = clots.get(clot);
        double portion;
        if (wild && tookShort) {
            portion = WILD_SHORT;
            double hit = WILD_SHORT_STRAIN;
            if (kind == PillKind.BEAUTY_TEAR) {
                hit *= shield;
            }
            if (poisonNature && kind.poisonous()) {
                hit *= POISON_NATURE_STRAIN;
            }
            strain += hit;
            lastOutcome = Outcome.WILD_SHORT;
        } else if (wild) {
            portion = WILD_LONG;
            strain -= WILD_LONG_RELIEF;
            lastOutcome = Outcome.WILD_LONG;
        } else if (tookShort) {
            portion = CALM_SHORT;
            strain -= CALM_RELIEF;
            lastOutcome = Outcome.CALM_SHORT;
        } else {
            portion = CALM_LONG;
            lastOutcome = Outcome.CALM_LONG;
        }
        strain = Math.max(0.0D, strain);
        settled[clot] += portion / kind.forks();
        phase = Phase.BRANCH;
        phaseTicks = 0;
        if (strain >= 1.0D) {
            strain = 1.0D;
            result = Result.BACKLASH;
            phase = Phase.DONE;
        }
    }

    /** Развилка, к которой идёт или по которой идёт сгусток. */
    public Fork currentFork() {
        return plan.get(clot).get(fork);
    }

    /** Показывает ли сгусток норов прямо сейчас и какой он (с учётом переворота слезы). */
    public boolean showsTemper() {
        return phase == Phase.APPROACH && phaseTicks >= APPROACH_TICKS - READ_TICKS;
    }

    public boolean wildNow() {
        Fork f = currentFork();
        return f.wild() || (f.flips() && phase == Phase.APPROACH && phaseTicks >= APPROACH_TICKS - FLIP_TICKS)
                || (f.flips() && phase != Phase.APPROACH);
    }

    public List<PillKind> clots() {
        return clots;
    }

    public int clot() {
        return clot;
    }

    public int fork() {
        return fork;
    }

    public Phase phase() {
        return phase;
    }

    public int phaseTicks() {
        return phaseTicks;
    }

    public int choice() {
        return choice;
    }

    public boolean tookShort() {
        return tookShort;
    }

    public double strain() {
        return strain;
    }

    public Outcome lastOutcome() {
        return lastOutcome;
    }

    public int ticks() {
        return ticks;
    }

    public Result result() {
        return result;
    }

    /** Осевшая доля сгустка: 0 — ничего, 1 — всё, до 1,3 — с рискованными короткими путями. */
    public double settled(int index) {
        return settled[index];
    }

    /** Сколько тиков займёт вся игра без ошибок по времени (для тестов и подсказки). */
    public static int expectedTicks(List<PillKind> pills) {
        int total = 0;
        for (PillKind k : pills) {
            total += k.forks() * (APPROACH_TICKS + (SHORT_TICKS + LONG_TICKS) / 2) + SETTLE_TICKS;
        }
        return total;
    }
}
