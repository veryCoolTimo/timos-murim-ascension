package io.github.verycooltimo.murim.sect;

import java.util.Locale;
import java.util.Optional;

/**
 * Положение игрока в секте — лестница доступа (docs/design/23-mount-hua-sect.md §2.3, «С3, часть 2»).
 * Поколение у игрока всегда третье (Чхон), растёт положение: кто с ним говорит, куда пускают охраняемые
 * залы. Чистая функция от {@link SectState} и ранга: её читают диалоги (условия {@code min_standing},
 * {@code below_standing}), охрана и юнит-тесты.
 *
 * <ul>
 *   <li>{@link #OUTSIDER} — чужак: дальше ворот секты не пускают;</li>
 *   <li>{@link #NOVICE} — новичок третьего поколения: двор, лагерь, площадки; к главе и старейшинам — только по зову;</li>
 *   <li>{@link #DISCIPLE} — ученик третьего класса: первый урок наставника сдан (или ранг и заслуги);
 *       Главный зал, зал предков, Финансовый зал днём; старейшины говорят сами;</li>
 *   <li>{@link #GRADUATE} — выпускник Белого Цветка, на правах второго поколения: урок «три чистых удара»;
 *       Зал писаний и дома старейшин днём, глава принимает без зова;</li>
 *   <li>{@link #TRUSTED} — доверенный, на правах старших: выпускник + одобрение главы + заслуги;
 *       ночью пускают везде, кроме тайника и пещеры покаяния без причины.</li>
 * </ul>
 */
public enum SectStanding {
    OUTSIDER, NOVICE, DISCIPLE, GRADUATE, TRUSTED;

    /** Первый урок наставника сдан (mentor.json). */
    public static final String LESSON_ONE = "lesson.six.done";
    /** Урок «три чистых удара» сдан (mentor.json). */
    public static final String LESSON_TWO = "lesson.spar.done";
    /** Глава одобрил: доверие секты (leader.json). */
    public static final String APPROVAL = "approval.leader";

    /** Ученик третьего класса без урока: ранг и столько заслуг (трудом, а не уроком). */
    public static final int DISCIPLE_RANK = 1;
    public static final int DISCIPLE_CONTRIBUTION = 15;
    /** Доверенный: столько заслуг за всё время (и одобрение главы). */
    public static final int TRUSTED_CONTRIBUTION = 30;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Ключ названия: {@code murim.sect.standing.<id>}. */
    public String nameKey() {
        return "murim.sect.standing." + id();
    }

    public boolean atLeast(SectStanding other) {
        return ordinal() >= other.ordinal();
    }

    /** Положение по состоянию секты и рангу культивации. */
    public static SectStanding of(SectState s, int rank) {
        if (!s.member()) {
            return OUTSIDER;
        }
        boolean graduate = s.has(LESSON_TWO);
        if (graduate && s.has(APPROVAL) && s.contribution() >= TRUSTED_CONTRIBUTION) {
            return TRUSTED;
        }
        if (graduate) {
            return GRADUATE;
        }
        if (s.has(LESSON_ONE) || rank >= DISCIPLE_RANK && s.contribution() >= DISCIPLE_CONTRIBUTION) {
            return DISCIPLE;
        }
        return NOVICE;
    }

    public static Optional<SectStanding> parse(String id) {
        for (SectStanding s : values()) {
            if (s.id().equals(id)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
