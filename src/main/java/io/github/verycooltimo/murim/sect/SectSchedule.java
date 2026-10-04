package io.github.verycooltimo.murim.sect;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Распорядок дня секты (docs/design/23-mount-hua-sect.md §4.2): «время → место → действие». Чистая
 * функция от игрового времени и человека, без мира: её проверяют юнит-тесты, а исполняет
 * {@code ScheduleGoal} у NPC.
 *
 * <p>Сутки — 24000 тиков (20 минут). 23000–1000 — рассвет, строй на площади: Шесть Равновесий
 * синхронно, наставник ходит вдоль рядов; 1000–2000 — завтрак; 2000–9000 — занятия (спарринги парами,
 * столбы, одиночные формы, хозяйство, медитация); 9000–11000 — ужин; 11000–13000 — вечер, медитация и
 * отдых; 13000–23000 — сон.
 *
 * <p>Места — смещения {@code (du, dv)} от центра площадки в локальной рамке горы ({@code u} — восток,
 * {@code v} — юг), направление взгляда — тоже локальное. В мир переводит {@link SectLayout}.
 */
public final class SectSchedule {

    /** Длина суток. */
    public static final int DAY = 24000;

    /** Такт строя: каждая форма Шести Равновесий — раз в столько тиков, у всех одновременно. */
    public static final int BEAT = 30;
    /** Удар формы в такте: замах длится столько тиков от начала такта (поза замаха — та же, что в бою). */
    public static final int BEAT_STRIKE = 8;

    /** Колонок в ряду строя: пятеро учеников и одно место с краю свободно — для игрока. */
    public static final int COLUMNS = 6;
    public static final int FILLED = 5;
    /** Шаг строя, блоков. */
    public static final double SPACING = 3.0D;
    /** Первый ряд (ближе к помосту наставника) — смещение по v от центра площади. */
    public static final double FRONT_ROW = 7.0D;

    /** Части суток. */
    public enum Period {
        FORMATION(23000), BREAKFAST(1000), TRAINING(2000), DINNER(9000), EVENING(11000), NIGHT(13000);

        private final int start;

        Period(int start) {
            this.start = start;
        }

        /** Начало в тиках суток. */
        public int start() {
            return start;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Трапеза. */
        public boolean meal() {
            return this == BREAKFAST || this == DINNER;
        }

        public static Optional<Period> of(String id) {
            for (Period p : values()) {
                if (p.id().equals(id)) {
                    return Optional.of(p);
                }
            }
            return Optional.empty();
        }
    }

    /** Что делает человек. */
    public enum Kind {
        /** Строй: стоит на своём месте и повторяет формы в такт. */
        FORM_ROW,
        /** Наставник ходит вдоль рядов. */
        INSPECT,
        /** Стоит и смотрит в сторону. */
        WATCH,
        /** Старейшина у своего зала: стоит, переходит с места на место. */
        WORK,
        /** Стоит у ворот лицом наружу. */
        GUARD,
        /** Глава выходит к воротам навстречу чужаку (принимает без экзамена). */
        GREET,
        /** Спарринг в паре на песчаной площадке. */
        SPAR,
        /** Столбы цветущей сливы: прыжки с точки на точку и формы. */
        POLES,
        /** Формы в одиночку за строем. */
        DRILL,
        /** Медитация в лотосе. */
        MEDITATE,
        /** Хозяйство: обход площадок. */
        CHORE,
        /** Трапеза: сидит за столом. */
        EAT,
        /** Вечерний отдых кружком в лагере. */
        REST,
        /** Сон: кровать в общежитии, если есть, иначе сидя в лагере. */
        SLEEP,
        /**
         * Носит груз между двумя точками: берёт в {@code to} (ступени тропы, колодец), несёт в {@code zone}
         * (кладовая, кухня) с грузом в руках, обратно идёт пустой.
         */
        CARRY,
        /** Метёт двор: медленный обход площадки, остановки (поза sweep — у агента поз). */
        SWEEP,
        /** Повар у очага: стоит у кухни, иногда отходит и возвращается. */
        COOK,
        /** Раздаёт еду: ходит между столами столовой. */
        SERVE,
        /** Травник на грядках у павильона алхимии: обход, остановки на корточках (поза tend). */
        TEND
    }

    /**
     * Задание: площадка, смещение от её центра и куда смотреть.
     *
     * @param partner ключ партнёра (спарринг) или пусто
     */
    public record Task(Kind kind, String zone, double du, double dv, double faceU, double faceV, String partner,
                       String toZone, double toU, double toV) {
        Task(Kind kind, String zone, double du, double dv, double faceU, double faceV) {
            this(kind, zone, du, dv, faceU, faceV, "");
        }

        Task(Kind kind, String zone, double du, double dv, double faceU, double faceV, String partner) {
            this(kind, zone, du, dv, faceU, faceV, partner, "", 0.0D, 0.0D);
        }

        /** Второй конец пути ({@link Kind#CARRY}): площадка и смещение; пусто — пути нет. */
        public boolean route() {
            return !toZone.isEmpty();
        }
    }

    /** Ношение груза: взять в {@code (toZone, toU, toV)}, отнести в {@code (zone, du, dv)}, вернуться. */
    static Task carry(String zone, double du, double dv, String toZone, double toU, double toV) {
        return new Task(Kind.CARRY, zone, du, dv, 0.0D, 1.0D, "", toZone, toU, toV);
    }

    private SectSchedule() {
    }

    /** Часть суток по времени мира ({@code Level#getDayTime}). */
    public static Period at(long dayTime) {
        int t = (int) Math.floorMod(dayTime, (long) DAY);
        if (t >= 23000 || t < 1000) {
            return Period.FORMATION;
        }
        if (t < 2000) {
            return Period.BREAKFAST;
        }
        if (t < 9000) {
            return Period.TRAINING;
        }
        if (t < 11000) {
            return Period.DINNER;
        }
        if (t < 13000) {
            return Period.EVENING;
        }
        return Period.NIGHT;
    }

    /** Сколько тиков прошло с начала текущей части суток. */
    public static int sincePeriodStart(long dayTime) {
        int t = (int) Math.floorMod(dayTime, (long) DAY);
        int start = at(dayTime).start();
        return Math.floorMod(t - start, DAY);
    }

    /** День секты: строй в 23000 открывает уже следующий день. */
    public static long day(long dayTime) {
        return Math.floorDiv(dayTime + 1000L, (long) DAY);
    }

    /** Номер формы в такте строя (0..5) — одинаковый у всех. */
    public static int beatForm(long gameTime) {
        return (int) Math.floorMod(gameTime / BEAT, 6L);
    }

    /** Такт строя начинается в этот тик. */
    public static boolean beatStarts(long gameTime) {
        return Math.floorMod(gameTime, (long) BEAT) == 0;
    }

    /** Удар формы игрока попал в такт: близко к удару строя. */
    public static boolean onBeat(long gameTime) {
        int phase = (int) Math.floorMod(gameTime, (long) BEAT);
        return phase >= BEAT_STRIKE - 6 && phase <= BEAT_STRIKE + 8;
    }

    /** Номер такта (чтобы считать не больше одной формы за такт). */
    public static long beat(long gameTime) {
        return Math.floorDiv(gameTime, (long) BEAT);
    }

    // ------------------------------------------------------------------ места строя

    /**
     * Место в строю: ряд 0 — второе поколение, ряды 1–3 — третье; пять учеников в ряду, шестое место
     * (восточный край) свободно. Строй смотрит на юг, к помосту наставника.
     *
     * @return {du, dv} от центра площади, или null — человек в строю не стоит
     */
    public static double[] formationSlot(SectRoster m) {
        int row;
        int col;
        if (m.generation() == 2) {
            List<SectRoster> gen = SectRoster.generation(2);
            row = 0;
            col = gen.indexOf(m);
        } else if (m.generation() == 3 && m.role() != SectRole.GATEKEEPER) {
            int i = SectRoster.generation(3).indexOf(m);
            row = 1 + i / FILLED;
            col = i % FILLED;
        } else {
            return null;
        }
        if (col < 0) {
            return null;
        }
        return slot(row, col);
    }

    /** Смещение места (ряд, колонка) от центра площади. */
    public static double[] slot(int row, int col) {
        return new double[] {(col - (COLUMNS - 1) / 2.0D) * SPACING, FRONT_ROW - row * SPACING};
    }

    /** Сколько рядов в строю. */
    public static int rows() {
        return 1 + (SectRoster.generation(3).size() + FILLED - 1) / FILLED;
    }

    // ------------------------------------------------------------------ задания

    /**
     * Что делает человек в эту часть суток. {@code day} — день секты ({@link #day}): занятия днём
     * сменяются по дням, чтобы ученики не стояли на одном месте всю жизнь.
     */
    public static Task task(SectRoster m, Period p, long day) {
        return switch (m.role()) {
            case GATEKEEPER -> new Task(Kind.GUARD, "gate", 0.0D, -2.0D, 0.0D, -1.0D);
            case GUARD -> post(m);
            case STEWARD, COOK, PORTER, GARDENER, SWEEPER, WATER_CARRIER -> SectStaff.task(m, p, day);
            case LEADER -> leaderTask(m, p);
            case MENTOR -> mentor(m, p);
            case ELDER -> elder(m, p);
            default -> m.generation() == 2 ? second(m, p, day) : third(m, p, day);
        };
    }

    private static Task leaderTask(SectRoster m, Period p) {
        return switch (p) {
            // Рассвет: смотрит на строй с помоста рядом с наставником.
            case FORMATION -> new Task(Kind.WATCH, "mentor", 2.0D, 0.0D, 0.0D, -1.0D);
            case EVENING -> new Task(Kind.WORK, "ancestors", 0.0D, -4.0D, 0.0D, 1.0D);
            case NIGHT -> sleep(m);
            default -> new Task(Kind.WORK, "main_hall", 0.0D, -8.0D, 0.0D, -1.0D);
        };
    }

    private static Task mentor(SectRoster m, Period p) {
        return switch (p) {
            case FORMATION -> new Task(Kind.INSPECT, "training", 0.0D, FRONT_ROW + 3.0D, 0.0D, -1.0D);
            case BREAKFAST, DINNER -> eat(m);
            // Днём — на помосте, лицом к песчаной площадке спаррингов.
            case TRAINING -> new Task(Kind.WATCH, "mentor", -1.0D, 1.0D, 0.0D, 1.0D);
            case EVENING -> new Task(Kind.WATCH, "training", 0.0D, FRONT_ROW + 3.0D, 0.0D, -1.0D);
            case NIGHT -> sleep(m);
        };
    }

    private static Task elder(SectRoster m, Period p) {
        boolean am = "un_am".equals(m.key());
        return switch (p) {
            // Хён Сан (Зал боевых искусств) смотрит строй с края площади; остальные — у себя.
            case FORMATION -> "hyun_sang".equals(m.key())
                    ? new Task(Kind.WATCH, "training", 16.0D, 4.0D, -1.0D, 0.0D)
                    : home(m);
            case TRAINING -> am ? new Task(Kind.GUARD, "sect_gate", -3.0D, 0.0D, 0.0D, -1.0D) : home(m);
            case EVENING -> new Task(Kind.MEDITATE, m.home(), 2.0D, 2.0D, 0.0D, -1.0D);
            case NIGHT -> sleep(m);
            default -> home(m);
        };
    }

    /**
     * Пост охраны — у входа в свой зал, днём и ночью (ночная стража — те же люди, с фонарём). Лицом туда,
     * откуда приходят.
     */
    static Task post(SectRoster m) {
        return switch (m.key()) {
            // Ворота секты: изнутри, рядом с Ун Амом; чужак дальше ворот не идёт.
            case "baek_mu" -> new Task(Kind.GUARD, "sect_gate", 5.0D, 1.0D, 0.0D, -1.0D);
            // Главный зал: у входа со стороны площади.
            case "baek_ryeong" -> new Task(Kind.GUARD, "main_hall", -4.0D, -10.0D, 0.0D, -1.0D);
            // Тайник: вход с западного бока главного зала.
            case "baek_gi" -> new Task(Kind.GUARD, "main_hall", -25.0D, 4.0D, -1.0D, 0.0D);
            // Верхний уступ: зал предков, рядом Зал писаний.
            case "baek_jin" -> new Task(Kind.GUARD, "ancestors", 8.0D, -7.0D, 0.0D, -1.0D);
            // Казна: со стороны площади, у края внутреннего двора (управляющий — снаружи, на западном краю).
            case "baek_won" -> new Task(Kind.GUARD, "treasury", -7.0D, -6.0D, -1.0D, 0.0D);
            // Дома старейшин и пещера покаяния за ними.
            default -> new Task(Kind.GUARD, m.home(), -6.0D, 7.0D, 1.0D, 0.0D);
        };
    }

    private static Task home(SectRoster m) {
        return new Task(Kind.WORK, m.home(), 0.0D, -2.0D, 0.0D, -1.0D);
    }

    private static Task second(SectRoster m, Period p, long day) {
        return switch (p) {
            case FORMATION -> row(m);
            case BREAKFAST, DINNER -> eat(m);
            case TRAINING -> secondTraining(m, day);
            case EVENING -> meditate(m);
            case NIGHT -> sleep(m);
        };
    }

    private static Task third(SectRoster m, Period p, long day) {
        return switch (p) {
            case FORMATION -> row(m);
            case BREAKFAST, DINNER -> eat(m);
            case TRAINING -> thirdTraining(m, day);
            case EVENING -> rest(m);
            case NIGHT -> sleep(m);
        };
    }

    private static Task row(SectRoster m) {
        double[] s = formationSlot(m);
        return new Task(Kind.FORM_ROW, "training", s[0], s[1], 0.0D, 1.0D);
    }

    // ------------------------------------------------------------------ дневные занятия

    /** Рингов на песчаной площадке: 3 × 2, по рингу на пару. */
    public static final double[][] RINGS = {{-9, -5}, {0, -5}, {9, -5}, {-9, 5}, {0, 5}, {9, 5}};
    /** Половина дистанции между бойцами пары в начале боя. */
    public static final double RING_HALF = 2.5D;

    /**
     * Второе поколение днём: старший всегда на площадке поединков (партнёр урока «три чистых удара»);
     * из четверых остальных двое бьются друг с другом, третий — со старшим, четвёртый медитирует.
     */
    static Task secondTraining(SectRoster m, long day) {
        List<SectRoster> gen = SectRoster.generation(2);
        SectRoster senior = gen.get(0);
        List<SectRoster> rest = gen.subList(1, gen.size());
        int n = rest.size();
        if (m.equals(senior)) {
            SectRoster partner = rest.get((int) Math.floorMod(2 - day, (long) n));
            return ring(1, 0, partner.key());
        }
        int k = (int) Math.floorMod(rest.indexOf(m) + day, (long) n);
        return switch (k) {
            case 0 -> ring(0, 0, rest.get((int) Math.floorMod(1 - day, (long) n)).key());
            case 1 -> ring(0, 1, rest.get((int) Math.floorMod(-day, (long) n)).key());
            case 2 -> ring(1, 1, senior.key());
            default -> meditate(m);
        };
    }

    /**
     * Третье поколение днём (15 человек): шестеро — три пары спарринга, четверо — столбы, двое —
     * формы за строем, трое — хозяйство. Состав групп сдвигается каждый день.
     */
    static Task thirdTraining(SectRoster m, long day) {
        List<SectRoster> gen = SectRoster.generation(3);
        int n = gen.size();
        int k = (int) Math.floorMod(gen.indexOf(m) + day * 4L, (long) n);
        if (k < 6) {
            int mate = k ^ 1;
            SectRoster partner = gen.get((int) Math.floorMod(mate - day * 4L, (long) n));
            return ring(2 + k / 2, k & 1, partner.key());
        }
        if (k < 10) {
            double[][] spots = {{-3, -2}, {3, -2}, {-3, 2}, {3, 2}};
            double[] s = spots[k - 6];
            return new Task(Kind.POLES, "poles", s[0], s[1], 0.0D, 1.0D);
        }
        if (k < 12) {
            return new Task(Kind.DRILL, "training", (k - 10.5D) * 8.0D, -9.0D, 0.0D, 1.0D);
        }
        String[] zones = {"training", "treasury", "camp"};
        return new Task(Kind.CHORE, zones[k - 12], 0.0D, 0.0D, 0.0D, 1.0D);
    }

    /** Место бойца в ринге {@code ring}: сторона 0 — запад, 1 — восток; лицом друг к другу. */
    static Task ring(int ring, int side, String partner) {
        double[] r = RINGS[ring];
        double du = r[0] + (side == 0 ? -RING_HALF : RING_HALF);
        return new Task(Kind.SPAR, "sparring", du, r[1], side == 0 ? 1.0D : -1.0D, 0.0D, partner);
    }

    // ------------------------------------------------------------------ трапеза, отдых, медитация

    /** Места за столами: 12 в столовой, остальные — за столом во дворе лагеря рядом. */
    static Task eat(SectRoster m) {
        int seat = seatIndex(m);
        if (seat < 12) {
            int side = seat % 2;
            double du = -5.0D + (seat / 2) * 2.0D;
            double dv = side == 0 ? -1.5D : 1.5D;
            return new Task(Kind.EAT, "dining", du, dv, 0.0D, side == 0 ? 1.0D : -1.0D);
        }
        int s = seat - 12;
        int side = s % 2;
        double dv = -1.5D - (s / 2) * 1.6D;
        return new Task(Kind.EAT, "camp", side == 0 ? 3.5D : 6.5D, dv + 8.0D, side == 0 ? 1.0D : -1.0D, 0.0D);
    }

    /** Порядок за столом: наставник, второе поколение, третье, слуги (во дворе лагеря, с краю). */
    static int seatIndex(SectRoster m) {
        if (m.role() == SectRole.MENTOR) {
            return 0;
        }
        if (m.generation() == 2) {
            return 1 + SectRoster.generation(2).indexOf(m);
        }
        int disciples = 1 + SectRoster.generation(2).size() + SectRoster.generation(3).size();
        if (m.lay()) {
            return disciples + SectStaff.index(m);
        }
        return 1 + SectRoster.generation(2).size() + SectRoster.generation(3).indexOf(m);
    }

    /** Вечер третьего поколения: кружки по трое во дворе лагеря. */
    static Task rest(SectRoster m) {
        int i = SectRoster.generation(3).indexOf(m);
        int group = i / 3;
        int place = i % 3;
        double cv = -16.0D + group * 8.0D;
        double a = place * (Math.PI * 2.0D / 3.0D);
        double du = Math.cos(a) * 2.0D;
        double dv = Math.sin(a) * 2.0D;
        return new Task(Kind.REST, "camp", du, cv + dv, -du, -dv);
    }

    /** Медитация в сливовой роще: два ряда по четыре. */
    static Task meditate(SectRoster m) {
        int i = Math.max(0, SectRoster.generation(2).indexOf(m));
        double du = -8.0D + (i % 4) * 5.0D;
        double dv = i < 4 ? -4.0D : 3.0D;
        return new Task(Kind.MEDITATE, "grove", du, dv, 0.0D, -1.0D);
    }

    /**
     * Ночлег: место на площадке общежития (старейшины — у своих домов). Есть кровати — ляжет в
     * ближайшую свободную; нет — сидит на своём месте (автор строит общежития сам).
     */
    static Task sleep(SectRoster m) {
        if (m.lay()) {
            return SectStaff.sleep(m);
        }
        if (m.generation() <= 1 && m.role() != SectRole.MENTOR) {
            int i = 0;
            for (SectRoster r : SectRoster.ALL) {
                if (r.equals(m)) {
                    break;
                }
                if (r.generation() <= 1 && !r.lay() && r.role() != SectRole.MENTOR) {
                    i++;
                }
            }
            return new Task(Kind.SLEEP, "elders", -8.0D + i * 4.0D, 0.0D, 0.0D, -1.0D);
        }
        if (m.role() == SectRole.MENTOR) {
            return new Task(Kind.SLEEP, "dorm_3rd", 4.0D, 7.0D, 0.0D, -1.0D);
        }
        if (m.generation() == 2) {
            int i = SectRoster.generation(2).indexOf(m);
            return new Task(Kind.SLEEP, "dorm_2nd", -3.0D + (i % 2) * 6.0D, -5.0D + (i / 2) * 4.0D, 0.0D, 1.0D);
        }
        int i = Math.max(0, SectRoster.generation(3).indexOf(m));
        return new Task(Kind.SLEEP, "dorm_3rd", -4.0D + (i % 3) * 4.0D, -6.0D + (i / 3) * 3.0D, 0.0D, 1.0D);
    }
}
