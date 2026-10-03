package io.github.verycooltimo.murim.cultivation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/**
 * Метод культивации — то, с чего начинается путь.
 *
 * <p>По docs/design/19-dantian-qi-meditation.md §1: даньтянь рождается не в церемонии с меню
 * оснований, а из первого найденного метода и практики. Бывшие основания Кровь / Пустота / Гора
 * стали свойствами методов, а цена метода написана на самом свитке.
 *
 * <p>Метод описывается JSON в датапаке ({@code data/<ns>/murim_methods/*.json}), как техника:
 * новый метод — это данные, а не код.
 *
 * @param id         идентификатор; совпадает с путём файла
 * @param nature     природа энергии: {@code pure}, {@code poison}, {@code demonic}, {@code dead}
 * @param purity     чистота ци, которую даёт метод при идеальном отсеве примесей, 0..1
 * @param capacity   стартовая ёмкость даньтяня
 * @param impurity   доля примесей в потоке, 0..1: у сектовых методов мало, у уличных много
 * @param route      маршрут метода по телу — ключ для клиентского рисунка меридиан
 * @param outerForms внешние формы: приёмы без ци, доступные сразу после прочтения
 * @param nightOnly  практика возможна только ночью (мёртвая ци)
 */
public record CultivationMethod(
        ResourceLocation id,
        String nature,
        double purity,
        double capacity,
        double impurity,
        String route,
        List<ResourceLocation> outerForms,
        boolean nightOnly
) {

    /** Природы энергии MVP (решение автора 2026-09-29). */
    public static final Set<String> NATURES = Set.of("pure", "poison", "demonic", "dead");

    public static final Codec<CultivationMethod> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(CultivationMethod::id),
            Codec.STRING.fieldOf("nature").forGetter(CultivationMethod::nature),
            Codec.DOUBLE.fieldOf("purity").forGetter(CultivationMethod::purity),
            Codec.DOUBLE.fieldOf("capacity").forGetter(CultivationMethod::capacity),
            Codec.DOUBLE.fieldOf("impurity").forGetter(CultivationMethod::impurity),
            Codec.STRING.fieldOf("route").forGetter(CultivationMethod::route),
            ResourceLocation.CODEC.listOf().optionalFieldOf("outer_forms", List.of())
                    .forGetter(CultivationMethod::outerForms),
            Codec.BOOL.optionalFieldOf("night_only", false).forGetter(CultivationMethod::nightOnly)
    ).apply(i, CultivationMethod::new));

    public CultivationMethod {
        java.util.Objects.requireNonNull(id, "id");
        if (!NATURES.contains(nature)) {
            // Опечатка в природе молча выключила бы все проверки совместимости техник.
            throw new IllegalArgumentException("Неизвестная природа энергии: " + nature);
        }
        if (!(purity >= 0.0D) || !(purity <= 1.0D)) {
            throw new IllegalArgumentException("Чистота вне 0..1: " + purity);
        }
        if (!(impurity >= 0.0D) || !(impurity <= 1.0D)) {
            throw new IllegalArgumentException("Доля примесей вне 0..1: " + impurity);
        }
        if (!(capacity > 0.0D) || !Double.isFinite(capacity)) {
            throw new IllegalArgumentException("Недопустимая ёмкость: " + capacity);
        }
        outerForms = List.copyOf(outerForms);
    }
}
