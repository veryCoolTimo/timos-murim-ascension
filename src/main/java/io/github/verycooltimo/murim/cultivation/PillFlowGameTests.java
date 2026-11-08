package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.PillPayloads;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serverside GameTests of the pill rules of 25.10 (the author's numbers; the game «Провести поток» of the mockup): the qi of a pill is
 * fixed and never a share of the wall, the rank rule (two below tears, one below halves, own, +10 %, +20 %), a body that does not endure is
 * torn (by one pill or by the total load), the first try at such a pill is only a warning, several pills go into one stream with +10 % for
 * a clean one, the same pill again in a row is weaker and a combination resets it, a pill eaten and not sat on gives a fifth, the qi
 * beyond the wall is lost.
 *
 * <p>The player is a real {@link ServerPlayer} on an embedded channel (vanilla packets go nowhere; our payloads are skipped by the
 * {@code hasChannel} guard in {@link PillService}). The client's game is replaced by its report ({@link PillService#report}), at a game
 * time the test chooses.
 *
 * <p>API: reference/minecraft-src/net/minecraft/server/network/ServerGamePacketListenerImpl.java (the constructor sets
 * player.connection), reference/minecraft-src/net/minecraft/world/level/CollisionGetter.java#noCollision.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PillFlowGameTests {

    private static final String FLOOR = "small_floor";

    private PillFlowGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer player(GameTestHelper helper, int rank) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "pill-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(),
                cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(
                net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        // The framework encases every test in barriers: the sky above the seated player is a barrier ceiling at y 5. Open it.
        for (int dx = 1; dx <= 3; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                helper.setBlock(new BlockPos(dx, 5, dz), Blocks.AIR);
            }
        }
        Vec3 at = helper.absoluteVec(new Vec3(2.5D, 3.0D, 2.5D));
        p.setPos(at.x, at.y, at.z);
        DantianProfile profile = DantianProfile.INITIAL.withTags("clear", "debug").withAxes(60.0D, 0.8D, 0.4D);
        // the capacity grows with the rank (x1.8 each): the wall is 240 * 1.8^rank
        profile = profile.withRank(rank);
        p.setData(ModAttachments.PROFILE, profile.withPool(Realm.wall(profile) * 0.1D).withCirculating(profile.maxCirculating()));
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.empty(), CultivationState.SEEDED));
        p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        p.getRandom().setSeed(2510L);
        return p;
    }

    private static DantianProfile profile(ServerPlayer p) {
        return p.getData(ModAttachments.PROFILE);
    }

    private static PillState pills(ServerPlayer p) {
        return p.getData(ModAttachments.PILLS);
    }

    private static PillService.Slot slot(ServerPlayer p) {
        return p.getData(ModAttachments.ABSORB);
    }

    private static void withPool(ServerPlayer p, double pool) {
        p.setData(ModAttachments.PROFILE, profile(p).withPool(pool));
    }

    /** Eats the pills standing (several inside one window) and sits down; the game starts. */
    private static void sit(ServerPlayer p, PillKind... kinds) {
        p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        for (PillKind k : kinds) {
            if (!PillService.eat(p, k)) {
                PillService.eat(p, k); // a pill the body will not endure is only warned about at the first try
            }
        }
        p.setData(ModAttachments.MEDITATION, MeditationState.started(true, CultivationState.SEEDED));
        PillService.onMeditationStart(p);
        // These legacy report fixtures isolate the interactive quality/interrupt contract. The automatic production
        // entry path is exercised separately by ordinaryPillsFinishByTheServerClock and assistedGreatElixirIsSafe.
        if (slot(p).flow != null) slot(p).flow.automatic = false;
    }

    /** The client's report of a finished game, at the earliest honest tick. */
    private static void finish(ServerPlayer p, PillPayloads.Outcome outcome, int progress, int lost) {
        PillService.Session flow = slot(p).flow;
        PillService.report(p, flow.id, outcome, progress, lost, Math.max(flow.earliest, flow.startTick));
    }

    /** One clean sitting: the stream ran to the end without a loss. */
    private static void clean(ServerPlayer p, PillKind... kinds) {
        sit(p, kinds);
        finish(p, PillPayloads.Outcome.DONE, 1000, 0);
    }

    /** A fresh fake player is spawn-protected for 60 ticks and never ticks: lift the protection so hits land. */
    private static void unprotect(ServerPlayer p) {
        try {
            java.lang.reflect.Field f = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            f.setAccessible(true);
            f.setInt(p, 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeWindow(ServerPlayer p) {
        PillState s = pills(p);
        p.setData(ModAttachments.PILLS, new PillState(s.taken(), s.poisonResistant(), s.pending(), s.first(),
                p.level().getGameTime() - 1, s.streak(), s.streakCount(), s.streakAt(), s.reserve(), s.tolerances()));
    }

    private static double gained(ServerPlayer p, double before) {
        return profile(p).pool() - before;
    }

    /** One clean sitting from an empty pool: what the pills paid (the wall is never in the way). */
    private static double take(ServerPlayer p, PillKind... kinds) {
        withPool(p, 0.0D);
        clean(p, kinds);
        return profile(p).pool();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void pillAchievementsFollowAbsorptionAndTearing(GameTestHelper h) {
        ServerPlayer ordinary = player(h, Realm.THIRD);
        clean(ordinary, PillKind.VITALITY);
        io.github.verycooltimo.murim.advancement.AchievementGameTests.done(h, ordinary, "first_pill");

        ServerPlayer full = player(h, Realm.THIRD);
        withPool(full, Realm.wall(profile(full)));
        clean(full, PillKind.VITALITY);
        io.github.verycooltimo.murim.advancement.AchievementGameTests.pending(h, full, "first_pill");

        ServerPlayer greedy = player(h, Realm.THIRD);
        h.assertFalse(PillService.eat(greedy, PillKind.ORIGIN_ENERGY), "Too-high pill was not warned about");
        io.github.verycooltimo.murim.advancement.AchievementGameTests.pending(h, greedy, "torn_meridians");
        sit(greedy, PillKind.ORIGIN_ENERGY);
        io.github.verycooltimo.murim.advancement.AchievementGameTests.done(h, greedy, "torn_meridians");
        io.github.verycooltimo.murim.advancement.AchievementGameTests.pending(h, greedy, "first_pill");
        io.github.verycooltimo.murim.advancement.AchievementGameTests.pending(h, greedy, "great_elixir");

        ServerPlayer great = player(h, Realm.PEAK);
        great.setData(ModAttachments.PROFILE, profile(great).withAxes(600.0D, 0.8D, 0.4D).withPool(0.0D));
        clean(great, PillKind.ORIGIN_ENERGY);
        io.github.verycooltimo.murim.advancement.AchievementGameTests.done(h, great, "great_elixir");
        io.github.verycooltimo.murim.advancement.AchievementGameTests.done(h, great, "first_pill");
        h.succeed();
    }

    // ------------------------------------------------------------------ fixed qi

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void theQiOfAPillIsFixedAndNotAShareOfTheWall(GameTestHelper h) {
        // the same rank, two different walls (a small and a big dantian): the same qi
        ServerPlayer small = player(h, Realm.THIRD);
        ServerPlayer big = player(h, Realm.THIRD);
        big.setData(ModAttachments.PROFILE, profile(big).withAxes(240.0D, 0.8D, 0.4D));
        withPool(small, 0.0D);
        withPool(big, 0.0D);
        h.assertTrue(Realm.wall(profile(big)) > Realm.wall(profile(small)) * 1.5D, "setup: the walls do not differ");
        clean(small, PillKind.VITALITY);
        clean(big, PillKind.VITALITY);
        h.assertTrue(Math.abs(profile(small).pool() - 35.0D) < 1.0E-6D, "the Vitality pill on a small wall: " + profile(small).pool());
        h.assertTrue(Math.abs(profile(big).pool() - 35.0D) < 1.0E-6D, "the Vitality pill on a big wall: " + profile(big).pool());
        // the table of the author
        int[][] table = {{PillKind.VITALITY.ordinal(), 35, Realm.THIRD}, {PillKind.SNOW_PLUM.ordinal(), 250, Realm.SECOND},
                {PillKind.ORIGIN_ENERGY.ordinal(), 1500, Realm.PEAK}, {PillKind.THOUSAND_POISON.ordinal(), 1800, Realm.PEAK},
                {PillKind.BLUE.ordinal(), 2200, Realm.TRANSCENDENT}, {PillKind.BLOOD.ordinal(), 2800, Realm.TRANSCENDENT},
                {PillKind.BEAUTY_TEAR.ordinal(), 3500, Realm.TRANSCENDENT}, {PillKind.THOUSAND_GINSENG.ordinal(), 300, Realm.SECOND},
                {PillKind.THOUSAND_LINGZHI.ordinal(), 350, Realm.SECOND},
                {PillKind.SMALL_RESTORATION.ordinal(), 200, Realm.THIRD},
                {PillKind.HEAVENLY_EARTH.ordinal(), 250, Realm.SECOND},
                {PillKind.MOONLIGHT.ordinal(), 250, Realm.SECOND},
                {PillKind.ASCENDING_DRAGON.ordinal(), 400, Realm.SECOND},
                {PillKind.GREAT_RESTORATION.ordinal(), 1500, Realm.FIRST}};
        for (int[] row : table) {
            PillKind k = PillKind.byId(row[0]);
            h.assertTrue(k.qi() == row[1] && k.rank() == row[2], k + " is not " + row[1] + " qi for rank " + row[2]);
        }
        for (PillKind k : PillKind.values()) {
            if (k.utility()) {
                h.assertTrue(k.qi() == 0, k + " is a utility pill with qi");
            }
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void theRankRuleScalesTheQi(GameTestHelper h) {
        // Snow Plum is made for the second rank: own x1, one above x1.1, two or more above x1.2, one below x0.5
        double[][] expect = {{Realm.THIRD, 0.5D}, {Realm.SECOND, 1.0D}, {Realm.FIRST, 1.1D}, {Realm.PEAK, 1.2D}, {Realm.TRANSCENDENT, 1.2D}};
        for (double[] row : expect) {
            ServerPlayer p = player(h, (int) row[0]);
            withPool(p, 0.0D);
            clean(p, PillKind.SNOW_PLUM);
            double want = 250.0D * row[1];
            h.assertTrue(Math.abs(profile(p).pool() - want) < 1.0E-6D,
                    "rank " + (int) row[0] + " took " + profile(p).pool() + " of the Snow Plum pill, expected " + want);
        }
        h.assertTrue(PillRules.mult(-2) == 0.0D && PillRules.mult(-5) == 0.0D, "two ranks below must not give qi");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aHardPillIsFlaggedToTheClient(GameTestHelper h) {
        ServerPlayer one = player(h, Realm.THIRD);
        sit(one, PillKind.SNOW_PLUM);
        h.assertTrue(slot(one).flow != null && slot(one).flow.plan.hard(), "a pill one rank above did not make the game hard");
        ServerPlayer own = player(h, Realm.SECOND);
        sit(own, PillKind.SNOW_PLUM);
        h.assertTrue(slot(own).flow != null && !slot(own).flow.plan.hard(), "a pill of the own rank made the game hard");
        h.succeed();
    }

    // ------------------------------------------------------------------ torn

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aPillTwoRanksAboveTearsTheBody(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.THIRD);
        unprotect(p);
        p.setHealth(p.getMaxHealth());
        double pool = profile(p).pool();
        double max = p.getMaxHealth();
        double capacity = profile(p).capacity();
        sit(p, PillKind.ORIGIN_ENERGY); // rank 4 for a rank 1 body
        h.assertTrue(slot(p).flow == null, "a torn body played the game");
        h.assertTrue(pills(p).pending().isEmpty(), "the torn pill is still in the window");
        h.assertTrue(Math.abs(p.getHealth() - (max - max * 0.5D)) < 0.01D, "half of the max health expected, health " + p.getHealth());
        h.assertTrue(Math.abs(profile(p).pool() - pool * (1.0D - PillRules.TORN_POOL_LOSS)) < 1.0E-6D, "the pool was not knocked: " + profile(p).pool());
        h.assertTrue(profile(p).circulating() == 0.0D, "the circulating qi survived");
        h.assertTrue(profile(p).capacity() == capacity, "the dantian grew from a torn pill");
        h.assertTrue(slot(p).standAt >= 0L, "a torn body was not stood up");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aTooBigLoadTearsTheBodyEvenWhenEveryPillIsOk(GameTestHelper h) {
        // a Peak body: wall 240 * 1.8^4 = 2519, 1.5 walls = 3779. 1500 + 1800 + 2200 * 0.5 (one rank above the body) = 4400
        ServerPlayer p = player(h, Realm.PEAK);
        double wall = Realm.wall(profile(p));
        PillRules.Plan single = PillRules.plan(List.of(PillKind.BLUE), Realm.PEAK, wall, 1.0D);
        h.assertTrue(!single.torn() && single.hard(), "one Blue elixir must be hard but bearable at Peak");
        PillRules.Plan two = PillRules.plan(List.of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON), Realm.PEAK, wall, 1.0D);
        h.assertTrue(!two.torn(), "3300 at a wall of " + wall + " must be borne");
        PillRules.Plan three = PillRules.plan(List.of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON, PillKind.BLUE), Realm.PEAK, wall, 1.0D);
        h.assertTrue(three.torn() && three.overload() && !three.tooHigh(), "4400 at a wall of " + wall + " must tear by the load");
        unprotect(p);
        sit(p, PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON, PillKind.BLUE);
        h.assertTrue(slot(p).flow == null && pills(p).pending().isEmpty(), "the overloaded body played the game");
        h.assertTrue(p.getHealth() <= p.getMaxHealth() * 0.5F + 0.01F, "the load did not hurt: " + p.getHealth());
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aTornBodyIsAlsoTornWhenTheWindowRunsOut(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.THIRD);
        unprotect(p);
        p.setHealth(p.getMaxHealth());
        double pool = profile(p).pool();
        p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        if (!PillService.eat(p, PillKind.ORIGIN_ENERGY)) {
            PillService.eat(p, PillKind.ORIGIN_ENERGY);
        }
        h.assertTrue(pills(p).windowOpen(p.level().getGameTime()), "the confirmed pill did not open the window");
        closeWindow(p);
        PillService.expireWindow(p);
        h.assertTrue(p.getHealth() < p.getMaxHealth(), "a torn pill left without sitting did not tear");
        h.assertTrue(profile(p).pool() < pool, "the pool was not knocked");
        h.succeed();
    }

    // ------------------------------------------------------------------ the first-try warning

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aPillThatWouldTearIsOnlyWarnedAboutAtTheFirstTry(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.THIRD);
        h.assertFalse(PillService.eat(p, PillKind.ORIGIN_ENERGY), "the first try ate a pill that tears the body");
        h.assertTrue(pills(p).pending().isEmpty(), "the warned pill is in the window");
        h.assertTrue(slot(p).warnKind == PillKind.ORIGIN_ENERGY, "the warning was not remembered");
        // the second try within the time confirms
        h.assertTrue(PillService.eat(p, PillKind.ORIGIN_ENERGY), "the second try did not confirm the pill");
        h.assertTrue(pills(p).pending().equals(List.of(PillKind.ORIGIN_ENERGY)), "the confirmed pill is not in the window");
        // the confirmation is gone: a later try (after the time) warns again
        ServerPlayer q = player(h, Realm.THIRD);
        h.assertFalse(PillService.eat(q, PillKind.THOUSAND_POISON), "setup");
        slot(q).warnAt = q.level().getGameTime() - PillRules.WARN_TICKS - 5;
        h.assertFalse(PillService.eat(q, PillKind.THOUSAND_POISON), "a stale warning confirmed the pill");
        // a pill the body can bear is never warned about
        ServerPlayer r = player(h, Realm.SECOND);
        h.assertTrue(PillService.eat(r, PillKind.SNOW_PLUM), "a bearable pill was refused");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aLoadThatWouldTearIsWarnedAboutToo(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.PEAK);
        p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        h.assertTrue(PillService.eat(p, PillKind.ORIGIN_ENERGY), "setup");
        h.assertTrue(PillService.eat(p, PillKind.THOUSAND_POISON), "setup 2");
        h.assertFalse(PillService.eat(p, PillKind.BLUE), "the pill that overloads the body was eaten at the first try");
        h.assertTrue(pills(p).pending().size() == 2, "the warned pill joined the window");
        h.assertTrue(PillService.eat(p, PillKind.BLUE), "the second try did not confirm");
        h.assertTrue(pills(p).pending().size() == 3, "three pills expected in the window");
        h.succeed();
    }

    // ------------------------------------------------------------------ several in one stream

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void severalPillsGoIntoOneStreamAndACleanOneGivesTenPercentForEveryExtra(GameTestHelper h) {
        // a Peak body (wall 2519): Snow Plum (two ranks below the body: x1.2 -> 300) and Origin Energy (own, 1500); one stream, one game
        ServerPlayer p = player(h, Realm.PEAK);
        withPool(p, 0.0D);
        sit(p, PillKind.SNOW_PLUM, PillKind.ORIGIN_ENERGY);
        h.assertTrue(slot(p).flow != null && slot(p).flow.kinds.size() == 2, "two pills did not make one stream");
        finish(p, PillPayloads.Outcome.DONE, 1000, 0);
        double clean = 1300.0D * 1.1D;
        h.assertTrue(Math.abs(profile(p).pool() - clean) < 1.0E-6D, "a clean pair: expected " + clean + ", got " + profile(p).pool());
        // three pills: +20 %
        PillRules.Plan plan = PillRules.plan(List.of(PillKind.SNOW_PLUM, PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_GINSENG),
                Realm.PEAK, Realm.wall(profile(p)), 1.0D);
        h.assertTrue(Math.abs(plan.gain(1.0D) - (300 + 1000 + 360) * 1.2D) < 1.0E-6D, "three pills: " + plan.gain(1.0D));
        // not clean (quality under 80 %): no bonus
        ServerPlayer d = player(h, Realm.PEAK);
        withPool(d, 0.0D);
        sit(d, PillKind.SNOW_PLUM, PillKind.ORIGIN_ENERGY);
        finish(d, PillPayloads.Outcome.DONE, 1000, 400); // lost 40 % -> quality 60 %
        h.assertTrue(Math.abs(profile(d).pool() - 1300.0D * 0.6D) < 1.0E-6D, "a muddy pair got " + profile(d).pool());
        // exactly at the clean line the bonus is still there
        ServerPlayer e = player(h, Realm.PEAK);
        withPool(e, 0.0D);
        sit(e, PillKind.SNOW_PLUM, PillKind.ORIGIN_ENERGY);
        finish(e, PillPayloads.Outcome.DONE, 1000, 200);
        h.assertTrue(Math.abs(profile(e).pool() - 1300.0D * 0.8D * 1.1D) < 1.0E-6D, "a pair at 80 % got " + profile(e).pool());
        // the streams merge: the load of every pill counts with its own rank rule
        PillRules.Plan mixed = PillRules.plan(List.of(PillKind.SNOW_PLUM, PillKind.ORIGIN_ENERGY), Realm.PEAK, 10_000.0D, 1.0D);
        h.assertTrue(Math.abs(mixed.load() - (250 * 1.2D + 1500)) < 1.0E-6D, "the load is the sum of qi times the rank rule: " + mixed.load());
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void onlyThreeDifferentPillsGoIntoOneWindow(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.PEAK);
        p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        h.assertTrue(PillService.eat(p, PillKind.ORIGIN_ENERGY), "setup");
        h.assertFalse(PillService.eat(p, PillKind.ORIGIN_ENERGY), "two of the same were eaten");
        h.assertTrue(PillService.check(p, PillKind.ORIGIN_ENERGY, profile(p), pills(p)) == PillRules.Refusal.SAME_KIND, "wrong refusal");
        h.assertTrue(PillService.eat(p, PillKind.THOUSAND_POISON), "the second kind was refused");
        h.assertTrue(PillService.eat(p, PillKind.THOUSAND_GINSENG), "the third kind was refused");
        h.assertTrue(PillService.check(p, PillKind.THOUSAND_LINGZHI, profile(p), pills(p)) == PillRules.Refusal.FULL, "a fourth pill was accepted");
        h.succeed();
    }

    // ------------------------------------------------------------------ tolerance

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void sameKindToleranceIsIndependent(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.SECOND);
        h.assertTrue(Math.abs(take(p, PillKind.SNOW_PLUM) - 250.0D) < 1.0E-6D, "the first pill: " + profile(p).pool());
        double second = take(p, PillKind.SNOW_PLUM);
        h.assertTrue(Math.abs(second - 187.5D) < 1.0E-6D, "the second pill in a row must be x0.75: " + second);
        double third = take(p, PillKind.SNOW_PLUM);
        h.assertTrue(Math.abs(third - 140.625D) < 1.0E-6D, "the third pill in a row must be x0.5625: " + third);
        // A different kind has its own tolerance and does not clear the first kind.
        double other = take(p, PillKind.THOUSAND_GINSENG);
        h.assertTrue(Math.abs(other - 300.0D) < 1.0E-6D, "a different pill is not weakened: " + other);
        double again = take(p, PillKind.SNOW_PLUM);
        h.assertTrue(Math.abs(again - 105.46875D) < 1.0E-6D, "the old tolerance was cleared by a different pill: " + again);
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aCombinationNeitherIsWeakenedNorKeepsTheStreak(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.SECOND);
        take(p, PillKind.SNOW_PLUM);
        take(p, PillKind.SNOW_PLUM);
        h.assertTrue(pills(p).streakOf(PillKind.SNOW_PLUM, p.level().getGameTime()) == 2, "setup: the streak is not two");
        // eaten together with another pill, the same pill is full strength (no tolerance applies to a combination) ...
        double together = take(p, PillKind.SNOW_PLUM, PillKind.THOUSAND_GINSENG);
        h.assertTrue(Math.abs(together - (250.0D + 300.0D) * 1.1D) < 1.0E-6D, "a combination was weakened: " + together);
        // ... and it resets the streak
        h.assertTrue(pills(p).streakOf(PillKind.SNOW_PLUM, p.level().getGameTime()) == 0, "a combination kept the streak");
        double after = take(p, PillKind.SNOW_PLUM);
        h.assertTrue(Math.abs(after - 250.0D) < 1.0E-6D, "the pill after a combination was weakened: " + after);
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aQuarterDayRecoversOneToleranceStep(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.SECOND);
        take(p, PillKind.SNOW_PLUM);
        // the report of the test carries its own clock: count the day from the moment the streak was written
        long now = pills(p).streakAt();
        h.assertTrue(pills(p).streakOf(PillKind.SNOW_PLUM, now) == 1, "setup");
        h.assertTrue(pills(p).streakOf(PillKind.SNOW_PLUM, now + PillRules.TOLERANCE_RECOVERY_TICKS - 1) == 1, "the streak ended before the day");
        h.assertTrue(pills(p).streakOf(PillKind.SNOW_PLUM, now + PillRules.TOLERANCE_RECOVERY_TICKS) == 0, "a full day did not reset the streak");
        PillState s = pills(p);
        p.setData(ModAttachments.PILLS, new PillState(s.taken(), s.poisonResistant(), s.pending(), s.first(), s.deadline(),
                s.streak(), s.streakCount(), p.level().getGameTime() - PillRules.TOLERANCE_RECOVERY_TICKS - 1L, s.reserve()));
        double next = take(p, PillKind.SNOW_PLUM);
        h.assertTrue(Math.abs(next - 250.0D) < 1.0E-6D, "the pill the next day was weakened: " + next);
        h.succeed();
    }

    // Author playtest 27.10: every suspected zero, for awakened NONE and THIRD players.
    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void beginnersConfirmRankTearingAndReceiveZero(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            withPool(p, 0.0D);
            h.assertFalse(PillService.eat(p, PillKind.ORIGIN_ENERGY), "dangerous pill was not warned");
            h.assertTrue(pills(p).pending().isEmpty(), "warning consumed a pill");
            sit(p, PillKind.ORIGIN_ENERGY);
            h.assertTrue(profile(p).pool() == 0.0D && !PillService.absorbing(p), "rank tearing paid qi");
            h.assertTrue(p.getHealth() < p.getMaxHealth(), "rank tearing did not hurt");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void beginnersConfirmOverloadAndReceiveZero(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            p.setData(ModAttachments.PROFILE, profile(p).withAxes(20.0D, 0.8D, 0.4D).withPool(0.0D));
            h.assertTrue(PillService.eat(p, PillKind.VITALITY), "Vitality refused");
            h.assertFalse(PillService.eat(p, PillKind.THOUSAND_GINSENG), "overload not warned");
            h.assertTrue(pills(p).pending().size() == 1, "warning consumed herb");
            sit(p, PillKind.THOUSAND_GINSENG);
            h.assertTrue(profile(p).pool() == 0.0D && !PillService.absorbing(p), "overload paid qi");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aFullBeginnerPoolReallyStoresZero(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            double wall = Realm.wall(profile(p));
            withPool(p, wall);
            h.assertFalse(PillService.eat(p, PillKind.VITALITY), "FULL was not warned");
            clean(p, PillKind.VITALITY);
            h.assertTrue(profile(p).pool() == wall, "full pool grew beyond wall");
            h.assertTrue(pills(p).taken(PillKind.VITALITY) == 1, "confirmed pill was not consumed");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void burstIsNotZeroUnlessTheBeginnerPoolIsFull(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            for (boolean full : new boolean[] {false, true}) {
                ServerPlayer p = player(h, rank);
                double before = full ? Realm.wall(profile(p)) : 0.0D;
                withPool(p, before);
                sit(p, PillKind.VITALITY);
                finish(p, PillPayloads.Outcome.BURST, 0, 1000);
                h.assertTrue(Math.abs(gained(p, before) - (full ? 0.0D : 3.5D)) < 1.0E-6D, "burst settlement wrong");
            }
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void hitDeviationIsNotZeroUnlessTheBeginnerPoolIsFull(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            for (boolean full : new boolean[] {false, true}) {
                ServerPlayer p = player(h, rank);
                double before = full ? Realm.wall(profile(p)) : 0.0D;
                withPool(p, before);
                sit(p, PillKind.VITALITY);
                PillService.onMeditationStop(p, true);
                PillService.deviate(p);
                h.assertTrue(Math.abs(gained(p, before) - (full ? 0.0D : 7.0D)) < 1.0E-6D, "hit settlement wrong");
                h.assertTrue(p.getHealth() < p.getMaxHealth(), "hit deviation did not hurt");
            }
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void injuredOrBlockedMeridiansDoNotZeroPillAbsorption(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            p.setData(ModAttachments.PROFILE, Realm.injure(profile(p)));
            h.assertTrue(Math.abs(take(p, PillKind.VITALITY) - 35.0D) < 1.0E-6D, "injured meridians reduced pills");
            p.setData(ModAttachments.PILLS, PillState.NONE);
            p.setData(ModAttachments.PROFILE, profile(p).withAxes(profile(p).capacity(), profile(p).purity(), 0.0D));
            h.assertTrue(Math.abs(take(p, PillKind.VITALITY) - 35.0D) < 1.0E-6D, "blocked meridians reduced pills");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void toleranceNoLongerRoundsBeginnerPillsToZero(GameTestHelper h) {
        h.assertTrue(Math.round(35.0D * Math.pow(0.5D, 7)) == 0L, "old rounded-zero reproduction changed");
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            long now = p.level().getGameTime();
            p.setData(ModAttachments.PILLS, new PillState(java.util.Map.of(), false, List.of(), 0, 0,
                    PillKind.VITALITY.name(), 2000, now, PillState.Reserve.NONE));
            h.assertTrue(Math.abs(take(p, PillKind.VITALITY) - 8.75D) < 1.0E-6D, "tolerance floor did not pay");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void fullComboRefusesOnlyTheFourthPill(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            p.setData(ModAttachments.PILLS, pills(p).withEaten(PillKind.VITALITY, p.level().getGameTime())
                    .withEaten(PillKind.THOUSAND_GINSENG, p.level().getGameTime())
                    .withEaten(PillKind.THOUSAND_LINGZHI, p.level().getGameTime()));
            h.assertTrue(PillService.check(p, PillKind.SNOW_PLUM, profile(p), pills(p)) == PillRules.Refusal.FULL, "fourth accepted");
            h.assertFalse(PillService.eat(p, PillKind.SNOW_PLUM), "FULL consumed fourth pill");
            h.assertTrue(pills(p).pending().size() == 3 && profile(p).pool() > 0, "FULL changed pending pills or pool");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void staleReservesDoNotStealNewPillQi(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            p.setData(ModAttachments.PILLS, pills(p).withReserve(new PillState.Reserve(100, 0, 0, 0, 0)));
            PillService.tickReserve(p);
            h.assertFalse(pills(p).reserve().active(), "stale reserve stayed active");
            h.assertTrue(Math.abs(take(p, PillKind.VITALITY) - 35) < 1.0E-6D, "stale reserve stole new qi");
            withPool(p, Realm.wall(profile(p)));
            p.setData(ModAttachments.PILLS, pills(p).withReserve(new PillState.Reserve(100, 0, 0, 0, 20)));
            PillService.tickReserve(p);
            h.assertTrue(pills(p).reserve().pool() == 100, "full pool lost waiting reserve");
            double before = profile(p).pool();
            clean(p, PillKind.VITALITY);
            h.assertTrue(profile(p).pool() == before, "full pool with reserve grew");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void expiredWindowsPayStandingAndSittingPaysNothingTwice(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            withPool(p, 0);
            PillService.eat(p, PillKind.VITALITY);
            closeWindow(p);
            p.setData(ModAttachments.MEDITATION, MeditationState.started(true, CultivationState.SEEDED));
            PillService.onMeditationStart(p);
            h.assertTrue(Math.abs(profile(p).pool() - 7) < 1.0E-6D && !PillService.absorbing(p), "expired pill not paid standing");
            PillService.onMeditationStart(p);
            h.assertTrue(Math.abs(profile(p).pool() - 7) < 1.0E-6D, "expired pill paid twice");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void fiveBeginnerVitalityPillsHaveUsefulDiminishingReturns(GameTestHelper h) {
        double[] expected = {35, 26.25D, 19.6875D, 14.765625D, 11.07421875D};
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            for (double qi : expected) h.assertTrue(Math.abs(take(p, PillKind.VITALITY) - qi) < 1.0E-6D, "Vitality schedule mismatch");
            for (int i = 0; i < 8; i++) h.assertTrue(take(p, PillKind.VITALITY) >= 8.75D, "below floor");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void naturalTreasuresAreGentleAtBothBeginnerRanks(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            for (PillKind herb : new PillKind[] {PillKind.THOUSAND_GINSENG, PillKind.THOUSAND_LINGZHI}) {
                ServerPlayer p = player(h, rank);
                var plan = PillRules.plan(List.of(herb), rank, 1.0D, 1.0D);
                h.assertTrue(plan.hard() && !plan.torn(), "herb tore a small beginner body");
                h.assertTrue(Math.abs(take(p, herb) - herb.qi() * 0.5D) < 1.0E-6D, "herb did not pay half");
                h.assertTrue(p.getHealth() == p.getMaxHealth(), "herb hurt beginner");
                ServerPlayer small = player(h, rank);
                small.setData(ModAttachments.PROFILE, profile(small).withAxes(10, 0.8D, 0.4D).withPool(0));
                sit(small, herb);
                h.assertTrue(PillService.absorbing(small) && slot(small).flow.plan.hard(), "small body did not get harder herb game");
                finish(small, PillPayloads.Outcome.DONE, 1000, 0);
                h.assertTrue(profile(small).pool() == 40 && small.getHealth() == small.getMaxHealth(), "gentle overload tore small body");
            }
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void independentToleranceRecoversStepByStepAndSurvivesSaving(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            long now = p.level().getGameTime();
            PillState state = PillState.NONE;
            for (int i = 0; i < 3; i++) state = state.afterIntake(List.of(PillKind.VITALITY), now);
            state = state.afterIntake(List.of(PillKind.THOUSAND_GINSENG), now + 1000);
            var saved = PillState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, state).getOrThrow();
            state = PillState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, saved).getOrThrow();
            h.assertTrue(state.streakOf(PillKind.VITALITY, now + 5999) == 3, "early recovery");
            h.assertTrue(state.streakOf(PillKind.VITALITY, now + 6000) == 2, "not one step at 6000");
            h.assertTrue(state.streakOf(PillKind.VITALITY, now + 12000) == 1, "not one step at 12000");
            h.assertTrue(state.streakOf(PillKind.VITALITY, now + 18000) == 0, "not recovered");
            h.assertTrue(state.streakOf(PillKind.THOUSAND_GINSENG, now + 6000) == 1, "other kind recovered too soon");
            p.setData(ModAttachments.PILLS, state);
            h.assertTrue(Math.abs(take(p, PillKind.VITALITY) - 35 * Math.pow(0.75D, 3)) < 1.0E-6D, "saved tolerance not applied");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void richBeginnerHerbSchedulePaysThirteenHundredBeforeTheWall(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            double total = 0.0D;
            PillState state = PillState.NONE;
            for (int i = 0; i < 8; i++) {
                PillKind herb = i % 2 == 0 ? PillKind.THOUSAND_GINSENG : PillKind.THOUSAND_LINGZHI;
                long now = i * (long) PillKind.HERB_COOLDOWN;
                double factor = PillRules.tolerance(state, List.of(herb), now);
                var plan = PillRules.plan(List.of(herb), rank, 240, factor);
                h.assertTrue(!plan.torn() && plan.hard(), "herb schedule tore beginner");
                total += plan.gain(1.0D);
                state = state.afterIntake(List.of(herb), now);
            }
            h.assertTrue(Math.abs(total - 1300) < 1.0E-6D, "herb schedule total wrong");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void noDantianRefusesWithoutConsumingAndPaysNoSession(GameTestHelper h) {
        for (int rank : new int[] {Realm.NONE, Realm.THIRD}) {
            ServerPlayer p = player(h, rank);
            p.setData(ModAttachments.PROFILE, profile(p).withTags("none", "none").withPool(0));
            var stack = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.PILL_VITALITY.get(), 2);
            stack.getItem().finishUsingItem(stack, p.level(), p);
            h.assertTrue(stack.getCount() == 2 && pills(p).pending().isEmpty(), "no dantian consumed item");
            h.assertTrue(profile(p).pool() == 0 && !PillService.absorbing(p), "no dantian paid qi");
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ standing and the game

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aPillNotSatOnGivesAFifth(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.SECOND);
        withPool(p, 0.0D);
        p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        h.assertTrue(PillService.eat(p, PillKind.SNOW_PLUM), "setup");
        h.assertTrue(profile(p).pool() == 0.0D, "a pill paid before the window closed");
        closeWindow(p);
        PillService.expireWindow(p);
        h.assertTrue(Math.abs(profile(p).pool() - 250.0D * PillRules.STANDING_SHARE) < 1.0E-6D, "standing gave " + profile(p).pool());
        h.assertFalse(pills(p).reserve().active(), "the rest of the pill went to a reserve (it is lost)");
        h.assertTrue(pills(p).taken(PillKind.SNOW_PLUM) == 1, "the pill was not counted");
        // the tolerance counts the standing intake too
        h.assertTrue(pills(p).streakOf(PillKind.SNOW_PLUM, p.level().getGameTime()) == 1, "standing did not count for the tolerance");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void theGameDecidesBetweenFortyAndAHundredPercent(GameTestHelper h) {
        double[][] expect = {{0, 1.0D}, {100, 0.9D}, {300, 0.7D}, {600, 0.4D}, {900, 0.4D}};
        for (double[] row : expect) {
            ServerPlayer p = player(h, Realm.SECOND);
            withPool(p, 0.0D);
            sit(p, PillKind.SNOW_PLUM);
            finish(p, PillPayloads.Outcome.DONE, 1000, (int) row[0]);
            double want = 250.0D * row[1];
            h.assertTrue(Math.abs(profile(p).pool() - want) < 1.0E-6D, "lost " + row[0] / 10.0D + " % gave " + profile(p).pool() + ", expected " + want);
        }
        // a timeout is paid by the progress: (100 - lost) * progress * 0.8
        ServerPlayer t = player(h, Realm.SECOND);
        withPool(t, 0.0D);
        sit(t, PillKind.SNOW_PLUM);
        finish(t, PillPayloads.Outcome.TIMEOUT, 500, 100);
        h.assertTrue(Math.abs(profile(t).pool() - 250.0D * Math.round(90.0D * 0.5D * 0.8D) / 100.0D) < 1.0E-6D, "a timeout paid " + profile(t).pool());
        // a burst pays a tenth and tears the channels
        ServerPlayer b = player(h, Realm.SECOND);
        unprotect(b);
        b.setHealth(b.getMaxHealth());
        withPool(b, 0.0D);
        sit(b, PillKind.SNOW_PLUM);
        finish(b, PillPayloads.Outcome.BURST, 300, 500);
        h.assertTrue(Math.abs(profile(b).pool() - 25.0D) < 1.0E-6D, "a burst paid " + profile(b).pool());
        h.assertTrue(b.getHealth() < b.getMaxHealth(), "a burst did not hurt");
        h.assertTrue(profile(b).circulating() == 0.0D, "a burst left the circulating qi");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void theServerWorksTheQualityOutItselfAndClampsTheReport(GameTestHelper h) {
        // an absurd report: the progress and the loss are clamped, the quality never leaves 0..1
        ServerPlayer p = player(h, Realm.SECOND);
        withPool(p, 0.0D);
        sit(p, PillKind.SNOW_PLUM);
        finish(p, PillPayloads.Outcome.DONE, 99999, -50);
        h.assertTrue(Math.abs(profile(p).pool() - 250.0D) < 1.0E-6D, "a negative loss gave more than the pill holds: " + profile(p).pool());
        ServerPlayer q = player(h, Realm.SECOND);
        withPool(q, 0.0D);
        sit(q, PillKind.SNOW_PLUM);
        finish(q, PillPayloads.Outcome.DONE, 1000, 99999);
        h.assertTrue(Math.abs(profile(q).pool() - 250.0D * 0.4D) < 1.0E-6D, "an enormous loss paid " + profile(q).pool());
        // a game cannot end before the stream could have crossed: an early «done» is paid as the floor
        ServerPlayer r = player(h, Realm.SECOND);
        withPool(r, 0.0D);
        sit(r, PillKind.SNOW_PLUM);
        PillService.Session flow = slot(r).flow;
        PillService.report(r, flow.id, PillPayloads.Outcome.DONE, 1000, 0, flow.startTick);
        h.assertTrue(Math.abs(profile(r).pool() - 250.0D * PillRules.MIN_SAT_QUALITY) < 1.0E-6D, "an instant run paid " + profile(r).pool());
        // a report of an old session or without one changes nothing
        ServerPlayer s = player(h, Realm.SECOND);
        withPool(s, 0.0D);
        sit(s, PillKind.SNOW_PLUM);
        PillService.report(s, slot(s).flow.id + 7, PillPayloads.Outcome.DONE, 1000, 0, Long.MAX_VALUE / 2);
        h.assertTrue(slot(s).flow != null && profile(s).pool() == 0.0D, "a report of another session was taken");
        // a client that never reports is paid as a timeout by the watchdog
        PillService.tickAbsorb(s, slot(s).flow.deadline + 1L);
        h.assertTrue(slot(s).flow == null, "the watchdog never ended the game");
        h.assertTrue(profile(s).pool() >= 250.0D * PillRules.STANDING_SHARE - 1.0E-6D, "the silent client got less than the standing share");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void theQiBeyondTheWallIsLost(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.SECOND);
        double wall = Realm.wall(profile(p));
        withPool(p, wall - 100.0D);
        clean(p, PillKind.SNOW_PLUM);
        h.assertTrue(Math.abs(profile(p).pool() - wall) < 1.0E-6D, "the pool passed the wall or stopped short: " + profile(p).pool());
        h.assertFalse(pills(p).reserve().active(), "the qi beyond the wall went to a reserve");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void theSeatedBodyStandsUpAfterTheGame(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.SECOND);
        clean(p, PillKind.SNOW_PLUM);
        h.assertTrue(slot(p).standAt >= 0L, "the finished absorption did not schedule the stand-up");
        slot(p).standAt = 0L;
        h.assertTrue(PillService.standUpDue(p), "the stand-up never came due");
        h.succeed();
    }

    // ------------------------------------------------------------------ side effects

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aFlawlessHighPillLiftsAndACommonOneDoesNot(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.PEAK);
        clean(p, PillKind.ORIGIN_ENERGY);
        h.assertTrue(PillService.lifting(p), "a flawless high pill did not lift");
        ServerPlayer q = player(h, Realm.PEAK);
        sit(q, PillKind.ORIGIN_ENERGY);
        finish(q, PillPayloads.Outcome.DONE, 1000, 200); // 80 %: clean enough for a bonus, not flawless
        h.assertFalse(PillService.lifting(q), "a muddy absorption lifted");
        ServerPlayer c = player(h, Realm.THIRD);
        clean(c, PillKind.VITALITY);
        h.assertFalse(PillService.lifting(c), "a common pill lifted the player");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void theSideEffectsFollowWhatThePillWasWorth(GameTestHelper h) {
        // Origin Energy widens the dantian by 4 % of what it was worth (rank rule x tolerance x quality)
        ServerPlayer p = player(h, Realm.PEAK);
        double capacity = profile(p).capacity();
        clean(p, PillKind.ORIGIN_ENERGY);
        h.assertTrue(Math.abs(profile(p).capacity() - capacity * (1.0D + 0.04D * 2.0D / 3.0D)) < 1.0E-6D, "capacity " + capacity + " -> " + profile(p).capacity());
        ServerPlayer q = player(h, Realm.PEAK);
        double c2 = profile(q).capacity();
        sit(q, PillKind.ORIGIN_ENERGY);
        finish(q, PillPayloads.Outcome.DONE, 1000, 400);
        h.assertTrue(Math.abs(profile(q).capacity() - c2 * (1.0D + 0.04D * 0.6D * 2.0D / 3.0D)) < 1.0E-6D, "a muddy absorption widened the dantian by the full amount");
        // the Thousand-Poison pill gives poison resistance only for a good absorption
        ServerPlayer t = player(h, Realm.PEAK);
        clean(t, PillKind.THOUSAND_POISON);
        h.assertTrue(pills(t).poisonResistant(), "a clean Thousand-Poison pill gave no resistance");
        ServerPlayer u = player(h, Realm.PEAK);
        sit(u, PillKind.THOUSAND_POISON);
        finish(u, PillPayloads.Outcome.DONE, 1000, 700);
        h.assertFalse(pills(u).poisonResistant(), "a poor absorption gave resistance");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void youngRawRootsAreSafeAndShareEveryHerbCooldown(GameTestHelper h) {
        for (PillKind kind : new PillKind[] {PillKind.HUNDRED_GINSENG, PillKind.PURPLE_GINSENG}) {
            h.assertTrue(kind.qi() == (kind == PillKind.HUNDRED_GINSENG ? 40 : 50), "wrong raw qi");
            var plan = PillRules.plan(List.of(kind), Realm.THIRD, 1.0D, 1.0D);
            h.assertTrue(!plan.torn(), "young root tears weak meridians");
            ServerPlayer p = player(h, Realm.THIRD);
            withPool(p, 0.0D);
            clean(p, kind);
            h.assertTrue(Math.abs(profile(p).pool() - kind.qi()) < 1.0E-6D, "raw root qi not absorbed");
            ServerPlayer eater = player(h, Realm.THIRD);
            var item = io.github.verycooltimo.murim.registry.ModItems.itemOf(kind);
            var stack = new net.minecraft.world.item.ItemStack(item, 2);
            item.finishUsingItem(stack, eater.level(), eater);
            h.assertTrue(stack.getCount() == 1, "raw root not consumed");
            for (PillKind other : PillKind.values()) if (other.herb()) {
                h.assertTrue(eater.getCooldowns().isOnCooldown(
                        io.github.verycooltimo.murim.registry.ModItems.itemOf(other)), "unshared cooldown: " + other);
            }
            item.finishUsingItem(stack, eater.level(), eater);
            h.assertTrue(stack.getCount() == 1 && PillKind.HERB_COOLDOWN == 6000, "cooldown bypassed");
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ herbs

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void thousandYearHerbsAreMiddleLikeAndHaveACooldown(GameTestHelper helper) {
        for (PillKind herb : new PillKind[] {PillKind.THOUSAND_GINSENG, PillKind.THOUSAND_LINGZHI}) {
            ServerPlayer p = player(helper, Realm.SECOND);
            withPool(p, 0.0D);
            clean(p, herb);
            helper.assertTrue(Math.abs(profile(p).pool() - herb.qi()) < 1.0E-6D, herb + " paid " + profile(p).pool());
            helper.assertTrue(herb.qi() >= 200 && herb.qi() <= 500, herb + " is not a middle pill");
        }
        ServerPlayer p = player(helper, Realm.SECOND);
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(
                io.github.verycooltimo.murim.registry.ModItems.THOUSAND_GINSENG.get(), 2);
        net.minecraft.world.item.Item item = stack.getItem();
        item.finishUsingItem(stack, p.level(), p);
        helper.assertTrue(stack.getCount() == 1, "the herb was not eaten");
        helper.assertTrue(p.getCooldowns().isOnCooldown(item), "no cooldown after the herb");
        helper.assertTrue(p.getCooldowns().isOnCooldown(
                io.github.verycooltimo.murim.registry.ModItems.THOUSAND_LINGZHI.get()), "the cooldown is not shared by herbs");
        item.finishUsingItem(stack, p.level(), p);
        helper.assertTrue(stack.getCount() == 1, "a second herb was eaten during the cooldown");
        helper.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aWarnedPillStaysInTheStack(GameTestHelper helper) {
        ServerPlayer p = player(helper, Realm.THIRD);
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(
                io.github.verycooltimo.murim.registry.ModItems.PILL_ORIGIN_ENERGY.get(), 2);
        net.minecraft.world.item.Item item = stack.getItem();
        item.finishUsingItem(stack, p.level(), p);
        helper.assertTrue(stack.getCount() == 2, "the warned pill was consumed");
        item.finishUsingItem(stack, p.level(), p);
        helper.assertTrue(stack.getCount() == 1, "the confirmed pill was not consumed");
        helper.succeed();
    }

    // ------------------------------------------------------------------ interruptions, blows, logout

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aLogoutMidGamePaysTheProgressAndNeverLessThanTheStandingShare(GameTestHelper helper) {
        ServerPlayer p = player(helper, Realm.SECOND);
        withPool(p, 0.0D);
        sit(p, PillKind.SNOW_PLUM);
        helper.assertTrue(PillService.absorbing(p), "the game did not start");
        PillService.report(p, slot(p).flow.id, PillPayloads.Outcome.RUNNING, 700, 0, p.level().getGameTime());
        io.github.verycooltimo.murim.profile.ProfileEvents.settleAbsorption(p);
        helper.assertFalse(PillService.absorbing(p), "the game outlived the logout");
        // 70 % of the way, nothing lost: (100 - 0) * 0.7 * 0.8 = 56 %
        helper.assertTrue(Math.abs(profile(p).pool() - 250.0D * 0.56D) < 1.0E-6D, "the logout paid " + profile(p).pool());
        double settled = profile(p).pool();
        io.github.verycooltimo.murim.profile.ProfileEvents.settleAbsorption(p);
        helper.assertTrue(profile(p).pool() == settled, "settling twice paid twice");
        // a logout at the very start still pays the standing share
        ServerPlayer q = player(helper, Realm.SECOND);
        withPool(q, 0.0D);
        sit(q, PillKind.SNOW_PLUM);
        io.github.verycooltimo.murim.profile.ProfileEvents.settleAbsorption(q);
        helper.assertTrue(Math.abs(profile(q).pool() - 250.0D * PillRules.STANDING_SHARE) < 1.0E-6D, "an instant logout paid " + profile(q).pool());
        helper.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void pillsCanBeEatenInCombat(GameTestHelper helper) {
        ServerPlayer p = player(helper, Realm.SECOND);
        net.minecraft.world.entity.monster.Zombie zombie = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.ZOMBIE, new BlockPos(1, 3, 1));
        p.setLastHurtByMob(zombie);
        helper.assertTrue(p.getLastHurtByMob() == zombie && p.tickCount - p.getLastHurtByMobTimestamp() < 100, "the player is not «in combat»");
        for (PillKind kind : PillKind.values()) {
            helper.assertTrue(PillService.check(p, kind, profile(p), PillState.NONE) == PillRules.Refusal.NONE,
                    kind + " was refused in combat");
        }
        helper.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aHitDuringTheGameBreaksTheFlowAndHalvesTheHealth(GameTestHelper helper) {
        ServerPlayer p = player(helper, Realm.SECOND);
        unprotect(p);
        // Full armour: the recoil must ignore it (the hit itself does not).
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_HELMET));
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE));
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_LEGGINGS));
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_BOOTS));
        withPool(p, 0.0D);
        sit(p, PillKind.SNOW_PLUM);
        helper.assertTrue(PillService.absorbing(p), "the flow did not start");
        net.minecraft.world.entity.monster.Zombie zombie = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.ZOMBIE, new BlockPos(1, 3, 1));
        // A hit with nothing behind it is not a hit.
        p.hurt(p.damageSources().mobAttack(zombie), 0.0F);
        helper.assertTrue(PillService.absorbing(p), "a zero-damage touch broke the flow");
        float before = p.getHealth();
        helper.assertTrue(p.hurt(p.damageSources().mobAttack(zombie), 6.0F), "the hit did not land");
        float afterHit = p.getHealth();
        helper.assertTrue(afterHit < before, "the hit did no damage");
        helper.assertFalse(PillService.absorbing(p), "a hit did not stop the flow");
        helper.assertFalse(p.getData(ModAttachments.MEDITATION).active(), "a hit did not stand the player up");
        PillService.Slot slot = slot(p);
        helper.assertTrue(slot.deviation, "the qi deviation was not scheduled");
        // the floor of the standing share was paid
        helper.assertTrue(Math.abs(profile(p).pool() - 250.0D * PillRules.STANDING_SHARE) < 1.0E-6D, "the interrupted game paid " + profile(p).pool());
        PillService.deviate(p);
        helper.assertFalse(slot.deviation, "the deviation stayed scheduled");
        float expected = afterHit - p.getMaxHealth() * 0.5F;
        helper.assertTrue(Math.abs(p.getHealth() - expected) < 0.01F,
                "half of the max health (armour ignored) expected " + expected + ", got " + p.getHealth());
        PillService.deviate(p);
        helper.assertTrue(Math.abs(p.getHealth() - expected) < 0.01F, "the deviation was taken twice: " + p.getHealth());
        helper.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void theQiDeviationNeverKills(GameTestHelper helper) {
        ServerPlayer p = player(helper, Realm.SECOND);
        unprotect(p);
        p.setHealth(6.0F);
        sit(p, PillKind.SNOW_PLUM);
        net.minecraft.world.entity.monster.Zombie zombie = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.ZOMBIE, new BlockPos(1, 3, 1));
        p.hurt(p.damageSources().mobAttack(zombie), 2.0F);
        float afterHit = p.getHealth();
        helper.assertTrue(afterHit > 1.0F && !PillService.absorbing(p), "setup failed: " + afterHit);
        PillService.deviate(p);
        helper.assertTrue(p.isAlive() && p.getHealth() == 1.0F, "the recoil must leave exactly one health, got " + p.getHealth());
        ServerPlayer q = player(helper, Realm.SECOND);
        unprotect(q);
        sit(q, PillKind.SNOW_PLUM);
        q.setHealth(1.0F);
        PillService.onMeditationStop(q, true);
        PillService.deviate(q);
        helper.assertTrue(q.isAlive() && q.getHealth() == 1.0F, "the recoil killed a player at one health");
        helper.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void theQiDeviationIsADistinctArmourIgnoringDamageType(GameTestHelper helper) {
        ServerPlayer p = player(helper, Realm.SECOND);
        var source = io.github.verycooltimo.murim.registry.ModDamageTypes.qiDeviation(p);
        helper.assertTrue(source.is(io.github.verycooltimo.murim.registry.ModDamageTypes.QI_DEVIATION), "not the qi deviation type");
        helper.assertTrue(source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR), "the recoil does not ignore armour");
        helper.assertTrue(source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_COOLDOWN), "the recoil is eaten by the hit cooldown");
        helper.assertTrue(source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ENCHANTMENTS), "the recoil is softened by enchantments");
        helper.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void aCleanGameChangesNothingButTheQi(GameTestHelper helper) {
        ServerPlayer p = player(helper, Realm.SECOND);
        float hp = p.getHealth();
        clean(p, PillKind.SNOW_PLUM);
        helper.assertFalse(slot(p).deviation, "no hit, yet a deviation is pending");
        PillService.deviate(p);
        helper.assertTrue(p.getHealth() == hp, "a clean absorption changed the health: " + p.getHealth());
        helper.assertTrue(p.getActiveEffects().isEmpty(), "a clean absorption applied effects");
        helper.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void ordinaryPillsFinishByTheServerClock(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.SECOND);
        withPool(p, 0);
        h.assertTrue(PillService.eat(p, PillKind.SNOW_PLUM), "pill refused");
        p.setData(ModAttachments.MEDITATION, MeditationState.started(true, CultivationState.SEEDED));
        PillService.onMeditationStart(p);
        var flow = slot(p).flow;
        h.assertTrue(flow != null && flow.automatic, "ordinary pill requires steering");
        PillService.report(p, flow.id, PillPayloads.Outcome.BURST, 1000, 1000, flow.startTick);
        h.assertTrue(slot(p).flow != null && profile(p).pool() == 0, "client settled automatic pill");
        PillService.tickAbsorb(p, flow.startTick + 49);
        h.assertTrue(profile(p).pool() == 0, "settled before 2.5 seconds");
        PillService.tickAbsorb(p, flow.startTick + 50);
        h.assertTrue(!PillService.absorbing(p) && Math.abs(profile(p).pool() - 250) < 1e-6, "automatic pill did not settle fully");
        PillService.tickAbsorb(p, flow.startTick + 51);
        h.assertTrue(Math.abs(profile(p).pool() - 250) < 1e-6, "automatic pill paid twice");
        var game = new io.github.verycooltimo.murim.cultivation.flow.FlowGame(PillService.character(List.of(PillKind.SNOW_PLUM)), 1, false, false, false);
        for (int i = 0; i < 49; i++) game.updateAutomatic(0.05D);
        h.assertTrue(game.state == io.github.verycooltimo.murim.cultivation.flow.FlowGame.State.RUN, "short scene ended early");
        game.updateAutomatic(0.051D);
        h.assertTrue(game.state == io.github.verycooltimo.murim.cultivation.flow.FlowGame.State.DONE && game.s == 1, "short scene incomplete");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void assistedGreatElixirIsSafeAndPaysFullyWhileAlonePaysTwoThirds(GameTestHelper h) {
        ServerPlayer p = player(h, Realm.FIRST);
        unprotect(p);
        p.setHealth(p.getMaxHealth());
        p.setData(ModAttachments.PROFILE, profile(p).withAxes(700.0D / MeditationService.POOL_CAP, 0.8D, 0.4D).withPool(0));
        p.setData(ModAttachments.SECT, new io.github.verycooltimo.murim.sect.SectState(5, true, 3, java.util.Set.of(), 100, 0));
        h.assertFalse(PillService.eat(p, PillKind.BLACK_LINGZHI), "unguided overload skipped warning");
        h.assertTrue(PillService.eat(p, PillKind.BLACK_LINGZHI), "confirmation failed");
        h.assertTrue(GreatElixirs.assist(p), "elder refused eligible intake");
        h.assertTrue(GreatElixirs.assist(p) && p.getData(ModAttachments.SECT).merit() == 80, "assistance charged twice");
        // It survives save/read through the existing sect journal, without changing the save schema.
        h.assertTrue(GreatElixirs.assisted(p, pills(p)), "guidance missing");
        p.setData(ModAttachments.MEDITATION, MeditationState.started(true, CultivationState.SEEDED));
        PillService.onMeditationStart(p);
        var flow = slot(p).flow;
        h.assertTrue(flow != null && flow.plan.assisted() && !flow.plan.torn() && flow.automatic, "guided elixir tore");
        h.assertTrue(Math.abs(flow.plan.gain(1) - 1500) < 1e-6, "guidance lost qi");
        PillService.tickAbsorb(p, flow.startTick + 50);
        h.assertTrue(p.getHealth() == p.getMaxHealth() && !p.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS), "guidance hurt meridians");
        h.assertTrue(Math.abs(profile(p).pool() - 700) < 1e-6, "guidance lost qi below the wall");
        // The existing storage wall remains: fully absorbed qi beyond it is still explicitly reported as excess.
        ServerPlayer q = player(h, Realm.FIRST);
        q.setData(ModAttachments.PROFILE, profile(q).withAxes(600, 0.8D, 0.4D).withPool(0));
        clean(q, PillKind.BLACK_LINGZHI);
        h.assertTrue(Math.abs(profile(q).pool() - 1000) < 1e-6, "unguided elixir did not give two thirds");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void greatElixirWaivesOnlyTheNextTechniqueGateOnce(GameTestHelper h) throws Exception {
        ServerPlayer p = player(h, Realm.SECOND);
        withPool(p, 0);
        clean(p, PillKind.PURPLE_HEAVEN);
        h.assertTrue(GreatElixirs.ready(p), "great elixir gave no waiver");
        var atWall = profile(p).withPool(Realm.wall(profile(p)));
        h.assertTrue(Realm.check(atWall, java.util.Map.of(), false, GreatElixirs.ready(p)) == Realm.Blocker.NONE, "technique gate not waived");
        h.assertTrue(Realm.check(atWall.withPool(0), java.util.Map.of(), true, true) == Realm.Blocker.NOT_AT_WALL, "elixir skipped qi wall");
        h.assertTrue(Realm.check(atWall.withRank(Realm.THIRD), java.util.Map.of(), false, true) == Realm.Blocker.NO_SECT_SKILLS, "elixir skipped practical exams");
        p.setData(ModAttachments.PROFILE, atWall);
        h.assertFalse(MeditationService.checkWall(p, atWall, MeditationState.started(true, CultivationState.SEEDED)), "elixir skipped body training");
        // Cancellation does not spend the benefit; success does, and a new rank cannot reuse it.
        h.assertTrue(GreatElixirs.ready(p), "failed attempt spent waiver");
        io.github.verycooltimo.murim.training.TrainingService.setPoints(p,
                io.github.verycooltimo.murim.training.BodyRules.threshold(io.github.verycooltimo.murim.training.BodyRules.breakthroughNeed(Realm.FIRST)));
        var sitting = MeditationState.started(true, CultivationState.SEEDED);
        h.assertTrue(MeditationService.checkWall(p, atWall, sitting), "ready body still needed technique stars");
        var finish = MeditationService.class.getDeclaredMethod("tickBreakthrough", ServerPlayer.class, MeditationState.class);
        finish.setAccessible(true);
        finish.invoke(null, p, sitting.withBreakthrough(Realm.WARNING_TICKS + Realm.breakthroughTicks(Realm.FIRST) - 1));
        h.assertTrue(profile(p).rank() == Realm.FIRST, "breakthrough did not finish");
        h.assertFalse(GreatElixirs.ready(p), "waiver was reusable");
        h.assertTrue(Realm.check(profile(p).withPool(Realm.wall(profile(p))), java.util.Map.of(), true, GreatElixirs.ready(p)) == Realm.Blocker.NO_TECHNIQUE, "second breakthrough waived");
        h.succeed();
    }

    // ------------------------------------------------------------------ the wire

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void theFlowPacketsSurviveTheWire(GameTestHelper h) {
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        var start = new PillPayloads.FlowStart(7, new int[] {PillKind.ORIGIN_ENERGY.ordinal(), PillKind.THOUSAND_POISON.ordinal()},
                -123456789012L, true, false, true, true, true);
        PillPayloads.FlowStart.STREAM_CODEC.encode(buf, start);
        var back = PillPayloads.FlowStart.STREAM_CODEC.decode(buf);
        h.assertTrue(buf.readableBytes() == 0, "the start packet was longer than its decoder read");
        h.assertTrue(back.id() == 7 && back.seed() == -123456789012L && back.hard() && !back.torn() && back.overload() && back.poisonNature() && back.automatic()
                && java.util.Arrays.equals(back.kinds(), start.kinds()), "the start packet changed on the wire");
        var report = new PillPayloads.FlowReport(7, PillPayloads.Outcome.DONE.ordinal(), 1000, 123);
        PillPayloads.FlowReport.STREAM_CODEC.encode(buf, report);
        var rback = PillPayloads.FlowReport.STREAM_CODEC.decode(buf);
        h.assertTrue(buf.readableBytes() == 0 && rback.equals(report), "the report packet changed on the wire");
        h.succeed();
    }
    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void treasuresOfOctoberThirtyRespectCanon(GameTestHelper h) {
        h.assertTrue(PillKind.BLACK_LINGZHI.qi() == 1500 && PillKind.BLACK_LINGZHI.rank() == Realm.FIRST
                && PillKind.BLACK_LINGZHI.tier() == PillTier.HIGH && PillKind.BLACK_LINGZHI.herb(), "black lingzhi table");
        h.assertTrue(PillRules.plan(List.of(PillKind.BLACK_LINGZHI), Realm.THIRD, 10000, 1).tooHigh(), "black lingzhi must tear");
        h.assertTrue(PillKind.PURPLE_HEAVEN.qi() == 1600 && PillKind.PURPLE_HEAVEN.rank() == Realm.FIRST
                && PillKind.PURPLE_HEAVEN.tier() == PillTier.HIGH, "purple heaven table");
        for (int rank = Realm.NONE; rank <= Realm.TOP; rank++) {
            h.assertTrue(!PillRules.plan(List.of(PillKind.PURPLE_HEAVEN), rank, 1, 1).torn(), "clean qi tore at rank " + rank);
        }
        var hard = PillRules.plan(List.of(PillKind.PURPLE_HEAVEN), Realm.SECOND, 1, 1);
        h.assertTrue(hard.hard() && hard.load() == 800, "purple heaven must be harder and half below first rank");
        ServerPlayer weak = player(h, Realm.THIRD);
        unprotect(weak);
        h.assertTrue(!PillService.eat(weak, PillKind.BLACK_LINGZHI), "dangerous treasure skipped warning");
        h.assertTrue(PillService.eat(weak, PillKind.BLACK_LINGZHI), "confirmed treasure was refused");
        closeWindow(weak);
        PillService.expireWindow(weak);
        h.assertTrue(weak.getHealth() < weak.getMaxHealth(), "confirmed treasure did not tear");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void pillTooltipsHaveOnlyOneSummaryAndOneWarning(GameTestHelper h) {
        var oldProfile = io.github.verycooltimo.murim.item.PillItem.clientProfile;
        var oldStreak = io.github.verycooltimo.murim.item.PillItem.clientStreak;
        try {
            for (int rank = Realm.NONE; rank <= Realm.TOP; rank++) {
                final int current = rank;
                io.github.verycooltimo.murim.item.PillItem.clientProfile = () -> DantianProfile.INITIAL.withTags("clear", "debug")
                        .withRank(current).withPool(0);
                io.github.verycooltimo.murim.item.PillItem.clientStreak = kind -> 2;
                for (var item : new net.minecraft.world.item.Item[] {
                        io.github.verycooltimo.murim.registry.ModItems.BLACK_LINGZHI.get(),
                        io.github.verycooltimo.murim.registry.ModItems.PILL_PURPLE_HEAVEN.get(),
                        io.github.verycooltimo.murim.registry.ModItems.PILL_SMALL_RESTORATION.get(),
                        io.github.verycooltimo.murim.registry.ModItems.PILL_HEAVENLY_EARTH.get(),
                        io.github.verycooltimo.murim.registry.ModItems.PILL_MOONLIGHT.get(),
                        io.github.verycooltimo.murim.registry.ModItems.PILL_ASCENDING_DRAGON.get(),
                        io.github.verycooltimo.murim.registry.ModItems.PILL_GREAT_RESTORATION.get(),
                        io.github.verycooltimo.murim.registry.ModItems.PILL_FASTING.get()}) {
                    var stack = item.getDefaultInstance();
                    java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
                    lines.add(stack.getHoverName());
                    // This override does not inspect context or flags; null keeps the fixture server-only.
                    item.appendHoverText(stack, null, lines, null);
                    h.assertTrue(lines.size() >= 2 && lines.size() <= (((io.github.verycooltimo.murim.item.PillItem) item).kind().greatElixir() ? 4 : 3), "tooltip too long at rank " + rank);
                }
            }
        } finally {
            io.github.verycooltimo.murim.item.PillItem.clientProfile = oldProfile;
            io.github.verycooltimo.murim.item.PillItem.clientStreak = oldStreak;
        }
        h.succeed();
    }

    /** Uses the same seed and real rank growth as FilmPillTooltips, without loading client classes. */
    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void pillTooltipsExplainRankAndLoadAndShowActualQi(GameTestHelper h) {
        var oldProfile = io.github.verycooltimo.murim.item.PillItem.clientProfile;
        var oldStreak = io.github.verycooltimo.murim.item.PillItem.clientStreak;
        try {
            var third = DantianProfile.INITIAL.withTags("calm", "none")
                    .withAxes(30.0D, 0.5D, 0.5D).withRank(Realm.THIRD).withPool(0);
            var first = DantianProfile.INITIAL.withTags("calm", "none")
                    .withAxes(30.0D, 0.5D, 0.5D).withRank(Realm.FIRST).withPool(0);
            io.github.verycooltimo.murim.item.PillItem.clientStreak = kind -> 0;
            io.github.verycooltimo.murim.item.PillItem.clientProfile = () -> third;
            assertPillTooltipWarning(h, PillKind.GREAT_RESTORATION,
                    net.minecraft.network.chat.Component.translatable("murim.pill.rank.torn")
                            .withStyle(net.minecraft.ChatFormatting.RED));
            io.github.verycooltimo.murim.item.PillItem.clientProfile = () -> first;
            // The real seed-30 wall is 699.84, displayed as 700 elsewhere: floor(699.84 * 1.5) = 1049.
            assertPillTooltipWarning(h, PillKind.GREAT_RESTORATION,
                    net.minecraft.network.chat.Component.translatable("murim.pill.load.torn", 1049L)
                            .withStyle(net.minecraft.ChatFormatting.RED));
            io.github.verycooltimo.murim.item.PillItem.clientProfile = () -> first.withAxes(
                    700.0D / MeditationService.POOL_CAP, first.purity(), first.meridians());
            assertPillTooltipWarning(h, PillKind.GREAT_RESTORATION,
                    net.minecraft.network.chat.Component.translatable("murim.pill.load.torn", 1050L)
                            .withStyle(net.minecraft.ChatFormatting.RED));
            io.github.verycooltimo.murim.item.PillItem.clientProfile = () -> third;
            assertPillTooltipWarning(h, PillKind.HEAVENLY_EARTH,
                    net.minecraft.network.chat.Component.translatable("murim.pill.rank.hard", 125L)
                            .withStyle(net.minecraft.ChatFormatting.YELLOW));
            assertPillTooltipWarning(h, PillKind.PURPLE_HEAVEN,
                    net.minecraft.network.chat.Component.translatable("murim.pill.rank.hard", 800L)
                            .withStyle(net.minecraft.ChatFormatting.YELLOW));
            // A repeat must show the tolerance-adjusted, rounded qi rather than a fixed half.
            io.github.verycooltimo.murim.item.PillItem.clientStreak = kind -> 1;
            assertPillTooltipWarning(h, PillKind.HEAVENLY_EARTH,
                    net.minecraft.network.chat.Component.translatable("murim.pill.rank.hard", 94L)
                            .withStyle(net.minecraft.ChatFormatting.YELLOW));
        } finally {
            io.github.verycooltimo.murim.item.PillItem.clientProfile = oldProfile;
            io.github.verycooltimo.murim.item.PillItem.clientStreak = oldStreak;
        }
        h.succeed();
    }

    private static void assertPillTooltipWarning(GameTestHelper h, PillKind kind,
                                                net.minecraft.network.chat.Component expected) {
        // ModItems.itemOf maps only the herbs; look the pill up by its own id.
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", kind.itemId()));
        var stack = item.getDefaultInstance();
        java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
        lines.add(stack.getHoverName());
        item.appendHoverText(stack, null, lines, null);
        h.assertTrue(lines.size() == (kind.greatElixir() ? 4 : 3), "expected name, summary, elixir effect and warning for " + kind);
        h.assertTrue(lines.get(lines.size() - 1).equals(expected), "wrong warning for " + kind + ": " + lines.get(lines.size() - 1));
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void rareCanonPillsKeepRankRiskAndFlow(GameTestHelper h) {
        PillKind[] kinds = {PillKind.SMALL_RESTORATION, PillKind.HEAVENLY_EARTH, PillKind.MOONLIGHT,
                PillKind.ASCENDING_DRAGON, PillKind.GREAT_RESTORATION};
        int[] qi = {200, 250, 250, 400, 1500};
        int[] ranks = {Realm.THIRD, Realm.SECOND, Realm.SECOND, Realm.SECOND, Realm.FIRST};
        for (int i = 0; i < kinds.length; i++) {
            PillKind kind = kinds[i];
            h.assertTrue(kind.qi() == qi[i] && kind.rank() == ranks[i], "canon qi/rank: " + kind);
            h.assertTrue(kind.tier() == (i == 4 ? PillTier.HIGH : PillTier.MIDDLE), "canon tier: " + kind);
            h.assertTrue(kind.flow() != null && !kind.gentle(), "missing flow or rank risk: " + kind);
            // THIRD has no playable rank two below; exercise the same rank delta arithmetically.
            h.assertTrue(PillRules.plan(List.of(kind), kind.rank() - 2, 10000, 1).tooHigh(), "must tear two below: " + kind);
            h.assertTrue(!PillRules.plan(List.of(kind), kind.rank(), 10000, 1).torn(), "safe at designed rank: " + kind);
        }
        h.succeed();
    }

}
