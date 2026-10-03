package io.github.verycooltimo.murim.combat;

/**
 * Формы основы меча — Меч Шести Равновесий на обычной атаке (автор 01.10).
 *
 * <p>По книге (Хуашань гл. 70, 105, 298): медленная методичная основа «по одному шагу за раз»;
 * первая форма — косой удар вниз; в бою — шаг и укол в точку, шаг и удар сверху, взмах, блок —
 * «взмах, разрез, удар, блок», без зазора между движениями. Здесь это цепочка форм на ЛКМ:
 * каждый удар — следующая форма с шагом и фиксацией; слой освоения открывает новые формы.
 *
 * <p>Правило темпа (автор 01.10, как у Better Combat): форма никогда не медленнее ванильного
 * удара тем же оружием — анимация растягивается под перезарядку, урон — от оружия.
 */
public final class FoundationForms {

    public enum Form {
        /** Первая форма: косой удар вниз. */
        DOWN_SLASH,
        /** Укол в точку. */
        THRUST,
        /** Удар сверху. */
        OVERHEAD,
        /** Боковой разрез. */
        SIDE,
        /** Восходящий взмах. */
        RISING,
        /** Блок с ответом. */
        BLOCK;

        /** Имя анимации игрока: six_form_1..6. */
        public String animation() {
            return "six_form_" + (ordinal() + 1);
        }
    }

    /** Номинальная длина анимации формы — ванильная перезарядка меча, 12,5 тика. */
    public static final float NOMINAL_TICKS = 12.5F;

    /** Сколько форм открыто на слое: 0 → 1, 1 → 2, 2 → 3, 3 → 5, 4+ → все 6. */
    public static int unlocked(int layer) {
        return switch (Math.max(0, layer)) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 3;
            case 3 -> 5;
            default -> 6;
        };
    }

    /** Форма для шага {@code step} цепочки: по кругу через открытые. */
    public static Form at(int step, int layer) {
        return Form.values()[Math.floorMod(step, unlocked(layer))];
    }

    /** Цепочка рвётся, если пауза дольше двух перезарядок плюс полсекунды. */
    public static int chainWindow(float cooldownTicks) {
        return Math.round(cooldownTicks * 2.0F + 10.0F);
    }

    /** Скорость анимации: номинальная длина ÷ перезарядка оружия. */
    public static float speed(float cooldownTicks) {
        return cooldownTicks <= 0.0F ? 1.0F : NOMINAL_TICKS / cooldownTicks;
    }

    private FoundationForms() {
    }
}
