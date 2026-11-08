package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.cultivation.RankRequirements;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Симулятор темпа на настоящих правилах (docs/design/27-balance.md, hua-main-1810 §5, §10 D-1): освоение —
 * {@link MasteryRules} (таблица опыта на 12 звёзд, резерв 20 %, общий потолок помощи) и {@link MasteryPacing}, запас —
 * {@link MeditationService#gainPerTick} (затухания нет), стены и прорывы — {@link Realm} и {@link RankRequirements}.
 * Сценарии игрока — таблицы допущений ниже; они и есть то, что стоит оспаривать.
 *
 * <p>Три части, каждая печатает таблицу ({@code ./gw test --tests '*PacingSim*' -i}) и проверяет окна:
 * <ol>
 *   <li>первое прохождение на 3 часа: вехи docs/design/27 §1 (зомби, ★2, первый прорыв, босс);</li>
 *   <li>сколько всего надо СИДЕТЬ до Пика: прежнее затухание сессии против постоянного притока;</li>
 *   <li>одно искусство до ★12 за смешанную игру при помощи 0 / 10 / 20 % (D-1: 10–14 ч).</li>
 * </ol>
 */
class PacingSimTest {
    // Historical 18 October scenario: retain its original gates against the frozen baseline.
    // ReleaseBalanceSimTest checks current server pacing for EVERY art and form.
    static final double HISTORICAL_INCOME = 2.1D;
    static double historicalWorth(MasteryPacing.Kind kind, int targetRank, int playerRank, int hits) {
        return ReleaseBalanceSimTest.beforeWorth(kind, hits)
                * (kind == MasteryPacing.Kind.TRAINING ? 1 : MasteryPacing.rankFactor(targetRank, playerRank));
    }


    /** Игровые сутки — 20 минут; ночь (зомби) — вторая половина. */
    static final int DAY_MIN = 20;
    static final int TICKS_PER_MIN = 1200;
    // MethodLoader rate rule, author 31.10: Six Elements at zero stars, purity 0.9.
    static final double SIX_GAIN_MULT = method("six_harmonies").gainFactor(0);

    private static io.github.verycooltimo.murim.cultivation.CultivationMethod method(String name) {
        String resource = "/data/murim/murim_methods/" + name + ".json";
        try (var stream = PacingSimTest.class.getResourceAsStream(resource)) {
            if (stream == null) throw new AssertionError("Missing method resource: " + resource);
            try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                return io.github.verycooltimo.murim.cultivation.CultivationMethod.CODEC
                        .parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseReader(reader))
                        .getOrThrow();
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("Cannot read method resource: " + resource, e);
        }
    }

    @Test
    void allMethodRateLadders() {
        String[] names = {"street_strength", "six_harmonies", "mountain_root", "tang_poison_breath",
                "reverse_breathing", "holy_fire_breath", "purple_sky_qi", "purple_sky_divine"};
        double[] starts = {0.8D, 1.0D, 1.0D, 1.5D, 1.5D, 2.0D, 2.5D, 5.0D};
        double[] ends = {1.5D, 2.0D, 2.0D, 3.0D, 3.5D, 4.5D, 4.5D, 15.0D};
        double efficiency = DantianProfile.INITIAL.withAxes(30.0D, 0.9D, 0.5D).efficiency();
        double common = TICKS_PER_MIN * MeditationService.gainPerTick() * efficiency;
        for (int i = 0; i < names.length; i++) {
            var method = method(names[i]);
            for (int stars = 0; stars <= Arts.MAX_STARS; stars++) {
                double expected = 20.0D * (starts[i] + (ends[i] - starts[i]) * Math.max(0, stars - 1) / 11.0D);
                assertEquals(expected, common * method.gainFactor(stars), 1e-9, names[i] + " at " + stars);
                if (stars > 1) assertTrue(method.gainFactor(stars) > method.gainFactor(stars - 1), names[i]);
            }
            if (i < names.length - 1) {
                assertTrue(method("purple_sky_divine").gainFactor(1) > method.gainFactor(12), names[i]);
            }
        }
        assertTrue(method("purple_sky_qi").gainFactor(1) > method("six_harmonies").gainFactor(12));
        var six = method("six_harmonies");
        // Empty-to-full reserve at purity 0.9, without external bonuses: seed, first breakthrough, first class.
        for (int rank : new int[] {Realm.NONE, Realm.THIRD, Realm.FIRST}) {
            var profile = DantianProfile.INITIAL.withAxes(
                    six.capacity() * Realm.capacityFactor(rank, 0), 0.9D, 0.5D).withRank(rank);
            for (int stars : new int[] {0, 12}) {
                double reserve = Realm.wall(profile) * six.capacityFactor(stars);
                System.out.printf(Locale.ROOT, "Six Elements rank %d star %d: %.2f qi, %.2f min to fill%n",
                        rank, stars, reserve, reserve / (common * six.gainFactor(stars)));
            }
        }
    }

    // =====================================================================================================
    // Часть 1. Первое прохождение (допущения, docs/design/27 §2)
    // =====================================================================================================

    static final double SEED_AT = 8.0D;          // метод из скита и три такта — семя
    static final double SIX_AT = 12.0D;          // книга Шести Равновесий в руках
    static final double JOIN_SECT_AT = 30.0D;    // поднялся на гору, принят; утренний строй со следующего утра
    static final double[] CAMPS_AT = {55.0D, 115.0D};
    static final double BOSS_AT_EARLIEST = 140.0D; // карта с главаря первого лагеря + дорога к крепости
    static final double DAY_KILLS_PER_MIN = 0.4D;  // днём — редкие мобы по пути
    static final double NIGHT_KILLS_PER_MIN = 1.5D; // ночью — зомби, скелеты, пауки
    static final int HITS_PER_WEAK_KILL = 4;       // 20 здоровья, меч 5–6
    static final int WHIFFS_PER_KILL = 1;
    static final double FP_CASTS_PER_COMBAT_MIN = 1.5D; // кулдаун 8 с, но бой не непрерывный и ци жалко
    static final double FP_TARGETS = 1.3D;
    static final double SIT_MIN = 1.5D;            // обычная сессия медитации
    static final int GOAL_SESSIONS = 3;            // до прорыва не хватает только запаса: сел трижды (раньше — «встал-сел»)
    /** Страницы лагеря — помощь: пятая часть звезды (потолок), а не 1,38 слоя (CampLootSim, док. 24 §4). */
    static final double CAMP_PAGE_SHARE = MasteryRules.HELP_CAP;

    static final class Sim {
        final boolean legacy;
        TechniqueProgress six;
        TechniqueProgress petal;
        double wisdom;
        DantianProfile profile;
        boolean boss;
        boolean sectSparProof;
        double t;
        long day = -1;
        boolean morning;
        int sits;
        final Map<String, Integer> hits = new HashMap<>();
        final Map<String, Double> milestones = new LinkedHashMap<>();
        double sixUseStart = -1;
        double weakAcc;
        double fpAcc;
        final java.util.Random rng = new java.util.Random(42L);

        Sim(boolean legacy) {
            this.legacy = legacy;
        }

        long gameDay() {
            return (long) (t / DAY_MIN);
        }

        void newDayCheck() {
            if (gameDay() != day) {
                day = gameDay();
                hits.clear();
                morning = false;
            }
        }

        double worth(MasteryPacing.Kind kind, String key, int targetRank) {
            int before = hits.merge(key, 1, Integer::sum) - 1;
            if (legacy) {
                return HISTORICAL_INCOME;
            }
            int rank = profile == null ? 0 : profile.rank();
            return historicalWorth(kind, targetRank, rank, before);
        }

        /** The morning formation gives no flat multiplier any more (Track C, T3): it teaches the drilled forms, see {@link #morningStudy}. */
        double bonus() {
            return 1.0D;
        }

        /** What the formation teaches: a share of the next star of the foundation, a little more for a second form drilled. */
        void morningStudy() {
            double share = io.github.verycooltimo.murim.sect.SectLife.MORNING_SHARE + io.github.verycooltimo.murim.sect.SectLife.MORNING_PER_FORM;
            six = MasteryRules.study(six, MasteryRules.need(six.stars()) * share * MasteryRules.wisdomFactor(wisdom)).progress();
        }

        TechniqueProgress exp(TechniqueProgress p, MasteryRules.Source src, double amount, double difficulty) {
            if (p == null) {
                return null;
            }
            return MasteryRules.experience(p, src, amount * difficulty * bonus(), wisdom).progress();
        }

        void swing() {
            if (six == null) {
                return;
            }
            double w = worth(MasteryPacing.Kind.TRAINING, "training", 0);
            six = exp(six, MasteryRules.Source.TRAINING, 0.5D * w, 1.0D);
        }

        void sixHit(MasteryPacing.Kind kind, String key, int rank) {
            if (six == null) {
                return;
            }
            double w = worth(kind, key, rank);
            six = exp(six, kind == MasteryPacing.Kind.TRAINING ? MasteryRules.Source.TRAINING : MasteryRules.Source.FIGHT, w, 1.0D);
        }

        void petalCast(MasteryPacing.Kind kind, String key, int rank, double targets) {
            if (petal == null) {
                return;
            }
            // Ци на каст: TechniqueService.techniqueCost (1 + 84 тика × 0,05) × costFactor слоя; тратится
            // циркулирующая, а её пассивно доливает запас — значит, каст съедает запас.
            double cost = (1.0D + 84 * 0.05D) * MasteryRules.costFactor(petal.stars(), Arts.MAX_STARS);
            profile = profile.withPool(profile.pool() - cost);
            for (int i = 0; i < Math.round(targets); i++) {
                double w = worth(kind, key, rank);
                petal = exp(petal, MasteryRules.Source.FIGHT, w, 1.0D);
            }
        }

        /** Убийство слабого моба: взмахи основы, попадания, иногда — Падающий Цветок. */
        void weakKill(String key) {
            for (int i = 0; i < HITS_PER_WEAK_KILL + WHIFFS_PER_KILL; i++) {
                swing();
            }
            for (int i = 0; i < HITS_PER_WEAK_KILL; i++) {
                sixHit(MasteryPacing.Kind.WEAK, key, 0);
            }
        }

        /**
         * Сессия медитации: запас растёт ровно, без затухания; в руке книга одного из двух искусств по очереди, и её Эхо
         * (30 секунд) обрабатывает весь резерв именно этого искусства. Сидеть меньше 30 секунд — Очищение, резерв не трогает.
         */
        void meditate(double minutes) {
            int ticks = (int) (minutes * TICKS_PER_MIN);
            if (profile != null) {
                double gain = MeditationService.gainPerTick() * SIX_GAIN_MULT * profile.efficiency() * ticks;
                profile = profile.withPool(Math.min(Realm.wall(profile), profile.pool() + gain));
            }
            if (minutes * 60.0D >= MeditationService.ECHO_TICKS / 20.0D) {
                boolean sixTurn = petal == null || sits % 2 == 0;
                if (sixTurn && six != null) {
                    six = MasteryRules.process(six, Arts.MAX_STARS).progress();
                } else if (petal != null) {
                    petal = MasteryRules.process(petal, Arts.MAX_STARS).progress();
                }
                sits++;
            }
            t += minutes;
            if (profile != null) {
                Map<ResourceLocation, Integer> stars = starsNow();
                if (Realm.atWall(profile)) {
                    mark("wall rank " + (profile.rank() + 1));
                }
                if (Realm.check(profile, stars, sectSparProof) == Realm.Blocker.NONE) {
                    profile = Realm.advance(profile);
                    mark("rank " + profile.rank());
                    t += 0.25D;
                }
            }
        }

        Map<ResourceLocation, Integer> starsNow() {
            Map<ResourceLocation, Integer> stars = new HashMap<>();
            if (six != null) {
                stars.put(id("six_harmonies"), six.stars());
            }
            if (petal != null) {
                stars.put(id("falling_petal_sword"), petal.stars());
            }
            return stars;
        }

        boolean onlyWallMissing() {
            if (profile == null) {
                return false;
            }
            Map<ResourceLocation, Integer> stars = starsNow();
            return Realm.check(profile, stars, sectSparProof) == Realm.Blocker.NOT_AT_WALL
                    && Realm.check(profile.withPool(Realm.wall(profile)), stars, sectSparProof) == Realm.Blocker.NONE;
        }

        void mark(String name) {
            milestones.putIfAbsent(name, t);
        }

        void checkStars() {
            if (six != null) {
                for (int l = 1; l <= six.stars() && l <= 5; l++) {
                    if (!milestones.containsKey("Six star " + l)) {
                        mark("Six star " + l);
                        milestones.put("six" + l, t - sixUseStart);
                    }
                }
            }
            if (petal != null) {
                for (int l = 1; l <= petal.stars() && l <= 3; l++) {
                    mark("Falling Petal star " + l);
                }
            }
        }

        void camp() {
            // 4 мечника, лучник, бандит с ци (ранг 1), главарь (ранг 2) — документ 24 §3.
            String[] who = {"sword", "sword", "sword", "sword", "archer", "qi", "chief"};
            int[] hp = {4, 4, 4, 4, 4, 6, 14};
            int[] rank = {0, 0, 0, 0, 0, 1, 2};
            for (int i = 0; i < who.length; i++) {
                for (int h = 0; h < hp[i]; h++) {
                    swing();
                    if (petal != null && h % 3 == 0) {
                        petalCast(MasteryPacing.Kind.BANDIT, "bandit_" + who[i], rank[i], 1);
                    } else {
                        sixHit(MasteryPacing.Kind.BANDIT, "bandit_" + who[i], rank[i]);
                    }
                }
            }
            t += 6.0D;
            if (six != null && !six.atCap()) {
                // The camp's pages are help: a fifth of the star at most, through the shared pool.
                double amount = CAMP_PAGE_SHARE * MasteryRules.need(six.stars());
                six = MasteryRules.help(six, amount, false, Arts.MAX_STARS).gain().progress();
            }
            checkStars();
            meditate(1.5D);
        }

        void bossFight() {
            for (int h = 0; h < 36; h++) {
                swing();
                if (petal != null && h % 3 == 0) {
                    petalCast(MasteryPacing.Kind.BOSS, "boss", Realm.SECOND, 1);
                } else {
                    sixHit(MasteryPacing.Kind.BOSS, "boss", Realm.SECOND);
                }
            }
            boss = true;
            t += 5.0D;
            mark("boss defeated");
            checkStars();
            meditate(1.5D);
        }

        Sim run(double until) {
            int camp = 0;
            while (t < until) {
                newDayCheck();
                double mod = t % DAY_MIN;
                if (profile == null && t >= SEED_AT) {
                    profile = DantianProfile.INITIAL.withAxes(30.0D, 0.9D, 0.5D)
                            .withTags("pure", "murim:six_harmonies").withPool(30.0D);
                    mark("seed");
                }
                if (six == null && t >= SIX_AT) {
                    six = TechniqueProgress.learned(0, Arts.MAX_STARS);
                    wisdom += MasteryRules.wisdomForLearning(TechniqueTier.BASIC);
                    sixUseStart = t;
                }
                if (petal == null && six != null && six.stars() >= 2 && profile != null && profile.rank() >= 1) {
                    t += 3.0D; // разговор с наставником
                    petal = TechniqueProgress.learned(0, Arts.MAX_STARS);
                    wisdom += MasteryRules.wisdomForLearning(TechniqueTier.ADVANCED);
                    mark("Falling Petal learned");
                    continue;
                }
                if (camp < CAMPS_AT.length && t >= CAMPS_AT[camp]) {
                    camp++;
                    camp();
                    mark("camp " + camp + " cleared");
                    continue;
                }
                if (!boss && t >= BOSS_AT_EARLIEST && profile != null && profile.rank() >= 1) {
                    bossFight();
                    continue;
                }
                if (!sectSparProof && profile != null && profile.rank() == Realm.THIRD && t >= 90.0D) {
                    // Three distinct disciple wins and the teacher's thirty-second demonstration, on a sect visit.
                    t += 3.5D;
                    sectSparProof = true;
                    mark("sect skills tested");
                }
                // Утренний строй: 10 форм в такт — «Утренняя тренировка».
                if (t >= JOIN_SECT_AT && mod < 1.0D && !morning && six != null) {
                    for (int i = 0; i < 10; i++) {
                        swing();
                    }
                    morningStudy();
                    morning = true;
                }
                if (Math.abs(mod - 9.0D) < 0.5D || Math.abs(mod - 18.5D) < 0.5D) {
                    // Обычно — две минуты; если до прорыва не хватает только запаса — сидит дольше.
                    int sessions = onlyWallMissing() ? GOAL_SESSIONS : 1;
                    for (int i = 0; i < sessions; i++) {
                        meditate(SIT_MIN);
                    }
                    checkStars();
                    continue;
                }
                double kills = mod < 10.0D ? DAY_KILLS_PER_MIN : NIGHT_KILLS_PER_MIN;
                weakAcc += kills;
                while (weakAcc >= 1.0D) {
                    weakAcc -= 1.0D;
                    double r = rng.nextDouble();
                    String key = r < 0.6D ? "zombie" : r < 0.85D ? "skeleton" : "spider";
                    weakKill(key);
                    if (petal != null) {
                        fpAcc += FP_CASTS_PER_COMBAT_MIN / Math.max(0.1D, kills);
                        while (fpAcc >= 1.0D) {
                            fpAcc -= 1.0D;
                            petalCast(MasteryPacing.Kind.WEAK, key, 0, FP_TARGETS);
                        }
                    }
                }
                t += 1.0D;
                checkStars();
            }
            return this;
        }
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("murim", path);
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** Сколько живых зомби (по 4 попадания и взмах мимо) нужно новичку на первую звезду — с Эхом после. */
    static int zombiesToFirstStar(boolean legacy) {
        Sim s = new Sim(legacy);
        s.t = SIX_AT;
        s.day = 0;
        s.profile = DantianProfile.INITIAL.withAxes(30.0D, 0.9D, 0.5D).withTags("pure", "x").withPool(30.0D);
        s.six = TechniqueProgress.learned(0, Arts.MAX_STARS);
        s.wisdom = 1.0D;
        for (int z = 1; z <= 200; z++) {
            s.weakKill("zombie");
            // The reserve is 20 %: it counts after the Echo (30 s) or a night.
            if (MasteryRules.process(s.six, Arts.MAX_STARS).progress().stars() >= 1) {
                return z;
            }
        }
        return 999;
    }

    @Test
    @DisplayName("Часть 1. Первое прохождение: вехи в целевых окнах (docs/design/27-balance.md)")
    void firstPlaythroughPacing() {
        Sim now = new Sim(false).run(200.0D);
        Sim old = new Sim(true).run(200.0D);
        System.out.println("=== Pacing sim, part 1: milestone -> minute (flat price per hit | with MasteryPacing) ===");
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(old.milestones.keySet());
        keys.addAll(now.milestones.keySet());
        for (String k : keys) {
            if (k.startsWith("six")) {
                continue;
            }
            if (k.startsWith("Six star ")) {
                String n = k.substring(9);
                Double ua = old.milestones.get("six" + n);
                Double ub = now.milestones.get("six" + n);
                k = k + " (use " + (ua == null ? "-" : fmt(ua)) + " | " + (ub == null ? "-" : fmt(ub)) + ")";
                Double a = old.milestones.get("Six star " + n);
                Double b = now.milestones.get("Six star " + n);
                System.out.printf(Locale.ROOT, "%-40s %8s | %8s%n", k, a == null ? "-" : fmt(a), b == null ? "-" : fmt(b));
                continue;
            }
            Double a = old.milestones.get(k);
            Double b = now.milestones.get(k);
            System.out.printf(Locale.ROOT, "%-40s %8s | %8s%n", k, a == null ? "-" : fmt(a), b == null ? "-" : fmt(b));
        }
        int zOld = zombiesToFirstStar(true);
        int zNow = zombiesToFirstStar(false);
        System.out.println("zombies to Six star 1 (fresh player, Echo after): flat price " + zOld + " | with pacing " + zNow);
        System.out.println("pool " + fmt(now.profile.pool()) + " / wall " + fmt(Realm.wall(now.profile)));
        System.out.println("final: Six star " + now.six.stars() + ", Petal star " + (now.petal == null ? -1 : now.petal.stars())
                + ", rank " + now.profile.rank());

        assertTrue(zNow >= 6, "звезда 1 Шести Равновесий за " + zNow + " зомби — снова «2–3 убийства»");
        // Звезда 2 стоит 120 опыта (таблица D-4) против прежних 44: первый урок наставника приходит позже, но в окне MVP
        // «первый урок и прорыв ≤ 60 мин» (hua-main-1810 §9).
        Double six2 = now.milestones.get("six2");
        assertTrue(six2 != null && six2 >= 20.0D && six2 <= 45.0D, "Six star 2 через " + six2 + " мин применения, цель 20–45");
        Double six2at = now.milestones.get("Six star 2");
        assertTrue(six2at != null && six2at <= 60.0D, "первый урок (★2) на " + six2at + " мин, MVP ≤ 60");
        // Прорыв 1 теперь ждёт ★1 (≈ 20 мин), а не стену запаса: она берётся за ≈ 3 минуты сидения.
        Double rank1 = now.milestones.get("rank 1");
        assertTrue(rank1 != null && rank1 >= 18.0D && rank1 <= 60.0D, "первый прорыв на " + rank1 + " мин, цель 18–60");
        Double boss = now.milestones.get("boss defeated");
        assertTrue(boss != null && boss >= 120.0D && boss <= 180.0D, "босс на " + boss + " мин, цель 2–3 ч");
        // Author 21.10 (docs/adr/rank-two-skills-2026-10-21.md): the optional boss no longer
        // unlocks rank two. This weak-mob route has not earned the required ★6 / ★3 yet.
        assertTrue(now.sectSparProof, "визит в секту не дал доказательство спаррингов");
        assertTrue(!Realm.secondArtsMet(now.starsNow()), "маршрут слабых мобов неожиданно освоил ★6 / ★3 за 200 мин");
        assertNull(now.milestones.get("rank 2"), "босс обошёл требования искусств секты");
        assertEquals(Realm.THIRD, now.profile.rank(), "до освоения искусств игрок остаётся третьесортным");
        assertEquals(Realm.Blocker.NO_SECT_SKILLS,
                Realm.check(now.profile.withPool(Realm.wall(now.profile)), now.starsNow(), now.sectSparProof),
                "при полной ци прорыв должны удерживать именно искусства секты");
        assertTrue(now.petal != null && now.petal.stars() >= 1, "Падающий Цветок не освоен к боссу");
    }

    // =====================================================================================================
    // Часть 2. Сколько сидеть до Пика: затухание сессии против постоянного притока
    // =====================================================================================================

    /** Legacy decay with today's method multiplier, isolating session length from the method-rate rebalance. */
    static double oldGain(int sessionTick) {
        return 0.03D * Math.exp(-sessionTick / 1200.0D) * SIX_GAIN_MULT;
    }

    /** Приток по новому правилу: постоянный. */
    static double newGain(int sessionTick) {
        return MeditationService.gainPerTick() * SIX_GAIN_MULT;
    }

    /**
     * Минуты суммарного сидения от семени до входа в Пик (стена каждого из четырёх рангов, условия по звёздам считаются
     * выполненными): сессии длиной {@code sessionTicks}, эффективность и ёмкость как в части 1.
     */
    static double sittingToPeak(java.util.function.IntToDoubleFunction gain, int sessionTicks) {
        DantianProfile profile = DantianProfile.INITIAL.withAxes(30.0D, 0.9D, 0.5D)
                .withTags("pure", "murim:six_harmonies").withPool(30.0D);
        long ticks = 0;
        int inSession = 0;
        while (profile.rank() < Realm.PEAK) {
            if (Realm.atWall(profile)) {
                profile = Realm.advance(profile);
                continue;
            }
            profile = profile.withPool(Math.min(Realm.wall(profile), profile.pool() + gain.applyAsDouble(inSession) * profile.efficiency()));
            ticks++;
            inSession = (inSession + 1) % sessionTicks;
        }
        return ticks / (double) TICKS_PER_MIN;
    }

    @Test
    @DisplayName("Часть 2. Сидение до Пика: без затухания столько же при любой длине сессии, не больше прежнего «сессиями по минуте»")
    void sittingToPeakDoesNotDependOnSessionLength() {
        int[] sessionSeconds = {15, 30, 60, 90, 180, 600};
        System.out.println("=== Pacing sim, part 2: total sitting from the seed to the Peak, minutes (same method multiplier for both decay rules) ===");
        System.out.printf(Locale.ROOT, "%-18s %10s %10s%n", "session length", "before", "now");
        double[] before = new double[sessionSeconds.length];
        double[] after = new double[sessionSeconds.length];
        for (int i = 0; i < sessionSeconds.length; i++) {
            int ticks = sessionSeconds[i] * 20;
            before[i] = sittingToPeak(PacingSimTest::oldGain, ticks);
            after[i] = sittingToPeak(PacingSimTest::newGain, ticks);
            System.out.printf(Locale.ROOT, "%-18s %10s %10s%n", sessionSeconds[i] + " s", fmt(before[i]), fmt(after[i]));
        }
        for (int i = 1; i < after.length; i++) {
            assertEquals(after[0], after[i], 1.0E-6D, "приток зависит от длины сессии");
        }
        // Both simulations use the same method multiplier in every comparison (docs/design/27 §8).
        assertTrue(after[2] <= before[2] * 1.02D, "до Пика " + after[2] + " мин против прежних " + before[2] + " при сессиях по минуте");
        assertTrue(after[3] <= before[3], "до Пика " + after[3] + " мин против прежних " + before[3] + " при сессиях по 1,5 мин");
        assertTrue(after[0] >= before[0] * 0.95D, "постоянный приток не должен быть быстрее лучшего прежнего дробления "
                + after[0] + " < " + before[0]);
        assertTrue(after[5] < before[5] / 5.0D, "долгое сидение больше не наказывается");
    }

    // =====================================================================================================
    // Часть 3. Одно искусство до ★12 за смешанную игру: помощь 0 / 10 / 20 %
    // =====================================================================================================

    /**
     * Рацион одних игровых суток (20 мин) смешанной игры — сколько попаданий по каждому виду противника набирается в
     * среднем, пока человек ещё и ходит, строит, торгуется, готовит. Противники сильнее слабых мобов подбираются по рангу
     * игрока (секта, лагеря, смотр): множитель ранга у них 1. Это допущение, его и стоит оспаривать.
     */
    static final int WEAK_KINDS = 3;
    static final int WEAK_HITS_PER_KIND = 14;
    static final int BANDIT_SWORD_HITS = 20;
    static final int BANDIT_ARCHER_HITS = 10;
    static final int BANDIT_QI_HITS = 6;
    static final int SPAR_HITS = 25;
    static final int DUMMY_HITS = 40;
    static final int FORMATION_SWINGS = 10;

    /** Что человек пытается взять помощью на каждом переходе, долями цены перехода: источник и доля. */
    record Attempt(MasteryRules.HelpSource source, double share) {
    }

    /** Профили помощи; «40 %» — попытка взять больше потолка: лишнее обязано отсекаться. */
    static final Map<String, List<Attempt>> PROFILES = new LinkedHashMap<>();

    static {
        PROFILES.put("no help", List.of());
        PROFILES.put("10 %", List.of(new Attempt(MasteryRules.HelpSource.PAGE, 0.10D)));
        PROFILES.put("20 %", List.of(new Attempt(MasteryRules.HelpSource.PAGE, 0.10D),
                new Attempt(MasteryRules.HelpSource.LESSON, 0.10D)));
        PROFILES.put("40 % tried", List.of(new Attempt(MasteryRules.HelpSource.PAGE, 0.10D),
                new Attempt(MasteryRules.HelpSource.PAGE, 0.10D), new Attempt(MasteryRules.HelpSource.LESSON, 0.10D),
                new Attempt(MasteryRules.HelpSource.READING, 0.20D), new Attempt(MasteryRules.HelpSource.GUIDANCE, 0.10D),
                new Attempt(MasteryRules.HelpSource.RUSH, 0.25D)));
    }

    /** Итог одного прогона. */
    record Run(double hours, double helpShare, double[] starHours, double lastTwoShare) {
    }

    static Run longRun(List<Attempt> attempts) {
        TechniqueProgress art = TechniqueProgress.learned(0, Arts.MAX_STARS);
        double wisdom = 2.0D; // две-три выученные техники: широта +0,10 (потолок)
        double minutes = 0.0D;
        double helpTotal = 0.0D;
        double[] starHours = new double[Arts.MAX_STARS + 1];
        int day = 0;
        int helpedAt = -1;
        while (art.stars() < Arts.MAX_STARS && day < 5000) {
            // Помощь берётся один раз в начале каждого перехода (страницы и урок в руках раньше практики) — до потолка 20 %.
            if (helpedAt != art.stars()) {
                helpedAt = art.stars();
                for (Attempt a : attempts) {
                    double amount = a.share() * MasteryRules.need(art.stars(), Arts.MAX_STARS);
                    MasteryRules.Helped helped = MasteryRules.help(art, amount, false, Arts.MAX_STARS);
                    helpTotal += helped.granted();
                    art = helped.gain().progress();
                    for (int star : helped.gain().starsReached()) {
                        starHours[star] = minutes / 60.0D;
                    }
                }
            }
            Map<String, Integer> hits = new HashMap<>();
            // «Ранг»: сколько ворот таблицы звёзд пройдено (★1, босс ≈ ★4, ★6, ★8) — для слабых мобов, которые не растут.
            int rank = art.stars() >= 8 ? 4 : art.stars() >= 6 ? 3 : art.stars() >= 4 ? 2 : art.stars() >= 1 ? 1 : 0;
            // The formation gives no flat multiplier any more (Track C): it teaches the forms drilled in it (see morningStudy above).
            double bonus = 1.0D;
            // Утренний строй.
            for (int i = 0; i < FORMATION_SWINGS; i++) {
                double w = 0.5D * historicalWorth(MasteryPacing.Kind.TRAINING, 0, 0, hits.merge("training", 1, Integer::sum) - 1);
                art = gain(art, MasteryRules.Source.TRAINING, w * bonus, wisdom, starHours, minutes);
            }
            art = formationTeaches(art, starHours, minutes);
            // Слабые мобы по дороге: рангом не растут.
            for (int k = 0; k < WEAK_KINDS; k++) {
                for (int i = 0; i < WEAK_HITS_PER_KIND; i++) {
                    double w = historicalWorth(MasteryPacing.Kind.WEAK, 0, rank, hits.merge("weak" + k, 1, Integer::sum) - 1);
                    art = gain(art, MasteryRules.Source.FIGHT, w * bonus, wisdom, starHours, minutes);
                }
            }
            // Бандиты и лагеря: ранг по игроку.
            int[] banditHits = {BANDIT_SWORD_HITS, BANDIT_ARCHER_HITS, BANDIT_QI_HITS};
            for (int b = 0; b < banditHits.length; b++) {
                for (int i = 0; i < banditHits[b]; i++) {
                    double w = historicalWorth(MasteryPacing.Kind.BANDIT, rank, rank, hits.merge("bandit" + b, 1, Integer::sum) - 1);
                    art = gain(art, MasteryRules.Source.FIGHT, w * bonus, wisdom, starHours, minutes);
                }
            }
            // Спарринг со старшим: засчитывается как озарение (SPARRING снимает стену).
            for (int i = 0; i < SPAR_HITS; i++) {
                double w = historicalWorth(MasteryPacing.Kind.SPAR, rank, rank, hits.merge("spar", 1, Integer::sum) - 1);
                art = gain(art, MasteryRules.Source.SPARRING, w * bonus, wisdom, starHours, minutes);
            }
            // Манекен.
            for (int i = 0; i < DUMMY_HITS; i++) {
                double w = historicalWorth(MasteryPacing.Kind.TRAINING, 0, 0, hits.merge("training", 1, Integer::sum) - 1);
                art = gain(art, MasteryRules.Source.TRAINING, w * bonus, wisdom, starHours, minutes);
            }
            // Эхо в конце суток (30 с на искусстве в руке) обрабатывает весь резерв, без множителя.
            MasteryRules.Gain echo = MasteryRules.process(art, Arts.MAX_STARS);
            art = echo.progress();
            for (int star : echo.starsReached()) {
                starHours[star] = (minutes + DAY_MIN) / 60.0D;
            }
            minutes += DAY_MIN;
            day++;
        }
        double total = MasteryRules.TOTAL_XP;
        double last = (MasteryRules.cumulative(12, 12) - MasteryRules.cumulative(10, 12)) / total;
        return new Run(minutes / 60.0D, helpTotal / total, starHours, last);
    }

    /**
     * The morning formation teaches the art whose forms were drilled in it ({@code SectLife#teachDrilledForms}): a share of the next
     * star, a little more for a second form. The sim drills two forms of the art every sect day.
     */
    private static TechniqueProgress formationTeaches(TechniqueProgress art, double[] starHours, double minutes) {
        double share = io.github.verycooltimo.murim.sect.SectLife.MORNING_SHARE + io.github.verycooltimo.murim.sect.SectLife.MORNING_PER_FORM;
        MasteryRules.Gain g = MasteryRules.study(art, MasteryRules.need(art.stars(), Arts.MAX_STARS) * share, Arts.MAX_STARS, false);
        for (int star : g.starsReached()) {
            starHours[star] = minutes / 60.0D;
        }
        return g.progress();
    }

    private static TechniqueProgress gain(TechniqueProgress art, MasteryRules.Source source, double amount, double wisdom,
                                          double[] starHours, double minutes) {
        MasteryRules.Gain g = MasteryRules.experience(art, source, amount, wisdom);
        for (int star : g.starsReached()) {
            starHours[star] = minutes / 60.0D;
        }
        return g.progress();
    }

    @Test
    @DisplayName("Часть 3. ★12 одного искусства за смешанную игру: без помощи / 10 % / 20 % — 10–14 ч (D-1), потолок помощи держит 20 %")
    void twelveStarsInTenToFourteenHours() {
        System.out.println("=== Pacing sim, part 3: one art to star 12, mixed play (20-min days), hours ===");
        System.out.println("table: cumulative XP " + java.util.Arrays.toString(java.util.stream.IntStream.rangeClosed(0, 12)
                .mapToObj(s -> (long) MasteryRules.cumulative(s, 12)).toArray()));
        Map<String, Run> runs = new LinkedHashMap<>();
        for (Map.Entry<String, List<Attempt>> profile : PROFILES.entrySet()) {
            Run run = longRun(profile.getValue());
            runs.put(profile.getKey(), run);
            System.out.printf(Locale.ROOT, "%-12s star 12 in %6s h   help of the whole path %5.1f %%   last two stars %4.1f %% of the cost%n",
                    profile.getKey(), fmt(run.hours()), run.helpShare() * 100.0D, run.lastTwoShare() * 100.0D);
        }
        Run none = runs.get("no help");
        StringBuilder row = new StringBuilder("hours of each star (no help):");
        for (int s = 1; s <= 12; s++) {
            row.append(' ').append(s).append('=').append(fmt(none.starHours()[s]));
        }
        System.out.println(row);
        double perHour = MasteryRules.TOTAL_XP * (1.0D - none.helpShare()) / none.hours();
        System.out.println("effective practice per hour, no help: " + fmt(perHour) + " XP/h  (price per hit scale "
                + HISTORICAL_INCOME + ")");

        for (Map.Entry<String, Run> e : runs.entrySet()) {
            assertTrue(e.getValue().hours() >= 10.0D && e.getValue().hours() <= 14.0D,
                    e.getKey() + ": ★12 за " + e.getValue().hours() + " ч, цель 10–14");
        }
        Run ten = runs.get("10 %");
        Run twenty = runs.get("20 %");
        Run tried = runs.get("40 % tried");
        assertTrue(ten.hours() < none.hours() && twenty.hours() < ten.hours(), "помощь ускоряет");
        assertTrue(twenty.helpShare() <= MasteryRules.HELP_CAP + 1.0E-9D, "помощь " + twenty.helpShare());
        assertTrue(tried.helpShare() <= MasteryRules.HELP_CAP + 1.0E-9D, "попытка взять 40 % дала " + tried.helpShare());
        assertEquals(twenty.hours(), tried.hours(), 0.05D, "всё сверх потолка отсекается: 40 % ≡ 20 %");
        assertTrue(none.hours() - twenty.hours() <= none.hours() * 0.25D, "помощь не должна покупать больше пятой части пути");
        assertTrue(none.lastTwoShare() > 0.34D && none.lastTwoShare() < 0.36D, "последние две звезды — 34,7 % опыта");
    }
}
