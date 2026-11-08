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
        boolean nightOnly,
        int starCap,
        List<Double> speed,
        double capacityMult,
        String access,
        int mist
) {

    private static final double REFERENCE_EFFICIENCY = io.github.verycooltimo.murim.profile.DantianProfile.INITIAL
            .withAxes(30.0D, 0.9D, 0.5D).efficiency();

    /** Who may learn the method: anyone with the scroll, an elder's trusted disciple of the first rank, or nobody. */
    public static final String ACCESS_OPEN = "open";
    public static final String ACCESS_ELDER = "elder";
    public static final String ACCESS_HEAD = "head";

    public CultivationMethod(ResourceLocation id, String nature, double purity, double capacity,
                             double impurity, String route, List<ResourceLocation> outerForms, boolean nightOnly) {
        this(id, nature, purity, capacity, impurity, route, outerForms, nightOnly,
                io.github.verycooltimo.murim.mastery.Arts.methodCap(id));
    }

    public CultivationMethod(ResourceLocation id, String nature, double purity, double capacity,
                             double impurity, String route, List<ResourceLocation> outerForms, boolean nightOnly,
                             int starCap) {
        this(id, nature, purity, capacity, impurity, route, outerForms, nightOnly, starCap, List.of(1.0D, 2.0D), 1.0D, ACCESS_OPEN, 0);
    }

    /** Share of the method's bonus that is already active at the given stars: 40 % at star 0, all of it at the cap (docs 43 rule 5). */
    public static double bonusShare(int stars) {
        return 0.4D + 0.6D * Math.max(0, Math.min(stars, io.github.verycooltimo.murim.mastery.Arts.MAX_STARS))
                / io.github.verycooltimo.murim.mastery.Arts.MAX_STARS;
    }

    /** Multiplier of the 20 qi/min reference rate (author 2026-10-31); star zero equals star one. */
    public double speedMultiplier(int stars) {
        return speed.get(0) + (speed.get(1) - speed.get(0))
                * Math.max(0, Math.min(stars, 12) - 1) / 11.0D;
    }

    /** Convert the reference rate to the existing seated gain pipeline, retaining purity scaling. */
    public double gainFactor(int stars) {
        return 20.0D / (1200.0D * MeditationService.gainPerTick() * REFERENCE_EFFICIENCY)
                * speedMultiplier(stars);
    }

    public double capacityFactor(int stars) {
        return 1.0D + (capacityMult - 1.0D) * bonusShare(stars);
    }

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
            Codec.BOOL.optionalFieldOf("night_only", false).forGetter(CultivationMethod::nightOnly),
            Codec.INT.optionalFieldOf("star_cap", io.github.verycooltimo.murim.mastery.Arts.MAX_STARS).forGetter(CultivationMethod::starCap),
            Codec.DOUBLE.listOf().fieldOf("speed").forGetter(CultivationMethod::speed),
            Codec.DOUBLE.optionalFieldOf("capacity_mult", 1.0D).forGetter(CultivationMethod::capacityMult),
            Codec.STRING.optionalFieldOf("access", ACCESS_OPEN).forGetter(CultivationMethod::access),
            Codec.INT.optionalFieldOf("mist", 0).forGetter(CultivationMethod::mist)
    ).apply(i, CultivationMethod::new));

    public CultivationMethod {
        java.util.Objects.requireNonNull(id, "id");
        // Retain the datapack field for compatibility; completion is universal.
        starCap = io.github.verycooltimo.murim.mastery.Arts.MAX_STARS;
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
        speed = List.copyOf(speed);
        if (speed.size() != 2 || !Double.isFinite(speed.get(0)) || !Double.isFinite(speed.get(1))
                || !(speed.get(0) > 0.0D) || !(speed.get(1) > speed.get(0))) {
            throw new IllegalArgumentException("Speed must contain positive, strictly growing endpoints: " + id);
        }
        if (!(capacityMult > 0.0D)) {
            throw new IllegalArgumentException("Non-positive method multiplier: " + id);
        }
        if (!java.util.Set.of(ACCESS_OPEN, ACCESS_ELDER, ACCESS_HEAD).contains(access)) {
            throw new IllegalArgumentException("Unknown method access: " + access);
        }
        outerForms = List.copyOf(outerForms);
    }
}
