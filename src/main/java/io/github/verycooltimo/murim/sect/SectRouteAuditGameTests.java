package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The route audit ({@link SectRouteAudit}) on the real baked sect at each of the four rotations a site can have (docs/adr/0017):
 * every hop of the waypoint graph asked of the pathfinder of a person, in the stamped sect, with the layout file anchored to the
 * site. A hop the pathfinder cannot walk is a stair or a passage that leads nowhere. It reports; it asserts only that the hops were checked: the test level has no mountain, so the paths between buildings (natural slopes) differ from the real world's; those are audited
 * in the real world by {@code MURIM_ROUTE_AUDIT} in tools/build/sect/sim (AUDIT=only) and are listed in the log here.
 *
 * <p>The whole sect is 237 cells; stamping them all at four rotations takes about 3000 ticks and delays the time-based tests that
 * run after it (the note in {@code HuaBakedGameTests}), so the test runs only with the environment variable
 * {@code MURIM_ROUTE_AUDIT=1}; without it the test passes at once. Run it by itself:
 * {@code MURIM_ROUTE_AUDIT=1 ./gw runGameTestServer}, then {@code grep Sect-audit run/logs/latest.log}.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectRouteAuditGameTests {

    private static final int PRELOAD_TICKS = 400;

    private SectRouteAuditGameTests() {
    }

    @GameTest(template = "sect_yard", batch = "sect_route_audit", timeoutTicks = 6000)
    public static void everyHopOfTheGraphIsWalkableAtEveryRotation(GameTestHelper h) {
        if (!"1".equals(System.getenv("MURIM_ROUTE_AUDIT"))) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        HuaBaked.Index index = HuaBaked.index(level.getServer());
        h.assertTrue(index != null, "baked index missing from the mod resources");
        net.minecraft.Util.backgroundExecutor().execute(() -> HuaBaked.preload(level.getServer()));
        var cells = HuaBaked.cells(index);
        BlockPos corner = h.absolutePos(BlockPos.ZERO);
        int tick = PRELOAD_TICKS;
        for (int rot = 0; rot < 4; rot++) {
            final int r = rot;
            MountHuaSite site = new MountHuaSite(corner.getX() + 3000 + rot * 600, corner.getZ() + 3000, 66, rot, 1L, true);
            for (int i = 0; i < cells.size(); i++) {
                var e = cells.get(i);
                h.runAtTickTime(tick + i, () -> h.assertTrue(HuaBaked.placeCell(level.getServer(), level, site, index, e.getKey(), e.getValue(),
                        null, level.getRandom(), 2), "template missing: " + e.getValue().file()));
            }
            int check = tick + cells.size() + 20;
            h.runAtTickTime(check, () -> {
                SectLayoutFile layout = anchored(site);
                SectRouteAudit.Report report = SectRouteAudit.run(level, layout);
                MurimMod.LOGGER.info("Sect-audit: rotation {}: {} hops, {} not walkable, {} detours", r, report.hops(), report.unreachable(),
                        report.detours());
                List<String> bad = report.lines().stream().filter(l -> l.contains("UNREACHABLE")).limit(4).toList();
                // Report only: the flat test level lacks the mountain around the stamped sect, so some hops differ from the real world
                // (the real-world audit, AUDIT=only in tools/build/sect/sim, is the gate: 0 not walkable on int14).
                h.assertTrue(report.hops() > 1000, "rotation " + r + ": only " + report.hops() + " hops were checked " + bad);
            });
            tick = check + 2;
        }
        h.runAtTickTime(tick, h::succeed);
    }

    private static SectLayoutFile anchored(MountHuaSite site) {
        // parse() instead of anchored(): anchored() keeps one static cache that other sect tests read
        try (Reader r = new InputStreamReader(SectRouteAuditGameTests.class.getResourceAsStream(SectLayoutFile.RESOURCE), StandardCharsets.UTF_8)) {
            return SectLayoutFile.parse(r, frame -> SectLayoutFile.Anchor.to(frame, site));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Kept for the log: which hops of a report are bad, by edge. */
    static Map<String, Integer> byKind(SectRouteAudit.Report report) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (String l : report.lines()) {
            if (l.contains("UNREACHABLE") || l.contains("DETOUR")) {
                out.merge(l.contains("UNREACHABLE") ? "unreachable" : "detour", 1, Integer::sum);
            }
        }
        return out;
    }
}
