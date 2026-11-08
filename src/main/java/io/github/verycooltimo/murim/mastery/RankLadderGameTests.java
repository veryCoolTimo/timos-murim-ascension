package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.RankRequirements;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * Art ladder (author 21.10, built 24.10): Third Rate teaches Six Harmonies, Second Rate asks Six Harmonies plus Falling Petal
 * and gives Seven Plum, First Rate asks Seven Plum ★6, the Peak Seven Plum ★8, its stages ★9 and ★11. No other art counts.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class RankLadderGameTests {

    private static final String FLOOR = "small_floor";
    private static final ResourceLocation BASIC = id("seven_plum_basic");
    private static final ResourceLocation SIX = id("six_harmonies");
    private static final ResourceLocation PETAL = id("falling_petal_sword");
    private static final ResourceLocation TWENTY_FOUR = id("twenty_four_plum_rainfall");

    private RankLadderGameTests() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    private static DantianProfile atWall(int rank, int stage) {
        DantianProfile p = new DantianProfile(30.0D, 0.8D, 0.5D, "pure", "six_harmonies", 0.0D, 0, 1, 0, 0)
                .withRank(rank).withStage(stage);
        return p.withPool(Realm.wall(p));
    }

    private static Map<ResourceLocation, Integer> stars(ResourceLocation form, int n) {
        return MasteryState.EMPTY.with(form, TechniqueProgress.learned(n, Arts.cap(form))).combatStars();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void firstRateNeedsSevenPlumSix(GameTestHelper h) {
        DantianProfile second = atWall(Realm.SECOND, 0);
        h.assertTrue(Realm.check(second, stars(BASIC, 5), true) == Realm.Blocker.NO_TECHNIQUE, "Seven Plum 5 opened First Rate");
        h.assertTrue(Realm.check(second, stars(BASIC, 6), true) == Realm.Blocker.NONE, "Seven Plum 6 did not open First Rate");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void peakNeedsSevenPlumEight(GameTestHelper h) {
        DantianProfile first = atWall(Realm.FIRST, 0);
        h.assertTrue(Realm.check(first, stars(BASIC, 7), true) == Realm.Blocker.NO_TECHNIQUE, "Seven Plum 7 opened the Peak");
        h.assertTrue(Realm.check(first, stars(BASIC, 8), true) == Realm.Blocker.NONE, "Seven Plum 8 did not open the Peak");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void peakStagesNeedSevenPlumNineAndEleven(GameTestHelper h) {
        h.assertTrue(Realm.checkStage(atWall(Realm.PEAK, 0), stars(BASIC, 8)) == Realm.Blocker.NO_TECHNIQUE, "stage 1 at 8");
        h.assertTrue(Realm.checkStage(atWall(Realm.PEAK, 0), stars(BASIC, 9)) == Realm.Blocker.NONE, "stage 1 at 9");
        h.assertTrue(Realm.checkStage(atWall(Realm.PEAK, 1), stars(BASIC, 10)) == Realm.Blocker.NO_TECHNIQUE, "stage 2 at 10");
        h.assertTrue(Realm.checkStage(atWall(Realm.PEAK, 1), stars(BASIC, 11)) == Realm.Blocker.NONE, "stage 2 at 11");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void otherArtsDoNotOpenTheUpperGates(GameTestHelper h) {
        for (ResourceLocation other : new ResourceLocation[] {SIX, PETAL, TWENTY_FOUR, id("wind_god_steps")}) {
            h.assertTrue(Realm.check(atWall(Realm.SECOND, 0), stars(other, Arts.cap(other)), true) == Realm.Blocker.NO_TECHNIQUE,
                    other + " opened First Rate");
            h.assertTrue(Realm.check(atWall(Realm.FIRST, 0), stars(other, Arts.cap(other)), true) == Realm.Blocker.NO_TECHNIQUE,
                    other + " opened the Peak");
            h.assertTrue(Realm.checkStage(atWall(Realm.PEAK, 0), stars(other, Arts.cap(other))) == Realm.Blocker.NO_TECHNIQUE,
                    other + " settled a Peak stage");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void secondRateAsksBothSwordArts(GameTestHelper h) {
        h.assertTrue(!Realm.secondArtsMet(stars(SIX, 6)), "Six Harmonies alone");
        Map<ResourceLocation, Integer> both = new java.util.HashMap<>(stars(SIX, 6));
        both.put(PETAL, 3);
        h.assertTrue(Realm.secondArtsMet(both), "Six 6 and Petal 3 not enough");
        h.assertTrue(Realm.check(atWall(Realm.THIRD, 0), both, false) == Realm.Blocker.NO_SECT_SKILLS, "spar proof skipped");
        h.assertTrue(Realm.check(atWall(Realm.THIRD, 0), both, true) == Realm.Blocker.NONE, "complete proof refused");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void gateWordsNameSevenPlum(GameTestHelper h) {
        for (RankRequirements.Gate gate : RankRequirements.Gate.values()) {
            h.assertTrue(gate.art().isPresent() == (gate != RankRequirements.Gate.THIRD), "wrong art of " + gate);
            if (gate.art().isPresent()) {
                h.assertTrue(gate.art().get().equals(id("seven_plum")), "gate " + gate + " is not Seven Plum");
            }
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void sevenPlumIsLearnableFromTheBook(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ladder"), false);
        var p = new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        p.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "debug"));
        MasteryService.Learn result = MasteryService.learn(p, BASIC, 12);
        h.assertTrue(result == MasteryService.Learn.LEARNED && MasteryService.knows(p, BASIC), "the Seven Plum basics cannot be learned: " + result);
        h.succeed();
    }
}
