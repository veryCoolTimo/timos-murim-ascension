package io.github.verycooltimo.murim.world.camp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** План лагеря (docs/design/24-bandit-camp.md §2): постройки не налезают друг на друга и на частокол. */
class CampLayoutTest {

    private static Set<Long> cells(CampLayout.Spot s) {
        Set<Long> out = new HashSet<>();
        for (int[] c : CampLayout.cells(s)) {
            out.add(((long) c[0] << 32) ^ (c[1] & 0xFFFFFFFFL));
        }
        return out;
    }

    @Test
    @DisplayName("Постройки не пересекаются и стоят внутри частокола — на 2000 зёрнах")
    void noOverlaps() {
        List<String> problems = new ArrayList<>();
        for (long seed = 0; seed < 2000; seed++) {
            CampLayout plan = CampLayout.plan(seed);
            Set<Long> ring = new HashSet<>();
            for (CampLayout.Stake st : plan.stakes()) {
                ring.add(((long) st.dx() << 32) ^ (st.dz() & 0xFFFFFFFFL));
            }
            List<CampLayout.Spot> spots = plan.spots();
            for (int i = 0; i < spots.size(); i++) {
                Set<Long> a = cells(spots.get(i));
                for (Long c : a) {
                    if (ring.contains(c)) {
                        problems.add(seed + ": " + spots.get(i).kind() + " на частоколе");
                        break;
                    }
                }
                for (int j = i + 1; j < spots.size(); j++) {
                    Set<Long> b = cells(spots.get(j));
                    b.retainAll(a);
                    if (!b.isEmpty()) {
                        problems.add(seed + ": " + spots.get(i).kind() + " × " + spots.get(j).kind());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " пересечений, например " + problems.subList(0, Math.min(8, problems.size())));
    }

    @Test
    @DisplayName("3–4 шатра, вышка, телега, стойка, два ящика; ворота — разрыв в частоколе")
    void composition() {
        for (long seed = 0; seed < 200; seed++) {
            CampLayout plan = CampLayout.plan(seed);
            long tents = plan.spots().stream().filter(s -> s.kind() == CampLayout.Kind.TENT
                    || s.kind() == CampLayout.Kind.LEAN_TO || s.kind() == CampLayout.Kind.CHIEF_TENT).count();
            assertTrue(tents >= 3 && tents <= 4, "шатров " + tents);
            assertEquals(2, plan.spots().stream().filter(s -> s.kind() == CampLayout.Kind.CRATE).count());
            assertFalse(plan.gateCells().isEmpty(), "нет ворот");
            assertTrue(plan.stakes().size() > 60, "частокол редкий: " + plan.stakes().size());
            assertEquals(plan.spots(), CampLayout.plan(seed).spots(), "план не детерминирован");
        }
    }

    @Test
    @DisplayName("Состав: 5–8, один главарь, хотя бы один с ци и один лучник; посты существуют")
    void roster() {
        for (long seed = 0; seed < 500; seed++) {
            CampLayout plan = CampLayout.plan(seed);
            List<CampRoster.Member> r = CampRoster.plan(plan);
            assertTrue(r.size() >= 5 && r.size() <= 8);
            assertEquals(1, r.stream().filter(CampRoster.Member::chief).count());
            assertTrue(CampRoster.qiCount(r) >= 1);
            assertTrue(r.stream().anyMatch(CampRoster.Member::archer));
            assertTrue(r.stream().allMatch(m -> m.post() < plan.posts().size()));
            assertTrue(plan.posts().get(1).onTower(), "пост 1 — вышка");
        }
    }
}
