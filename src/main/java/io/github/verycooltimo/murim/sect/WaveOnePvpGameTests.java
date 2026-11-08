package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Uses the same coordinate decision as the server damage hook, with real sect zones. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class WaveOnePvpGameTests {
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void restrictionRequiresBothPlayersInsideAndOptIn(GameTestHelper h) {
        MountHuaSite site = MountHuaSite.placement(10000, 10000, 100, 0);
        var zone = MountHuaPlan.ZONES.stream().filter(z -> SectLayout.SECT_ZONES.contains(z.id())).findFirst().orElseThrow();
        int[] at = site.toWorld(zone.u(), zone.v());
        Vec3 inside = new Vec3(at[0], site.worldY(zone.y()), at[1]);
        Vec3 outside = inside.add(10000, 0, 10000);
        h.assertTrue(SectTerritory.contains(site, inside.x, inside.y, inside.z), "inside fixture is outside");
        boolean before = Config.PVP_ONLY_IN_DUEL.get();
        try {
            for (boolean enabled : new boolean[] {false, true}) {
                Config.PVP_ONLY_IN_DUEL.set(enabled);
                h.assertTrue(SectDuel.restrictedPvp(Config.PVP_ONLY_IN_DUEL.get(), site, inside, inside) == enabled,
                        "wrong inside PvP policy");
                h.assertFalse(SectDuel.restrictedPvp(Config.PVP_ONLY_IN_DUEL.get(), site, outside, outside), "outside restricted");
                h.assertFalse(SectDuel.restrictedPvp(Config.PVP_ONLY_IN_DUEL.get(), site, inside, outside), "mixed restricted");
                h.assertFalse(SectDuel.restrictedPvp(Config.PVP_ONLY_IN_DUEL.get(), site, outside, inside), "mixed reverse restricted");
                h.assertFalse(SectDuel.restrictedPvp(Config.PVP_ONLY_IN_DUEL.get(), null, inside, inside), "missing sect restricted");
            }
        } finally {
            Config.PVP_ONLY_IN_DUEL.set(before);
        }
        h.succeed();
    }
    private WaveOnePvpGameTests() { }
}
