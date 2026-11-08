package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track A of the Mount Hua plan (hua-main-1810 §5, §7, decisions of 18.10): the table of experience per star, the 80 / 20
 * split with the reserve, and the ONE shared help pool of every transition.
 */
class MasteryTableTest {

    private static final double EPS = 1.0E-9D;
    private static final double[] CUMULATIVE = {0, 40, 120, 280, 520, 850, 1300, 1900, 2700, 3600, 4700, 5900, 7200};

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("murim", name);
    }

    @Test
    @DisplayName("Таблица автора: суммарный опыт до ★1…★12 — 40/120/280/520/850/1300/1900/2700/3600/4700/5900/7200")
    void cumulativeTable() {
        for (int stars = 0; stars <= 12; stars++) {
            assertEquals(CUMULATIVE[stars], MasteryRules.cumulative(stars, 12), EPS, "★" + stars);
        }
        double sum = 0.0D;
        for (int stars = 0; stars < 12; stars++) {
            assertEquals(CUMULATIVE[stars + 1] - CUMULATIVE[stars], MasteryRules.need(stars, 12), EPS, "переход в ★" + (stars + 1));
            assertEquals(MasteryRules.need(stars), MasteryRules.need(stars, 12), EPS);
            sum += MasteryRules.need(stars, 12);
        }
        assertEquals(7200.0D, sum, EPS);
        assertEquals(7200.0D, MasteryRules.TOTAL_XP, EPS);
        double lastTwo = MasteryRules.need(10, 12) + MasteryRules.need(11, 12);
        assertEquals(0.347D, lastTwo / MasteryRules.TOTAL_XP, 0.001D, "последние две звезды — 34,7 % опыта");
        for (int stars = 1; stars < 12; stars++) {
            assertTrue(MasteryRules.need(stars, 12) > MasteryRules.need(stars - 1, 12), "цена растёт с каждой звездой");
        }
        assertEquals(MasteryRules.need(11, 12), MasteryRules.need(40, 12), EPS, "за пределом искусства — цена последней звезды");
    }

    @Test
    @DisplayName("Шаги: общие 12 звёзд, 7200 опыта и озарение для последних двух")
    void footworkUsesCommonTwelveStarCurve() {
        int cap = Arts.cap(id("wind_god_steps"));
        assertEquals(12, cap);
        assertEquals(7200.0D, MasteryRules.cumulative(cap, cap), EPS);
        assertEquals(10, MasteryRules.insightStar(cap));
        assertEquals(MasteryRules.need(10, 12), MasteryRules.need(10, cap), EPS);
        double last = (MasteryRules.cumulative(12, cap) - MasteryRules.cumulative(10, cap))
                / MasteryRules.cumulative(12, cap);
        assertEquals(0.347D, last, 0.001D);
    }

    @Test
    @DisplayName("80 % заработанного сразу, 20 % в резерв; резерв не сгорает при росте звезды и от любых «суток»")
    void eightyTwentySplit() {
        TechniqueProgress p = TechniqueProgress.learned(0, 12);
        MasteryRules.Gain gain = MasteryRules.experience(p, MasteryRules.Source.FIGHT, 100.0D, 0.0D);
        assertEquals(80.0D * 1.0D, gain.progress().progress() + MasteryRules.cumulative(gain.progress().stars(), 12), EPS);
        assertEquals(20.0D, gain.progress().unprocessed(), EPS);
        // Звезда выросла, резерв остался.
        assertTrue(gain.progress().stars() >= 1);
        assertEquals(20.0D, gain.progress().unprocessed(), EPS);
        // Широта и Эхо вершины применяются один раз, при заработке, а не при обработке резерва.
        MasteryRules.Gain wise = MasteryRules.experience(p, MasteryRules.Source.FIGHT, 100.0D, 10.0D, 0.10D, 12);
        assertEquals(100.0D * MasteryRules.practiceFactor(10.0D, 0.10D) * 0.2D, wise.progress().unprocessed(), EPS);
        TechniqueProgress processed = MasteryRules.process(wise.progress(), 12).progress();
        double total = MasteryRules.cumulative(processed.stars(), 12) + processed.progress();
        assertEquals(100.0D * MasteryRules.practiceFactor(10.0D, 0.10D), total, 1.0E-6D, "ни одного очка сверх заработанного");
        assertEquals(0.0D, processed.unprocessed(), EPS);
    }

    @Test
    @DisplayName("Законченное искусство резерва не копит")
    void finishedArtKeepsNoReserve() {
        TechniqueProgress done = new TechniqueProgress(12, 0, 0, 0, 12);
        assertEquals(0.0D, MasteryRules.experience(done, MasteryRules.Source.FIGHT, 100.0D, 0.0D).progress().unprocessed(), EPS);
        TechniqueProgress torn = new TechniqueProgress(3, 0, 0, 0, 3);
        assertEquals(0.0D, MasteryRules.experience(torn, MasteryRules.Source.FIGHT, 100.0D, 0.0D).progress().unprocessed(), EPS,
                "рваная книга на пределе: осмысливать нечего");
    }

    @Test
    @DisplayName("Эхо упёршегося в стену искусства снимает стену и обрабатывает резерв за один раз")
    void echoReleasesTheWall() {
        TechniqueProgress atWall = new TechniqueProgress(10, MasteryRules.need(10, 12), 30.0D, 0, 12);
        MasteryRules.Gain gain = MasteryRules.process(atWall, 12);
        assertTrue(gain.progress().stars() >= 11, "стена снята");
        assertEquals(0.0D, gain.progress().unprocessed(), EPS);
        assertFalse(gain.starsReached().isEmpty());
        // Без резерва и без накопленного — ничего не меняется.
        TechniqueProgress calm = new TechniqueProgress(4, 10.0D, 0.0D, 0, 12);
        assertSame(calm, MasteryRules.process(calm, 12).progress());
    }

    // ---------------------------------------------------------------------------------- the help pool

    @Test
    @DisplayName("Один общий потолок: страницы + урок + чтение + наставление + Рывок вместе не больше 20 % перехода")
    void oneSharedHelpPool() {
        TechniqueProgress p = TechniqueProgress.learned(3, 12);
        double need = MasteryRules.need(3, 12);
        double granted = 0.0D;
        for (MasteryRules.HelpSource source : MasteryRules.HelpSource.values()) {
            MasteryRules.Helped helped = MasteryRules.help(p, 0.10D * need, false, 12);
            granted += helped.granted();
            p = helped.gain().progress();
        }
        assertEquals(0.20D * need, granted, EPS, "пять источников по 10 % дали ровно 20 %");
        assertEquals(0.20D * need, p.help(), EPS);
        assertEquals(0.20D * need, p.progress(), EPS);
        assertEquals(0.0D, MasteryRules.helpRoom(p, 12), EPS);
        // Сверх потолка — ни очка.
        MasteryRules.Helped more = MasteryRules.help(p, need, false, 12);
        assertEquals(0.0D, more.granted(), EPS);
        assertEquals(p, more.gain().progress());
    }

    @Test
    @DisplayName("Потолок считается на каждый переход заново: после новой звезды пул пуст, пройденное не переносится")
    void poolStartsAgainAtEveryStar() {
        TechniqueProgress p = TechniqueProgress.learned(3, 12);
        p = MasteryRules.help(p, 1000.0D, false, 12).gain().progress();
        assertEquals(0.20D * MasteryRules.need(3, 12), p.help(), EPS);
        // Своя практика переводит на ★4: пул нового перехода пуст.
        p = MasteryRules.experience(p, MasteryRules.Source.FIGHT, MasteryRules.need(3, 12) * 1.01D, 0.0D).progress();
        assertEquals(4, p.stars());
        assertEquals(0.0D, p.help(), EPS);
        assertEquals(0.20D * MasteryRules.need(4, 12), MasteryRules.helpRoom(p, 12), EPS);
        // Помощь, пересёкшая границу звезды: та её часть, что перешла за границу, считается в пул НОВОГО перехода.
        TechniqueProgress edge = new TechniqueProgress(3, MasteryRules.need(3, 12) - 1.0D, 0, 0, 12);
        MasteryRules.Helped over = MasteryRules.help(edge, 1000.0D, false, 12);
        assertEquals(4, over.gain().progress().stars());
        double granted = 0.20D * MasteryRules.need(3, 12);
        assertEquals(granted, over.granted(), EPS);
        assertEquals(granted - 1.0D, over.gain().progress().help(), EPS, "всё, что за границей, — помощь нового перехода");
        assertEquals(granted - 1.0D, over.gain().progress().progress(), EPS);
    }

    @Test
    @DisplayName("Помощь через границу звезды не даёт перехода больше пятой части (пример ревью: 39 из 40, страница 4)")
    void helpOverTheEdgeCannotExceedAFifthInTheNextTransition() {
        TechniqueProgress p = new TechniqueProgress(0, 39.0D, 0, 0, 12);
        MasteryRules.Helped page = MasteryRules.help(p, 4.0D, false, 12);
        assertEquals(1, page.gain().progress().stars());
        assertEquals(3.0D, page.gain().progress().progress(), EPS);
        assertEquals(3.0D, page.gain().progress().help(), EPS, "три из четырёх ушли в новую звезду");
        // В новой звезде (80 опыта) пул 16: три уже заняты, ещё можно 13, и ни одним больше.
        MasteryRules.Helped more = MasteryRules.help(page.gain().progress(), 100.0D, false, 12);
        assertEquals(13.0D, more.granted(), EPS);
        assertEquals(0.20D * MasteryRules.need(1, 12), more.gain().progress().help(), EPS);
        // Своя практика, пересёкшая границу, помощью не считается.
        TechniqueProgress own = MasteryRules.experience(new TechniqueProgress(0, 39.0D, 0, 0, 12),
                MasteryRules.Source.FIGHT, 20.0D, 0.0D).progress();
        assertEquals(1, own.stars());
        assertEquals(0.0D, own.help(), EPS);
    }

    @Test
    @DisplayName("Резерв не сгорает у предела рукописи: переходит только то, что искусство ещё может принять")
    void reserveWaitsAtTheCapOfATornManuscript() {
        // Рваная книга до ★2: на ★1 прогресс 79 из 80, резерв 10 — принимается 1 (до предела), 9 остаётся.
        TechniqueProgress torn = new TechniqueProgress(1, 79.0D, 10.0D, 0, 2);
        MasteryRules.Gain gain = MasteryRules.process(torn, 12);
        assertEquals(2, gain.progress().stars());
        assertEquals(0.0D, gain.progress().progress(), EPS);
        assertEquals(9.0D, gain.progress().unprocessed(), EPS);
        // На пределе резерв не трогается вовсе и ждёт продолжения книги.
        TechniqueProgress atCap = new TechniqueProgress(2, 0.0D, 9.0D, 0, 2);
        assertSame(atCap, MasteryRules.process(atCap, 12).progress());
        // Страница продолжила книгу: резерв ещё здесь и обрабатывается.
        TechniqueProgress deepened = atCap.withCap(3);
        assertEquals(0.0D, MasteryRules.process(deepened, 12).progress().unprocessed(), EPS);
        // Целое искусство: на последней звезде всё помещается и резерв пуст.
        TechniqueProgress last = new TechniqueProgress(11, 0.0D, 50.0D, 0, 12);
        assertEquals(0.0D, MasteryRules.process(last, 12).progress().unprocessed(), EPS);
        TechniqueProgress done = new TechniqueProgress(12, 0.0D, 7.0D, 0, 12);
        assertEquals(7.0D, MasteryRules.process(done, 12).progress().unprocessed(), EPS, "законченное искусство ничего не принимает");
    }

    @Test
    @DisplayName("Помощь частично: остаток потолка отдаётся, лишнее отсекается; у законченного искусства потолка нет")
    void partialGrantAndFinishedArt() {
        TechniqueProgress p = TechniqueProgress.learned(5, 12);
        double need = MasteryRules.need(5, 12);
        TechniqueProgress half = MasteryRules.help(p, 0.15D * need, false, 12).gain().progress();
        MasteryRules.Helped rest = MasteryRules.help(half, 0.15D * need, false, 12);
        assertEquals(0.05D * need, rest.granted(), EPS, "из второй пачки вошло только то, что поместилось");
        TechniqueProgress done = new TechniqueProgress(12, 0, 0, 0, 12);
        assertEquals(0.0D, MasteryRules.helpRoom(done, 12), EPS);
        assertEquals(0.0D, MasteryRules.help(done, 100.0D, false, 12).granted(), EPS);
        TechniqueProgress torn = new TechniqueProgress(3, 0, 0, 0, 3);
        assertEquals(0.0D, MasteryRules.helpRoom(torn, 12), EPS, "рваная книга на пределе");
    }

    @Test
    @DisplayName("Урок сверх потолка не даёт опыта, но озарение мастера всё равно снимает стену")
    void insightOfALessonSurvivesAnEmptyPool() {
        double need = MasteryRules.need(10, 12);
        TechniqueProgress full = new TechniqueProgress(10, need, 0, 0, 12, TechniqueProgress.VERSION, 0.20D * need);
        MasteryRules.Helped plain = MasteryRules.help(full, need, false, 12);
        assertEquals(0.0D, plain.granted(), EPS);
        assertEquals(10, plain.gain().progress().stars(), "страница стену не снимает");
        MasteryRules.Helped lesson = MasteryRules.help(full, need, true, 12);
        assertEquals(0.0D, lesson.granted(), EPS, "опыта нет");
        assertEquals(11, lesson.gain().progress().stars(), "не опытные эффекты урока остаются");
    }

    @Test
    @DisplayName("Помощь не умножается мудростью: доля перехода одна и та же")
    void helpIsNotMultiplied() {
        TechniqueProgress p = TechniqueProgress.learned(2, 12);
        double need = MasteryRules.need(2, 12);
        assertEquals(0.10D * need, MasteryRules.help(p, 0.10D * need, false, 12).granted(), EPS);
    }

    // ---------------------------------------------------------------------------------- saves

    @Test
    @DisplayName("Миграция v4 → v5: звёзды искусства те же, доля внутри звезды та же, пересчитана в таблицу; второй раз не трогает")
    void versionFourMigratesToTheTable() {
        ResourceLocation form = id("seven_plum_basic");
        for (int stars = 0; stars < 12; stars++) {
            double legacy = MasteryRules.legacyNeed(stars);
            TechniqueProgress raw = new TechniqueProgress(stars, legacy * 0.4D, legacy * 0.25D, 9, 12, 4);
            TechniqueProgress migrated = raw.migrate(form);
            assertEquals(stars, migrated.stars(), "звёзд столько же");
            assertEquals(0.4D, migrated.progress() / MasteryRules.need(stars, 12), 1.0E-9D, "доля внутри звезды");
            assertEquals(0.25D, migrated.unprocessed() / MasteryRules.need(stars, 12), 1.0E-9D, "доля резерва");
            assertEquals(12, migrated.cap());
            assertEquals(TechniqueProgress.VERSION, migrated.version());
            assertEquals(0.0D, migrated.help(), EPS);
            assertSame(migrated, migrated.migrate(form), "второй раз миграция ничего не делает");
        }
        // Шаги: пересчёт по общим 12 звёздам.
        TechniqueProgress steps = new TechniqueProgress(7, MasteryRules.legacyNeed(7) * 0.5D, 0, 0, 10, 4)
                .migrate(id("wind_god_steps"));
        assertEquals(7, steps.stars());
        assertEquals(0.5D, steps.progress() / MasteryRules.need(7, 12), 1.0E-9D);
    }

    @Test
    @DisplayName("Сохранение v4 целиком: звёзды каждого искусства сохранены, файл переписывается как v5 и читается тем же")
    void wholeSaveKeepsEveryStarCount() {
        String json = """
                {"version": 4, "arts": {
                   "murim:seven_plum": {"version": 4, "stars": 8, "progress": 30, "unprocessed": 6, "day": 11, "cap": 12},
                   "murim:wind_god": {"version": 4, "stars": 5, "progress": 3, "unprocessed": 1, "day": 11, "cap": 10},
                   "murim:method/six_harmonies": {"version": 4, "stars": 2, "progress": 1, "cap": 12}},
                 "known": ["murim:seven_plum_basic", "murim:wind_god_steps", "murim:method/six_harmonies"], "wisdom": 5}
                """;
        MasteryState state = MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        assertEquals(8, state.progress(id("seven_plum")).stars());
        assertEquals(5, state.progress(id("wind_god")).stars());
        assertEquals(2, state.progress(id("method/six_harmonies")).stars());
        assertEquals(30.0D / MasteryRules.legacyNeed(8), state.progress(id("seven_plum")).progress() / MasteryRules.need(8, 12), EPS);
        assertEquals(6.0D / MasteryRules.legacyNeed(8), state.progress(id("seven_plum")).unprocessed() / MasteryRules.need(8, 12), EPS);
        assertEquals(3.0D / MasteryRules.legacyNeed(5), state.progress(id("wind_god")).progress() / MasteryRules.need(5, 12), EPS);
        JsonObject saved = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().getAsJsonObject();
        assertEquals(TechniqueProgress.VERSION, saved.get("version").getAsInt());
        assertEquals(state, MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
    }

    @Test
    @DisplayName("Потолок помощи живёт в состоянии освоения и переживает запись и чтение; в старом файле его нет")
    void helpPoolIsSaved() {
        ResourceLocation art = id("seven_plum");
        TechniqueProgress helped = MasteryRules.help(TechniqueProgress.learned(4, 12), 1000.0D, false, 12).gain().progress();
        MasteryState state = MasteryState.EMPTY.with(id("seven_plum_basic"), helped);
        JsonObject saved = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().getAsJsonObject();
        MasteryState back = MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(helped.help(), back.progress(art).help(), EPS);
        assertEquals(0.20D * MasteryRules.need(4, 12), back.progress(art).help(), EPS);
        assertEquals(helped, back.progress(art));
        assertFalse(back.progress(art).help() <= 0.0D);
        MasteryState old = MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"version\":5,\"arts\":{\"murim:seven_plum\":{\"version\":5,\"stars\":2,\"progress\":1,\"cap\":12}},"
                        + "\"known\":[\"murim:seven_plum_basic\"]}")).getOrThrow();
        assertEquals(0.0D, old.progress(art).help(), EPS);
        // Любое изменение состояния несёт пул с собой.
        assertEquals(helped.help(), state.with(id("seven_plum_basic"), helped).progress(art).help(), EPS);
        assertEquals(List.of(), state.comprehended().stream().toList());
    }
}
