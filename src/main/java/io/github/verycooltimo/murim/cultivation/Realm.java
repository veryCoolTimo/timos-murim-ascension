package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Map;

/**
 * Ранги и прорывы: правила без сервера, чтобы их проверяли тесты.
 *
 * <p>docs/design/19-dantian-qi-meditation.md §3д, §3е. Ранг хранится в
 * {@link DantianProfile#rank()}: 0 — без ранга (даньтянь только родился), 1 — третий,
 * 2 — второй, 3 — первый. Пик и выше — после MVP.
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

    /** Последний ранг MVP: дальше Пик с подстадиями, его пока нет. */
    public static final int MAX = FIRST;

    /** Во сколько раз прорыв поднимает ёмкость. Множителей к урону нет — запрет документа 05. */
    public static final double CAPACITY_GROWTH = 1.8D;

    /** Сколько очков здоровья даёт каждый ранг: тело перестроено, два сердца. */
    public static final double HEALTH_PER_RANK = 4.0D;

    /** Насколько прорыв прочищает меридианы. */
    public static final double MERIDIAN_GROWTH = 0.1D;

    /** Запас считается упёршимся в стену чуть раньше ровного потолка: прирост к концу медитации исчезающе мал. */
    public static final double WALL_FRACTION = 0.99D;

    /** Для прорыва в третий ранг хотя бы одна техника должна быть освоена до этого слоя. */
    public static final int THIRD_LAYER_NEED = 2;

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
    public enum Blocker { NONE, NO_DANTIAN, NOT_AT_WALL, NO_TECHNIQUE, MAX_RANK }

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
        if (!profile.isAwakened()) {
            return Blocker.NO_DANTIAN;
        }
        if (profile.rank() >= MAX) {
            return Blocker.MAX_RANK;
        }
        if (!atWall(profile)) {
            return Blocker.NOT_AT_WALL;
        }
        int need = layerNeed(profile.rank() + 1);
        boolean known = layers.values().stream().anyMatch(layer -> layer >= need);
        return known ? Blocker.NONE : Blocker.NO_TECHNIQUE;
    }

    /** Слой техники, которого требует прорыв в данный ранг: третий — 2, дальше на слой выше. */
    public static int layerNeed(int targetRank) {
        return THIRD_LAYER_NEED + Math.max(0, targetRank - THIRD);
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
        return Math.max(0, Math.min(MAX, rank)) * HEALTH_PER_RANK;
    }

    public static String nameKey(int rank) {
        return "murim.rank." + Math.max(0, Math.min(MAX, rank));
    }

    private Realm() {
    }
}
