package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.technique.TechniqueBehavior;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueVfx;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Таймлайн техники — чистая арифметика без мира и без сервера, поэтому проверяется обычным JUnit.
 *
 * <p>Тест защищает от ошибки на единицу в границах фаз. Такая ошибка не ловится компиляцией
 * и в игре выглядит как «удар проходит на кадр раньше анимации» — то есть как проблема визуала,
 * а искать её будут в рендере.
 *
 * <p>Проверки идут против <b>литералов</b>, а не против той же формулы, которой считает код:
 * тест, повторяющий тело метода, не может провалиться ни при какой ошибке в нём.
 */
class TechniqueTimelineTest {

    /**
     * Шкала церемониального выхвата — та же, что в датапаке. Дублируется здесь намеренно:
     * юнит-тест не поднимает загрузчик ресурсов, а проверять арифметику фаз надо. Расхождение
     * с JSON ловится отдельным тестом, читающим сам файл.
     */
    private static final TechniqueDefinition DRAW = technique(60, 14, 2, 6, 10, 110);

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("murim", path);
    }

    private static TechniqueDefinition technique(int ritual, int windup, int impact,
                                                 int recovery, int dissipation, int cooldown) {
        Map<TechniquePhase, Integer> map = new EnumMap<>(TechniquePhase.class);
        map.put(TechniquePhase.RITUAL, ritual);
        map.put(TechniquePhase.WINDUP, windup);
        map.put(TechniquePhase.IMPACT, impact);
        map.put(TechniquePhase.RECOVERY, recovery);
        map.put(TechniquePhase.DISSIPATION, dissipation);
        return new TechniqueDefinition(id("test"), map, "f", "i", "b",
                new TechniqueBehavior.MeleeArc(4.0D, 90.0D, 1.0F),
                VFX, id("anim"),
                new TechniqueDefinition.Interruption(null, false, 0.0F, 0, java.util.List.of()),
                "", 1, cooldown, 3, io.github.verycooltimo.murim.mastery.TechniqueTier.BASIC, java.util.List.of(), false);
    }

    /** Минимально допустимый визуал: тесты шкалы к нему не обращаются, но схема его требует. */
    private static final TechniqueVfx VFX = new TechniqueVfx(
            Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D),
            0.1D, 1.0D, 0.0D, 90.0D,
            new TechniqueVfx.Colour(1.0F, 1.0F, 1.0F),
            new TechniqueVfx.Layer(true, 1.0F, 0.4D, 5.0F),
            new TechniqueVfx.Layer(true, 0.5F, 0.2D, 3.0F),
            new TechniqueVfx.Layer(true, 0.5F, 0.2D, 3.0F),
            4, 6);

    @Test
    @DisplayName("Церемониальный выхват: длительности и границы фаз совпадают с задуманными")
    void ceremonialDrawHasExpectedShape() {
        assertEquals(60, DRAW.ticksOf(TechniquePhase.RITUAL));
        assertEquals(14, DRAW.ticksOf(TechniquePhase.WINDUP));
        assertEquals(2, DRAW.ticksOf(TechniquePhase.IMPACT));
        assertEquals(6, DRAW.ticksOf(TechniquePhase.RECOVERY));
        assertEquals(10, DRAW.ticksOf(TechniquePhase.DISSIPATION));
        assertEquals(92, DRAW.totalTicks());

        assertEquals(0, DRAW.startTickOf(TechniquePhase.RITUAL));
        assertEquals(60, DRAW.startTickOf(TechniquePhase.WINDUP));
        assertEquals(74, DRAW.startTickOf(TechniquePhase.IMPACT));
        assertEquals(76, DRAW.startTickOf(TechniquePhase.RECOVERY));
        assertEquals(82, DRAW.startTickOf(TechniquePhase.DISSIPATION));
    }

    @Test
    @DisplayName("Каждый тик до конца принадлежит ровно одной фазе, разрывов нет")
    void everyTickBelongsToExactlyOnePhase() {
        for (int tick = 0; tick < DRAW.totalTicks(); tick++) {
            TechniquePhase phase = DRAW.phaseAt(tick);
            assertTrue(phase != null, "тик " + tick + " не принадлежит ни одной фазе");
            int start = DRAW.startTickOf(phase);
            assertTrue(tick >= start && tick < start + DRAW.ticksOf(phase),
                    "тик " + tick + " отнесён к фазе " + phase + ", в чьи границы он не входит");
        }
    }

    @Test
    @DisplayName("Граница фазы: последний тик — старая фаза, следующий — уже новая")
    void phaseBoundaryIsExclusive() {
        assertEquals(TechniquePhase.RITUAL, DRAW.phaseAt(59));
        assertEquals(TechniquePhase.WINDUP, DRAW.phaseAt(60));
        assertEquals(TechniquePhase.WINDUP, DRAW.phaseAt(73));
        assertEquals(TechniquePhase.IMPACT, DRAW.phaseAt(74));
        assertEquals(TechniquePhase.IMPACT, DRAW.phaseAt(75));
        assertEquals(TechniquePhase.RECOVERY, DRAW.phaseAt(76));
    }

    @Test
    @DisplayName("За пределами техники фазы нет — по этому признаку сервер её и завершает")
    void afterEndThereIsNoPhase() {
        assertNull(DRAW.phaseAt(92));
        assertNull(DRAW.phaseAt(1000));
        assertNull(DRAW.phaseAt(-1));
    }

    @Test
    @DisplayName("Фаза нулевой длины пропускается, а не съедает тик")
    void zeroLengthPhaseIsSkipped() {
        TechniqueDefinition noImpact = technique(0, 3, 0, 2, 0, 5);

        assertEquals(5, noImpact.totalTicks());
        // Удар и восстановление начинаются в одной точке: удара просто нет.
        assertEquals(3, noImpact.startTickOf(TechniquePhase.IMPACT));
        assertEquals(3, noImpact.startTickOf(TechniquePhase.RECOVERY));

        assertEquals(TechniquePhase.WINDUP, noImpact.phaseAt(2));
        assertEquals(TechniquePhase.RECOVERY, noImpact.phaseAt(3), "нулевой удар не должен занимать тик");
        assertEquals(TechniquePhase.RECOVERY, noImpact.phaseAt(4));
        assertNull(noImpact.phaseAt(5));
    }

    @Test
    @DisplayName("Техника без единой фазы отвергается при создании, а не запускается пустой")
    void emptyTechniqueRejected() {
        // Раньше такая техника создавалась и просто завершалась на нулевом тике. Теперь схема
        // отвергает её сразу: пустое описание в датапаке — это ошибка автора, и она должна
        // быть видна при загрузке, а не проявляться молчаливым бездействием в бою.
        assertThrows(IllegalArgumentException.class, () -> technique(0, 0, 0, 0, 0, 10));
    }

    @Test
    @DisplayName("Кулдаун короче самой техники отвергается")
    void shortCooldownRejected() {
        // Иначе второй запуск затирает состояние первого: состояние одно на игрока.
        assertThrows(IllegalArgumentException.class, () -> technique(0, 10, 2, 2, 2, 5));
    }

    @Test
    @DisplayName("Карта фаз наружу отдаётся неизменяемой")
    void phaseMapIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> DRAW.phaseTicks().put(TechniquePhase.IMPACT, 99));
    }

    @Test
    @DisplayName("Схема отвергает значения, которые пришли бы из кривого датапака")
    void schemaRejectsBadValues() {
        Map<TechniquePhase, Integer> negative = new EnumMap<>(TechniquePhase.class);
        negative.put(TechniquePhase.WINDUP, -1);
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueDefinition(id("t"), negative, "f", "i", "b",
                        new TechniqueBehavior.MeleeArc(4.0D, 90.0D, 1.0F), VFX, id("a"),
                        new TechniqueDefinition.Interruption(null, false, 0.0F, 0, java.util.List.of()),
                        "", 1, 10, 3, io.github.verycooltimo.murim.mastery.TechniqueTier.BASIC, java.util.List.of(), false),
                "отрицательная длительность фазы");

        // Параметры воздействия теперь валидируются самим типом поведения — там же,
        // где они и объявлены, а не в общем описании техники.
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueBehavior.MeleeArc(0.0D, 90.0D, 1.0F), "нулевая дальность");
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueBehavior.MeleeArc(4.0D, 400.0D, 1.0F), "дуга больше полного круга");
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueBehavior.MeleeArc(4.0D, 90.0D, -1.0F),
                "отрицательный урон у мобов не клампится и вылечил бы цель");
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueBehavior.ProjectileFan(9, 40.0D, 1.0D, 40, 1.0F),
                "число снарядов сверх бюджета");
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueBehavior.Dash(0.0D, 1.0D, 1.0F), "нулевой рывок");
    }

    @Test
    @DisplayName("NaN не проскакивает мимо валидации")
    void constructorRejectsNaN() {
        // NaN даёт false в любом сравнении, поэтому прямая проверка «меньше нуля» его пропускает.
        // NaN в дуге отключил бы угловой фильтр целиком: техника била бы на все 360 градусов.
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueBehavior.MeleeArc(Double.NaN, 90.0D, 1.0F), "NaN в дальности");
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueBehavior.MeleeArc(4.0D, Double.NaN, 1.0F), "NaN в дуге");
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueBehavior.MeleeArc(4.0D, 90.0D, Float.NaN), "NaN в уроне");
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueBehavior.Dash(Double.NaN, 1.0D, 1.0F), "NaN в дальности рывка");
    }

    @Test
    @DisplayName("Состояние: отметка удара переживает продвижение тика")
    void impactFlagIsStickyAcrossTicks() {
        TechniqueState state = TechniqueState.started(DRAW.id(), 100L);
        assertFalse(state.impactDone());
        assertTrue(state.isActive());
        assertEquals(0, state.tick());

        state = state.withImpactDone().advanced();
        assertTrue(state.impactDone(), "после продвижения тика отметка не должна теряться");
        assertEquals(1, state.tick());
    }

    @Test
    @DisplayName("Состояние: завершение сохраняет время запуска, иначе кулдаун обнулялся бы")
    void finishKeepsCooldownClock() {
        TechniqueState finished = TechniqueState.started(DRAW.id(), 12345L)
                .advanced()
                .withImpactDone()
                .finished();

        assertFalse(finished.isActive());
        assertEquals(12345L, finished.lastStartGameTime());
        assertEquals(0, finished.tick());
        assertFalse(finished.impactDone());
    }

    @Test
    @DisplayName("Состояние: IDLE неактивно и не помнит запусков")
    void idleIsInactive() {
        assertFalse(TechniqueState.IDLE.isActive());
        assertEquals(Long.MIN_VALUE, TechniqueState.IDLE.lastStartGameTime());
    }
}
