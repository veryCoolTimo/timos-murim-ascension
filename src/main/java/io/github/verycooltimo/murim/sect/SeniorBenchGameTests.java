package io.github.verycooltimo.murim.sect;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The measuring runs of the senior spar ({@link SeniorBench}), in their own GameTest namespace so that the ordinary suite does not
 * spend minutes on them. Run: {@code -Dneoforge.enabledGameTestNamespaces=murimbench} (tools/capture/senior_bench.sh does it on
 * timoserver); {@code MURIM_BENCH_BOUTS} is the number of bouts of each test (default 12). The regression guard of the same
 * complaint, a short version, is {@link SeniorSpamGuardGameTests} in the ordinary suite.
 */
@GameTestHolder("murimbench")
@PrefixGameTestTemplate(false)
public final class SeniorBenchGameTests {
    private static final int BOUTS = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("MURIM_BENCH_BOUTS", "12")));
    private static final String BATCH = "senior_bench";

    private SeniorBenchGameTests() { }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = BATCH, timeoutTicks = 72000)
    public static void spamHuashanSwordVsSeoRang(GameTestHelper h) {
        SeniorBench.bench(h, "seo_rang", "spam", "huashan", BOUTS, r -> h.succeed());
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = BATCH, timeoutTicks = 72000)
    public static void spamWoodenSwordVsMokHayeon(GameTestHelper h) {
        SeniorBench.bench(h, "mok_hayeon", "spam", "wood", BOUTS, r -> h.succeed());
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = BATCH, timeoutTicks = 72000)
    public static void mashHuashanSwordVsSeoGyu(GameTestHelper h) {
        SeniorBench.bench(h, "seo_gyu", "mash", "huashan", BOUTS, r -> h.succeed());
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = BATCH, timeoutTicks = 72000)
    public static void spamHuashanSwordVsSeoHo(GameTestHelper h) {
        SeniorBench.bench(h, "seo_ho", "spam", "huashan", BOUTS, r -> h.succeed());
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = BATCH, timeoutTicks = 72000)
    public static void spamHuashanSwordVsSeoMu(GameTestHelper h) {
        SeniorBench.bench(h, "seo_mu", "spam", "huashan", BOUTS, r -> h.succeed());
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = BATCH, timeoutTicks = 72000)
    public static void spamHuashanSwordVsSeoGi(GameTestHelper h) {
        SeniorBench.bench(h, "seo_gi", "spam", "huashan", BOUTS, r -> h.succeed());
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = BATCH, timeoutTicks = 72000)
    public static void parryHuashanSwordVsSeoRang(GameTestHelper h) {
        SeniorBench.bench(h, "seo_rang", "parry", "huashan", BOUTS, r -> h.succeed());
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = BATCH, timeoutTicks = 72000)
    public static void parryHuashanSwordVsSeoMu(GameTestHelper h) {
        SeniorBench.bench(h, "seo_mu", "parry", "huashan", BOUTS, r -> h.succeed());
    }
}
