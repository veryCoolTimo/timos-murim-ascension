package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Map;

/**
 * Ранги и прорывы: правила без сервера, чтобы их проверяли тесты.
 *
 * <p>docs/design/19-dantian-qi-meditation.md §3д, §3е, docs/design/07-realms.md §2. Ранг хранится в
 * {@link DantianProfile#rank()} — номер большой ступени по роману (автор 03.10, этап M2):
 * 0 — без ранга, 1 — третий, 2 — второй, 3 — первый, 4 — Пик, 5 — Трансцендентный,
 * 6 — Преображение, 7 — Глубокий, 8 — Жизнь и Смерть, 9 — Природа, 10 — Пустота/Воля.
 * Играбельно (прорывом) — до Пика ({@link #MAX}); выше — данные на будущее, их ставит только
 * команда {@code /murim rank} и аура противника. Та же шкала у ауры ({@code AuraState.rank}).
 *
 * <p>«Стена» — потолок запаса ({@code ёмкость × }{@link MeditationService#POOL_CAP}). Упёрся —
 * расти дальше нельзя, нужен прорыв; прорыв поднимает ёмкость, и до новой стены снова копишь.
 * Так в книгах: количественный рост и качественный скачок через стену.
 */
public final class Realm {

    public static final int NONE = 0;
    public static final int THIRD = 1;
    public static final int SECOND = 2;
    public static final int FIRST = 3;

    /**
     * Пик — заготовка без подстадий: нужен, чтобы сцена «перестройки тела» существовала в игре
     * (автор 01.10: «пытайся воссоздать все»). Подстадии с Пика — после MVP.
     */
    public static final int PEAK = 4;

    public static final int TRANSCENDENT = 5;
    public static final int TRANSFORMATION = 6;
    public static final int PROFOUND = 7;
    public static final int LIFE_AND_DEATH = 8;
    public static final int NATURAL = 9;
    public static final int VOID = 10;

    /** Последний ранг, до которого ведут прорывы (MVP — до Пика, автор 03.10). */
    public static final int MAX = PEAK;

    /** Вершина лестницы по роману: ранги выше {@link #MAX} — данные на будущее. */
    public static final int TOP = VOID;

    /**
     * Подступени (начальная → утвердившаяся → вершина) — только с Пика (автор 01.10, вики Myst).
     * Хранятся в {@link DantianProfile#stage()} (автор 03.10). Растут без сцены прорыва — «утверждением»:
     * на Пике запас снова упёрся в стену, и техника освоена до слоя {@link #stageLayerNeed}
     * (6 — утвердившаяся, 7 — вершина). Так в книгах: внутри большой ступени копят ци и оттачивают
     * искусство, стены-прорыва нет.
     */
    public static final int STAGES = 3;

    public static final int STAGE_INITIAL = 0;
    public static final int STAGE_SETTLED = 1;
    public static final int STAGE_SUMMIT = 2;

    /** Утверждение подступени расширяет центр меньше прорыва: до следующей стены снова копить. */
    public static final double STAGE_CAPACITY_GROWTH = 1.25D;

    /**
     * Множитель силы техник по рангу (автор 03.10, этап M2): маленький, «чтобы Божественный мастер
     * не бил палкой на 99999». Применяется в одной точке — {@code TechniqueDamage.base}.
     * Это осознанное исключение из запрета множителей к урону документа 05: тот запрет — про оси
     * профиля даньтяня (чистота, ёмкость), ранг автор разрешил прямо.
     */
    private static final double[] POWER = {0.8D, 1.0D, 1.25D, 1.5D, 1.75D, 2.2D, 2.5D, 2.8D, 3.1D, 3.4D, 3.7D};

    /** Прибавка множителя за каждую подступень после начальной (с Пика). */
    public static final double POWER_PER_STAGE = 0.1D;

    /** Прибавка скорости бега за ранг: немного, чтобы шаги и рывки не ломались (автор 03.10: 2–4 %). */
    public static final double SPEED_PER_RANK = 0.03D;

    /** Во сколько раз прорыв поднимает ёмкость. */
    public static final double CAPACITY_GROWTH = 1.8D;

    /** Сколько очков здоровья даёт каждый ранг: тело перестроено, два сердца. */
    public static final double HEALTH_PER_RANK = 4.0D;

    /** Насколько прорыв прочищает меридианы. */
    public static final double MERIDIAN_GROWTH = 0.1D;

    /** Запас считается упёршимся в стену чуть раньше ровного потолка: прирост к концу медитации исчезающе мал. */
    public static final double WALL_FRACTION = 0.99D;

    /**
     * Прорыв 1 (в третий ранг) — «освоить любую технику» (автор 03.10): хотя бы одна техника
     * пройдена дальше прочтения, до этого слоя. Слой 0 — только прочитал; 1 — освоил.
     */
    public static final int THIRD_LAYER_NEED = 1;

    /** Что требует прорыв, кроме стены (план M2–M4, автор 03.10). */
    public enum Condition {
        /** Прорыв 1: освоить любую технику (слой {@link #THIRD_LAYER_NEED}). */
        MASTER_ANY,
        /**
         * Прорыв 2: победить хозяина крепости Зелёного Леса (docs/design/26-boss.md). Флаг —
         * {@code BossRegistry.BOSS_DEFEATED} у игрока; слой техники для этого прорыва не нужен.
         */
        DEFEAT_BOSS,
        /** Прорыв 3 (и Пик до решения автора): форма до слоя {@link #layerNeed}. TODO(M3): N уточнить на игре. */
        FORM_LAYER
    }

    /**
     * Предупреждение перед сценой: 2,5 секунды стука сердца (решение автора 01.10).
     * В это время можно встать без последствий — прорыв просто отложится.
     */
    public static final int WARNING_TICKS = 50;

    /** Длина сцены прорыва: 12 секунд. */
    public static final int BREAKTHROUGH_TICKS = 240;

    /** Какая доля запаса теряется, если прорыв прервали: травма меридиан. */
    public static final double INJURY_POOL_LOSS = 1.0D / 3.0D;

    /** Что мешает прорыву прямо сейчас. */
    public enum Blocker { NONE, NO_DANTIAN, NOT_AT_WALL, NO_TECHNIQUE, NO_BOSS, MAX_RANK }

    public static double wall(DantianProfile profile) {
        return profile.capacity() * MeditationService.POOL_CAP;
    }

    public static boolean atWall(DantianProfile profile) {
        return profile.isAwakened() && profile.pool() >= wall(profile) * WALL_FRACTION;
    }

    /**
     * Можно ли начать прорыв в следующий ранг.
     *
     * @param layers слои освоенных техник игрока
     */
    public static Blocker check(DantianProfile profile, Map<ResourceLocation, Integer> layers) {
        return check(profile, layers, false);
    }

    /**
     * Можно ли начать прорыв в следующий ранг.
     *
     * @param layers       слои освоенных техник игрока
     * @param bossDefeated игрок победил хозяина крепости (условие прорыва 2)
     */
    public static Blocker check(DantianProfile profile, Map<ResourceLocation, Integer> layers, boolean bossDefeated) {
        if (!profile.isAwakened()) {
            return Blocker.NO_DANTIAN;
        }
        if (profile.rank() >= MAX) {
            return Blocker.MAX_RANK;
        }
        if (!atWall(profile)) {
            return Blocker.NOT_AT_WALL;
        }
        // TODO(M5): место силы ускоряет медитацию с риском, но условием прорыва не является.
        if (condition(profile.rank() + 1) == Condition.DEFEAT_BOSS) {
            return bossDefeated ? Blocker.NONE : Blocker.NO_BOSS;
        }
        int need = layerNeed(profile.rank() + 1);
        boolean known = layers.values().stream().anyMatch(layer -> layer >= need);
        return known ? Blocker.NONE : Blocker.NO_TECHNIQUE;
    }

    /** Условие прорыва в данный ранг (кроме стены запаса). */
    public static Condition condition(int targetRank) {
        return switch (targetRank) {
            case THIRD -> Condition.MASTER_ANY;
            case SECOND -> Condition.DEFEAT_BOSS;
            default -> Condition.FORM_LAYER;
        };
    }

    /**
     * Слой техники, которого требует прорыв в данный ранг: третий — 1 («освоить любую»),
     * второй — 3 (не проверяется: условие — победа над хозяином крепости), первый — 4, Пик — 5.
     */
    public static int layerNeed(int targetRank) {
        return targetRank <= THIRD ? THIRD_LAYER_NEED : targetRank + 1;
    }

    /** Что мешает утвердить следующую подступень Пика прямо сейчас ({@code MAX_RANK} — не Пик или уже вершина). */
    public static Blocker checkStage(DantianProfile profile, Map<ResourceLocation, Integer> layers) {
        if (!profile.isAwakened()) {
            return Blocker.NO_DANTIAN;
        }
        if (profile.rank() != PEAK || profile.stage() >= STAGES - 1) {
            return Blocker.MAX_RANK;
        }
        if (!atWall(profile)) {
            return Blocker.NOT_AT_WALL;
        }
        int need = stageLayerNeed(profile.stage() + 1);
        return layers.values().stream().anyMatch(layer -> layer >= need) ? Blocker.NONE : Blocker.NO_TECHNIQUE;
    }

    /** Слой техники для подступени Пика: утвердившаяся — 6, вершина — 7 (вход в Пик — 5). */
    public static int stageLayerNeed(int targetStage) {
        return layerNeed(PEAK) + Math.max(1, targetStage);
    }

    /** Профиль после утверждения подступени: на ступень выше внутри Пика, центр чуть шире, запас сохранён. */
    public static DantianProfile settle(DantianProfile profile) {
        return profile.withAxes(profile.capacity() * STAGE_CAPACITY_GROWTH, profile.purity(), profile.meridians())
                .withStage(profile.stage() + 1);
    }

    /** Ключ названия подступени: {@code murim.rank.stage.0..2}. */
    public static String stageKey(int stage) {
        return "murim.rank.stage." + Math.max(0, Math.min(STAGES - 1, stage));
    }

    /** Множитель силы техник на ранге {@code rank}, подступень {@code stage} (0 — начальная; только с Пика). */
    public static double power(int rank, int stage) {
        int r = Math.max(0, Math.min(TOP, rank));
        int s = r >= PEAK ? Math.max(0, Math.min(STAGES - 1, stage)) : 0;
        return POWER[r] + s * POWER_PER_STAGE;
    }

    public static double power(int rank) {
        return power(rank, 0);
    }

    public static double power(DantianProfile profile) {
        return power(profile.rank(), profile.stage());
    }

    /** Прибавка к скорости бега (доля базовой): +3 % за ранг. */
    public static double bonusSpeed(int rank) {
        return Math.max(0, Math.min(TOP, rank)) * SPEED_PER_RANK;
    }

    /** Профиль после успешного прорыва: ранг выше, ёмкость и каналы шире, запас сохранён. */
    public static DantianProfile advance(DantianProfile profile) {
        DantianProfile grown = profile.withAxes(profile.capacity() * CAPACITY_GROWTH, profile.purity(),
                Mth.clamp(profile.meridians() + MERIDIAN_GROWTH, 0.0D, 1.0D));
        return grown.withRank(Math.min(MAX, profile.rank() + 1));
    }

    /** Профиль после прерванного прорыва: треть запаса потеряна, ранг прежний. */
    public static DantianProfile injure(DantianProfile profile) {
        return profile.withPool(profile.pool() * (1.0D - INJURY_POOL_LOSS));
    }

    /** Добавка к максимальному здоровью от ранга. */
    public static double bonusHealth(int rank) {
        return Math.max(0, Math.min(TOP, rank)) * HEALTH_PER_RANK;
    }

    public static String nameKey(int rank) {
        return "murim.rank." + Math.max(0, Math.min(TOP, rank));
    }

    private Realm() {
    }
}
