package io.github.verycooltimo.murim.client.vfx;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Геометрия дуги клинка: проверяется без запуска игры, это чистая математика. */
class BladeArcTest {

    @Test
    @DisplayName("остриё держит постоянный радиус на всём взмахе")
    void tipKeepsRadius() {
        BladeArc arc = BladeArc.CEREMONIAL_DRAW;
        for (int i = 0; i <= 10; i++) {
            double t = i / 10.0D;
            double radius = arc.tipAt(t).subtract(arc.pivot()).length();
            assertEquals(arc.outerRadius(), radius, 1.0E-6D, "радиус разъехался при progress=" + t);
        }
    }

    @Test
    @DisplayName("кромки ленты не пересекаются: внешняя всегда дальше внутренней")
    void outerEdgeStaysOutside() {
        BladeArc arc = BladeArc.CEREMONIAL_DRAW;
        for (int i = 0; i <= 10; i++) {
            double t = i / 10.0D;
            double outer = arc.tipAt(t).subtract(arc.pivot()).length();
            double inner = arc.baseAt(t).subtract(arc.pivot()).length();
            assertTrue(outer > inner, "кромки пересеклись при progress=" + t);
        }
    }

    @Test
    @DisplayName("взмах идёт сверху вниз: конец дуги ниже начала")
    void sweepGoesDownward() {
        BladeArc arc = BladeArc.CEREMONIAL_DRAW;
        assertTrue(arc.tipAt(1.0D).y < arc.tipAt(0.0D).y,
                "клинок обязан опускаться, иначе это не рубящий удар");
    }

    @Test
    @DisplayName("взмах пересекает корпус: начало справа, конец слева")
    void sweepCrossesBody() {
        // При нулевом рысканье игрок смотрит в +Z, и его правая рука указывает в -X.
        // Тест закрывает именно ту ошибку, из-за которой лента оказалась зеркальной.
        BladeArc arc = BladeArc.CEREMONIAL_DRAW;
        assertTrue(arc.tipAt(0.0D).x < 0.0D, "начало взмаха должно быть справа от игрока, то есть в -X");
        assertTrue(arc.tipAt(1.0D).x > 0.0D, "конец взмаха должен уходить влево, то есть в +X");
    }

    @Test
    @DisplayName("прогресс за границами обрезается, а не экстраполируется")
    void progressIsClamped() {
        BladeArc arc = BladeArc.CEREMONIAL_DRAW;
        assertAll(
                () -> assertEquals(arc.tipAt(0.0D), arc.tipAt(-5.0D), "отрицательный прогресс не обрезан"),
                () -> assertEquals(arc.tipAt(1.0D), arc.tipAt(17.0D), "прогресс больше единицы не обрезан"));
    }

    @Test
    @DisplayName("базис ортогонализуется: заданные на глаз векторы дают круговую дугу")
    void basisIsOrthogonalised() {
        // Векторы намеренно сильно не перпендикулярны: без Грама — Шмидта дуга получилась бы
        // скошенной, и радиус поплыл бы вместе с углом.
        BladeArc skewed = new BladeArc(Vec3.ZERO,
                new Vec3(1.0D, 0.0D, 0.0D), new Vec3(0.9D, 0.3D, 0.0D),
                0.2D, 1.0D, 0.0D, 180.0D);
        for (int i = 0; i <= 8; i++) {
            double t = i / 8.0D;
            assertEquals(1.0D, skewed.tipAt(t).length(), 1.0E-9D, "дуга не круговая при progress=" + t);
        }
    }

    @Test
    @DisplayName("вырожденные параметры отвергаются, в том числе NaN")
    void invalidParametersRejected() {
        Vec3 a = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 b = new Vec3(0.0D, 1.0D, 0.0D);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BladeArc(Vec3.ZERO, a, b, 1.0D, 0.5D, 0.0D, 90.0D),
                        "внешний радиус меньше внутреннего"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BladeArc(Vec3.ZERO, a, b, -0.1D, 1.0D, 0.0D, 90.0D),
                        "отрицательный внутренний радиус"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BladeArc(Vec3.ZERO, a, b, 0.2D, 1.0D, 30.0D, 30.0D),
                        "дуга нулевой длины"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BladeArc(Vec3.ZERO, a, b, 0.2D, 1.0D, Double.NaN, 90.0D),
                        "NaN обязан отвергаться, а не проходить сравнение"));
    }

    @Test
    @DisplayName("дуга непрерывна: соседние точки не прыгают")
    void arcIsContinuous() {
        BladeArc arc = BladeArc.CEREMONIAL_DRAW;
        double maxStep = 0.0D;
        Vec3 previous = arc.tipAt(0.0D);
        for (int i = 1; i <= 64; i++) {
            Vec3 current = arc.tipAt(i / 64.0D);
            maxStep = Math.max(maxStep, current.distanceTo(previous));
            previous = current;
        }
        // Полная дуга — 170° радиусом 1.55; на 64 сегментах шаг не может превышать ~0.08.
        assertTrue(maxStep < 0.12D, "разрыв в дуге, шаг " + maxStep);
    }
}
