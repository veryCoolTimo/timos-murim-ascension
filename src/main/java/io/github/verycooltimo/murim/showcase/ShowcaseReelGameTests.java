package io.github.verycooltimo.murim.showcase;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The showcase reel: its timeline, and that a stop puts the player and the world back. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class ShowcaseReelGameTests {
    private ShowcaseReelGameTests() { }

    @GameTest(template = "sect_yard", batch = "showcase", timeoutTicks = 60)
    public static void planIsInTheAuthorsOrder(GameTestHelper h) {
        var plan = ShowcaseShots.plan();
        h.assertTrue(plan.get(0).kind() == ShowcaseShots.Kind.MENTOR_DEMO, "the reel opens with the mentor");
        int lastMentor = -1, firstHead = -1, firstBt = -1, firstTech = -1;
        for (int i = 0; i < plan.size(); i++) {
            switch (plan.get(i).kind()) {
                case MENTOR_WIDE, MENTOR_DEMO, MENTOR_TALK -> lastMentor = i;
                case HALL_WIDE, HEAD_INTRO -> { if (firstHead < 0) firstHead = i; }
                case BREAKTHROUGH -> { if (firstBt < 0) firstBt = i; }
                case TECHNIQUE -> { if (firstTech < 0) firstTech = i; }
            }
        }
        h.assertTrue(lastMentor < firstHead && firstHead < firstBt && firstBt < firstTech, "scene order");
        h.assertTrue(ShowcaseShots.snapCount() >= 15, "screenshots: " + ShowcaseShots.snapCount());
        for (var s : plan) {
            for (int snap : s.snaps()) h.assertTrue(snap > 0 && snap < s.duration(160), s.name() + " snap outside the shot");
        }
        h.assertTrue(ShowcaseShots.totalTicks(160) > ShowcaseShots.totalTicks(40), "hold must lengthen the reel");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "showcase", timeoutTicks = 400)
    public static void stopRestoresPlayerAndRemovesWhatItSpawned(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(12.5, 2, 12.5));
        p.moveTo(at.x, at.y, at.z, 33.0F, 5.0F);
        p.setOnGround(true);
        DantianProfile profile = DantianProfile.INITIAL.withRank(2);
        p.setData(ModAttachments.PROFILE, profile);
        ResourceLocation art = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_rush");
        MasteryState mastery = MasteryState.EMPTY.with(art, TechniqueProgress.learned(2, 12));
        p.setData(ModAttachments.MASTERY, mastery);
        p.getInventory().setItem(3, new ItemStack(Items.APPLE, 7));
        p.getPersistentData().putInt("showcase_probe", 41);
        p.setGameMode(GameType.CREATIVE);

        h.assertTrue(ShowcaseReel.begin(p, 40), "reel did not start");
        h.assertTrue(!ShowcaseReel.begin(p, 40), "second start accepted");
        h.assertTrue(p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "reel did not take survival");
        // pre-roll, then walk the shots by hand: the sect is absent here, its shots skip; breakthrough and technique shots run.
        for (int i = 0; i < ShowcaseReel.PRE_ROLL; i++) ShowcaseReel.tick(p);
        h.assertTrue(ShowcaseReel.shotIndex(p) == 0, "first shot not entered after the pre-roll");
        var plan = ShowcaseShots.plan();
        boolean sawBandit = false, sawRank = false;
        for (int guard = 0; guard < 30000 && ShowcaseReel.running(p) && !(sawBandit && sawRank); guard++) {
            int cur = ShowcaseReel.shotIndex(p);
            // the sect people are absent in the test: shots that wait for them are skipped by hand
            if (cur >= 0 && cur < plan.size() && plan.get(cur).kind().ordinal() <= ShowcaseShots.Kind.HEAD_INTRO.ordinal()) ShowcaseReel.next(p);
            ShowcaseReel.tick(p);
            int idx = ShowcaseReel.shotIndex(p);
            if (idx < 0 || idx >= plan.size()) break;
            if (p.getPersistentData().getCompound(ShowcaseReel.KEY).hasUUID("bandit")) sawBandit = true;
            if (p.getData(ModAttachments.PROFILE).rank() != 2 && plan.get(idx).kind() == ShowcaseShots.Kind.BREAKTHROUGH) sawRank = true;
        }
        h.assertTrue(sawBandit, "no bandit was spawned by a technique shot");
        h.assertTrue(sawRank, "breakthrough shot did not change the rank");

        h.assertTrue(ShowcaseReel.stop(p, false), "stop refused");
        h.assertTrue(!ShowcaseReel.running(p), "state stays after stop");
        h.assertTrue(p.getData(ModAttachments.PROFILE).rank() == 2, "rank not restored: " + p.getData(ModAttachments.PROFILE).rank());
        h.assertTrue(p.getData(ModAttachments.MASTERY).equals(mastery), "arts not restored");
        h.assertTrue(p.getInventory().getItem(3).is(Items.APPLE) && p.getInventory().getItem(3).getCount() == 7, "inventory not restored");
        h.assertTrue(p.getInventory().getItem(p.getInventory().selected).getItem() != io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get()
                || p.getInventory().selected == 3, "the reel's sword stayed in the hand");
        h.assertTrue(p.gameMode.getGameModeForPlayer() == GameType.CREATIVE, "game mode not restored");
        h.assertTrue(p.getPersistentData().getInt("showcase_probe") == 41, "persistent data not restored");
        h.assertTrue(p.position().distanceTo(at) < 0.01D, "position not restored: " + p.position());
        h.assertTrue(!p.isInvulnerable(), "invulnerable flag stayed");
        for (Entity e : h.getLevel().getAllEntities()) {
            h.assertTrue(!e.getTags().contains(ShowcaseReel.TAG), "a spawned entity survived the stop");
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "showcase", timeoutTicks = 100)
    public static void interruptedReelIsUndoneAtNextLogin(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(12.5, 2, 12.5));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(1));
        h.assertTrue(ShowcaseReel.begin(p, 40), "no start");
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(4));
        h.assertTrue(ShowcaseReel.recover(p), "nothing to recover");
        h.assertTrue(!ShowcaseReel.running(p), "reel still active after login");
        h.assertTrue(p.getData(ModAttachments.PROFILE).rank() == 1, "peak rank left behind: " + p.getData(ModAttachments.PROFILE).rank());
        h.succeed();
    }
}
