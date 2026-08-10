package io.github.verycooltimo.murim.client.vfx;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Дуга, которую описывает остриё клинка, — в системе координат игрока.
 *
 * <p>Почему дуга задаётся параметрами, а не снимается с кости руки: Player Animation Library
 * 1.1.5 не предоставляет позицию кости в мире (проверено javap по jar — методов вида
 * {@code getBoneWorldPosition*} в библиотеке нет). Тянуть точку из рига пришлось бы через
 * собственные миксины в рендер игрока, что запрещено без письменного обоснования.
 * Правило 04 к тому же требует держать логику эффекта целиком на клиенте, поэтому форма
 * взмаха живёт здесь, рядом с рендером, а не в серверных данных техники.
 *
 * <p>Следствие, о котором нужно помнить: дуга и анимация связаны только глазами автора.
 * Меняешь ключевые кадры взмаха — перепроверь дугу по кадрам съёмки.
 *
 * <p>Класс намеренно не знает ни о {@code Minecraft}, ни о {@code PoseStack}: это чистая
 * геометрия, покрытая юнит-тестами.
 */
public record BladeArc(Vec3 pivot, Vec3 basisA, Vec3 basisB,
                       double innerRadius, double outerRadius,
                       double startAngleDeg, double endAngleDeg) {

    /**
     * Церемониальный выхват: клинок заведён за правое плечо и проходит по диагонали
     * вниз-поперёк корпуса. Значения подобраны по кадрам съёмки техники.
     *
     * <p><b>Соглашение о координатах, на котором легко ошибиться.</b> Дуга описана для игрока
     * с нулевым рысканьем, то есть смотрящего на юг, в направлении {@code +Z}. При таком взгляде
     * правая рука игрока указывает в {@code -X}, а левая — в {@code +X}: {@code forward × up = -X}.
     * Поэтому удар справа налево идёт от отрицательного X к положительному, а не наоборот.
     * Перепутанный знак даёт зеркальную ленту, висящую с противоположной от клинка стороны
     * (поймано на кадрах съёмки 2026-08-10).
     */
    public static final BladeArc CEREMONIAL_DRAW = new BladeArc(
            new Vec3(-0.22D, 1.24D, 0.0D),
            new Vec3(-0.55D, 0.83D, -0.10D),
            new Vec3(0.35D, -0.15D, 0.92D),
            0.15D, 1.15D,
            -18.0D, 152.0D);
    // Радиус 1.15 от плеча, а не «на глаз побольше»: рука игрока около 0.75 блока, клинок
    // примерно столько же, и остриё физически не уходит дальше. При 1.85 лента висела
    // на высоте 2.5 блока — заметно выше самого меча (поймано на кадрах 2026-08-10).

    /**
     * Ортонормированный базис плоскости взмаха, посчитанный один раз.
     *
     * <p>Раньше Грам — Шмидт выполнялся внутри {@code pointAt}, то есть два корня и три
     * временных вектора на каждую точку дуги, а точек за кадр — под сотню. Для горячего пути
     * рендера это чистые потери: базис зависит только от полей record-а и не меняется.
     */
    private static final class Basis {
        private final Vec3 a;
        private final Vec3 b;

        private Basis(Vec3 rawA, Vec3 rawB) {
            this.a = rawA.normalize();
            this.b = rawB.subtract(this.a.scale(rawB.dot(this.a))).normalize();
        }
    }

    private static final java.util.Map<BladeArc, Basis> BASIS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public BladeArc {
        if (!(outerRadius > innerRadius)) {
            throw new IllegalArgumentException(
                    "внешний радиус должен быть больше внутреннего: " + innerRadius + ".." + outerRadius);
        }
        if (!(innerRadius >= 0.0D)) {
            throw new IllegalArgumentException("внутренний радиус отрицателен: " + innerRadius);
        }
        // Условие записано отрицанием намеренно: при NaN прямое сравнение даёт true и пропускает мусор.
        if (!(Math.abs(endAngleDeg - startAngleDeg) > 1.0E-3D)) {
            throw new IllegalArgumentException("дуга нулевой длины");
        }
        // Коллинеарный базис Грам — Шмидт превращает в нулевой вектор, а Vec3.normalize
        // молча возвращает ZERO — дуга схлопнулась бы в отрезок без единой ошибки.
        Vec3 unitA = basisA.normalize();
        if (!(basisB.subtract(unitA.scale(basisB.dot(unitA))).lengthSqr() > 1.0E-6D)) {
            throw new IllegalArgumentException("базис вырожден: векторы коллинеарны");
        }
    }

    private Basis basis() {
        return BASIS_CACHE.computeIfAbsent(this, key -> new Basis(key.basisA, key.basisB));
    }

    /** Нормаль плоскости взмаха. Нужна как запасная опора, когда билборд вырождается. */
    public Vec3 planeNormal() {
        Basis basis = basis();
        return basis.a.cross(basis.b).normalize();
    }

    /**
     * Касательная к дуге — аналитическая производная, а не разность соседних точек.
     * Точная и не требует двух лишних вычислений точки на каждый сегмент.
     */
    public Vec3 tangentAt(double progress) {
        Basis basis = basis();
        double t = Mth.clamp(progress, 0.0D, 1.0D);
        double angle = Math.toRadians(startAngleDeg + (endAngleDeg - startAngleDeg) * t);
        return basis.a.scale(-Math.sin(angle)).add(basis.b.scale(Math.cos(angle)));
    }

    /**
     * Точка на дуге.
     *
     * @param progress доля взмаха от 0 до 1; за границами обрезается
     * @param radius   расстояние от оси вращения
     * @return точка в координатах относительно ног игрока, до поворота по рысканью
     */
    public Vec3 pointAt(double progress, double radius) {
        double t = Mth.clamp(progress, 0.0D, 1.0D);
        double angle = Math.toRadians(startAngleDeg + (endAngleDeg - startAngleDeg) * t);

        // Базис ортогонализован по Граму — Шмидту заранее: без ортогонализации дуга
        // получается не круговой, а скошенной, тем сильнее, чем менее перпендикулярны
        // исходные векторы.
        Basis basis = basis();
        return pivot.add(basis.a.scale(Math.cos(angle) * radius))
                    .add(basis.b.scale(Math.sin(angle) * radius));
    }

    /** Внешняя кромка ленты — след самого острия. */
    public Vec3 tipAt(double progress) {
        return pointAt(progress, outerRadius);
    }

    /** Внутренняя кромка ленты — ближе к рукояти. */
    public Vec3 baseAt(double progress) {
        return pointAt(progress, innerRadius);
    }
}
