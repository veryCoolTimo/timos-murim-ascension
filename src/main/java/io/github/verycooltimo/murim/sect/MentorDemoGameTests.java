package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The mentor's «show me» demonstration: when the option shows, how long it lasts, where he is afterwards, the cooldown. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class MentorDemoGameTests {
    private MentorDemoGameTests() { }

    private static ServerPlayer newcomer(GameTestHelper h, double x, boolean method) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(x, 2, 12.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL);
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE);
        SectService.join(p);
        for (int i = 0; i < p.getInventory().items.size(); i++) {
            if (p.getInventory().items.get(i).is(ModItems.METHOD_SCROLL.get())) p.getInventory().items.set(i, ItemStack.EMPTY);
        }
        if (method) p.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(SectService.SIX));
        return p;
    }

    private static SectDisciple mentor(GameTestHelper h, ServerPlayer p) {
        SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        npc.setMember(SectRoster.of("gyeong_pil").orElseThrow());
        npc.moveTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
        npc.setNoAi(true);
        npc.setKeepAwake(true);
        npc.setOnGround(true);
        h.getLevel().addFreshEntity(npc);
        return npc;
    }

    private static Dialogue.Option demoOption(SectDisciple mentor) {
        return DialogueLoader.get(mentor.dialogue()).nodes().get("journey").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("mentor_demo"))).findFirst().orElseThrow();
    }

    @GameTest(template = "sect_yard", batch = "mentor_demo", timeoutTicks = 320)
    public static void demoRunsEightSecondsAndHeReturns(GameTestHelper h) {
        ServerPlayer a = newcomer(h, 12.5, true);
        ServerPlayer none = newcomer(h, 14.5, false);
        SectDisciple m = mentor(h, a);
        h.assertTrue(SectSocial.matches(a, m, "mentor_demo"), "option hidden from a player with a method and no dantian");
        h.assertTrue(!SectSocial.matches(none, m, "mentor_demo"), "option shown to a player without a method");
        a.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(SectService.SIX).withBeats(3));
        h.assertTrue(!SectSocial.matches(a, m, "mentor_demo"), "option shown after the dantian formed");
        a.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(SectService.SIX));
        Vec3 home = m.position();
        Vec3 playerAt = a.position();
        ItemStack hand = m.getMainHandItem().copy();
        h.assertTrue(DialogueService.applyHeadless(a, m, demoOption(m)), "request rejected");
        h.assertTrue(!SectSocial.matches(a, m, "mentor_demo"), "second request accepted at once");
        long[] t0 = {-1};
        boolean[] seen = new boolean[3];
        h.onEachTick(() -> {
            long now = h.getLevel().getGameTime();
            var d = m.demo();
            if (d != null && d.start() >= 0) {
                if (t0[0] < 0) t0[0] = d.start();
                long t = now - t0[0];
                if (t == 10) seen[0] = m.getMainHandItem().is(ModItems.METHOD_SCROLL.get());
                if (t == 80) seen[1] = m.pose() == SectPose.MEDITATE && m.sitting() && m.getMainHandItem().isEmpty() == hand.isEmpty();
                h.assertTrue(a.position().equals(playerAt), "the player was moved");
                h.assertTrue(m.position().distanceTo(home) < 0.5D, "the mentor walked during the demo");
            }
            if (t0[0] >= 0 && d == null) {
                long len = now - t0[0];
                h.assertTrue(len >= 140 && len <= 180, "demo lasted " + len + " ticks");
                h.assertTrue(seen[0] && seen[1], "scroll/pose phases missing: " + seen[0] + " " + seen[1]);
                h.assertTrue(!m.sitting() && m.pose() != SectPose.MEDITATE && ItemStack.matches(m.getMainHandItem(), hand),
                        "he did not return to his routine state");
                h.assertTrue(m.position().distanceTo(home) < 0.5D && m.free(), "not back at his place: " + m.position());
                h.assertTrue(!SectSocial.matches(a, m, "mentor_demo"), "cooldown ignored after the demo");
                h.assertTrue(!none.getPersistentData().contains(MentorDemo.LAST), "another player got a cooldown");
                h.succeed();
            }
        });
    }

    @GameTest(template = "sect_yard", batch = "mentor_demo", timeoutTicks = 20)
    public static void cooldownIsTwentySeconds(GameTestHelper h) {
        ServerPlayer a = newcomer(h, 12.5, true);
        SectDisciple m = mentor(h, a);
        long now = h.getLevel().getGameTime();
        a.getPersistentData().putLong(MentorDemo.LAST, now - (MentorDemo.COOLDOWN - 1));
        h.assertTrue(!SectSocial.matches(a, m, "mentor_demo"), "offered 19.95 s after the last demo");
        a.getPersistentData().putLong(MentorDemo.LAST, now - MentorDemo.COOLDOWN);
        h.assertTrue(SectSocial.matches(a, m, "mentor_demo"), "not offered after 20 s");
        h.succeed();
    }

    private static boolean listed(ServerPlayer p) {
        DialogueService.Session session = p.getData(ModAttachments.DIALOGUE);
        Dialogue d = DialogueLoader.get(session.dialogue());
        String id = session.node().startsWith("@matters:") ? session.node().substring("@matters:".length()) : session.node();
        Dialogue.Node node = d.nodes().get(id);
        if (node == null) return false;
        for (int raw : session.shown()) {
            if (raw < node.options().size() && node.options().get(raw).text().equals("dialogue.murim.mentor.opt.demo")) return true;
        }
        return false;
    }

    /** The mentor's own greetings fold their native rows under «Matters»: look there too. */
    private static boolean offered(ServerPlayer p) {
        if (listed(p)) return true;
        DialogueService.Session session = p.getData(ModAttachments.DIALOGUE);
        int at = session.shown().indexOf(SectGeneralTalk.MATTERS);
        if (at < 0) return false;
        DialogueService.choose(p, at);
        return listed(p);
    }

    /** Close any talk, open the one the routing gives, report the node and whether the show is offered. */
    private static String talk(ServerPlayer p, SectDisciple m, boolean[] offer) {
        DialogueService.close(p, m);
        var route = DialogueService.route(p, m);
        DialogueService.show(p, m, route.id(), route.dialogue(), route.node(), "");
        offer[0] = offered(p);
        String node = route.node();
        DialogueService.close(p, m);
        return node;
    }

    /**
     * The real sequence (author's stand 9.11: «Покажи ещё раз» never appeared): join, take the first lesson, learn the scroll,
     * talk to the mentor. A newcomer with no dantian is offered the show on every routed talk (introduction, journey, and the
     * later «again»), also when he passed the journey without taking it; once the seed forms or the profile is awakened it is gone.
     */
    @GameTest(template = "sect_yard", batch = "mentor_demo_path", timeoutTicks = 40)
    public static void realPathOffersTheShowToANewcomer(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);   // at night the talks are not hubs
        ServerPlayer p = newcomer(h, 12.5, false);
        SectDisciple m = mentor(h, p);
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with("met_mentor"));
        Dialogue d = DialogueLoader.get(m.dialogue());
        h.assertTrue(DialogueService.applyHeadless(p, m, SectGameTests.option(d, "lessons", "dialogue.murim.mentor.opt.take_six", 0)), "take_six refused");
        if (p.getInventory().items.stream().noneMatch(s -> s.is(ModItems.METHOD_SCROLL.get()))) {
            p.getInventory().add(new ItemStack(ModItems.METHOD_SCROLL.get()));
        }
        var cultivation = p.getData(ModAttachments.CULTIVATION);
        var profile = p.getData(ModAttachments.PROFILE);
        MurimMod.LOGGER.info("MENTOR-DEMO state: member={} method={} seeded={} awakened={} cooled={} near={}",
                SectService.state(p).member(), cultivation.method().isPresent(), cultivation.seeded(), profile.isAwakened(),
                MentorDemo.cooledDown(-1L, h.getLevel().getGameTime()), m.distanceToSqr(p) <= 64);
        h.assertTrue(SectService.state(p).member(), "not a member");
        h.assertTrue(!profile.isAwakened() && !cultivation.seeded(), "a new member starts awakened or seeded");
        h.assertTrue(MentorDemo.canAsk(p, m), "canAsk false after join + take_six + scroll in the bag");
        p.setData(ModAttachments.CULTIVATION, cultivation.withMethod(SectService.SIX));   // the scroll is used
        h.assertTrue(MentorDemo.canAsk(p, m), "canAsk false after the scroll was used");
        boolean[] offer = new boolean[1];
        String log = "";
        // 1. the one-time basics offer keeps its story rows
        String node = talk(p, m, offer);
        log += node + ">";
        h.assertTrue(node.equals("basics_offer"), "first talk after the lesson was " + node);
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with("basics.offered"));
        // 2. the introduction
        node = talk(p, m, offer);
        log += node + (offer[0] ? "+" : "-") + ">";
        h.assertTrue(node.equals("intro_1") && offer[0], "introduction does not offer the show: " + log);
        // 3. he skips the introduction and passes the journey without choosing the show
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with("intro.done"));
        node = talk(p, m, offer);
        log += node + (offer[0] ? "+" : "-") + ">";
        h.assertTrue(node.equals("journey") && offer[0], "journey does not offer the show: " + log);
        // 4. a later talk: still offered; the «again» node too
        node = talk(p, m, offer);
        log += node + (offer[0] ? "+" : "-") + ">";
        h.assertTrue(offer[0], "a later talk does not offer the show: " + log);
        DialogueService.show(p, m, m.dialogue(), d, "again", "");
        h.assertTrue(offered(p), "the again node does not offer the show; method=" + p.getData(ModAttachments.CULTIVATION).method().isPresent()
                + " canAsk=" + MentorDemo.canAsk(p, m) + " node=" + p.getData(ModAttachments.DIALOGUE).node() + " shown=" + p.getData(ModAttachments.DIALOGUE).shown());
        DialogueService.close(p, m);
        MurimMod.LOGGER.info("MENTOR-DEMO path: {}", log);
        // 5. the seed forms: gone from every node
        p.setData(ModAttachments.CULTIVATION, p.getData(ModAttachments.CULTIVATION).withBeats(3));
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).seeded(), "setup: the seed did not form");
        node = talk(p, m, offer);
        h.assertTrue(!offer[0], "offered after the seed formed (node " + node + ")");
        DialogueService.show(p, m, m.dialogue(), d, "again", "");
        h.assertTrue(!offered(p), "the again node offers the show after the seed formed");
        DialogueService.close(p, m);
        // an awakened profile (the film stand) is not offered the show either
        p.setData(ModAttachments.CULTIVATION, p.getData(ModAttachments.CULTIVATION).withBeats(0));
        p.setData(ModAttachments.PROFILE, new DantianProfile(profile.capacity(), profile.purity(), profile.meridians(), "fire", profile.imprint(), profile.pool(), profile.circulating(), profile.foundation(), profile.rank(), profile.stage()));
        h.assertTrue(!MentorDemo.canAsk(p, m), "an awakened profile was offered the show");
        m.discard();
        h.succeed();
    }
}
