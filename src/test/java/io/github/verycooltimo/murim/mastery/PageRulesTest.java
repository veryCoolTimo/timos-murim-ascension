package io.github.verycooltimo.murim.mastery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Обрывки и дубликаты (docs/design/24-bandit-camp.md §3): ни одна находка не бесполезна. */
class PageRulesTest {

    @Test
    @DisplayName("Незнакомая книга: три страницы сшиваются, меньше — только счёт")
    void unknownBinds() {
        assertEquals(PageRules.Outcome.NEED_MORE, PageRules.page(false, 2, 0, 0, 0, false));
        assertEquals(PageRules.Outcome.BIND, PageRules.page(false, 3, 0, 0, 0, false));
        assertEquals(PageRules.Outcome.BIND, PageRules.page(false, 7, 0, 0, 0, false));
    }

    @Test
    @DisplayName("Рваная знакомая книга: страница продолжает её на слой, не выше полного")
    void shallowDeepens() {
        assertEquals(PageRules.Outcome.DEEPEN, PageRules.page(true, 1, 2, 5, 2, false));
        assertEquals(3, PageRules.deepen(2, 5));
        assertEquals(5, PageRules.deepen(5, 5));
        // Неполный предел у другой формы того же стиля — тоже продолжение.
        assertEquals(PageRules.Outcome.DEEPEN, PageRules.page(true, 1, 8, 8, 3, true));
    }

    @Test
    @DisplayName("Полная знакомая книга: озарение, а освоенная до конца — мудрость")
    void fullGivesInsightThenWisdom() {
        assertEquals(PageRules.Outcome.INSIGHT, PageRules.page(true, 1, 5, 5, 3, false));
        assertEquals(PageRules.Outcome.WISDOM, PageRules.page(true, 1, 5, 5, 5, false));
        assertEquals(PageRules.Outcome.INSIGHT, PageRules.reread(5, 0));
        assertEquals(PageRules.Outcome.WISDOM, PageRules.reread(5, 5));
    }

    @Test
    @DisplayName("Озарение от книги — целый слой: на нулевом слое его хватает на новый слой")
    void bookInsightIsALayer() {
        double amount = PageRules.insight(0, PageRules.BOOK_INSIGHT);
        MasteryRules.Gain gain = MasteryRules.study(TechniqueProgress.learned(0, 5), amount, 0L);
        assertEquals(1, gain.progress().layer());
        assertEquals(1, gain.layersReached().size());
        // Страница — меньше слоя, но не ноль.
        double page = PageRules.insight(2, PageRules.PAGE_INSIGHT);
        assertTrue(page > 0.0D && page < MasteryRules.need(2));
    }

    @Test
    @DisplayName("Озарение не поднимает выше предела рваной книги")
    void studyStopsAtCap() {
        MasteryRules.Gain gain = MasteryRules.study(TechniqueProgress.learned(1, 2), 1000.0D, 0L);
        assertEquals(2, gain.progress().layer());
        assertEquals(0.0D, gain.progress().progress(), 1.0E-9D);
    }
}
