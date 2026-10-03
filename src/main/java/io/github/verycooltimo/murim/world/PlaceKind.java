package io.github.verycooltimo.murim.world;

import net.minecraft.util.StringRepresentable;

/**
 * Вид места силы (docs/design/19b §3): где стоит узел ци и какая у него волна.
 *
 * @param colour цвет ауры, 0xRRGGBB
 */
public enum PlaceKind implements StringRepresentable {
    /** Пик: высота ≥ 110. Волна — небесный разряд. Белый с голубым. */
    PEAK("peak", 0xDDF2FF),
    /** У воды. Волна — холод. Циан. */
    WATER("water", 0x5FE6F0),
    /** Под кроной. Волна — зов: враждебные идут к тебе. Зелёный. */
    FOREST("forest", 0x8CE070),
    /** Руины алтаря. Волна — выброс смешанной ци. Тускло-золотой. */
    ALTAR("altar", 0xE0C060);

    private final String name;
    private final int colour;

    PlaceKind(String name, int colour) {
        this.name = name;
        this.colour = colour;
    }

    public int colour() {
        return colour;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
