package io.github.verycooltimo.murim.training;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordinary play becomes body points (docs/design/27-body-training.md): rates, daily limits, the one-off initial grant and
 * the pacing of the breakthrough gates against typical play. The pacing windows are the MVP "full ★12 ≈ 12 h" of
 * docs/design/41-economy-pacing-mvp.md; the player personas are assumptions ([НЕПРОВЕРЕНО: first live hour on the Mac
 * should replace them]).
 */
class BodyActivityTest {

    private static Map<BodyProbe, Long> stats(Object... kv) {
        Map<BodyProbe, Long> m = new EnumMap<>(BodyProbe.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((BodyProbe) kv[i], ((Number) kv[i + 1]).longValue());
        }
        return m;
    }

    private static Map<BodyProbe, Long> plus(Map<BodyProbe, Long> a, Map<BodyProbe, Long> b) {
        Map<BodyProbe, Long> m = new EnumMap<>(BodyProbe.class);
        for (BodyProbe p : BodyProbe.values()) {
            m.put(p, a.getOrDefault(p, 0L) + b.getOrDefault(p, 0L));
        }
        return m;
    }

    private static BodyState seeded() {
        return BodyActivity.seed(BodyState.NONE, 1L, stats());
    }

    // ------------------------------------------------------------------ rates

    @Test
    @DisplayName("Ставки: километр шага, спринта, прыжок, камень, руда, бревно, убийство — как в таблице; сумма — в нужный вид работы")
    void rates() {
        BodyState s = seeded();
        s = BodyActivity.advance(s, 1L, stats(BodyProbe.WALK, 100_000, BodyProbe.SPRINT, 100_000, BodyProbe.JUMP, 100,
                BodyProbe.STONE, 100, BodyProbe.ORE, 10, BodyProbe.LOG, 10, BodyProbe.KILLS, 5, BodyProbe.DAMAGE, 10_000), true);
        assertEquals(TrainingBalance.WALK_PER_KM + TrainingBalance.SPRINT_PER_KM, s.rawOf(BodySource.LEGS), 1e-9);
        assertEquals(100 * TrainingBalance.JUMP_EACH, s.rawOf(BodySource.JUMP), 1e-9);
        assertEquals(100 * TrainingBalance.MINE_STONE + 10 * TrainingBalance.MINE_ORE + 10 * TrainingBalance.MINE_LOG,
                s.rawOf(BodySource.LABOUR), 1e-9);
        assertEquals(5 * TrainingBalance.KILL_EACH + TrainingBalance.DAMAGE_PER_100 * 10.0D, s.rawOf(BodySource.FIGHT), 1e-9);
        assertTrue(TrainingBalance.SPRINT_PER_KM > TrainingBalance.WALK_PER_KM, "a sprinted kilometre is harder than a walked one");
        assertTrue(TrainingBalance.MINE_ORE > TrainingBalance.MINE_STONE && TrainingBalance.MINE_LOG > TrainingBalance.MINE_STONE);
    }

    @Test
    @DisplayName("Разность статистик платится один раз; упавшая статистика (сброс файла) сдвигает только базу")
    void deltasPayOnce() {
        BodyState s = seeded();
        Map<BodyProbe, Long> a = stats(BodyProbe.WALK, 100_000);
        s = BodyActivity.advance(s, 1L, a, true);
        double once = s.points();
        s = BodyActivity.advance(s, 1L, a, true);
        assertEquals(once, s.points(), 1e-12, "the same reading twice");
        s = BodyActivity.advance(s, 1L, stats(BodyProbe.WALK, 30_000), true);
        assertEquals(once, s.points(), 1e-12, "a stat that went down pays nothing");
        s = BodyActivity.advance(s, 1L, stats(BodyProbe.WALK, 80_000), true);
        assertEquals(once + 0.5D * TrainingBalance.WALK_PER_KM, s.points(), 1e-9, "growth from the new baseline pays");
    }

    @Test
    @DisplayName("Творческий режим и наблюдатель ничего не копят, но база уходит вперёд: потом это не оплачивается")
    void notPaying() {
        BodyState s = seeded();
        s = BodyActivity.advance(s, 1L, stats(BodyProbe.SPRINT, 500_000), false);
        assertEquals(0.0D, s.points(), 1e-12);
        s = BodyActivity.advance(s, 1L, stats(BodyProbe.SPRINT, 500_000), true);
        assertEquals(0.0D, s.points(), 1e-12);
    }

    // ------------------------------------------------------------------ daily limits

    @Test
    @DisplayName("Дневные пределы: любой один вид работы за сутки не даёт больше full + extra; новые сутки начинают заново")
    void dailyLimits() {
        BodyState s = seeded();
        // Absurd work of every kind in one day.
        s = BodyActivity.advance(s, 1L, stats(BodyProbe.SPRINT, 1_000_000_000L, BodyProbe.JUMP, 10_000_000, BodyProbe.STONE, 10_000_000,
                BodyProbe.KILLS, 10_000_000), true);
        double max = 0.0D;
        for (BodySource src : BodySource.values()) {
            max += src.dayMax();
        }
        assertTrue(s.points() <= max + 1e-9, "a farmed day gave " + s.points() + " of at most " + max);
        assertTrue(s.rawOf(BodySource.JUMP) > 0 && BodySource.JUMP.dayTotal(s.rawOf(BodySource.JUMP)) <= BodySource.JUMP.dayMax() + 1e-9);
        double day1 = s.points();
        // The next day the limits are open again.
        s = BodyActivity.advance(s, 2L, stats(BodyProbe.SPRINT, 2_000_000_000L, BodyProbe.JUMP, 20_000_000, BodyProbe.STONE, 20_000_000,
                BodyProbe.KILLS, 20_000_000), true);
        assertTrue(s.points() > day1 + 0.5D * max, "the second day paid " + (s.points() - day1));
    }

    @Test
    @DisplayName("Прыжки с зажатым пробелом (2400 за сутки) дают меньше предела прыжков; бег по кругу — не больше предела ног")
    void afkTricks() {
        BodyState s = seeded();
        s = BodyActivity.advance(s, 1L, stats(BodyProbe.JUMP, 2400), true);
        assertTrue(s.points() <= BodySource.JUMP.dayMax());
        assertTrue(s.points() < 4.0D, "held jump key for a whole day: " + s.points());
    }

    // ------------------------------------------------------------------ initial grant

    @Test
    @DisplayName("Начальный зачёт: игрок в алмазной броне (≈15 ч) попадает на ступень 5 — середина лестницы, не верх; пять часов — ступень 3")
    void diamondArmourPlayerLandsMidLadder() {
        Map<BodyProbe, Long> fifteenHours = stats(BodyProbe.WALK, 4_000_000, BodyProbe.SPRINT, 4_500_000, BodyProbe.CROUCH, 200_000,
                BodyProbe.SWIM, 300_000, BodyProbe.CLIMB, 50_000, BodyProbe.JUMP, 8000, BodyProbe.ORE, 300, BodyProbe.LOG, 600,
                BodyProbe.STONE, 9000, BodyProbe.DIG, 3000, BodyProbe.KILLS, 500, BodyProbe.DAMAGE, 150_000);
        BodyState veteran = BodyActivity.seed(BodyState.NONE, 5L, fifteenHours);
        assertTrue(veteran.seeded());
        assertEquals(TrainingBalance.INITIAL_CAP_LEVEL, veteran.level(), "points " + veteran.points());
        assertTrue(veteran.level() < TrainingBalance.MAX_LEVEL);
        assertTrue(BodyRules.breakthroughReady(3, veteran.level()), "the first-rate gate (stage 4) is passed");
        assertFalse(BodyRules.breakthroughReady(4, veteran.level()), "the Peak gate (stage 6) is still ahead");
        Map<BodyProbe, Long> fiveHours = stats(BodyProbe.WALK, 1_300_000, BodyProbe.SPRINT, 1_500_000, BodyProbe.JUMP, 2700,
                BodyProbe.ORE, 100, BodyProbe.LOG, 200, BodyProbe.STONE, 3000, BodyProbe.DIG, 1000, BodyProbe.KILLS, 170,
                BodyProbe.DAMAGE, 50_000);
        BodyState lighter = BodyActivity.seed(BodyState.NONE, 5L, fiveHours);
        assertEquals(3, lighter.level(), "points " + lighter.points());
        // A world with no history: nothing granted, the marker set.
        BodyState fresh = BodyActivity.seed(BodyState.NONE, 1L, stats());
        assertTrue(fresh.seeded() && fresh.points() == 0.0D);
    }

    @Test
    @DisplayName("Начальный зачёт не уменьшает и не складывается: очки старой лестницы приседаний выше зачёта остаются как были")
    void grantNeverLowers() {
        BodyState old = BodyState.NONE.withPoints(BodyRules.threshold(8));
        BodyState s = BodyActivity.seed(old, 5L, stats(BodyProbe.WALK, 10_000_000L, BodyProbe.STONE, 100_000));
        assertEquals(BodyRules.threshold(8), s.points(), 1e-9);
        BodyState low = BodyActivity.seed(BodyState.NONE.withPoints(10.0D), 5L, stats(BodyProbe.WALK, 1_000_000));
        assertEquals(Math.max(10.0D, 1_000_000 * BodyProbe.WALK.rate() * TrainingBalance.INITIAL_SHARE), low.points(), 1e-9);
    }

    // ------------------------------------------------------------------ saving

    @Test
    @DisplayName("Сохранение: круг кодека сохраняет очки, базу, метку и дневной счёт; старый формат (приседания) читается как «не просмотрен»")
    void codec() {
        BodyState s = BodyActivity.advance(seeded(), 1L, stats(BodyProbe.WALK, 300_000, BodyProbe.KILLS, 4), true).climbed(1234);
        JsonElement json = BodyState.CODEC.encodeStart(JsonOps.INSTANCE, s).getOrThrow();
        BodyState back = BodyState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(s, back);
        assertTrue(back.seeded());
        JsonElement legacy = JsonParser.parseString("{\"version\":1,\"points\":300.0,\"day\":9,\"today\":55.5,\"fatigue\":0.7,"
                + "\"best_climb\":1500,\"best_trail\":0,\"climbs_today\":2}");
        BodyState old = BodyState.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow();
        assertEquals(300.0D, old.points(), 1e-9);
        assertFalse(old.seeded(), "an old save is looked at once");
        assertEquals(1500, old.bestClimb());
        assertTrue(old.seen().isEmpty() && old.raw().isEmpty());
    }

    // ------------------------------------------------------------------ pacing against the gates

    /** Per Minecraft day (20 minutes) of a casual survival player: about 3 km of legs, some mining and felling, a few mobs. */
    private static Map<BodyProbe, Long> casualDay() {
        return stats(BodyProbe.WALK, 66_700, BodyProbe.SPRINT, 100_000, BodyProbe.CROUCH, 3000, BodyProbe.SWIM, 5000,
                BodyProbe.CLIMB, 1300, BodyProbe.JUMP, 167, BodyProbe.ORE, 8, BodyProbe.LOG, 27, BodyProbe.STONE, 150,
                BodyProbe.DIG, 50, BodyProbe.KILLS, 8, BodyProbe.DAMAGE, 1250);
    }

    /** Days (of 20 minutes) until each body level 1..10 is reached; −1 if not within a hundred days. */
    private static int[] daysToLevels(Map<BodyProbe, Long> perDay, double sparsPerDay, double drillRawPerDay) {
        int[] reached = new int[TrainingBalance.MAX_LEVEL + 1];
        java.util.Arrays.fill(reached, -1);
        BodyState s = seeded();
        Map<BodyProbe, Long> total = stats();
        for (int day = 1; day <= 100; day++) {
            total = plus(total, perDay);
            s = BodyActivity.advance(s, day, total, true);
            s = s.credited(BodySource.FIGHT, sparsPerDay * TrainingBalance.SPAR);
            s = s.credited(BodySource.DRILL, drillRawPerDay);
            for (int l = 1; l <= TrainingBalance.MAX_LEVEL; l++) {
                if (reached[l] < 0 && s.level() >= l) {
                    reached[l] = day;
                }
            }
        }
        return reached;
    }

    private static double hours(int days) {
        return days < 0 ? Double.POSITIVE_INFINITY : days / 3.0D;
    }

    @Test
    @DisplayName("Темп: обычная игра в секте (3 км в сутки, шахта, бревно, мобы, 2 спарринга) проходит ворота тела в окна MVP (★12 ≈ 12 ч)")
    void regularPlayerReachesTheGatesInTime() {
        int[] d = daysToLevels(casualDay(), 2.0D, 0.0D);
        // Stage 1 opens the first breakthrough (20-60 min); 2 the second; 4 the first-rate one; 6 the Peak; 7 / 9 the Peak stages.
        assertTrue(hours(d[1]) <= 0.5D, "stage 1 at " + hours(d[1]) + " h");
        assertTrue(hours(d[2]) <= 1.5D, "stage 2 at " + hours(d[2]) + " h");
        assertTrue(hours(d[4]) <= 3.5D, "stage 4 at " + hours(d[4]) + " h");
        assertTrue(hours(d[6]) <= 6.5D, "stage 6 at " + hours(d[6]) + " h");
        assertTrue(hours(d[7]) <= 8.0D, "stage 7 at " + hours(d[7]) + " h");
        assertTrue(hours(d[9]) <= 11.0D, "stage 9 at " + hours(d[9]) + " h");
        assertTrue(hours(d[10]) <= 13.0D, "stage 10 at " + hours(d[10]) + " h");
        // And not instantly: the body is a slow road, stage 6 is hours away.
        assertTrue(hours(d[6]) >= 3.0D, "stage 6 too early: " + hours(d[6]) + " h");
    }

    @Test
    @DisplayName("Темп: чистая ваниль без спаррингов тоже доходит до ступени 6 за ~6 ч и до 9 за ~11 ч; только бег — заметно медленнее")
    void casualAndRunnerOnlyPlayers() {
        int[] casual = daysToLevels(casualDay(), 0.0D, 0.0D);
        assertTrue(hours(casual[6]) <= 7.0D, "stage 6 at " + hours(casual[6]) + " h");
        assertTrue(hours(casual[9]) <= 12.0D, "stage 9 at " + hours(casual[9]) + " h");
        // Only running 1 km a day (no work, no fights) is not enough to ride the ladder.
        int[] runner = daysToLevels(stats(BodyProbe.SPRINT, 100_000, BodyProbe.WALK, 66_700, BodyProbe.JUMP, 167), 0.0D, 0.0D);
        assertTrue(hours(runner[6]) > hours(casual[6]) + 3.0D, "runner stage 6 at " + hours(runner[6]) + " h");
    }

    @Test
    @DisplayName("Предел накрутки: даже сутки за сутками выжатых до предела всех видов работы (с уроками секты) дают ступень 10 не раньше, чем через 4 часа")
    void aPerfectGrinderIsBounded() {
        Map<BodyProbe, Long> farm = stats(BodyProbe.SPRINT, 100_000_000L, BodyProbe.JUMP, 1_000_000, BodyProbe.STONE, 1_000_000,
                BodyProbe.KILLS, 1_000_000);
        int[] d = daysToLevels(farm, 100.0D, 1000.0D);
        assertTrue(hours(d[10]) >= 4.0D, "stage 10 after only " + hours(d[10]) + " h");
        assertTrue(hours(d[6]) >= 1.5D, "stage 6 after only " + hours(d[6]) + " h");
    }
}
