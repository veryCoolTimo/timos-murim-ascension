package io.github.verycooltimo.murim.technique;

/**
 * Что делает форма меча на каждом слое освоения (docs/design/techniques/huashan-swords.md,
 * концепт «Меч Шести Равновесий» 01.10). Чистые правила — общие для сервера (урон) и клиента
 * (анимация, эффекты), чтобы картинка и удары не разошлись.
 *
 * <p>По книге (Хуашань гл. 4, 101, 105): вводная форма — удар сверху вниз; основы — укол, рубка,
 * взмах; «начальная форма» мастера — давящая пауза и один удар; высший слой — меч и тело одним
 * вектором.
 */
public final class SwordFormRules {

    /**
     * Тиков между ударами серии. Восемь, а не четыре: серия — последовательность отдельных
     * движений с остановкой в позе после каждого удара, а не непрерывный поток (автор 01.10).
     */
    public static final int SERIES_GAP = 8;

    /** Сколько ударов: на нулевом слое — один, дальше — серия из трёх. */
    public static int strikes(int layer) {
        return layer <= 0 ? 1 : 3;
    }

    /** Последний удар серии — широкий разрез «начальной формы». */
    public static boolean wideFinish(int layer) {
        return layer >= 3;
    }

    /** Высший слой: раскол земли вперёд и столб ци. */
    public static boolean unity(int layer) {
        return layer >= 4;
    }

    /**
     * Множитель урона удара {@code index} (с нуля). Одиночный удар нулевого слоя полный;
     * удары серии легче, широкий разрез — тяжелее.
     */
    public static float damageFactor(int layer, int index) {
        if (layer <= 0) {
            return 1.0F;
        }
        boolean last = index == strikes(layer) - 1;
        if (last && wideFinish(layer)) {
            return 1.5F;
        }
        return 0.6F;
    }

    /** Анимация игрока по слою: одиночный удар, серия или единение с мечом. */
    public static String animationSuffix(int layer) {
        return layer <= 0 ? "" : unity(layer) ? "_unity" : "_series";
    }

    private SwordFormRules() {
    }
}
