package io.github.verycooltimo.murim.cultivation;

/**
 * Подлёт при полном усвоении хорошей пилюли (19.10: «усвоить 100 % должно быть сложно, и если 100 %
 * хорошей пилюли — ты подлетаешь, и как прорыв просто поглощая»).
 *
 * <p>Сцена длится {@link #TICKS} (5,5 с): плавный подъём на 1–1,5 блока, выброс ци у даньтяня в верхней
 * точке, спуск. Высоту ограничивает потолок: низкий потолок — не пролом, а «стук головой»
 * (Хуашань, гл. 249). Время и высоту считает сервер, клиент рисует по тем же формулам.
 *
 * <p>Чистая логика без мира.
 */
public final class PerfectLift {

    /** Сцена целиком: подъём, выброс, спуск. */
    public static final int TICKS = 110;
    /** Конец подъёма и начало удержания. */
    public static final int RISE_END = 42;
    /** Тик выброса ци у даньтяня. */
    public static final int BURST = 52;
    /** Начало спуска. */
    public static final int DESCENT = 80;

    /** Высота подъёма без потолка, блоки: у автора «блок–полтора». */
    public static final float MAX_HEIGHT = 1.3F;
    /** Верх головы сидящего над ступнями: от него считается запас до потолка. */
    public static final float SEATED_HEAD_TOP = 1.25F;
    /** Меньше этого запаса — подъёма почти нет, остаётся один стук. */
    public static final float MIN_HEIGHT = 0.15F;

    private PerfectLift() {
    }

    /** Итог осмотра потолка: до какой высоты можно подняться и будет ли стук. */
    public record Plan(float height, boolean bump) {
        public static final Plan NONE = new Plan(0.0F, false);
    }

    /**
     * @param headroom свободное место над головой сидящего, блоки (не меньше нуля)
     */
    public static Plan plan(float headroom) {
        if (headroom >= MAX_HEIGHT + 0.05F) {
            return new Plan(MAX_HEIGHT, false);
        }
        float clear = Math.max(0.0F, headroom - 0.05F);
        return new Plan(Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, clear)), true);
    }

    /** Высота над землёй на тике {@code age}: мягкий подъём, держится, плавный спуск. */
    public static float height(float age, float max) {
        if (age <= 0.0F || age >= TICKS) {
            return 0.0F;
        }
        float up = smooth(Math.min(1.0F, age / RISE_END));
        float down = smooth(Math.min(1.0F, (TICKS - age) / (TICKS - DESCENT)));
        return max * Math.min(up, down);
    }

    private static float smooth(float k) {
        return k * k * (3.0F - 2.0F * k);
    }
}
