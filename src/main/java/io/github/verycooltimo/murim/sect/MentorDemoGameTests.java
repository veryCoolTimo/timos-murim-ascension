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
}
