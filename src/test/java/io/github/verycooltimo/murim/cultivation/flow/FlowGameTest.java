package io.github.verycooltimo.murim.cultivation.flow;

import io.github.verycooltimo.murim.cultivation.PillKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game «Провести поток»: the port of the approved mockup keeps its numbers and its feel (author 25.10). */
class FlowGameTest {

    private static FlowCharacter of(PillKind... kinds) {
        List<FlowSpec> parts = new ArrayList<>();
        for (PillKind k : kinds) {
            parts.add(k.flow());
        }
        return FlowCharacter.of(parts);
    }

    private static FlowGame run(FlowGame g, boolean bot, double seconds) {
        g.setAuto(bot);
        for (int i = 0; i < seconds * 60 && g.running(); i++) {
            g.update(1 / 60.0D);
        }
        return g;
    }

    @Test
    @DisplayName("Поток течёт сам: без рук он доходит, но налетает на закрытые места и давление растёт")
    void theStreamFlowsByItself() {
        FlowGame g = new FlowGame(of(PillKind.ORIGIN_ENERGY), 99L, false, false, false);
        run(g, false, 0.5D);
        assertTrue(g.s > 0.0D, "the clot did not move without a hand");
        FlowGame whole = run(new FlowGame(of(PillKind.ORIGIN_ENERGY), 99L, false, false, false), false, 60.0D);
        assertFalse(whole.running(), "the game never ended");
        assertTrue(whole.lost > 0.0D || whole.state == FlowGame.State.TIMEOUT || whole.state == FlowGame.State.BURST,
                "an unrestrained stream through three closed spots and a fork lost nothing");
    }

    @Test
    @DisplayName("Держать поток замедляет его до десятой части и понемногу копит давление")
    void holdingRestrainsAndBuildsPressure() {
        FlowGame free = new FlowGame(of(PillKind.VITALITY), 5L, false, false, false);
        FlowGame held = new FlowGame(of(PillKind.VITALITY), 5L, false, false, false);
        held.hold = true;
        for (int i = 0; i < 120; i++) {
            free.update(1 / 60.0D);
            held.update(1 / 60.0D);
        }
        assertTrue(held.s < free.s * 0.2D, "restrained " + held.s + " against free " + free.s);
        assertTrue(held.pressure > 8.0D, "holding built no back-pressure: " + held.pressure);
        // nobody holds for ever
        FlowGame forever = new FlowGame(of(PillKind.VITALITY), 5L, false, false, false);
        forever.hold = true;
        for (int i = 0; i < 60 * 30 && forever.running(); i++) {
            forever.update(1 / 60.0D);
        }
        assertTrue(forever.state == FlowGame.State.BURST || forever.state == FlowGame.State.TIMEOUT, "held for ever: " + forever.state);
    }

    @Test
    @DisplayName("Чистый прогон занимает 50-70 % времени растворения и даёт 100 % по всей таблице пилюль")
    void aCleanRunTakesAboutHalfOfTheTime() {
        PillKind[] qi = {PillKind.VITALITY, PillKind.SNOW_PLUM, PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON, PillKind.BLUE,
                PillKind.BLOOD, PillKind.BEAUTY_TEAR, PillKind.THOUSAND_GINSENG, PillKind.THOUSAND_LINGZHI};
        for (PillKind kind : qi) {
            for (boolean hard : new boolean[] {false, true}) {
                for (long seed = 1; seed <= 12; seed++) {
                    FlowGame g = run(new FlowGame(of(kind), seed * 7919L, hard, false, false), true, 120.0D);
                    assertEquals(FlowGame.State.DONE, g.state, kind + " seed " + seed + " hard " + hard + " ended " + g.state);
                    assertTrue(g.t / g.time > 0.42D && g.t / g.time < 0.82D, kind + " took " + g.t + " of " + g.time + " s");
                    assertEquals(100.0D, g.quality(), 1.0E-9D, kind + " seed " + seed + " lost " + g.lost);
                }
            }
        }
    }

    @Test
    @DisplayName("Качество: чистое 40-100, по таймеру пропорционально пройденному, обрыв 10")
    void theQuality() {
        assertEquals(100.0D, FlowGame.quality(FlowGame.State.DONE, 1.0D, 0.0D));
        assertEquals(40.0D, FlowGame.quality(FlowGame.State.DONE, 1.0D, 90.0D));
        assertEquals(40.0D, FlowGame.quality(FlowGame.State.DONE, 1.0D, 1000.0D));
        assertEquals(74.0D, FlowGame.quality(FlowGame.State.DONE, 1.0D, 26.0D));
        assertEquals(Math.round(80.0D * 0.5D * 0.8D), FlowGame.quality(FlowGame.State.TIMEOUT, 0.5D, 20.0D));
        assertEquals(0.0D, FlowGame.quality(FlowGame.State.TIMEOUT, 0.0D, 0.0D));
        assertEquals(10.0D, FlowGame.quality(FlowGame.State.BURST, 0.3D, 50.0D));
        assertEquals(0.0D, FlowGame.quality(FlowGame.State.TORN, 0.0D, 0.0D));
    }

    @Test
    @DisplayName("Одно зерно - один и тот же поток; зёрна разные - потоки разные")
    void theSeedDecidesTheStream() {
        FlowGame a = new FlowGame(of(PillKind.BEAUTY_TEAR), 1234L, false, false, false);
        FlowGame b = new FlowGame(of(PillKind.BEAUTY_TEAR), 1234L, false, false, false);
        FlowGame c = new FlowGame(of(PillKind.BEAUTY_TEAR), 4321L, false, false, false);
        assertEquals(a.narrows.get(0).s, b.narrows.get(0).s);
        assertEquals(a.forks.get(1).good, b.forks.get(1).good);
        assertNotEquals(a.narrows.get(0).s, c.narrows.get(0).s);
        assertEquals(PillKind.BEAUTY_TEAR.flow().narrows(), a.narrows.size());
        assertEquals(PillKind.BEAUTY_TEAR.flow().forks(), a.forks.size());
        for (FlowGame.Fork f : a.forks) {
            // the key of the light branch is the key of the screen side it lies on
            assertEquals(f.good == f.leftSide ? -1 : 1, f.goodKey);
            assertEquals(f.leftSide, f.sideOf(-1));
            assertEquals(-f.leftSide, f.sideOf(1));
        }
        assertEquals(3, a.poison.size(), "poison pills have three burning spans");
        assertTrue(new FlowGame(of(PillKind.ORIGIN_ENERGY), 1L, false, false, false).poison.isEmpty());
    }

    @Test
    @DisplayName("Развилка: не тот поворот - завал, откат и давление; верный - дальше")
    void aWrongForkIsABlockage() {
        FlowGame g = new FlowGame(of(PillKind.ORIGIN_ENERGY), 31L, false, false, false);
        FlowGame.Fork f = g.forks.get(0);
        g.side = -f.goodKey;
        for (int i = 0; i < 60 * 40 && !f.done && g.running(); i++) {
            g.update(1 / 60.0D);
            if (g.s > f.s - 0.2D) {
                g.hold = false;
            }
        }
        assertTrue(f.wrong || !g.running(), "the wrong branch did not block the stream");
        FlowGame right = new FlowGame(of(PillKind.ORIGIN_ENERGY), 31L, false, false, false);
        right.side = right.forks.get(0).goodKey;
        right.setAuto(true);
        for (int i = 0; i < 60 * 40 && !right.forks.get(0).done && right.running(); i++) {
            right.update(1 / 60.0D);
        }
        assertFalse(right.forks.get(0).wrong, "the light branch blocked the stream");
    }

    @Test
    @DisplayName("Несколько пилюль в один поток: характеры складываются как в макете")
    void severalPillsMerge() {
        FlowCharacter two = of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON);
        assertEquals(2, two.count());
        assertEquals(1.15D, two.pulse(), 1.0E-9D);
        assertEquals(4, two.narrows());
        assertEquals(0.6D, two.wild(), 1.0E-9D);
        assertEquals(1, two.forks());
        assertTrue(two.poison() && !two.cold() && !two.hot() && !two.clash());
        assertEquals(26, two.time());
        FlowCharacter three = of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON, PillKind.BEAUTY_TEAR);
        assertEquals(3, three.count());
        assertEquals(3, three.forks(), "a third pill adds a fork: 2 + 1");
        assertEquals(34, three.time(), "the longest 26 s and four seconds for each extra pill");
        // cold and hot in one stream argue: more spots, faster pulses, wilder
        FlowCharacter clash = of(PillKind.BLUE, PillKind.BLOOD);
        assertTrue(clash.clash() && clash.cold() && clash.hot());
        assertEquals(1.0D * 0.8D, clash.pulse(), 1.0E-9D);
        assertEquals(4 + 1 + 1, clash.narrows());
        assertEquals(Math.min(1.2D, 0.75D + 0.15D + 0.2D), clash.wild(), 1.0E-9D);
        // the clot is the first pill, the others orbit it
        assertEquals(PillKind.ORIGIN_ENERGY.flow().colour(), two.colour());
        assertEquals(2, two.colours().length);
        // a merged run can still be played clean
        FlowGame g = run(new FlowGame(two, 77L, false, false, false), true, 120.0D);
        assertEquals(FlowGame.State.DONE, g.state);
    }

    @Test
    @DisplayName("Холод замедляет поток, жар и яд - свои; яд жжёт при долгом течении")
    void coldHotAndPoison() {
        FlowSpec plain = new FlowSpec(0xFFFFFF, 0xFFFFFF, 1.5D, 0, 0.0D, 0, FlowSpec.Special.NONE, 1.0D, 20);
        FlowSpec cold = new FlowSpec(0xFFFFFF, 0xFFFFFF, 1.5D, 0, 0.0D, 0, FlowSpec.Special.COLD, 1.0D, 20);
        FlowSpec poison = new FlowSpec(0xFFFFFF, 0xFFFFFF, 1.5D, 0, 0.0D, 0, FlowSpec.Special.POISON, 1.0D, 20);
        FlowGame a = new FlowGame(FlowCharacter.of(List.of(plain)), 3L, false, false, false);
        FlowGame b = new FlowGame(FlowCharacter.of(List.of(cold)), 3L, false, false, false);
        for (int i = 0; i < 60; i++) {
            a.update(1 / 60.0D);
            b.update(1 / 60.0D);
        }
        assertEquals(2.1D / 20.0D, a.s, 0.003D, "the stream speed is 2.1 / time");
        assertEquals(a.s * 0.85D, b.s, 0.003D, "cold is not 15 % slower");
        // a stream that has run long inside a poison span burns; a short one does not
        FlowGame p = new FlowGame(FlowCharacter.of(List.of(poison)), 8L, false, false, false);
        double[] span = p.poison.get(0);
        p.s = (span[0] + span[1]) / 2;
        p.flowT = 1.0D;
        p.update(0.05D);
        assertTrue(p.pressure > 2.0D && p.lost > 0.9D, "a poison span did not burn: " + p.pressure + " / " + p.lost);
        FlowGame q = new FlowGame(FlowCharacter.of(List.of(poison)), 8L, false, false, false);
        q.s = (span[0] + span[1]) / 2;
        q.flowT = 0.0D;
        q.update(0.05D);
        assertEquals(0.0D, q.lost, 1.0E-9D, "a stream that has just been restrained burned");
        assertTrue(FlowCharacter.of(List.of(PillKind.BLOOD.flow())).hot());
    }

    @Test
    @DisplayName("Сломанное тело: поток не идёт, идёт только картина разрыва")
    void aTornBodyPlaysNothing() {
        FlowGame g = new FlowGame(of(PillKind.ORIGIN_ENERGY), 1L, false, true, false);
        assertEquals(FlowGame.State.TORN, g.state);
        g.update(0.5D);
        assertEquals(0.0D, g.s);
        assertEquals(FlowGame.State.TORN, g.state);
        assertTrue(g.end > 0.4D);
    }

    @Test
    @DisplayName("Прерванная сервером игра останавливается и ничего больше не считает")
    void abortStops() {
        FlowGame g = new FlowGame(of(PillKind.ORIGIN_ENERGY), 1L, false, false, false);
        g.update(0.1D);
        g.abort();
        assertEquals(FlowGame.State.TIMEOUT, g.state);
        double s = g.s;
        g.update(1.0D);
        assertEquals(s, g.s);
    }

    @Test
    @DisplayName("Метод с природой яда легче усваивает ядовитые пилюли: реже срывы, выше качество без рук")
    void poisonNatureIsEasier() {
        int normal = 0;
        int easy = 0;
        double normalQuality = 0.0D;
        double easyQuality = 0.0D;
        for (long seed = 1; seed <= 40; seed++) {
            FlowGame a = new FlowGame(of(PillKind.THOUSAND_POISON), seed, false, false, false);
            FlowGame b = new FlowGame(of(PillKind.THOUSAND_POISON), seed, false, false, true);
            // never restraining: the stream runs through everything
            for (int i = 0; i < 60 * 40 && a.running(); i++) {
                a.update(1 / 60.0D);
            }
            for (int i = 0; i < 60 * 40 && b.running(); i++) {
                b.update(1 / 60.0D);
            }
            normal += a.state == FlowGame.State.BURST ? 1 : 0;
            easy += b.state == FlowGame.State.BURST ? 1 : 0;
            normalQuality += a.quality();
            easyQuality += b.quality();
        }
        assertTrue(easy < normal, "the poison nature did not help: " + easy + " bursts against " + normal);
        assertTrue(easyQuality > normalQuality, "the poison nature did not raise the quality: " + easyQuality + " against " + normalQuality);
        // it does not help with other pills
        FlowGame fire = new FlowGame(of(PillKind.BLOOD), 5L, false, false, true);
        FlowGame same = new FlowGame(of(PillKind.BLOOD), 5L, false, false, false);
        for (int i = 0; i < 600; i++) {
            fire.update(1 / 60.0D);
            same.update(1 / 60.0D);
        }
        assertEquals(same.s, fire.s, 1.0E-12D);
        assertEquals(same.pressure, fire.pressure, 1.0E-12D);
    }
}
