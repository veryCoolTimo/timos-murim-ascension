package io.github.verycooltimo.murim.world.fortress;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.entity.boss.BossMove;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.entity.boss.BossRules;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Track C, the boss (docs/design/research/hua-main-1810.md section 10, D-2 and D-3): the victory fills the missing qi of the
 * breakthrough to the second rank once and only for it; the master uses the three readable moves of the core; a lost
 * fight resets the fortress in seconds so that the retry is clean and the credit still comes at the end.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class BossCreditGameTests {

    private BossCreditGameTests() {
    }

    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "boss-credit-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        return p;
    }

    /** A player at the given rank with a centre and a pool of {@code share} of the wall of his rank. */
    private static ServerPlayer player(GameTestHelper helper, int rank, double share) {
        ServerPlayer p = fakePlayer(helper);
        DantianProfile profile = DantianProfile.INITIAL.withTags("clear", "debug").withAxes(60.0D, 0.8D, 0.4D).withRank(rank);
        p.setData(ModAttachments.PROFILE, profile.withPool(Realm.wall(profile) * share));
        return p;
    }

    private static FortressMaster master(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos c = helper.absolutePos(new BlockPos(17, 2, 17));
        FortressMaster m = BossRegistry.FORTRESS_MASTER.get().create(level);
        m.settle(c.asLong(), c.offset(0, 0, 10), c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D);
        level.addFreshEntity(m);
        return m;
    }

    private static TrainingDummy dummy(GameTestHelper helper, FortressMaster m, double dx, double dz) {
        TrainingDummy d = ModEntities.DUMMY.get().create(helper.getLevel());
        org.joml.Vector3f y = m.yard();
        d.moveTo(y.x + dx, y.y, y.z + dz, 180.0F, 0.0F);
        helper.getLevel().addFreshEntity(d);
        return d;
    }

    // ------------------------------------------------------------------ D-3: the missing qi, once

    /** The first victory fills the pool up to the wall of the second rank; a second victory, or another rank, adds nothing. */
    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void theFirstVictoryFillsTheMissingQiOnceAndOnlyForTheSecondBreakthrough(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortressMaster m = master(helper);
        ServerPlayer p = player(helper, Realm.THIRD, 0.4D);
        DantianProfile before = p.getData(ModAttachments.PROFILE);
        helper.assertTrue(!Realm.atWall(before), "the test player already stands at the wall");
        Fortresses.defeat(level, m, List.of(p));
        DantianProfile after = p.getData(ModAttachments.PROFILE);
        helper.assertTrue(p.getData(BossRegistry.BOSS_DEFEATED), "no credit");
        helper.assertTrue(Realm.atWall(after) && Math.abs(after.pool() - Realm.wall(after)) < 1.0E-9, "the wall is not full: " + after.pool());
        helper.assertTrue(after.rank() == Realm.THIRD, "the victory changed the rank");

        // A second victory (the master comes back after three days) fills nothing: once.
        DantianProfile spent = after.withPool(after.pool() * 0.5D);
        p.setData(ModAttachments.PROFILE, spent);
        Fortresses.defeat(level, m, List.of(p));
        helper.assertTrue(p.getData(ModAttachments.PROFILE).pool() == spent.pool(), "a second victory filled the pool again");

        // At another rank the credit carries no qi: nothing is banked for a later breakthrough.
        for (int rank : new int[] {Realm.NONE, Realm.SECOND}) {
            ServerPlayer other = player(helper, rank, 0.4D);
            double pool = other.getData(ModAttachments.PROFILE).pool();
            Fortresses.defeat(level, m, List.of(other));
            helper.assertTrue(other.getData(BossRegistry.BOSS_DEFEATED), "no credit at rank " + rank);
            helper.assertTrue(other.getData(ModAttachments.PROFILE).pool() == pool, "qi was given at rank " + rank);
        }
        m.discard();
        helper.succeed();
    }

    /** A player already at the wall gets nothing more, and the pool never goes above it. */
    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void theFillNeverGoesAboveTheWall(GameTestHelper helper) {
        ServerPlayer p = player(helper, Realm.THIRD, 1.0D);
        double wall = Realm.wall(p.getData(ModAttachments.PROFILE));
        helper.assertTrue(!BossCredit.fillWall(p), "something was added at the wall");
        helper.assertTrue(p.getData(ModAttachments.PROFILE).pool() <= wall + 1.0E-9, "the pool is above the wall");
        // an unawakened centre has no wall to fill
        ServerPlayer none = fakePlayer(helper);
        helper.assertTrue(!BossCredit.fillWall(none), "qi given to a player with no centre");
        helper.succeed();
    }

    // ------------------------------------------------------------------ D-2: three readable moves

    /** In every phase the master picks only the three moves of the core: the combo, the pounce and the ram. */
    @GameTest(template = "camp_floor", timeoutTicks = 520)
    public static void theMasterUsesOnlyTheThreeCoreMoves(GameTestHelper helper) {
        FortressMaster m = master(helper);
        TrainingDummy d = dummy(helper, m, 0.0D, 8.0D);
        m.startFight(d);
        Set<String> seen = new HashSet<>();
        helper.onEachTick(() -> {
            if (m.state() == FortressMaster.WINDUP || m.state() == FortressMaster.STRIKE) {
                seen.add(m.move().key());
            }
            // the dummy stays standing: the test is about the choice of moves
            if (d.getHealth() < 50.0F) {
                d.setHealth(d.getMaxHealth());
            }
        });
        helper.runAfterDelay(150, () -> m.setHealth(m.getMaxHealth() * 0.55F));
        helper.runAfterDelay(300, () -> m.setHealth(m.getMaxHealth() * 0.2F));
        helper.runAfterDelay(500, () -> {
            helper.assertTrue(m.phase() == 3, "the fight did not reach the third phase: " + m.phase());
            helper.assertTrue(seen.size() >= 2, "the master used too few moves: " + seen);
            for (String key : seen) {
                // the transition roar of the second phase is a one-off beat, not one of the moves he picks
                helper.assertTrue(key.equals("chain") || key.equals("pounce") || key.equals("ram") || key.equals("roar"),
                        "a move outside the core: " + key + " in " + seen);
            }
            helper.assertTrue(!seen.contains("split") && !seen.contains("whirl"), "split or whirl in the fight: " + seen);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ retry

    /** Every participant fell (here: left the world): the fortress resets within seconds, not half a minute. */
    @GameTest(template = "camp_floor", timeoutTicks = BossRules.DOWN_RESET_TICKS + 120)
    public static void theFortressResetsInSecondsWhenEveryParticipantIsDown(GameTestHelper helper) {
        FortressMaster m = master(helper);
        TrainingDummy d = dummy(helper, m, 0.0D, 6.0D);
        m.startFight(d);
        // a challenger who is no longer in the world: dead, or gone
        m.addParticipant(UUID.randomUUID());
        m.setHealth(m.getMaxHealth() * 0.7F);
        helper.runAfterDelay(10, () -> {
            d.discard();
            m.setTarget(null);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(m.state() == FortressMaster.SIT, "not back in the chair: " + m.state());
            helper.assertFalse(m.arena(), "the barrier stayed up");
            helper.assertTrue(m.getHealth() >= m.getMaxHealth() - 0.01F, "the health did not come back");
        });
    }

    /** After a reset the fight can begin again from the start and its victory still gives the credit and the qi. */
    @GameTest(template = "camp_floor", timeoutTicks = 200)
    public static void theRetryIsCleanAndItsVictoryStillCredits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortressMaster m = master(helper);
        TrainingDummy first = dummy(helper, m, 0.0D, 6.0D);
        m.startFight(first);
        m.setHealth(m.getMaxHealth() * 0.3F);
        m.resetFight();
        helper.assertTrue(m.state() == FortressMaster.SIT && !m.arena() && m.phase() == 1, "the reset is not clean");
        helper.assertTrue(m.getHealth() >= m.getMaxHealth() - 0.01F, "the health did not come back");
        first.discard();
        ServerPlayer p = player(helper, Realm.THIRD, 0.2D);
        TrainingDummy second = dummy(helper, m, 0.0D, 6.0D);
        m.startFight(second);
        helper.assertTrue(m.arena(), "the second fight did not begin");
        m.addParticipant(p.getUUID());
        Fortresses.defeat(level, m, List.of(p));
        helper.assertTrue(p.getData(BossRegistry.BOSS_DEFEATED), "the retry's victory gave no credit");
        helper.assertTrue(Realm.atWall(p.getData(ModAttachments.PROFILE)), "the retry's victory gave no qi");
        m.discard();
        helper.succeed();
    }
}
