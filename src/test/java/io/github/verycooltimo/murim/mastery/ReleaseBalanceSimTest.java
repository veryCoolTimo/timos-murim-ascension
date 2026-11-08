package io.github.verycooltimo.murim.mastery;

import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen pre-release baseline. No worlds, randomness, rewards, wisdom or external help. */
class ReleaseBalanceSimTest {
    static final String[] METHODS = {"street_strength", "six_harmonies", "mountain_root", "tang_poison_breath",
            "reverse_breathing", "holy_fire_breath", "purple_sky_qi", "purple_sky_divine"};

    @Test void beforeMethods() {
        // practiceMethod supplies .001 XP/tick, independently of qi/min and method identity.
        for (String method : METHODS) {
            TechniqueProgress p = TechniqueProgress.learned(0, 12);
            long ticks = 0;
            for (int star = 1; star <= 12; star++) {
                long step = (long) Math.ceil((MasteryRules.need(p.stars(), 12) - p.progress()) / .001) + 1;
                p = MasteryRules.study(p, step * .001, 12, true).progress();
                ticks += step;
                System.out.printf(Locale.ROOT, "BEFORE method %s star %d %.3f min%n", method, star, ticks / 1200.0);
            }
        }
    }

    @Test void methodsMeetTargetAndKeepFractions() {
        for (String method : METHODS) {
            TechniqueProgress p = TechniqueProgress.learned(0, 12);
            long ticks = 0;
            double previous = 0, previousGap = 0;
            for (int star = 1; star <= 12; star++) {
                long step = (long) Math.ceil((MethodMastery.need(p.stars()) - p.progress()) / .001) + 1;
                p = MethodMastery.study(p, step * .001).progress();
                ticks += step;
                double minutes = ticks / 1200.0;
                double gap = minutes - previous;
                assertTrue(star != 1 || minutes <= 5, method);
                assertTrue(gap > previousGap, method);
                if (star > 1) assertEquals(1.3, gap / previousGap, .002);
                System.out.printf(Locale.ROOT, "AFTER method %s star %d %.3f min%n", method, star, minutes);
                previous = minutes;
                previousGap = gap;
            }
            assertTrue(previous >= 216 && previous <= 264, method);
            var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "method/" + method);
            for (int star = 0; star <= 12; star++) {
                double old = MasteryRules.need(star, 12);
                var migrated = new TechniqueProgress(star, old * .37, old * .19, 42, 12, 5, old * .1).migrate(id);
                assertEquals(star, migrated.stars());
                assertEquals(.37, migrated.progress() / MethodMastery.need(star), 1e-9);
                assertEquals(.19, migrated.unprocessed() / MethodMastery.need(star), 1e-9);
                assertEquals(.1, migrated.help() / MethodMastery.need(star), 1e-9);
                assertEquals(migrated, migrated.migrate(id));
            }
        }
    }

    /** Frozen 5 November target income, without calling the rebalanced worth(). */
    static double beforeWorth(MasteryPacing.Kind kind, int hits) {
        return kind.worth * MasteryPacing.fatigue(kind, hits) * 2.1;
    }

    static double[] simulate(io.github.verycooltimo.murim.technique.TechniqueDefinition form, boolean before, boolean dummyOnly) {
        return simulate(form, before, dummyOnly, true);
    }

    static double[] simulate(io.github.verycooltimo.murim.technique.TechniqueDefinition form,
                             boolean before, boolean dummyOnly, boolean earlyTraining) {
        TechniqueProgress p = TechniqueProgress.learned(0, form.starCap());
        int intervalTicks = Math.max(120, Math.max(form.cooldownTicks(), form.totalTicks()));
        // Integer tick boundaries keep day resets and five-minute Echo sessions exact.
        // Repeatedly adding 0.1 minutes can postpone the first Echo to 5.1 minutes.
        long ticks = 0;
        double minutes = 0;
        double[] times = new double[13];
        int[] hits = new int[3];
        int day = -1, echoAt = 1;
        boolean previousTraining = false;
        for (int use = 0; p.stars() < form.starCap() && minutes < 10000000; use++) {
            // Echo is an independent event, including when its deadline falls between uses
            // (e.g. Wind God's 160-tick cadence does not divide 6,000 ticks).
            // Process it before pricing the next use: Echo may change that use's star factor.
            while (echoAt * 6000L < ticks + intervalTicks) {
                var echo = MasteryRules.process(p, form.starCap());
                p = echo.progress();
                for (int star : echo.starsReached()) times[star] = echoAt * 5.0;
                echoAt++;
            }
            if (p.stars() >= form.starCap()) break;
            int nextDay = (int) (ticks / 24000);
            if (day != nextDay) { java.util.Arrays.fill(hits, 0); day = nextDay; }
            int target = dummyOnly ? 0 : use % 3;
            var kind = target == 0 ? MasteryPacing.Kind.TRAINING : target == 1 ? MasteryPacing.Kind.SPAR : MasteryPacing.Kind.BANDIT;
            var source = target == 0 ? MasteryRules.Source.TRAINING : target == 1 ? MasteryRules.Source.SPARRING : MasteryRules.Source.FIGHT;
            double worth;
            if (MasteryPacing.completedUse(form)) {
                // Footwork/self arts call onMiss even during successful defensive use.
                if (before) {
                    worth = .5 * beforeWorth(MasteryPacing.Kind.TRAINING, hits[0]++);
                    source = MasteryRules.Source.TRAINING;
                } else {
                    worth = MasteryPacing.INCOME * (target == 0 ? 1 : MasteryPacing.COMBAT_MULTIPLIER * .3);
                }
            } else {
                worth = before ? beforeWorth(kind, hits[target]++) : MasteryPacing.worth(kind, 0, 0, hits[target]++);
            }
            double trainingFactor = before || !earlyTraining ? 1 : MasteryPacing.repeatedTrainingFactor(
                    form, p.stars(), source, previousTraining);
            previousTraining = source == MasteryRules.Source.TRAINING;
            double amount = worth * trainingFactor * (before ? 1 : MasteryPacing.progressionFactor(form, p.stars()))
                    * io.github.verycooltimo.murim.technique.JunkArts.practiceFactor(form.id());
            ticks += intervalTicks;
            minutes = ticks / 1200.0;
            var gain = MasteryRules.experience(p, source, amount, 0, 0, form.starCap());
            p = gain.progress();
            for (int star : gain.starsReached()) times[star] = minutes;
            // Echo after each five minutes of active use; seated time is excluded and reported explicitly.
            if (ticks >= echoAt * 6000L) {
                echoAt = (int) (ticks / 6000) + 1;
                gain = MasteryRules.process(p, form.starCap());
                p = gain.progress();
                for (int star : gain.starsReached()) times[star] = minutes;
            }
        }
        assertEquals(form.starCap(), p.stars(), form.id().toString());
        return times;
    }

    @Test void echoBetweenUsesKeepsItsFiveMinuteDeadline() throws java.io.IOException {
        try (var reader = java.nio.file.Files.newBufferedReader(techniqueDirectory().resolve("wind_god_shadow.json"))) {
            var form = io.github.verycooltimo.murim.technique.TechniqueDefinition.CODEC.parse(
                    com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseReader(reader)).getOrThrow();
            assertEquals(160, Math.max(120, Math.max(form.cooldownTicks(), form.totalTicks())));
            assertEquals(5.0, simulate(form, false, false)[1],
                    "Echo must process the reserve at five minutes, between the 160-tick uses");
        }
    }

    private static java.nio.file.Path techniqueDirectory() {
        // Rule 05 / DialogueCaptureGateTest: the runner may start below the source root.
        var relative = java.nio.file.Path.of("src/main/resources/data/murim/murim_techniques");
        var workingDirectory = java.nio.file.Path.of("").toAbsolutePath();
        var root = workingDirectory;
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve(relative))) {
            root = root.getParent();
        }
        assertNotNull(root, "Technique source tree not found above " + workingDirectory);
        return root.resolve(relative);
    }

    @Test void everyArtAndEveryForm() throws java.io.IOException {
        var saved = io.github.verycooltimo.murim.technique.TechniqueLoader.all();
        var definitions = new java.util.TreeMap<net.minecraft.resources.ResourceLocation,
                io.github.verycooltimo.murim.technique.TechniqueDefinition>();
        try (var files = java.nio.file.Files.list(techniqueDirectory())) {
            for (var path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                try (var reader = java.nio.file.Files.newBufferedReader(path)) {
                    var form = io.github.verycooltimo.murim.technique.TechniqueDefinition.CODEC.parse(
                            com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseReader(reader)).getOrThrow();
                    definitions.put(form.id(), form);
                }
            }
        }
        try {
            io.github.verycooltimo.murim.technique.TechniqueLoader.replaceAll(definitions);
            assertFalse(definitions.isEmpty());
            // Every form, rather than only the fastest form of an art: a conservative cadence check.
            for (var form : definitions.values()) {
                double[] before = simulate(form, true, false), after = simulate(form, false, false);
                double[] dummyBefore = simulate(form, true, true), dummy = simulate(form, false, true);
                assertArrayEquals(simulate(form, false, false, false), after, 0.0,
                        "Round 4 mixed curve must remain exact: " + form.id());
                assertTrue(dummy[1] <= 8, form.id() + " dummy first " + dummy[1]);
                assertTrue(dummy[2] <= 20, form.id() + " dummy second " + dummy[2]);
                assertTrue(dummy[3] <= 40, form.id() + " dummy third " + dummy[3]);
                printSummary("MIXED", form, before, after);
                printSummary("DUMMY", form, dummyBefore, dummy);
                double previousGap = 0;
                var art = Arts.masteryId(form.id());
                for (int star = 1; star <= form.starCap(); star++) {
                    double gap = after[star] - after[star - 1];
                    String label = form.id() + " star " + star + " gap " + gap;
                    double minimum = star == 1 ? 2 : star <= 8 ? 5 : star == 9 ? 30 : star == 10 ? 60 : 120;
                    double maximum = star == 1 ? 5 : star <= 8 ? 30 : star == 9 ? 60 : star == 10 ? 90 : 180;
                    assertTrue(gap >= minimum, label + " minimum " + minimum);
                    assertTrue(gap <= maximum, label + " maximum " + maximum);
                    assertTrue(gap + 1e-8 >= previousGap, label + " previous " + previousGap);
                    assertTrue(star <= 3 ? dummy[star] >= after[star] : dummy[star] > after[star], form.id() + " star " + star
                            + " dummy " + dummy[star] + " mixed " + after[star]);
                    previousGap = gap;
                    // Every form/tier unlock is at a star, so stars bound ALL consecutive novelty gaps.
                    final int reached = star;
                    var unlocks = definitions.values().stream().filter(d -> Arts.masteryId(d.id()).equals(art))
                            .filter(d -> d.requires().stream().anyMatch(r -> Arts.masteryId(r.technique()).equals(art) && r.star() == reached))
                            .map(d -> d.id().getPath()).toList();
                    System.out.printf(Locale.ROOT, "| %s | %s | %d | %.3f | %.3f | %.3f | %s | %d |%n",
                            art, form.id(), star, before[star], after[star], gap, unlocks, Arts.executionTier(form.id(), star));
                }
                assertEquals(12, form.starCap(), "Release target requires a full twelve-star art: " + form.id());
                assertTrue(after[8] >= 100 && after[8] <= 150, form.id() + " eight " + after[8]);
                assertTrue(after[10] >= 240 && after[10] <= 330, form.id() + " mastery " + after[10]);
                assertTrue(after[12] >= 540 && after[12] <= 690, form.id() + " peak " + after[12]);
                var old = new TechniqueProgress(7, 123, 45, 9, 12, 5, 20);
                var migrated = old.migrate(form.id());
                assertEquals(old.stars(), migrated.stars());
                assertEquals(old.progress(), migrated.progress());
                assertEquals(old.unprocessed(), migrated.unprocessed());
                assertEquals(old.help(), migrated.help());
            }
        } finally {
            io.github.verycooltimo.murim.technique.TechniqueLoader.replaceAll(saved);
        }
    }

    private static void printSummary(String schedule,
                                     io.github.verycooltimo.murim.technique.TechniqueDefinition form,
                                     double[] before, double[] after) {
        for (var row : new double[][] {before, after}) {
            System.out.printf(Locale.ROOT, "%s %s %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |%n",
                    row == before ? "BEFORE" : "AFTER", schedule, form.id(),
                    row[1], row[2], row[3], row[5], row[8], row[10], row[12]);
        }
    }

    @Test void combatBeatsBestDummyEvenAtWorstRankAndFatigue() {
        double bestDummy = MasteryPacing.dummyWorth(0, true) * YardRules.CLEAN_FACTOR * MasteryRules.Source.TRAINING.weight;
        for (var kind : new MasteryPacing.Kind[] {MasteryPacing.Kind.WEAK, MasteryPacing.Kind.SPAR, MasteryPacing.Kind.BANDIT, MasteryPacing.Kind.BOSS}) {
            for (int hits : new int[] {0, 40, 1000, Integer.MAX_VALUE}) {
                assertTrue(MasteryPacing.worth(kind, 0, 10, hits) >= bestDummy * 2.5, kind.toString());
            }
        }
    }

    @Test void earlyTrainingBonusRequiresRepetitionAndStopsAtThree() {
        for (int star = 0; star < 12; star++) {
            for (var source : MasteryRules.Source.values()) {
                assertEquals(1.0, MasteryPacing.repeatedTrainingFactor(null, star, source, false));
                double factor = MasteryPacing.repeatedTrainingFactor(null, star, source, true);
                if (source == MasteryRules.Source.TRAINING && star < 3) assertTrue(factor > 1);
                else assertEquals(1.0, factor);
            }
        }
    }
}
