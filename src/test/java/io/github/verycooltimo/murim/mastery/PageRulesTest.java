package io.github.verycooltimo.murim.mastery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("Страница — десятая часть перехода, перечитанная книга — весь потолок помощи, а не целая звезда")
    void pageIsATenthBookIsAFifth() {
        assertEquals(0.10D, PageRules.PAGE_INSIGHT, 1.0E-12D);
        assertEquals(MasteryRules.HELP_CAP, PageRules.BOOK_INSIGHT, 1.0E-12D);
        assertEquals(PageRules.MAX_PAGES_PER_TRANSITION * PageRules.PAGE_INSIGHT, MasteryRules.HELP_CAP, 1.0E-12D,
                "две страницы наполняют потолок");
        double page = PageRules.insight(2, PageRules.PAGE_INSIGHT);
        assertEquals(0.10D * MasteryRules.need(2), page, 1.0E-9D);
        assertTrue(page > 0.0D && page < MasteryRules.need(2));
        // Книга на нулевой звезде — пятая часть первой звезды, новой звезды она не даёт.
        double book = PageRules.insight(0, PageRules.BOOK_INSIGHT);
        MasteryRules.Gain gain = MasteryRules.study(TechniqueProgress.learned(0, 5), book);
        assertEquals(0, gain.progress().stars());
        assertEquals(8.0D, gain.progress().progress(), 1.0E-9D);
    }

    @Test
    @DisplayName("Цена страницы 8 + 2 s: 10 / 20 / 32 заслуги на ★1 / ★6 / ★12")
    void pagePriceFollowsTheTargetStar() {
        assertEquals(10, PageRules.pagePrice(1));
        assertEquals(20, PageRules.pagePrice(6));
        assertEquals(32, PageRules.pagePrice(12));
        assertEquals(10, PageRules.pagePrice(0), "ниже ★1 цена как у ★1");
        assertEquals(32, PageRules.pagePrice(40), "выше ★12 цена как у ★12");
        for (int s = 1; s < 12; s++) {
            assertTrue(PageRules.pagePrice(s + 1) > PageRules.pagePrice(s));
        }
    }

    @Test
    @DisplayName("Не больше двух страниц на переход: продажа закрыта, когда потолок занят страницами в руках и помощью")
    void saleStopsAtTwoPages() {
        double cost = MasteryRules.need(3);
        double room = MasteryRules.HELP_CAP * cost;
        assertTrue(PageRules.saleOpen(room, cost, 0));
        assertTrue(PageRules.saleOpen(room, cost, 1), "вторая страница ещё помещается");
        assertFalse(PageRules.saleOpen(room, cost, 2), "третья — нет");
        // Урок съел половину потолка: страница помещается одна.
        double afterLesson = room - 0.10D * cost;
        assertTrue(PageRules.saleOpen(afterLesson, cost, 0));
        assertFalse(PageRules.saleOpen(afterLesson, cost, 1));
        assertFalse(PageRules.saleOpen(0.0D, cost, 0), "потолок исчерпан");
    }

    @Test
    @DisplayName("Озарение не поднимает выше предела рваной книги")
    void studyStopsAtCap() {
        MasteryRules.Gain gain = MasteryRules.study(TechniqueProgress.learned(1, 2), 1000.0D);
        assertEquals(2, gain.progress().stars());
        assertEquals(0.0D, gain.progress().progress(), 1.0E-9D);
    }
}
