package io.github.verycooltimo.murim.sect;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Parry against a melee senior ({@link ParryProbe}), namespace {@code murimbench}: the reaction sweep of each way a player can
 * prepare the press. Read the {@code PARRY} lines of the log.
 */
@GameTestHolder("murimbench")
@PrefixGameTestTemplate(false)
public final class ParryProbeGameTests {
    private static final int[] REACTIONS = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    private static final int TICKS = Math.max(200, Integer.parseInt(System.getenv().getOrDefault("MURIM_PROBE_TICKS", "700")));

    private ParryProbeGameTests() { }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = "parry_probe", timeoutTicks = 72000)
    public static void swingThenPressVsSeoRang(GameTestHelper h) {
        ParryProbe.melee(h, "seo_rang", "swing", REACTIONS, TICKS, r -> h.succeed());
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = "parry_probe", timeoutTicks = 72000)
    public static void pressOnlyVsSeoGyu(GameTestHelper h) {
        ParryProbe.melee(h, "seo_gyu", "plain", REACTIONS, TICKS, r -> h.succeed());
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = "parry_probe", timeoutTicks = 72000)
    public static void spamThenPressVsSeoHo(GameTestHelper h) {
        ParryProbe.melee(h, "seo_ho", "spam", REACTIONS, TICKS, r -> h.succeed());
    }
}
