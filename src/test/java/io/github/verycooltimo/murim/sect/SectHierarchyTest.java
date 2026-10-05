package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import io.github.verycooltimo.murim.sect.SectSchedule.Period;
import io.github.verycooltimo.murim.sect.SectSchedule.Task;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Иерархия секты (С3, часть 2): лестница положения, закрытые места, охрана и слуги в распорядке. */
class SectHierarchyTest {

    /** Плоская раскладка: площадки — квадраты 20×20 в ряд по x, пол на y=0. */
    record Flat(Map<String, Integer> slots) implements SectLayout {
        @Override
        public Vec3 at(String zone, double du, double dv) {
            Integer i = slots.get(zone);
            return i == null ? null : new Vec3(i * 40 + du, 0, dv);
        }

        @Override
        public float yaw(double du, double dv) {
            return SectLayout.yawOf(du, dv);
        }

        @Override
        public double[] half(String zone) {
            return slots.containsKey(zone) ? new double[] {10, 10} : null;
        }

        @Override
        public double[] local(String zone, Vec3 pos) {
            Integer i = slots.get(zone);
            return i == null ? null : new double[] {pos.x - i * 40, pos.z};
        }
    }

    private static final Flat FLAT = new Flat(Map.of("sect_gate", 0, "training", 1, "treasury", 2, "main_hall", 3, "vault", 5));

    @Test
    @DisplayName("Лестница положения: чужак → новичок → ученик третьего класса → выпускник → доверенный")
    void ladder() {
        SectState s = SectState.NONE;
        assertEquals(SectStanding.OUTSIDER, SectStanding.of(s, 3));
        s = s.joined();
        assertEquals(SectStanding.NOVICE, SectStanding.of(s, 0));
        // Трудом: ранг и заслуги без урока.
        assertEquals(SectStanding.NOVICE, SectStanding.of(s.contribute(20), 0));
        assertEquals(SectStanding.DISCIPLE, SectStanding.of(s.contribute(SectStanding.DISCIPLE_CONTRIBUTION), 1));
        // Уроком.
        s = s.with(SectStanding.LESSON_ONE);
        assertEquals(SectStanding.DISCIPLE, SectStanding.of(s, 0));
        s = s.with(SectStanding.LESSON_TWO);
        assertEquals(SectStanding.GRADUATE, SectStanding.of(s, 2));
        // Доверие: одобрение главы и заслуги — нужно и то, и другое.
        assertEquals(SectStanding.GRADUATE, SectStanding.of(s.with(SectStanding.APPROVAL), 2));
        assertEquals(SectStanding.GRADUATE, SectStanding.of(s.contribute(40), 2));
        assertEquals(SectStanding.TRUSTED, SectStanding.of(s.with(SectStanding.APPROVAL).contribute(SectStanding.TRUSTED_CONTRIBUTION), 2));
        // Заслуги не падают ниже нуля.
        assertEquals(0, SectState.NONE.joined().contribute(-10).contribution());
    }

    private static SectAccess.Rule rule(String id) {
        return SectAccess.RULES.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Закрытые места: новичка и ученика не пускают в казну, выпускника — днём, ночью — только доверенного")
    void access() {
        SectAccess.Rule treasury = rule("treasury");
        assertFalse(SectAccess.allowed(treasury, SectStanding.NOVICE, Set.of(), Period.TRAINING));
        assertFalse(SectAccess.allowed(treasury, SectStanding.DISCIPLE, Set.of(), Period.TRAINING));
        assertTrue(SectAccess.allowed(treasury, SectStanding.GRADUATE, Set.of(), Period.TRAINING));
        assertFalse(SectAccess.allowed(treasury, SectStanding.GRADUATE, Set.of(), Period.NIGHT));
        // Дома старейшин — по приглашению.
        assertFalse(SectAccess.allowed(rule("elders"), SectStanding.GRADUATE, Set.of(), Period.TRAINING));
        assertTrue(SectAccess.allowed(rule("elders"), SectStanding.GRADUATE, Set.of("pass.elders"), Period.TRAINING));
        assertTrue(SectAccess.allowed(rule("main_hall"), SectStanding.DISCIPLE, Set.of(), Period.TRAINING));
        assertTrue(SectAccess.allowed(treasury, SectStanding.TRUSTED, Set.of(), Period.NIGHT));
        // Пропуск (зов, разрешение) открывает место своему, но не чужаку.
        assertTrue(SectAccess.allowed(rule("scriptures"), SectStanding.NOVICE, Set.of("pass.scriptures"), Period.TRAINING));
        assertFalse(SectAccess.allowed(rule("grounds"), SectStanding.OUTSIDER, Set.of("pass.scriptures"), Period.TRAINING));
        // Тайник — даже выпускнику нет.
        assertFalse(SectAccess.allowed(rule("vault"), SectStanding.GRADUATE, Set.of(), Period.TRAINING));

        Vec3 inTreasury = FLAT.at("treasury", 0, 0);
        assertEquals(List.of("treasury"), SectAccess.forbidden(FLAT, inTreasury, SectStanding.NOVICE, Set.of(), Period.TRAINING, 0)
                .stream().map(SectAccess.Rule::id).toList());
        // Внешний край хозяйственного двора (управляющий, кладовая) — не казна.
        assertTrue(SectAccess.forbidden(FLAT, FLAT.at("treasury", -9.5, 0), SectStanding.NOVICE, Set.of(), Period.TRAINING, 0).isEmpty());
        // Чужак: ворота можно, площадь — нет.
        assertTrue(SectAccess.forbidden(FLAT, FLAT.at("sect_gate", 0, 0), SectStanding.OUTSIDER, Set.of(), Period.TRAINING, 0).isEmpty());
        assertTrue(SectAccess.forbidden(FLAT, FLAT.at("training", 0, 0), SectStanding.OUTSIDER, Set.of(), Period.TRAINING, 0)
                .stream().anyMatch(r -> r.id().equals("grounds")));
        // Предупреждение у границы: у края казны — рядом, но не внутри.
        Vec3 edge = FLAT.at("treasury", 0, 9);
        assertTrue(SectAccess.forbidden(FLAT, edge, SectStanding.NOVICE, Set.of(), Period.TRAINING, 0).isEmpty());
        assertFalse(SectAccess.forbidden(FLAT, edge, SectStanding.NOVICE, Set.of(), Period.TRAINING, SectAccess.WARN).isEmpty());
        // Высота: глубоко под площадкой — не внутри.
        assertTrue(SectAccess.forbidden(FLAT, inTreasury.add(0, -20, 0), SectStanding.NOVICE, Set.of(), Period.TRAINING, 0).isEmpty());
    }

    @Test
    @DisplayName("Охрана видит ночью ближе, присевшего — ещё ближе")
    void sight() {
        assertTrue(SectAccess.sight(false, false, false) > SectAccess.sight(true, false, false));
        assertTrue(SectAccess.sight(true, false, false) > SectAccess.sight(true, true, false));
        assertTrue(SectAccess.sight(true, true, false) >= SectAccess.sight(true, true, true));
    }

    @Test
    @DisplayName("Охрана и слуги: не в строю и не в парах, у каждого дело в каждую часть суток, в пределах площадок")
    void staffAndGuards() {
        assertEquals(0, SectRoster.ALL.stream().filter(m -> m.role() == SectRole.GUARD).count(), "отдельной стражи нет (05.10)");
        assertTrue(SectRoster.ALL.stream().anyMatch(m -> m.role() == SectRole.STEWARD));
        for (SectRole r : List.of(SectRole.COOK, SectRole.PORTER, SectRole.GARDENER, SectRole.SWEEPER, SectRole.WATER_CARRIER)) {
            assertTrue(SectRoster.ALL.stream().anyMatch(m -> m.role() == r), "нет " + r);
        }
        for (SectRoster m : SectRoster.ALL) {
            if (!m.lay()) {
                continue;
            }
            assertFalse(SectRoster.generation(m.generation()).contains(m), m.key() + " в списке поколения");
            for (Period p : Period.values()) {
                Task t = SectSchedule.task(m, p, 2);
                assertNotNull(zone(t.zone()), m.key() + " " + p + " → " + t.zone());
                if (t.kind() == Kind.CARRY) {
                    assertTrue(t.route(), m.key() + ": ношение без второго конца");
                    assertNotNull(zone(t.toZone()), t.toZone());
                }
                if (m.lay()) {
                    assertTrue(m.role().lay() && !m.role().spars(), m.key());
                }
            }
        }
        // Носильщики днём носят, ночью спят.
        SectRoster porter = SectRoster.of("porter_jang").orElseThrow();
        assertEquals(Kind.CARRY, SectSchedule.task(porter, Period.TRAINING, 0).kind());
        assertEquals(Kind.SLEEP, SectSchedule.task(porter, Period.NIGHT, 0).kind());
    }

    private static MountHuaPlan.Zone zone(String id) {
        return MountHuaPlan.ZONES.stream().filter(z -> z.id().equals(id)).findFirst().orElse(null);
    }
}
