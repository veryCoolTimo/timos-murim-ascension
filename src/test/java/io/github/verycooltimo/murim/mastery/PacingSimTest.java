package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Симулятор первого прохождения на настоящих правилах (docs/design/27-balance.md): освоение —
 * {@link MasteryRules} и {@link MasteryPacing}, запас — {@link MeditationService#gainAt}, стены и прорывы —
 * {@link Realm}, страницы — {@link PageRules}. Сценарий игрока — минутный распорядок ниже; он и есть
 * допущение, которое стоит оспаривать.
 *
 * <p>Печатает таблицу вех ({@code ./gw test --tests '*PacingSim*' -i}) и проверяет, что вехи в целевых окнах.
 */
class PacingSimTest {

    /** Игровые сутки — 20 минут; ночь (зомби) — вторая половина. */
    static final int DAY_MIN = 20;
    static final int TICKS_PER_MIN = 1200;

    // --- Сценарий «типичный новичок» (допущения, docs/design/27 §2) ---
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
    static final int GOAL_SESSIONS = 3;            // до прорыва не хватает только запаса: встал-сел трижды
    static final double CAMP_PAGE_INSIGHT_LAYERS = 1.38D; // CampLootSim, «после M2» (документ 24 §4)

    static final class Sim {
        final boolean legacy;
        TechniqueProgress six;
        TechniqueProgress petal;
        double wisdom;
        DantianProfile profile;
        boolean boss;
        double t;
        long day = -1;
        boolean morning;
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
                return 1.0D;
            }
            int rank = profile == null ? 0 : profile.rank();
            return MasteryPacing.worth(kind, targetRank, rank, before);
        }

        double bonus() {
            return morning ? io.github.verycooltimo.murim.sect.SectLife.MORNING_BONUS : 1.0D;
        }

        TechniqueProgress exp(TechniqueProgress p, MasteryRules.Source src, double amount, double difficulty) {
            if (p == null) {
                return null;
            }
            MasteryRules.Gain g = MasteryRules.experience(p, src, amount * difficulty * bonus(), wisdom, day);
            return g.progress();
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
            double cost = (1.0D + 84 * 0.05D) * MasteryRules.costFactor(petal.layer(), 8);
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

        void meditate(double minutes) {
            int ticks = (int) (minutes * TICKS_PER_MIN);
            for (int i = 0; i < ticks; i++) {
                if (six != null) {
                    six = MasteryRules.meditate(six, wisdom, day).progress();
                }
                if (petal != null) {
                    petal = MasteryRules.meditate(petal, wisdom, day).progress();
                }
                if (profile != null) {
                    double gain = MeditationService.gainAt(i) * profile.efficiency();
                    profile = profile.withPool(Math.min(Realm.wall(profile), profile.pool() + gain));
                }
            }
            t += minutes;
            if (profile != null) {
                Map<net.minecraft.resources.ResourceLocation, Integer> layers = new HashMap<>();
                if (six != null) {
                    layers.put(id("six"), six.layer());
                }
                if (petal != null) {
                    layers.put(id("petal"), petal.layer());
                }
                if (Realm.atWall(profile)) {
                    mark("wall rank " + (profile.rank() + 1));
                }
                if (Realm.check(profile, layers, boss) == Realm.Blocker.NONE) {
                    profile = Realm.advance(profile);
                    mark("rank " + profile.rank());
                    t += 0.25D;
                }
            }
        }

        boolean onlyWallMissing() {
            if (profile == null) {
                return false;
            }
            Map<net.minecraft.resources.ResourceLocation, Integer> layers = new HashMap<>();
            layers.put(id("six"), six == null ? 0 : six.layer());
            layers.put(id("petal"), petal == null ? 0 : petal.layer());
            return Realm.check(profile, layers, boss) == Realm.Blocker.NOT_AT_WALL
                    && Realm.check(profile.withPool(Realm.wall(profile)), layers, boss) == Realm.Blocker.NONE;
        }

        void mark(String name) {
            milestones.putIfAbsent(name, t);
        }

        void checkLayers() {
            if (six != null) {
                for (int l = 1; l <= six.layer(); l++) {
                    if (!milestones.containsKey("Six L" + l)) {
                        mark("Six L" + l);
                        milestones.put("six" + l, t - sixUseStart);
                    }
                }
            }
            if (petal != null) {
                for (int l = 1; l <= petal.layer(); l++) {
                    mark("Falling Petal L" + l);
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
            if (six != null && six.atCap() == false) {
                double amount = CAMP_PAGE_INSIGHT_LAYERS * MasteryRules.need(six.layer());
                six = MasteryRules.study(six, amount * MasteryRules.wisdomFactor(wisdom), day).progress();
            }
            checkLayers();
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
            checkLayers();
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
                    six = TechniqueProgress.learned(0, 5);
                    wisdom += MasteryRules.wisdomForLearning(TechniqueTier.BASIC);
                    sixUseStart = t;
                }
                if (petal == null && six != null && six.layer() >= 2 && profile != null && profile.rank() >= 1) {
                    t += 3.0D; // разговор с наставником
                    petal = TechniqueProgress.learned(0, 8);
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
                // Утренний строй: 10 форм в такт — «Утренняя тренировка».
                if (t >= JOIN_SECT_AT && mod < 1.0D && !morning && six != null) {
                    for (int i = 0; i < 10; i++) {
                        swing();
                    }
                    morning = true;
                }
                if (Math.abs(mod - 9.0D) < 0.5D || Math.abs(mod - 18.5D) < 0.5D) {
                    // Обычно — две минуты; если до прорыва не хватает только запаса — сидит дольше.
                    int sessions = onlyWallMissing() ? GOAL_SESSIONS : 1;
                    for (int i = 0; i < sessions; i++) {
                        meditate(SIT_MIN);
                    }
                    checkLayers();
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
                checkLayers();
            }
            return this;
        }
    }

    static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", path);
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Сколько живых зомби (по 4 попадания и взмах мимо) нужно новичку на первый слой — с медитацией после. */
    static int zombiesToFirstLayer(boolean legacy) {
        Sim s = new Sim(legacy);
        s.t = SIX_AT;
        s.day = 0;
        s.profile = DantianProfile.INITIAL.withAxes(30.0D, 0.9D, 0.5D).withTags("pure", "x").withPool(30.0D);
        s.six = TechniqueProgress.learned(0, 5);
        s.wisdom = 1.0D;
        for (int z = 1; z <= 200; z++) {
            s.weakKill("zombie");
            Sim probe = copy(s);
            probe.meditate(2.0D);
            if (probe.six.layer() >= 1) {
                return z;
            }
        }
        return 999;
    }

    static Sim copy(Sim s) {
        Sim c = new Sim(s.legacy);
        c.six = s.six;
        c.petal = s.petal;
        c.wisdom = s.wisdom;
        c.profile = s.profile;
        c.t = s.t;
        c.day = s.day;
        return c;
    }

    @Test
    @DisplayName("Симулятор первого прохождения: вехи в целевых окнах (docs/design/27-balance.md)")
    void firstPlaythroughPacing() {
        Sim now = new Sim(false).run(200.0D);
        Sim old = new Sim(true).run(200.0D);
        System.out.println("=== Pacing sim: milestone -> minute (without MasteryPacing | with) ===");
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(old.milestones.keySet());
        keys.addAll(now.milestones.keySet());
        for (String k : keys) {
            if (k.startsWith("six")) {
                continue;
            }
            if (k.startsWith("Six L")) {
                String n = k.substring(5);
                Double ua = old.milestones.get("six" + n);
                Double ub = now.milestones.get("six" + n);
                k = k + " (use " + (ua == null ? "-" : fmt(ua)) + " | " + (ub == null ? "-" : fmt(ub)) + ")";
                Double a = old.milestones.get("Six L" + n);
                Double b = now.milestones.get("Six L" + n);
                System.out.printf(java.util.Locale.ROOT, "%-40s %8s | %8s%n", k, a == null ? "-" : fmt(a), b == null ? "-" : fmt(b));
                continue;
            }
            Double a = old.milestones.get(k);
            Double b = now.milestones.get(k);
            System.out.printf(java.util.Locale.ROOT, "%-40s %8s | %8s%n", k, a == null ? "-" : fmt(a), b == null ? "-" : fmt(b));
        }
        int zOld = zombiesToFirstLayer(true);
        int zNow = zombiesToFirstLayer(false);
        System.out.println("zombies to Six L1 (fresh player, sit after): without pacing " + zOld + " | with " + zNow);
        System.out.println("pool " + fmt(now.profile.pool()) + " / wall " + fmt(Realm.wall(now.profile)));
        System.out.println("final: Six L" + now.six.layer() + ", Petal L" + (now.petal == null ? -1 : now.petal.layer())
                + ", rank " + now.profile.rank());

        assertTrue(zNow >= 6, "слой 1 Шести Равновесий за " + zNow + " зомби — снова «2–3 убийства»");
        Double six2 = now.milestones.get("six2");
        assertTrue(six2 != null && six2 >= 15.0D && six2 <= 25.0D, "Six L2 через " + six2 + " мин применения, цель 15–25");
        Double rank1 = now.milestones.get("rank 1");
        assertTrue(rank1 != null && rank1 >= 25.0D && rank1 <= 60.0D, "первый прорыв на " + rank1 + " мин, цель 25–60");
        Double boss = now.milestones.get("boss defeated");
        assertTrue(boss != null && boss >= 120.0D && boss <= 180.0D, "босс на " + boss + " мин, цель 2–3 ч");
        Double rank2 = now.milestones.get("rank 2");
        assertTrue(rank2 != null && rank2 <= 200.0D, "второй прорыв на " + rank2 + " мин");
        assertTrue(now.petal != null && now.petal.layer() >= 1, "Падающий Цветок не освоен к боссу");
    }
}
