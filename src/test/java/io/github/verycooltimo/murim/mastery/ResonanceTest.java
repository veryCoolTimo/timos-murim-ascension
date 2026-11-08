package io.github.verycooltimo.murim.mastery;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Apex Echo rules (docs/design/research/synergy-and-fun-1910.md A4, author's decisions of 19.10), no world needed. */
class ResonanceTest {
    private static final double EPS = 1.0E-9D;

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("murim", name);
    }

    private static final ResourceLocation BRAWLER = id("village_brawler_fist");
    private static final ResourceLocation WIND_GOD = id("wind_god");
    private static final ResourceLocation PLUM_24 = id("twenty_four_plum");
    private static final ResourceLocation SIX = id("six_harmonies");
    /** Authored kin of the 24 Movements (art_lore.json). */
    private static final ResourceLocation KIN_ART = id("falling_petal_sword");
    /** Same school (plum) as the 24 Movements, not its kin. */
    private static final ResourceLocation SCHOOL_ART = id("dark_fragrance_step");
    /** An ordinary art that is nobody's apex. */
    private static final ResourceLocation OTHER_ART = id("crescent_sweep");

    /** The real generated school and kin tables, with the grade given by hand (the technique loader needs a world). */
    private static final Resonance.Lore REAL = new Resonance.Lore() {
        private final Set<ResourceLocation> apexes = Set.of(PLUM_24, id("demon_palm"), WIND_GOD);

        @Override public int grade(ResourceLocation art) { return apexes.contains(art) ? Resonance.APEX : 1; }
        @Override public String school(ResourceLocation art) { return Arts.school(art); }
        @Override public boolean kin(ResourceLocation a, ResourceLocation b) { return Arts.kin(a, b); }
        @Override public int cap(ResourceLocation art) { return Arts.cap(art); }
    };

    /** Every art is an apex of cap 12 and kin of every other: the worst case for the ceilings. */
    private static final Resonance.Lore ALL_KIN = new Resonance.Lore() {
        @Override public int grade(ResourceLocation art) { return Resonance.APEX; }
        @Override public String school(ResourceLocation art) { return "x"; }
        @Override public boolean kin(ResourceLocation a, ResourceLocation b) { return true; }
        @Override public int cap(ResourceLocation art) { return 12; }
    };

    private static Map<ResourceLocation, Integer> stars(Object... pairs) {
        Map<ResourceLocation, Integer> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((ResourceLocation) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    @Test
    @DisplayName("Рубежи вершины: 50 / 75 / 100 % звёзд, то есть ★6 / ★9 / ★12 и ★5 / ★8 / ★10")
    void milestonesAreHalfThreeQuartersAndAll() {
        assertEquals(List.of(6, 9, 12), List.of(Resonance.threshold(12, 1), Resonance.threshold(12, 2), Resonance.threshold(12, 3)));
        assertEquals(List.of(5, 8, 10), List.of(Resonance.threshold(10, 1), Resonance.threshold(10, 2), Resonance.threshold(10, 3)));
        assertEquals(0, Resonance.milestone(5, 12));
        assertEquals(1, Resonance.milestone(6, 12));
        assertEquals(1, Resonance.milestone(8, 12));
        assertEquals(2, Resonance.milestone(9, 12));
        assertEquals(3, Resonance.milestone(12, 12));
        assertEquals(1, Resonance.milestone(7, 10));
        assertEquals(3, Resonance.milestone(10, 10));
    }

    @Test
    @DisplayName("Данные: Движения Сливы, Ладонь и Шаги Бога Ветров, школы и двусторонняя родня; удалённых искусств в таблицах нет")
    void generatedDataHasSchoolsKinAndTheApexFlag() {
        assertTrue(Arts.apexFlag(WIND_GOD), "Шаги Бога Ветров — вершина по флагу");
        assertTrue(Arts.apexFlag(id("wind_god_steps")), "флаг читается и по форме");
        for (String other : List.of("twenty_four_plum", "demon_palm", "falling_petal_sword")) {
            assertFalse(Arts.apexFlag(id(other)), "секретные — вершины по тиру, а не по флагу: " + other);
        }
        assertEquals("plum", Arts.school(id("seven_plum_rush")), "школа читается по форме");
        assertEquals("demon", Arts.school(WIND_GOD));
        assertEquals("street", Arts.school(BRAWLER));
        assertEquals("tang", Arts.school(id("tang_twelve_daggers")));
        assertEquals("", Arts.school(Arts.methodArt(id("six_harmonies"))), "метод культивации не искусство школы");
        assertTrue(Arts.kin(SIX, PLUM_24) && Arts.kin(PLUM_24, KIN_ART) && Arts.kin(KIN_ART, PLUM_24), "основа и школа слив");
        assertFalse(Arts.kin(PLUM_24, SCHOOL_ART), "одна школа — не родня");
        assertFalse(Arts.kin(PLUM_24, BRAWLER));
        assertFalse(Arts.kin(PLUM_24, PLUM_24));
        // Шесть искусств Небесного Демона удалены 19.10: ни школы, ни родни, ни ступеней в таблицах не осталось.
        for (String removed : RemovedArts.ARTS) {
            assertEquals("", Arts.school(id(removed)), removed);
            assertFalse(Arts.kin(WIND_GOD, id(removed)) || Arts.kin(id(removed), WIND_GOD), removed);
            assertEquals(Arts.MAX_STARS, Arts.cap(id(removed)), removed + ": no frozen metadata is left");
            assertEquals(id(removed), Arts.masteryId(id(removed)), removed + ": no form-to-art alias is left");
        }
    }

    @Test
    @DisplayName("Связь: родня, школа, остальные")
    void links() {
        assertEquals(Resonance.Link.KIN, Resonance.link(REAL, PLUM_24, KIN_ART));
        assertEquals(Resonance.Link.SCHOOL, Resonance.link(REAL, PLUM_24, SCHOOL_ART));
        assertEquals(Resonance.Link.OTHER, Resonance.link(REAL, PLUM_24, BRAWLER));
        assertEquals(Resonance.Link.OTHER, Resonance.link(REAL, PLUM_24, id("unknown_datapack_art")), "без школы — не школа");
    }

    @Test
    @DisplayName("Лёгкость: на последнем рубеже не меньше ×1,06 / ×1,12 / ×1,20 для остальных / школы / родни")
    void lightnessTiersAreTheMinimums() {
        assertEquals(0.06D, Resonance.lightness(Resonance.Link.OTHER, 3), EPS);
        assertEquals(0.12D, Resonance.lightness(Resonance.Link.SCHOOL, 3), EPS);
        assertEquals(0.20D, Resonance.lightness(Resonance.Link.KIN, 3), EPS);
        assertEquals(0.4D * 0.06D, Resonance.lightness(Resonance.Link.OTHER, 1), EPS);
        assertEquals(0.7D * 0.12D, Resonance.lightness(Resonance.Link.SCHOOL, 2), EPS);
        assertEquals(0.0D, Resonance.lightness(Resonance.Link.KIN, 0), EPS, "до первого рубежа ничего");
        for (Resonance.Link link : Resonance.Link.values()) {
            assertTrue(Resonance.lightness(link, 1) < Resonance.lightness(link, 2));
            assertTrue(Resonance.lightness(link, 2) < Resonance.lightness(link, 3));
        }
        assertTrue(Resonance.lightness(Resonance.Link.OTHER, 3) < Resonance.lightness(Resonance.Link.SCHOOL, 3));
        assertTrue(Resonance.lightness(Resonance.Link.SCHOOL, 3) < Resonance.lightness(Resonance.Link.KIN, 3));
    }

    @Test
    @DisplayName("Вершина поднимает ВСЕ остальные искусства (поправка автора), родню сильнее всего")
    void anApexLiftsEveryOtherArt() {
        // Движения Сливы на ★6: первый рубеж.
        Map<ResourceLocation, Integer> onFirst = stars(PLUM_24, 6, BRAWLER, 1, SCHOOL_ART, 1, KIN_ART, 1);
        double other = Resonance.echo(REAL, BRAWLER, onFirst);
        double school = Resonance.echo(REAL, SCHOOL_ART, onFirst);
        double kin = Resonance.echo(REAL, KIN_ART, onFirst);
        assertTrue(other > 0.0D, "несвязанное искусство (другая школа, не родня) тоже получило долю");
        assertTrue(other < school && school < kin, other + " < " + school + " < " + kin);
        assertEquals(0.4D * 0.06D, other, EPS);
        assertEquals(0.4D * 0.12D, school, EPS);
        assertEquals(0.4D * 0.20D, kin, EPS);
        // На ★12: полные ступени.
        Map<ResourceLocation, Integer> onLast = stars(PLUM_24, 12, BRAWLER, 1, SCHOOL_ART, 1, KIN_ART, 1);
        assertEquals(0.06D, Resonance.echo(REAL, BRAWLER, onLast), EPS);
        assertEquals(0.12D, Resonance.echo(REAL, SCHOOL_ART, onLast), EPS);
        assertEquals(0.20D, Resonance.echo(REAL, KIN_ART, onLast), EPS);
    }

    @Test
    @DisplayName("Вершина не поднимает себя, метод культивации, и не поднимает ничего ниже первого рубежа или не-вершиной")
    void whoDoesNotGetLifted() {
        assertEquals(0.0D, Resonance.echo(REAL, PLUM_24, stars(PLUM_24, 12)), EPS, "сама себя");
        assertEquals(0.0D, Resonance.echo(REAL, Arts.methodArt(id("six_harmonies")),
                stars(PLUM_24, 12)), EPS, "метод культивации — не искусство Отклика");
        assertEquals(0.0D, Resonance.echo(REAL, BRAWLER, stars(PLUM_24, 5)), EPS, "★5 из 12 — рубежа ещё нет");
        assertEquals(0.0D, Resonance.echo(REAL, BRAWLER, stars(OTHER_ART, 12)), EPS, "продвинутое искусство не вершина");
        assertEquals(0.0D, Resonance.echo(REAL, BRAWLER, stars()), EPS);
        // Шаги Бога Ветров (12 звёзд): первый рубеж на ★6.
        assertTrue(Resonance.echo(REAL, BRAWLER, stars(WIND_GOD, 6)) > 0.0D);
        assertEquals(0.0D, Resonance.echo(REAL, BRAWLER, stars(WIND_GOD, 5)), EPS);
    }

    @Test
    @DisplayName("Несколько вершин: наибольшая доля плюс четверть остальных, не больше +0,20")
    void severalApexesCombineBelowTheCeiling() {
        double one = Resonance.lightness(Resonance.Link.OTHER, 3);
        Map<ResourceLocation, Integer> two = stars(WIND_GOD, 12, PLUM_24, 12, BRAWLER, 3);
        assertEquals(one + 0.25D * one, Resonance.echo(REAL, BRAWLER, two), EPS);
        Map<ResourceLocation, Integer> many = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            many.put(id("apex_" + i), 12);
        }
        assertEquals(Resonance.ECHO_CAP, Resonance.echo(ALL_KIN, id("target"), many), EPS, "потолок Лёгкости +0,20");
    }

    @Test
    @DisplayName("Весь множитель практики не больше ×1,30: широта ≤ +0,10 и Отклик ≤ +0,20")
    void practiceFactorNeverPassesOnePointThree() {
        assertEquals(1.30D, MasteryRules.MAX_PRACTICE_FACTOR, EPS);
        assertEquals(1.0D, MasteryRules.practiceFactor(0.0D, 0.0D), EPS);
        assertEquals(1.05D + 0.08D, MasteryRules.practiceFactor(1.0D, 0.08D), EPS);
        assertEquals(1.30D, MasteryRules.practiceFactor(1.0E9D, 1.0E9D), EPS);
        assertEquals(1.30D, MasteryRules.practiceFactor(52.0D, 0.99D), EPS);
        assertEquals(1.0D, MasteryRules.practiceFactor(-4.0D, -1.0D), EPS);
        for (double wisdom = 0; wisdom < 200; wisdom += 3.7D) {
            for (double echo = 0; echo < 2.0D; echo += 0.13D) {
                assertTrue(MasteryRules.practiceFactor(wisdom, echo) <= 1.30D + EPS);
            }
        }
        // Применён ровно один раз: опыт ×1,30, а не ×1,30².
        TechniqueProgress fresh = TechniqueProgress.learned(0, 12);
        double plain = MasteryRules.experience(fresh, MasteryRules.Source.FIGHT, 4.0D, 0.0D, 0.0D, 12).progress().progress();
        double full = MasteryRules.experience(fresh, MasteryRules.Source.FIGHT, 4.0D, 99.0D, 0.99D, 12).progress().progress();
        assertEquals(1.30D, full / plain, 1.0E-6D);
    }

    @Test
    @DisplayName("Рывок: родне 10 / 15 / 25 %, остальным вдвое меньше, всё внутри общего потолка помощи 20 %")
    void rushIsInsideTheHelpCap() {
        assertEquals(0.20D, MasteryRules.HELP_CAP, EPS);
        assertEquals(0.10D, Resonance.rushShare(Resonance.Link.KIN, 1), EPS);
        assertEquals(0.15D, Resonance.rushShare(Resonance.Link.KIN, 2), EPS);
        assertEquals(0.20D, Resonance.rushShare(Resonance.Link.KIN, 3), EPS, "25 % упирается в потолок 20 %");
        assertEquals(0.05D, Resonance.rushShare(Resonance.Link.OTHER, 1), EPS);
        assertEquals(0.075D, Resonance.rushShare(Resonance.Link.SCHOOL, 2), EPS);
        assertEquals(0.125D, Resonance.rushShare(Resonance.Link.OTHER, 3), EPS);
        for (Resonance.Link link : Resonance.Link.values()) {
            for (int m = 1; m <= 3; m++) {
                assertTrue(Resonance.rushShare(link, m) <= MasteryRules.HELP_CAP + EPS);
            }
        }
    }

    @Test
    @DisplayName("Рывок достаёт только ниже ⌊0,75 × звёзд вершины⌋ и ниже стены озарения")
    void rushReach() {
        assertTrue(Resonance.rushReaches(3, 12, 6) && !Resonance.rushReaches(4, 12, 6), "★6 → ниже ★4");
        assertTrue(Resonance.rushReaches(5, 12, 9) && !Resonance.rushReaches(6, 12, 9), "★9 → ниже ★6");
        assertTrue(Resonance.rushReaches(8, 12, 12) && !Resonance.rushReaches(9, 12, 12), "★12 → ниже ★9");
        assertFalse(Resonance.rushReaches(8, 10, 12), "★8 у 10-звёздного — это стена озарения");
        assertTrue(Resonance.rushReaches(7, 10, 12));
        assertFalse(Resonance.rushReaches(10, 12, 12), "на стену ★10–12 не заходит");
    }

    @Test
    @DisplayName("Рывок идёт родне и всем остальным, кроме вершины, метода и тех, до кого не достаёт")
    void rushGoesToKinAndToEveryoneElse() {
        Map<ResourceLocation, TechniqueProgress> arts = new HashMap<>();
        arts.put(PLUM_24, TechniqueProgress.learned(6, 12));
        arts.put(KIN_ART, TechniqueProgress.learned(2, 12));
        arts.put(SCHOOL_ART, TechniqueProgress.learned(3, 12));
        arts.put(BRAWLER, TechniqueProgress.learned(1, 12));
        arts.put(OTHER_ART, TechniqueProgress.learned(5, 12));
        arts.put(Arts.methodArt(id("six_harmonies")), TechniqueProgress.learned(0, 12));
        arts.put(SIX, TechniqueProgress.learned(3, 3));
        List<Resonance.Rush> rush = Resonance.rush(REAL, PLUM_24, 1, 6, arts);
        Map<ResourceLocation, Resonance.Rush> byArt = new HashMap<>();
        rush.forEach(r -> byArt.put(r.art(), r));
        assertEquals(Set.of(KIN_ART, SCHOOL_ART, BRAWLER), byArt.keySet(),
                "вершина, метод, ★5 выше досягаемости и искусство на пределе рукописи не получают");
        assertEquals(0.10D * MasteryRules.need(2), byArt.get(KIN_ART).amount(), EPS);
        assertEquals(Resonance.Link.KIN, byArt.get(KIN_ART).link());
        assertEquals(0.05D * MasteryRules.need(3, 12), byArt.get(SCHOOL_ART).amount(), EPS, "школа — как остальные: вдвое меньше родни (шаги: потолок 12)");
        assertEquals(0.05D * MasteryRules.need(1), byArt.get(BRAWLER).amount(), EPS);
        for (Resonance.Rush r : rush) {
            TechniqueProgress p = arts.get(r.art());
            assertTrue(r.amount() <= MasteryRules.HELP_CAP * MasteryRules.need(p.stars()) + EPS, "внутри потолка помощи");
        }
        // Третий рубеж, родня: ровно потолок, а не 25 %.
        List<Resonance.Rush> last = Resonance.rush(REAL, PLUM_24, 3, 12, arts);
        Resonance.Rush kin = last.stream().filter(r -> r.art().equals(KIN_ART)).findFirst().orElseThrow();
        assertEquals(0.20D * MasteryRules.need(2), kin.amount(), EPS);
    }

    @Test
    @DisplayName("Старт с высоты: родня ⌊звёзд/4⌋ до ★3, школа ⌊звёзд/6⌋ до ★2, остальные ничего")
    void startFromHeight() {
        assertEquals(1, Resonance.startHeight(REAL, KIN_ART, stars(PLUM_24, 6)));
        assertEquals(2, Resonance.startHeight(REAL, KIN_ART, stars(PLUM_24, 9)));
        assertEquals(3, Resonance.startHeight(REAL, KIN_ART, stars(PLUM_24, 12)));
        assertEquals(1, Resonance.startHeight(REAL, SCHOOL_ART, stars(PLUM_24, 6)));
        assertEquals(2, Resonance.startHeight(REAL, SCHOOL_ART, stars(PLUM_24, 12)));
        assertEquals(0, Resonance.startHeight(REAL, BRAWLER, stars(PLUM_24, 12)), "несвязанное искусство начинает с нуля");
        assertEquals(0, Resonance.startHeight(REAL, KIN_ART, stars(PLUM_24, 5)), "рубежа ещё нет");
        assertEquals(0, Resonance.startHeight(REAL, KIN_ART, stars(OTHER_ART, 12)), "продвинутое — не вершина");
        assertEquals(0, Resonance.startHeight(REAL, PLUM_24, stars(PLUM_24, 12)));
        assertEquals(3, Resonance.startHeight(REAL, KIN_ART, stars(PLUM_24, 12, WIND_GOD, 6, BRAWLER, 9)),
                "берётся наибольшая высота");
    }

    @Test
    @DisplayName("Рубеж срабатывает один раз: множество сработавших не даёт повторить")
    void milestoneFiresOnce() {
        assertEquals(List.of(1), Resonance.firing(PLUM_24, 12, List.of(5, 6), Set.of()));
        assertEquals(List.of(), Resonance.firing(PLUM_24, 12, List.of(5), Set.of()));
        assertEquals(List.of(), Resonance.firing(PLUM_24, 12, List.of(), Set.of()));
        assertEquals(List.of(), Resonance.firing(PLUM_24, 12, List.of(6), Set.of(Resonance.key(PLUM_24, 1))),
                "второй раз те же звёзды ничего не дают");
        assertEquals(List.of(2), Resonance.firing(PLUM_24, 12, List.of(9, 10), Set.of(Resonance.key(PLUM_24, 1))));
        assertEquals(List.of(1, 2, 3), Resonance.firing(PLUM_24, 12, List.of(6, 7, 8, 9, 10, 11, 12), Set.of()));
        assertEquals(List.of(1), Resonance.firing(WIND_GOD, 12, List.of(6), Set.of()));
        assertEquals(List.of(1), Resonance.firing(WIND_GOD, 12, List.of(6), Set.of(Resonance.key(PLUM_24, 1))),
                "рубеж другой вершины — не этой");
        assertEquals("murim:twenty_four_plum#3", Resonance.key(PLUM_24, 3));
    }

    @Test
    @DisplayName("Прорыв называет выученную родню вершины")
    void kinAmongNamesOnlyLearnedKin() {
        List<ResourceLocation> known = List.of(BRAWLER, KIN_ART, PLUM_24, Arts.methodArt(id("six_harmonies")));
        assertEquals(List.of(KIN_ART), Resonance.kinAmong(REAL, PLUM_24, known));
        assertEquals(List.of(), Resonance.kinAmong(REAL, BRAWLER, List.of(PLUM_24)));
    }
}
