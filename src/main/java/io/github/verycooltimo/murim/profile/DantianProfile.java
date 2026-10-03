package io.github.verycooltimo.murim.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;

/**
 * Профиль даньтяня — то, чем один культиватор отличается от другого.
 *
 * <p>Три оси и два тега, по docs/design/05-dantian-profile.md. Управляемость сознательно
 * вынесена наружу: это развиваемое умение персонажа, а не свойство центра.
 *
 * <p>Энергия двухуровневая. {@code pool} — накопленный за всю культивацию запас, мера ранга,
 * меняется медленно. {@code circulating} — то, что тратится в бою и восстанавливается из запаса
 * сидячей циркуляцией, а не сном. Разделение взято из книг: боевое истощение там тактически
 * значимо, персонаж не может применить лучшую технику из-за расхода в предыдущих схватках.
 *
 * @param capacity    ёмкость даньтяня, растёт регулярно
 * @param purity      чистота ци: сильнее в применении, но копится в разы медленнее
 * @param meridians   пропускная способность каналов
 * @param nature      природа энергии — тег, меняется только редкими событиями
 * @param imprint     отпечаток первого метода — тег
 * @param pool        накопленный запас
 * @param circulating циркулирующая ци
 * @param foundation  фундамент: его жгут запретные техники, когда циркулирующая кончилась
 * @param rank        ранг мастерства
 * @param stage       подступень Пика: 0 — начальная, 1 — утвердившаяся, 2 — вершина (автор 03.10);
 *                    ниже Пика всегда 0. В старых сохранениях поля нет — читается как 0
 */
public record DantianProfile(
        double capacity,
        double purity,
        double meridians,
        String nature,
        String imprint,
        double pool,
        double circulating,
        double foundation,
        int rank,
        int stage
) {

    /** Последняя подступень Пика (вершина). Та же граница, что {@code Realm.STAGES - 1}. */
    public static final int MAX_STAGE = 2;

    /** Профиль новичка: центр не сформирован, запас пуст, фундамент цел. */
    public static final DantianProfile INITIAL =
            new DantianProfile(10.0D, 0.5D, 0.5D, "none", "none", 0.0D, 0.0D, 1.0D, 0, 0);

    public static final Codec<DantianProfile> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("capacity").forGetter(DantianProfile::capacity),
            Codec.DOUBLE.fieldOf("purity").forGetter(DantianProfile::purity),
            Codec.DOUBLE.fieldOf("meridians").forGetter(DantianProfile::meridians),
            Codec.STRING.fieldOf("nature").forGetter(DantianProfile::nature),
            Codec.STRING.fieldOf("imprint").forGetter(DantianProfile::imprint),
            Codec.DOUBLE.fieldOf("pool").forGetter(DantianProfile::pool),
            Codec.DOUBLE.fieldOf("circulating").forGetter(DantianProfile::circulating),
            Codec.DOUBLE.fieldOf("foundation").forGetter(DantianProfile::foundation),
            Codec.INT.fieldOf("rank").forGetter(DantianProfile::rank),
            // Необязательное: сохранения до подступеней (03.10) его не знают — начальная.
            Codec.INT.optionalFieldOf("stage", 0).forGetter(DantianProfile::stage)
    ).apply(i, DantianProfile::new));

    public DantianProfile {
        // Отрицанием: NaN проходит любое прямое сравнение и разрушил бы всю арифметику ци.
        // Бесконечность проходит любое сравнение «больше нуля», поэтому проверяется отдельно.
        if (!(capacity > 0.0D) || !Double.isFinite(capacity)) {
            throw new IllegalArgumentException("Недопустимая ёмкость даньтяня: " + capacity);
        }
        if (!(purity >= 0.0D) || !(purity <= 1.0D)) {
            throw new IllegalArgumentException("Чистота вне 0..1: " + purity);
        }
        if (!(meridians >= 0.0D) || !(meridians <= 1.0D)) {
            throw new IllegalArgumentException("Пропускная способность вне 0..1: " + meridians);
        }
        if (!(pool >= 0.0D) || !(circulating >= 0.0D)
                || !Double.isFinite(pool) || !Double.isFinite(circulating)) {
            throw new IllegalArgumentException("Недопустимый запас ци");
        }
        if (!(foundation >= 0.0D) || !(foundation <= 1.0D)) {
            throw new IllegalArgumentException("Фундамент вне 0..1: " + foundation);
        }
        if (rank < 0) {
            throw new IllegalArgumentException("Отрицательный ранг");
        }
        if (stage < 0 || stage > MAX_STAGE) {
            throw new IllegalArgumentException("Подступень вне 0.." + MAX_STAGE + ": " + stage);
        }
        java.util.Objects.requireNonNull(nature, "nature");
        java.util.Objects.requireNonNull(imprint, "imprint");
    }

    /**
     * Сколько ци удерживает центр одновременно.
     *
     * <p>Чистая энергия плотнее: тот же объём центра вмещает её больше. Это и есть обратная
     * сторона размена «чистота стоит скорости» — копится медленно, но работает лучше.
     */
    public double maxCirculating() {
        return capacity * (0.6D + 0.4D * purity);
    }

    /**
     * Скорость восстановления циркулирующей ци за тик сидячей циркуляции.
     *
     * <p>Зависит от каналов: забитые меридианы пропускают энергию медленнее, сколько бы
     * её ни лежало в запасе.
     */
    public double circulationRate() {
        return 0.02D + 0.08D * meridians;
    }

    /**
     * Насколько экономно центр расходует энергию.
     *
     * <p>Влияет на ЦЕНУ и скорость, но <b>не на урон</b>. Дизайн запрещает множители к урону
     * прямым текстом: они превращают профиль в рейтинг вместо горизонтальных различий.
     * Раньше это значение умножало урон завершающего удара — прямое нарушение, поймано ревью.
     */
    public double efficiency() {
        return 0.85D + 0.45D * purity;
    }

    /** Потолок ёмкости. Без него медитация даёт неограниченный рост и обесценивает риск. */
    public static final double MAX_CAPACITY = 240.0D;

    public DantianProfile withCirculating(double value) {
        return new DantianProfile(capacity, purity, meridians, nature, imprint, pool,
                Mth.clamp(value, 0.0D, maxCirculating()), foundation, rank, stage);
    }

    public DantianProfile withPool(double value) {
        return new DantianProfile(capacity, purity, meridians, nature, imprint,
                Math.max(0.0D, value), circulating, foundation, rank, stage);
    }

    public DantianProfile withFoundation(double value) {
        return new DantianProfile(capacity, purity, meridians, nature, imprint, pool, circulating,
                Mth.clamp(value, 0.0D, 1.0D), rank, stage);
    }

    public DantianProfile withAxes(double newCapacity, double newPurity, double newMeridians) {
        DantianProfile changed = new DantianProfile(
                Mth.clamp(newCapacity, 0.1D, MAX_CAPACITY),
                Mth.clamp(newPurity, 0.0D, 1.0D),
                Mth.clamp(newMeridians, 0.0D, 1.0D),
                nature, imprint, pool, circulating, foundation, rank, stage);
        // Пересжатие обязательно: смена осей меняет предел центра, и без этого
        // циркулирующая ци могла навсегда остаться выше собственного максимума.
        return changed.withCirculating(changed.circulating());
    }

    /** Новый ранг. Подступень сохраняется только на том же ранге: новая ступень начинается с начальной. */
    public DantianProfile withRank(int value) {
        return new DantianProfile(capacity, purity, meridians, nature, imprint, pool, circulating,
                foundation, value, value == rank ? stage : 0);
    }

    /** Подступень Пика; ниже Пика (ранг 4) её нет — остаётся 0. */
    public DantianProfile withStage(int value) {
        return new DantianProfile(capacity, purity, meridians, nature, imprint, pool, circulating,
                foundation, rank, rank >= 4 ? Mth.clamp(value, 0, MAX_STAGE) : 0);
    }

    public DantianProfile withTags(String newNature, String newImprint) {
        return new DantianProfile(capacity, purity, meridians, newNature, newImprint, pool,
                circulating, foundation, rank, stage);
    }

    /**
     * Переливает ци из накопленного запаса в циркулирующую.
     *
     * <p>Ровно это и есть двухуровневая модель: запас — итог культивации, циркулирующая —
     * то, что тратится в бою. Скорость ограничена каналами: забитые меридианы пропускают
     * медленнее, сколько бы ни лежало в запасе.
     *
     * @return профиль после перелива за один тик
     */
    public DantianProfile circulateOnce() {
        double room = maxCirculating() - circulating;
        if (room <= 0.0D || pool <= 0.0D) {
            return this;
        }
        double moved = Math.min(Math.min(room, pool), circulationRate());
        return withPool(pool - moved).withCirculating(circulating + moved);
    }

    /** Сформирован ли центр вообще: до ритуала техники недоступны. */
    public boolean isAwakened() {
        return !"none".equals(nature);
    }
}
