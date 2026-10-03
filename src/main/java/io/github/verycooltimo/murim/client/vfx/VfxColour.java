package io.github.verycooltimo.murim.client.vfx;

/**
 * Цвет слоя эффекта.
 *
 * <p>Отдельный тип, а не три параметра подряд: три float-а в сигнатуре переставляются
 * местами молча, и такая перестановка не ловится ни компилятором, ни измерением — только
 * глазами на кадре.
 *
 * @param red   красный, 0..1
 * @param green зелёный, 0..1
 * @param blue  синий, 0..1
 */
public record VfxColour(float red, float green, float blue) {

    /**
     * Холодное ядро.
     *
     * <p>На референсах ладони холодного мало: оно занимает плотную сердцевину и самые
     * яркие кромки, а не всю площадь. Первая версия эффекта делала наоборот — белая
     * вспышка на весь кадр и почти без цвета.
     */
    public static final VfxColour COLD_CORE = new VfxColour(0.88F, 0.99F, 1.0F);

    /** Ядовитая зелень: основная масса ладони. */
    public static final VfxColour VENOM = new VfxColour(0.36F, 1.0F, 0.52F);

    /** Тот же зелёный, но глубже — для тумана и дальних слоёв. */
    public static final VfxColour VENOM_DEEP = new VfxColour(0.26F, 0.95F, 0.42F);

    /** Тёмные штрихи поверх свечения: они создают фактуру движения. */
    public static final VfxColour SOOT = new VfxColour(0.05F, 0.08F, 0.06F);

    /** Тот же цвет с изменённой яркостью. */
    public VfxColour scaled(float factor) {
        return new VfxColour(red * factor, green * factor, blue * factor);
    }
}
