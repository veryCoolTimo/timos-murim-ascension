package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.trade.PeddlerStock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Обмен заслуг и смотр учеников: чистые правила (docs/design/23-mount-hua-sect.md §6.1–6.2). */
class MeritShopTest {

    @Test
    @DisplayName("Цены: своим не дешевле рынка, серебро обратно хуже пожертвования, меч — несколько дней труда")
    void prices() {
        Set<String> ids = new HashSet<>();
        for (MeritShop.Offer o : MeritShop.OFFERS) {
            assertTrue(ids.add(o.id()), "дубль " + o.id());
            assertTrue(o.price() > 0, o.id());
            assertTrue(List.of("hyun_young", "un_gak").contains(o.seller()), o.id());
        }
        // Пожертвование: 5 лян серебра = 2 заслуги (economy §4) → заслуга ≈ 2,5 ляна; торговец — в вэнях.
        double wenPerMerit = 5.0D * 9.0D / 2.0D;
        MeritShop.Offer pill = MeritShop.offer("snow_plum").orElseThrow();
        assertTrue(pill.price() * wenPerMerit >= PeddlerStock.sellPrice("murim:pill_snow_plum"), "пилюля в секте дешевле рынка");
        MeritShop.Offer silver = MeritShop.offer("silver").orElseThrow();
        assertTrue(silver.count() / (double) silver.price() < 5.0D / 2.0D, "арбитраж: серебро за заслуги выгоднее пожертвования");
        MeritShop.Offer sword = MeritShop.offer("sword").orElseThrow();
        assertTrue(sword.price() >= 15 && sword.price() <= 30, "меч: " + sword.price());
        assertEquals(SectStanding.GRADUATE, MeritShop.offer("origin_energy").orElseThrow().need());
    }

    @Test
    @DisplayName("Трата не роняет положение; без заслуг, без положения и повторный пропуск — отказ")
    void check() {
        SectState s = SectState.NONE.joined().with(SectStanding.LESSON_ONE).contribute(30);
        MeritShop.Offer sword = MeritShop.offer("sword").orElseThrow();
        assertEquals(MeritShop.Result.OK, MeritShop.check(s, SectStanding.of(s, 1), sword));
        SectState after = s.spend(sword.price());
        assertEquals(30, after.contribution());
        assertEquals(10, after.merit());
        assertEquals(SectStanding.of(s, 1), SectStanding.of(after, 1), "положение упало");
        assertEquals(MeritShop.Result.MERIT, MeritShop.check(after, SectStanding.of(after, 1), sword));
        assertEquals(MeritShop.Result.STANDING, MeritShop.check(after, SectStanding.DISCIPLE, MeritShop.offer("origin_energy").orElseThrow()));
        assertEquals(MeritShop.Result.OUTSIDER, MeritShop.check(SectState.NONE, SectStanding.OUTSIDER, MeritShop.offer("silver").orElseThrow()));
        SectState pass = s.with("pass.scriptures");
        assertEquals(MeritShop.Result.OWNED, MeritShop.check(pass, SectStanding.of(pass, 1), MeritShop.offer("scriptures").orElseThrow()));
        // Штраф после траты: остаток не уходит ниже нуля.
        assertEquals(0, after.contribute(-25).merit());
    }

    @Test
    @DisplayName("Смотр раз в 7 дней, в окне занятий до совета; сетка из четырёх разных, игрок первым; зрители у площадки")
    void review() {
        int days = 0;
        for (long day = 0; day < 28; day++) {
            if (SectReview.reviewDay(day)) {
                days++;
                assertTrue(SectReview.window(day * 24000L + SectReview.FROM + 1));
                assertFalse(SectReview.window(day * 24000L + SectSchedule.COUNCIL_FROM));
                assertEquals(0, SectReview.daysUntil(day * 24000L + 100L));
            }
            List<String> b = SectReview.bracket(day, day % 2 == 0);
            assertEquals(4, b.size());
            assertEquals(4, new HashSet<>(b).size(), "дубль в сетке");
            if (day % 2 == 0) {
                assertEquals(SectReview.PLAYER, b.get(0));
            }
        }
        assertEquals(4, days);
        assertFalse(SectReview.bracket(6, false).equals(SectReview.bracket(13, false)), "каждую неделю одни и те же");
        // Зрители: ученики не на посту — у площадки поединков, места не совпадают.
        long reviewTime = 6L * 24000L + SectReview.FROM + 100L;
        Set<String> spots = new HashSet<>();
        for (SectRoster m : SectReview.crowd()) {
            SectSchedule.Task t = SectSchedule.task(m, reviewTime);
            if (SectRota.onDuty(m, reviewTime) || SectRota.afterNight(m, reviewTime)) {
                continue;
            }
            assertEquals(SectSchedule.Kind.WATCH, t.kind(), m.key());
            assertEquals("sparring", t.zone());
            assertTrue(spots.add(t.du() + "," + t.dv()), "место зрителя занято: " + m.key());
        }
        assertNotNull(SectReview.spectator(SectRoster.of("hyun_jong").orElseThrow()));
    }
}
