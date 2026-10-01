package io.github.verycooltimo.murim.combat;

/**
 * Давление ауры: сколько стоит стоять рядом с тем, кто сильнее (docs/design/01, 19 §3ж).
 *
 * <p>Чистая функция без Minecraft: её считают и сервер (замедление), и клиент (экран, звук),
 * и обе стороны обязаны совпадать — иначе экран говорит «тяжело», а ноги идут как обычно.
 *
 * <p>Правила из документов:
 * <ul>
 *   <li>давление — градиент, а не выключатель; ввод не отнимается и отход всегда работает;</li>
 *   <li>равный и слабее — ничем не отличается;</li>
 *   <li>давление слабеет с расстоянием: игрок чувствует его на подходе и успевает отойти;</li>
 *   <li>чем сильнее противник, тем дальше достаёт давление и тем меньше игрок может сделать.</li>
 * </ul>
 */
public final class AuraPressure {

    /** Ближе этого давление полное. */
    public static final double FULL_DISTANCE = 2.5D;

    /** С какой силы перестают запускаться техники: тело ещё слушается, ци — уже нет. */
    public static final float TECHNIQUE_LOCK = 0.8F;

    /** С какой силы нельзя бежать. */
    public static final float SPRINT_LOCK = 0.45F;

    /** Сила давления при разнице рангов {@code gap} на близкой дистанции. */
    public static float base(int gap) {
        return switch (gap) {
            case 0 -> 0.0F;
            case 1 -> 0.3F;
            case 2 -> 0.55F;
            case 3 -> 0.75F;
            case 4 -> 0.9F;
            default -> gap < 0 ? 0.0F : 1.0F;
        };
    }

    /** Докуда достаёт давление: на каждую ступень разницы — три блока сверх четырёх. */
    public static double range(int gap) {
        return gap <= 0 ? 0.0D : 4.0D + 3.0D * gap;
    }

    /**
     * Давление одного источника.
     *
     * @param auraRank   ранг ауры источника
     * @param viewerRank ранг того, на кого давят
     * @param distance   расстояние между ними в блоках
     * @return 0..1
     */
    public static float of(int auraRank, int viewerRank, double distance) {
        int gap = auraRank - viewerRank;
        float base = base(gap);
        if (base <= 0.0F) {
            return 0.0F;
        }
        double range = range(gap);
        if (distance >= range) {
            return 0.0F;
        }
        if (distance <= FULL_DISTANCE) {
            return base;
        }
        double t = (distance - FULL_DISTANCE) / (range - FULL_DISTANCE);
        // Плавный спад: на краю давление подкрадывается, а не включается ступенькой.
        double fall = 1.0D - t * t * (3.0D - 2.0D * t);
        return (float) (base * fall);
    }

    /** Множитель скорости ходьбы: при полном давлении остаётся 15 %, но не ноль. */
    public static double speedPenalty(float pressure) {
        return -0.85D * pressure;
    }

    public static double attackPenalty(float pressure) {
        return -0.6D * pressure;
    }

    public static double jumpPenalty(float pressure) {
        return -0.55D * pressure;
    }

    /** С какой силы давления начинаются порывы. */
    public static final float GUST_FROM = 0.3F;

    /** Через сколько тиков следующий порыв: чем тяжелее, тем чаще (1,2 → 0,7 с). */
    public static int gustInterval(float pressure) {
        return Math.round(24.0F - 10.0F * Math.min(1.0F, Math.max(0.0F, (pressure - GUST_FROM) / (1.0F - GUST_FROM))));
    }

    /**
     * Скорость толчка в блоках за тик: от 0,1 до 0,35. Обратная тяга — 90 % толчка: игрока
     * качает на месте, а не выносит за радиус за три порыва (кадры 01.10).
     */
    public static double gustPush(float pressure) {
        return 0.1D + 0.25D * Math.min(1.0F, Math.max(0.0F, (pressure - GUST_FROM) / (1.0F - GUST_FROM)));
    }

    public static final double PULL_RATIO = 0.9D;

    /** Через сколько тиков после толчка тянет обратно: «швыряет туда-сюда». */
    public static final int PULL_DELAY = 7;

    private AuraPressure() {
    }
}
