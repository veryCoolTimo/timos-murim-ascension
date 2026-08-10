package io.github.verycooltimo.murim.technique;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.phys.Vec3;

/**
 * Описание визуала техники — данными, а не кодом.
 *
 * <p>Смысл этапа 1: новая техника добавляется правкой JSON. Пока форма дуги, цвета и яркость
 * слоёв зашиты в рендерере, любая новая техника требует Java, и обещание «час, а не день»
 * не выполняется.
 *
 * <p>Все значения проходят валидацию: из датапака приходят произвольные числа, включая
 * отрицательные и NaN. Проверки записаны отрицанием ({@code !(x > 0)}), потому что прямое
 * сравнение с NaN даёт false и пропускает мусор дальше в рендер.
 */
public record TechniqueVfx(
        Vec3 pivot,
        Vec3 basisA,
        Vec3 basisB,
        double innerRadius,
        double outerRadius,
        double startAngleDeg,
        double endAngleDeg,
        Colour colour,
        Layer trail,
        Layer crescent,
        Layer core,
        int windupMotes,
        int ritualMotes
) {
    /** Цвет свечения. Альфа задаётся слоями отдельно: у каждого своя кривая затухания. */
    public record Colour(float red, float green, float blue) {
        public static final Codec<Colour> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("r").forGetter(Colour::red),
                Codec.FLOAT.fieldOf("g").forGetter(Colour::green),
                Codec.FLOAT.fieldOf("b").forGetter(Colour::blue)
        ).apply(i, Colour::new));

        public Colour {
            if (!(red >= 0.0F) || !(green >= 0.0F) || !(blue >= 0.0F)) {
                throw new IllegalArgumentException("Отрицательный или нечисловой цвет");
            }
        }
    }

    /**
     * Один слой эффекта.
     *
     * @param enabled   рисуется ли слой вообще
     * @param alpha     пиковая непрозрачность; для аддитивных слоёв заметно ниже единицы,
     *                  иначе три слоя уходят в насыщение и сливаются в белое пятно
     * @param size      ширина ленты, полуширина серпа или размер вспышки — по смыслу слоя
     * @param lifeTicks сколько тиков живёт слой после своего старта
     */
    public record Layer(boolean enabled, float alpha, double size, float lifeTicks) {
        public static final Codec<Layer> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Layer::enabled),
                Codec.FLOAT.fieldOf("alpha").forGetter(Layer::alpha),
                Codec.DOUBLE.fieldOf("size").forGetter(Layer::size),
                Codec.FLOAT.optionalFieldOf("life_ticks", 3.0F).forGetter(Layer::lifeTicks)
        ).apply(i, Layer::new));

        public Layer {
            if (!(alpha >= 0.0F) || !(alpha <= 4.0F)) {
                throw new IllegalArgumentException("Непрозрачность слоя вне диапазона 0..4: " + alpha);
            }
            if (!(size >= 0.0D)) {
                throw new IllegalArgumentException("Отрицательный или нечисловой размер слоя");
            }
            if (!(lifeTicks >= 0.0F)) {
                throw new IllegalArgumentException("Отрицательная или нечисловая длительность слоя");
            }
        }
    }

    private static final Codec<Vec3> VEC3 = Codec.DOUBLE.listOf().comapFlatMap(
            list -> list.size() == 3
                    ? com.mojang.serialization.DataResult.success(
                            new Vec3(list.get(0), list.get(1), list.get(2)))
                    : com.mojang.serialization.DataResult.error(() -> "Вектор должен иметь три числа"),
            vec -> java.util.List.of(vec.x, vec.y, vec.z));

    public static final Codec<TechniqueVfx> CODEC = RecordCodecBuilder.create(i -> i.group(
            VEC3.fieldOf("pivot").forGetter(TechniqueVfx::pivot),
            VEC3.fieldOf("basis_a").forGetter(TechniqueVfx::basisA),
            VEC3.fieldOf("basis_b").forGetter(TechniqueVfx::basisB),
            Codec.DOUBLE.fieldOf("inner_radius").forGetter(TechniqueVfx::innerRadius),
            Codec.DOUBLE.fieldOf("outer_radius").forGetter(TechniqueVfx::outerRadius),
            Codec.DOUBLE.fieldOf("start_angle").forGetter(TechniqueVfx::startAngleDeg),
            Codec.DOUBLE.fieldOf("end_angle").forGetter(TechniqueVfx::endAngleDeg),
            Colour.CODEC.fieldOf("colour").forGetter(TechniqueVfx::colour),
            Layer.CODEC.fieldOf("trail").forGetter(TechniqueVfx::trail),
            Layer.CODEC.fieldOf("crescent").forGetter(TechniqueVfx::crescent),
            Layer.CODEC.fieldOf("core").forGetter(TechniqueVfx::core),
            Codec.INT.optionalFieldOf("windup_motes", 0).forGetter(TechniqueVfx::windupMotes),
            Codec.INT.optionalFieldOf("ritual_motes", 0).forGetter(TechniqueVfx::ritualMotes)
    ).apply(i, TechniqueVfx::new));

    public TechniqueVfx {
        if (!(outerRadius > innerRadius)) {
            throw new IllegalArgumentException(
                    "Внешний радиус должен быть больше внутреннего: " + innerRadius + ".." + outerRadius);
        }
        if (!(innerRadius >= 0.0D)) {
            throw new IllegalArgumentException("Отрицательный внутренний радиус");
        }
        if (!(Math.abs(endAngleDeg - startAngleDeg) > 1.0E-3D)) {
            throw new IllegalArgumentException("Дуга нулевой длины");
        }
        // Коллинеарный базис Грам — Шмидт схлопывает в ноль, а Vec3.normalize молча вернёт
        // нулевой вектор — дуга выродилась бы в отрезок без единой ошибки.
        Vec3 unitA = basisA.normalize();
        if (!(basisB.subtract(unitA.scale(basisB.dot(unitA))).lengthSqr() > 1.0E-6D)) {
            throw new IllegalArgumentException("Базис вырожден: векторы коллинеарны");
        }
        if (windupMotes < 0 || ritualMotes < 0) {
            throw new IllegalArgumentException("Отрицательное число искр");
        }
    }
}
