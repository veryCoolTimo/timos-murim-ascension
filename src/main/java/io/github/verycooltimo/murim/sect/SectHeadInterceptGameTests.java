package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/**
 * Bug 3 of the 19.10 playtest (docs/adr/0013): a newcomer at the head of the sect is met by a guard, with or without a
 * click. Every test has its own batch (they spawn named people: two of one key within 256 blocks are folded into one).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectHeadInterceptGameTests {

    private static final String YARD = "sect_yard";

    private SectHeadInterceptGameTests() {
    }

    /** «Хотя я был просто новичком, ко мне не подошла охрана, не искала, типа что ты творишь, это глава секты». */
    @GameTest(template = YARD, timeoutTicks = 300, batch = "playtest1019_head_near")
    public static void aNewcomerWalkingUpToTheHeadIsStoppedByAGuardWithoutARightClick(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectHierarchyGameTests.Only layout = empty(h);
        ServerPlayer p = player(h, 4, 4, SectState.NONE.joined());
        npc(h, layout, "tae_hwi", 6, 4, true);
        String key = SectRota.dayWatch(SectSchedule.day(h.getLevel().getDayTime()), 0).key();
        SectDisciple guard = npc(h, layout, key, 12, 4, true);
        // what the server does for every player on the mountain, once in ten ticks
        SectWatch.check(p, layout);
        h.assertTrue(guard.blocking() && guard.blockTarget() == p.getId(), "no guard came over to the newcomer standing at the head");
        h.succeedWhen(() -> {
            var session = p.getData(ModAttachments.DIALOGUE);
            h.assertTrue(session.active() && session.npc() == guard.getId() && "intercept_leader".equals(session.dialogue().getPath()),
                    "the guard did not question the newcomer");
            h.assertTrue(!p.getData(ModAttachments.SECT).has("penance.summoned") && p.getData(ModAttachments.SECT).merit() == 0, "turned away with a punishment");
        });
    }

    /** The right click on the head: the guard may be far (the head sits in the hall, the post is at the door), he still comes. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "playtest1019_head_click")
    public static void aGuardFarFromTheHeadStillAnswersARightClickOfANewcomer(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectHierarchyGameTests.Only layout = empty(h);
        ServerPlayer p = player(h, 3, 3, SectState.NONE.joined());
        SectDisciple leader = npc(h, layout, "tae_hwi", 5, 3, false);
        String key = SectRota.dayWatch(SectSchedule.day(h.getLevel().getDayTime()), 0).key();
        SectDisciple guard = npc(h, layout, key, 22, 22, false);
        DialogueService.Route r = DialogueService.route(p, leader);
        h.assertTrue(r != null && r.intercepted() && r.speaker() == guard, "the guard 28 blocks away was not called: " + (r == null ? "none" : r.node()));
        // an elder passes: the office is what the guards bow to
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectPositions.ELDER));
        DialogueService.Route elder = DialogueService.route(p, leader);
        h.assertTrue(elder != null && !elder.intercepted() && elder.speaker() == leader, "an elder was stopped at the head");
        h.succeed();
    }
}
