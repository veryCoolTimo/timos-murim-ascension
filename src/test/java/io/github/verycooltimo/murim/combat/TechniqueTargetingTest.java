package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.technique.BehaviorExecutor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка попадания в дугу и кулдауна.
 *
 * <p>Обе функции вынесены из {@code TechniqueService} в чистые статические методы именно ради
 * этого теста: в мире их проверить нельзя без сервера, а ошибка здесь тихая — техника просто
 * иногда не попадает, и списывают это на «лаги».
 */
class TechniqueTargetingTest {

    /** Взгляд строго вдоль оси X. */
    private static final Vec3 EYE = new Vec3(0.0D, 0.0D, 0.0D);
    private static final Vec3 LOOK = new Vec3(1.0D, 0.0D, 0.0D);

    private static final double REACH = 4.0D;
    /** Дуга 140 градусов, как у церемониального выхвата: косинус половины. */
    private static final double COS_LIMIT = Math.cos(Math.toRadians(140.0D / 2.0D));

    private static AABB box(double cx, double cy, double cz, double halfSize) {
        return new AABB(cx - halfSize, cy - halfSize, cz - halfSize,
                cx + halfSize, cy + halfSize, cz + halfSize);
    }

    private static boolean hits(AABB target) {
        return BehaviorExecutor.inArc(EYE, LOOK, target, REACH, COS_LIMIT);
    }

    @Test
    @DisplayName("Цель прямо перед носом в пределах дальности поражается")
    void targetStraightAheadIsHit() {
        assertTrue(hits(box(2.0D, 0.0D, 0.0D, 0.3D)));
    }

    @Test
    @DisplayName("Цель за спиной не поражается")
    void targetBehindIsMissed() {
        assertFalse(hits(box(-2.0D, 0.0D, 0.0D, 0.3D)));
    }

    @Test
    @DisplayName("Цель дальше дальности не поражается")
    void targetOutOfReachIsMissed() {
        assertFalse(hits(box(6.0D, 0.0D, 0.0D, 0.3D)));
    }

    @Test
    @DisplayName("Крупная цель попадает по телу, даже если её центр дальше дальности")
    void hugeTargetIsHitByItsBodyNotItsCentre() {
        // Полутораблочный радиус, центр на 5 блоках — дальше дальности 4. Но ближняя грань
        // всего в 2 блоках, то есть тело стоит вплотную. Проверка по центру дала бы промах,
        // и крупные существа вроде дракона или равагера были бы неуязвимы вплотную.
        AABB huge = box(5.0D, 0.0D, 0.0D, 3.0D);
        assertTrue(huge.getCenter().distanceTo(EYE) > REACH, "предпосылка теста: центр вне дальности");
        assertTrue(hits(huge), "цель должна поражаться по ближайшей точке хитбокса");
    }

    @Test
    @DisplayName("Крупная цель сбоку попадает краем тела, хотя её центр вне дуги")
    void hugeTargetIsHitByItsEdgeNotItsCentre() {
        // Центр смещён вбок настолько, что направление на него выходит за половину дуги,
        // а ближний угол хитбокса остаётся внутри конуса.
        AABB wide = new AABB(1.0D, -0.5D, 2.0D, 1.5D, 0.5D, 7.0D);
        Vec3 toCentre = wide.getCenter().subtract(EYE).normalize();
        assertTrue(LOOK.dot(toCentre) < COS_LIMIT, "предпосылка теста: центр вне дуги");
        assertTrue(hits(wide), "цель должна поражаться по ближайшей точке хитбокса");
    }

    @Test
    @DisplayName("Цель, внутри хитбокса которой стоит игрок, считается попаданием")
    void targetEnclosingEyeIsHit() {
        // Нулевое расстояние до ближайшей точки: направления нет вовсе. Трактовать это как
        // промах значило бы, что вплотную прижавшийся моб неуязвим.
        assertTrue(hits(box(0.0D, 0.0D, 0.0D, 1.0D)));
    }

    @Test
    @DisplayName("Узкая дуга отсекает цель, которую широкая поражает")
    void narrowArcRejectsWhatWideArcAccepts() {
        AABB side = box(1.0D, 0.0D, 2.0D, 0.3D);
        double narrow = Math.cos(Math.toRadians(30.0D / 2.0D));

        assertTrue(BehaviorExecutor.inArc(EYE, LOOK, side, REACH, COS_LIMIT), "дуга 140° достаёт");
        assertFalse(BehaviorExecutor.inArc(EYE, LOOK, side, REACH, narrow), "дуга 30° не достаёт");
    }

    @Test
    @DisplayName("Первый запуск техники не упирается в кулдаун")
    void firstUseIsAlwaysAllowed() {
        assertTrue(TechniqueService.offCooldown(0L, Long.MIN_VALUE, 40),
                "сентинел «ни разу не применялось» обязан пропускать, иначе разность переполнится");
        assertTrue(TechniqueService.offCooldown(Long.MAX_VALUE, Long.MIN_VALUE, 40));
    }

    @Test
    @DisplayName("Кулдаун истекает ровно на заданном тике, не раньше")
    void cooldownBoundaryIsInclusive() {
        assertFalse(TechniqueService.offCooldown(139L, 100L, 40), "на тик раньше — ещё рано");
        assertTrue(TechniqueService.offCooldown(140L, 100L, 40), "ровно на границе — уже можно");
        assertTrue(TechniqueService.offCooldown(200L, 100L, 40));
    }

    @Test
    @DisplayName("Нулевой кулдаун ничего не запрещает")
    void zeroCooldownAllowsImmediateReuse() {
        assertTrue(TechniqueService.offCooldown(100L, 100L, 0));
    }
}
