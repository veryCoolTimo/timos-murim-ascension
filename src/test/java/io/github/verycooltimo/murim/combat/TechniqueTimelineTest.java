package io.github.verycooltimo.murim.combat;

import net.minecraft.resources.ResourceLocation;
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

    private static final Technique DRAW = Techniques.CEREMONIAL_DRAW;

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("murim", path);
    }

    private static Technique technique(int windup, int impact, int recovery, int dissipation) {
        Map<TechniquePhase, Integer> map = new EnumMap<>(TechniquePhase.class);
        map.put(TechniquePhase.WINDUP, windup);
        map.put(TechniquePhase.IMPACT, impact);
        map.put(TechniquePhase.RECOVERY, recovery);
        map.put(TechniquePhase.DISSIPATION, dissipation);
        return new Technique(id("test"), map, 4.0D, 90.0D, 1.0F, 1, 1);
    }

    @Test
    @DisplayName("Церемониальный выхват: длительности и границы фаз совпадают с задуманными")
    void ceremonialDrawHasExpectedShape() {
        assertEquals(14, DRAW.ticksOf(TechniquePhase.WINDUP));
        assertEquals(2, DRAW.ticksOf(TechniquePhase.IMPACT));
        assertEquals(6, DRAW.ticksOf(TechniquePhase.RECOVERY));
        assertEquals(10, DRAW.ticksOf(TechniquePhase.DISSIPATION));
        assertEquals(32, DRAW.totalTicks());

        assertEquals(0, DRAW.startTickOf(TechniquePhase.WINDUP));
        assertEquals(14, DRAW.startTickOf(TechniquePhase.IMPACT));
        assertEquals(16, DRAW.startTickOf(TechniquePhase.RECOVERY));
        assertEquals(22, DRAW.startTickOf(TechniquePhase.DISSIPATION));
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
        assertEquals(TechniquePhase.WINDUP, DRAW.phaseAt(13));
        assertEquals(TechniquePhase.IMPACT, DRAW.phaseAt(14));
        assertEquals(TechniquePhase.IMPACT, DRAW.phaseAt(15));
        assertEquals(TechniquePhase.RECOVERY, DRAW.phaseAt(16));
    }

    @Test
    @DisplayName("За пределами техники фазы нет — по этому признаку сервер её и завершает")
    void afterEndThereIsNoPhase() {
        assertNull(DRAW.phaseAt(32));
        assertNull(DRAW.phaseAt(1000));
        assertNull(DRAW.phaseAt(-1));
    }

    @Test
    @DisplayName("Фаза нулевой длины пропускается, а не съедает тик")
    void zeroLengthPhaseIsSkipped() {
        Technique noImpact = technique(3, 0, 2, 0);

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
    @DisplayName("Техника из одних нулей завершается сразу и ни разу не бьёт")
    void emptyTechniqueEndsImmediately() {
        Technique empty = technique(0, 0, 0, 0);
        assertEquals(0, empty.totalTicks());
        assertNull(empty.phaseAt(0), "иначе сервер запустит технику, которая никогда не кончится");
    }

    @Test
    @DisplayName("Карта фаз наружу отдаётся неизменяемой")
    void phaseMapIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> DRAW.phaseTicks().put(TechniquePhase.IMPACT, 99));
    }

    @Test
    @DisplayName("Конструктор отвергает значения, которые пришли бы из кривого датапака")
    void constructorRejectsBadValues() {
        Map<TechniquePhase, Integer> ok = new EnumMap<>(TechniquePhase.class);
        ok.put(TechniquePhase.IMPACT, 2);

        Map<TechniquePhase, Integer> negative = new EnumMap<>(TechniquePhase.class);
        negative.put(TechniquePhase.WINDUP, -1);
        assertThrows(IllegalArgumentException.class,
                () -> new Technique(id("t"), negative, 4.0D, 90.0D, 1.0F, 1, 1),
                "отрицательная длительность фазы");

        assertThrows(IllegalArgumentException.class,
                () -> new Technique(id("t"), ok, 0.0D, 90.0D, 1.0F, 1, 1), "нулевая дальность");
        assertThrows(IllegalArgumentException.class,
                () -> new Technique(id("t"), ok, 4.0D, 400.0D, 1.0F, 1, 1), "дуга больше полного круга");
        assertThrows(IllegalArgumentException.class,
                () -> new Technique(id("t"), ok, 4.0D, 90.0D, -1.0F, 1, 1),
                "отрицательный урон у мобов не клампится и вылечил бы цель");
        assertThrows(IllegalArgumentException.class,
                () -> new Technique(id("t"), ok, 4.0D, 90.0D, 1.0F, -1, 1), "отрицательный hit stop");
    }

    @Test
    @DisplayName("NaN не проскакивает мимо валидации")
    void constructorRejectsNaN() {
        Map<TechniquePhase, Integer> ok = new EnumMap<>(TechniquePhase.class);
        ok.put(TechniquePhase.IMPACT, 2);

        // NaN даёт false в любом сравнении, поэтому прямая проверка «меньше нуля» его пропускает.
        // NaN в дуге отключил бы угловой фильтр целиком: техника била бы на все 360 градусов.
        assertThrows(IllegalArgumentException.class,
                () -> new Technique(id("t"), ok, Double.NaN, 90.0D, 1.0F, 1, 1), "NaN в дальности");
        assertThrows(IllegalArgumentException.class,
                () -> new Technique(id("t"), ok, 4.0D, Double.NaN, 1.0F, 1, 1), "NaN в дуге");
        assertThrows(IllegalArgumentException.class,
                () -> new Technique(id("t"), ok, 4.0D, 90.0D, Float.NaN, 1, 1), "NaN в уроне");
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
