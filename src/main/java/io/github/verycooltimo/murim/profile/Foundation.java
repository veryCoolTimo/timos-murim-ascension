package io.github.verycooltimo.murim.profile;

import net.minecraft.network.chat.Component;

/**
 * Основание пути — единственный необратимый выбор при создании даньтяня.
 *
 * <p>Три крупных варианта вместо сети развилок. Сеть меридианов с ветвлением на блочной
 * модели шириной шестнадцать пикселей нечитаема: выбор, которого игрок не видит, — это
 * не выбор (отвергнуто в плане MVP 2).
 *
 * <p><b>Цена видна до выбора.</b> Открытый вопрос плана — «игрок не понимает Кровь,
 * Пустоту и Гору до выбора, первое необратимое решение рискует стать лотереей». Ответ:
 * у каждого основания есть короткая строка последствия, которую игрок читает прямо в
 * сцене, и она описывает не число, а ощущение от игры за это основание.
 */
public enum Foundation {

    /**
     * Кровь: много силы сразу, но мутная природа.
     *
     * <p>Быстрый старт ценой чистоты. Мутная природа позже упирается в потолок на
     * прорывах — это цена, а не штраф.
     */
    BLOOD("blood", 16.0D, 0.34D, 0.72D, "turbid"),

    /**
     * Пустота: мало, но безупречно чисто.
     *
     * <p>Медленный старт, зато чистая природа даёт запас на будущие прорывы.
     */
    VOID("void", 9.0D, 0.88D, 0.60D, "clear"),

    /**
     * Гора: большой объём и широкие каналы, но всё идёт медленно.
     */
    MOUNTAIN("mountain", 22.0D, 0.55D, 0.38D, "stone");

    private final String id;
    private final double capacity;
    private final double purity;
    private final double meridians;
    private final String nature;

    Foundation(String id, double capacity, double purity, double meridians, String nature) {
        this.id = id;
        this.capacity = capacity;
        this.purity = purity;
        this.meridians = meridians;
        this.nature = nature;
    }

    public String id() {
        return id;
    }

    public String nature() {
        return nature;
    }

    /** Название для сцены: {@code murim.foundation.blood} и так далее. */
    public Component title() {
        return Component.translatable("murim.foundation." + id);
    }

    /** Строка последствия — читается ДО выбора, иначе решение становится лотереей. */
    public Component cost() {
        return Component.translatable("murim.foundation." + id + ".cost");
    }

    /**
     * Накладывает основание на профиль новичка.
     *
     * <p>Значения ЗАМЕЩАЮТ начальные, а не прибавляются к ним: даньтянь создаётся один раз,
     * и его исходная форма определяется целиком этим выбором. Прибавление дало бы разным
     * основаниям одинаковую базу и стёрло бы разницу между ними.
     */
    public DantianProfile apply(DantianProfile base) {
        return base.withAxes(capacity, purity, meridians).withTags(nature, "awakening_" + id);
    }

    /** Разбор идентификатора; {@code null}, если такого основания нет. */
    public static Foundation byId(String value) {
        for (Foundation foundation : values()) {
            if (foundation.id.equals(value)) {
                return foundation;
            }
        }
        return null;
    }
}
