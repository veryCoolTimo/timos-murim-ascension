package io.github.verycooltimo.murim.sect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Реплики над головой (автор 05.10: «реакции и фразы — да»): короткие строки, которые люди горы бросают за делом, без
 * разговора с игроком. Здесь — только какие строки бывают и какая группа подходит делу; кто и когда говорит — в
 * {@link SectChatter}, показ — клиентский пузырь над головой.
 *
 * <p>Ключи {@code murim.bubble.<группа>.<n>}, ru_ru и en_us, не длиннее {@link #MAX_CHARS} символов (тест
 * {@code SectBubblesTest}). Черта ученика — та же, что в разговоре ({@link SectTalk#trait}): у неё по две свои строки за
 * столом и своя реакция на технику игрока.
 */
public final class SectBubbles {

    /** Длина строки без аргументов. */
    public static final int MAX_CHARS = 40;
    /** Строк своей черты за столом и в кругу. */
    public static final int TRAIT_LINES = 2;

    /** Группа строк: сколько их. */
    public enum Group {
        /** За столом. */
        MEAL(6),
        /** Вечерний круг. */
        EVENING(6),
        /** Ответ соседу. */
        REPLY(8),
        /** Наставник вдоль рядов строя. */
        MENTOR(6),
        /** Наставник в начале строя (колокол). */
        MENTOR_START(2),
        /** Ночная стража бормочет. */
        GUARD_NIGHT(5),
        /** Пост днём. */
        GUARD_DAY(2),
        SWEEP(2),
        COOK(2),
        CARRY(2),
        SERVE(2),
        TEND(2),
        /** Управляющий с книгой. */
        STEWARD(2),
        /** Поединок учеников. */
        SPAR(3),
        /** Столбы и формы в одиночку. */
        DRILL(2),
        /** Под крышей во время дождя. */
        RAIN(5),
        /** Игрок применил технику рядом. */
        WATCH(3),
        /** Поклон победителю смотра. */
        BOW_WINNER(2),
        /** Поклон старшему по положению. */
        BOW_SENIOR(2),
        /** Лекарь подошёл к раненому игроку. */
        HEAL_START(2),
        /** Лекарь закончил. */
        HEAL_DONE(2);

        private final int count;

        Group(int count) {
            this.count = count;
        }

        public int count() {
            return count;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Строка {@code i} (1…{@link #count}). */
        public String key(int i) {
            return "murim.bubble." + id() + "." + i;
        }

        /** Случайная строка по броску. */
        public String pick(int roll) {
            return key(1 + Math.floorMod(roll, count));
        }
    }

    /** Слух за столом: кто ночью на посту (два имени). */
    public static final String RUMOUR_GUARDS = "murim.bubble.rumour.guards";
    /** Слух за столом: кто выиграл смотр (имя). */
    public static final String RUMOUR_CHAMPION = "murim.bubble.rumour.champion";

    private SectBubbles() {
    }

    /** Строка черты за столом (1…{@link #TRAIT_LINES}). */
    public static String traitKey(SectTalk.Trait t, int i) {
        return "murim.bubble.trait." + t.id() + "." + i;
    }

    /** Реакция черты на технику игрока. */
    public static String watchKey(SectTalk.Trait t) {
        return "murim.bubble.watch." + t.id();
    }

    /**
     * Группа по делу распорядка; null — за этим делом молчат (строй — бьёт формы, сон, совет, медитация).
     *
     * @param lay       мирянин (слуга, управляющий)
     * @param mentor    наставник
     * @param night     ночь
     * @param sinceStart тиков с начала части суток
     */
    public static Group forKind(SectSchedule.Kind kind, boolean lay, boolean mentor, boolean night, int sinceStart) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case EAT -> Group.MEAL;
            case REST -> Group.EVENING;
            case INSPECT -> mentor && sinceStart < 400 ? Group.MENTOR_START : Group.MENTOR;
            case GUARD -> night ? Group.GUARD_NIGHT : Group.GUARD_DAY;
            case SWEEP, CHORE -> Group.SWEEP;
            case COOK -> Group.COOK;
            case CARRY -> Group.CARRY;
            case SERVE -> Group.SERVE;
            case TEND -> Group.TEND;
            case WORK -> lay ? Group.STEWARD : null;
            case SPAR -> Group.SPAR;
            case POLES, DRILL -> Group.DRILL;
            case SHELTER -> Group.RAIN;
            default -> null;
        };
    }

    /** Дело, за которым двое переговариваются (сосед отвечает). */
    public static boolean exchange(SectSchedule.Kind kind) {
        return kind == SectSchedule.Kind.EAT || kind == SectSchedule.Kind.REST || kind == SectSchedule.Kind.SHELTER;
    }

    /** Все ключи (для проверки lang-файлов). */
    public static List<String> allKeys() {
        List<String> out = new ArrayList<>();
        for (Group g : Group.values()) {
            for (int i = 1; i <= g.count(); i++) {
                out.add(g.key(i));
            }
        }
        for (SectTalk.Trait t : SectTalk.Trait.values()) {
            for (int i = 1; i <= TRAIT_LINES; i++) {
                out.add(traitKey(t, i));
            }
            out.add(watchKey(t));
        }
        out.add(RUMOUR_GUARDS);
        out.add(RUMOUR_CHAMPION);
        return out;
    }
}
