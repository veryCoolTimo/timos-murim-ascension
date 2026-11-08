package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The sect test commands run through the real dispatcher on a mock player. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectAdminCommandGameTests {
    private SectAdminCommandGameTests() { }

    private static void run(GameTestHelper h, ServerPlayer p, String command) {
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2), command);
    }

    private static int tablets(ServerPlayer p) {
        return (int) p.getInventory().items.stream().filter(s -> s.is(ModItems.JADE_SECT_TABLET.get())).count();
    }

    @GameTest(template = "sect_yard", batch = "sect_admin", timeoutTicks = 100)
    public static void adminCommandsChangeSectState(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        run(h, p, "murim dev sect standing disciple");
        h.assertTrue(!SectService.state(p).member(), "standing worked on an outsider");

        run(h, p, "murim dev sect join");
        h.assertTrue(SectService.state(p).member() && tablets(p) == 1, "join did not admit with a tablet");
        h.assertTrue(SectService.standing(p) == SectStanding.NOVICE, "joined as " + SectService.standing(p));

        for (String id : new String[] {"trusted", "graduate", "disciple", "newcomer"}) {
            run(h, p, "murim dev sect standing " + id);
            String got = SectService.standing(p).id();
            h.assertTrue(got.equals("newcomer".equals(id) ? "novice" : id), "standing " + id + " gave " + got);
        }
        // Rank and merit alone must not leave a "newcomer" a disciple.
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(2));
        run(h, p, "murim dev sect merit 50");
        run(h, p, "murim dev sect standing newcomer");
        h.assertTrue(SectService.standing(p) == SectStanding.NOVICE, "newcomer kept disciple standing: " + SectService.standing(p));

        run(h, p, "murim dev sect merit 40");
        h.assertTrue(SectService.state(p).merit() == 40, "merit " + SectService.state(p).merit());
        run(h, p, "murim dev sect merit 7");
        h.assertTrue(SectService.state(p).merit() == 7, "merit lowered to " + SectService.state(p).merit());

        run(h, p, "murim dev sect position elder");
        h.assertTrue(SectService.state(p).has(SectPositions.ELDER) && SectService.state(p).has(SectPositions.FIRST), "elder flags");
        run(h, p, "murim dev sect position first");
        h.assertTrue(SectService.state(p).has(SectPositions.FIRST) && !SectService.state(p).has(SectPositions.ELDER), "first flags");
        run(h, p, "murim dev sect position none");
        h.assertTrue(!SectService.state(p).has(SectPositions.FIRST) && !SectService.state(p).has(SectPositions.ELDER), "none flags");

        run(h, p, "murim dev sect status");
        run(h, p, "murim dev help sect");

        run(h, p, "murim dev sect leave");
        h.assertTrue(SectService.state(p).equals(SectState.NONE) && tablets(p) == 0, "leave left state or tablet");
        h.succeed();
    }
}
