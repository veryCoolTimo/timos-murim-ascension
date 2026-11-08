package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.trade.Peddler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The escort contract's three routes and the merchant's short lines on the way (Track C, docs/design/research/hua-main-1810.md
 * section 8): a line at the start, one at each talking point of the route in order, one at the end; never two at once; the
 * offer carries its route and its texts follow it.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectEscortLinesGameTests {
    private static final String BATCH = "sect_escort_lines";

    private SectEscortLinesGameTests() {
    }

    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(10.5, 2, 10.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO)
                .with(SectStanding.APPROVAL).contribute(40));
        return p;
    }

    private static SectContracts.Data board(GameTestHelper h, ServerPlayer p) {
        MinecraftServer server = h.getLevel().getServer();
        // The game test world has a mountain: without this the board would rotate through the routes by the day.
        SectContracts.routesOverride = List.of(new SectContracts.RouteRef(EscortRoutes.FOOT, BlockPos.ZERO));
        Map<UUID, ServerPlayer> by = new HashMap<>();
        by.put(p.getUUID(), p);
        SectContracts.resolver = by::get;
        SectContracts.Data d = SectContracts.data(server);
        d.runs.clear();
        d.day = -1;
        SectContracts.refresh(server, SectContracts.today(server), List.of());
        return d;
    }

    private static void put(GameTestHelper h, Peddler merchant, BlockPos at) {
        merchant.moveTo(at.getX() + 0.5D, merchant.getY(), at.getZ() + 0.5D);
    }

    /** The start line, then a line at each talking point in order, then the end line. */
    @GameTest(template = "sect_yard", batch = BATCH + "_themerchanttalksatthestartonthewayandattheend", timeoutTicks = 60)
    public static void theMerchantTalksAtTheStartOnTheWayAndAtTheEnd(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectContracts.Data d = board(h, p);
        h.assertTrue(SectContracts.take(p, 1), "escort contract not taken");
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        Peddler merchant = (Peddler) h.getLevel().getEntity(run.escort);
        // twenty blocks between the points and the end far from both: the radius of a point is 14
        BlockPos one = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos two = h.absolutePos(new BlockPos(2, 2, 22));
        BlockPos end = h.absolutePos(new BlockPos(21, 2, 12));
        SectContracts.talkingOverride = List.of(Vec3.atBottomCenterOf(one), Vec3.atBottomCenterOf(two));
        SectContracts.stallOverride = end;
        try {
            // the player stands by him: the start line
            put(h, merchant, h.absolutePos(new BlockPos(11, 2, 10)));
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(merchant.bubbleKey().startsWith(SectContracts.ESCORT_START + "."), "no start line: " + merchant.bubbleKey());
            h.assertTrue(run.leg == 1, "the start line did not count: " + run.leg);

            // not twice at once: the first point passed in the pause is passed without a word
            String said = merchant.bubbleKey();
            put(h, merchant, one);
            p.moveTo(merchant.getX(), merchant.getY(), merchant.getZ() + 3.0D, 0, 0);
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(merchant.bubbleKey().equals(said) && run.leg == 2, "two lines within the gap, or the point was not passed: leg " + run.leg);
            run.leg = 1;

            // in order: the first point, then the second
            run.lastLine = Long.MIN_VALUE / 2;
            put(h, merchant, one);
            p.moveTo(merchant.getX(), merchant.getY(), merchant.getZ() + 3.0D, 0, 0);
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(run.leg == 2 && merchant.bubbleKey().startsWith(SectContracts.ESCORT_WAY + "."), "no line at the first point: "
                    + merchant.bubbleKey() + " leg " + run.leg);
            run.lastLine = Long.MIN_VALUE / 2;
            put(h, merchant, two);
            p.moveTo(merchant.getX(), merchant.getY(), merchant.getZ() + 3.0D, 0, 0);
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(run.leg == 3, "no line at the second point");

            // no more points: nothing to say until the end; then the end line and the arrival
            run.lastLine = Long.MIN_VALUE / 2;
            put(h, merchant, h.absolutePos(new BlockPos(2, 2, 12)));
            p.moveTo(merchant.getX(), merchant.getY(), merchant.getZ() + 3.0D, 0, 0);
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(run.leg == 2 + 1, "a line with no point left");
            put(h, merchant, end);
            p.moveTo(merchant.getX(), merchant.getY(), merchant.getZ() + 3.0D, 0, 0);
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(run.arrived, "arrival not detected");
            h.assertTrue(merchant.bubbleKey().startsWith(SectContracts.ESCORT_END + "."), "no line at the end: " + merchant.bubbleKey());
            h.assertTrue(run.leg == -1, "the end line is not remembered: " + run.leg);
        } finally {
            SectContracts.talkingOverride = null;
            SectContracts.stallOverride = null;
        }
        h.succeed();
    }

    /** A point passed in the pause is passed, a missed point is passed with the next one, and the end line waits out the pause (once). */
    @GameTest(template = "sect_yard", batch = BATCH + "_progressdoesnotwaitforthespeechpauseandtheendwaitsitout", timeoutTicks = 60)
    public static void progressDoesNotWaitForTheSpeechPauseAndTheEndWaitsItOut(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectContracts.Data d = board(h, p);
        h.assertTrue(SectContracts.take(p, 1), "escort contract not taken");
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        Peddler merchant = (Peddler) h.getLevel().getEntity(run.escort);
        BlockPos one = h.absolutePos(new BlockPos(2, 2, 2));
        BlockPos two = h.absolutePos(new BlockPos(2, 2, 22));
        BlockPos end = h.absolutePos(new BlockPos(21, 2, 12));
        SectContracts.talkingOverride = List.of(Vec3.atBottomCenterOf(one), Vec3.atBottomCenterOf(two));
        SectContracts.stallOverride = end;
        try {
            put(h, merchant, h.absolutePos(new BlockPos(11, 2, 10)));
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(run.leg == 1, "no start line");
            String said = merchant.bubbleKey();

            // the first point is passed in the pause: no line, but the cursor moves on
            put(h, merchant, one);
            p.moveTo(merchant.getX(), merchant.getY(), merchant.getZ() + 3.0D, 0, 0);
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(run.leg == 2 && merchant.bubbleKey().equals(said), "a point passed in the pause did not count: leg " + run.leg);
            // the pause is over: the second point speaks at once, nothing is stuck behind the first
            run.lastLine = Long.MIN_VALUE / 2;
            put(h, merchant, two);
            p.moveTo(merchant.getX(), merchant.getY(), merchant.getZ() + 3.0D, 0, 0);
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(run.leg == 3 && merchant.bubbleKey().startsWith(SectContracts.ESCORT_WAY + "."), "the second point did not speak: leg " + run.leg);

            // a missed point is passed with the next one: back to the start of the route and straight to the second point
            run.leg = 1;
            run.lastLine = Long.MIN_VALUE / 2;
            merchant.markBubble("");
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(run.leg == 3 && merchant.bubbleKey().startsWith(SectContracts.ESCORT_WAY + "."), "a missed point blocked the next: leg " + run.leg);

            // arrival right after a line: the end line waits for the pause, then comes once
            run.lastLine = h.getLevel().getGameTime();
            merchant.markBubble("");
            put(h, merchant, end);
            p.moveTo(merchant.getX(), merchant.getY(), merchant.getZ() + 3.0D, 0, 0);
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(run.arrived && merchant.bubbleKey().isEmpty() && run.leg == 3, "the end line broke the pause: " + merchant.bubbleKey());
            run.lastLine = Long.MIN_VALUE / 2;
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(merchant.bubbleKey().startsWith(SectContracts.ESCORT_END + ".") && run.leg == -1, "the end line did not come after the pause");
            merchant.markBubble("");
            run.lastLine = Long.MIN_VALUE / 2;
            SectContracts.escortStep(h.getLevel());
            h.assertTrue(merchant.bubbleKey().isEmpty(), "the end line was said twice");
        } finally {
            SectContracts.talkingOverride = null;
            SectContracts.stallOverride = null;
        }
        h.succeed();
    }

    /** The merchant talks to somebody: with nobody of the group near him, he says nothing. */
    @GameTest(template = "sect_yard", batch = BATCH + "_themerchantsaysnothingwithnobodynear", timeoutTicks = 60)
    public static void theMerchantSaysNothingWithNobodyNear(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectContracts.Data d = board(h, p);
        h.assertTrue(SectContracts.take(p, 1), "escort contract not taken");
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        Peddler merchant = (Peddler) h.getLevel().getEntity(run.escort);
        p.moveTo(p.getX() + 40.0D, p.getY(), p.getZ(), 0, 0);
        SectContracts.escortStep(h.getLevel());
        h.assertTrue(merchant.bubbleKey().isEmpty() && run.leg == 0, "he spoke to nobody: " + merchant.bubbleKey());
        h.succeed();
    }

    /** The offer names its route and the texts follow it: title, where and status exist for the route. */
    @GameTest(template = "sect_yard", batch = BATCH + "_theoffercarriesitsrouteandthetextsfollow", timeoutTicks = 60)
    public static void theOfferCarriesItsRouteAndTheTextsFollow(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectContracts.Data d = board(h, p);
        SectContracts.Offer o = SectContracts.offer(d, 1);
        h.assertTrue(o.kind() == SectContracts.Kind.ESCORT && EscortRoutes.IDS.contains(SectContracts.routeId(o)), "the escort has no route: " + o);
        h.assertTrue(says(SectContracts.title(o), "murim.contract.kind.escort." + SectContracts.routeId(o)), "title does not follow the route");
        h.assertTrue(says(SectContracts.escortWhere(o), "murim.tablet.contract.escort.where." + SectContracts.routeId(o)), "where does not follow the route");
        h.assertTrue(SectContracts.take(p, 1), "not taken");
        h.assertTrue(says(SectContracts.status(p), "murim.contract.status.walking." + SectContracts.routeId(o)), "status does not follow the route");
        h.succeed();
    }

    private static boolean says(net.minecraft.network.chat.Component c, String key) {
        if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            if (t.getKey().equals(key)) {
                return true;
            }
            for (Object a : t.getArgs()) {
                if (a instanceof net.minecraft.network.chat.Component ac && says(ac, key)) {
                    return true;
                }
            }
        }
        return c.getSiblings().stream().anyMatch(x -> says(x, key));
    }
}
