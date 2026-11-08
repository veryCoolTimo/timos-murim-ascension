package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.item.MethodScrollItem;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectState;
import io.github.verycooltimo.murim.sect.SectStanding;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;

/** Held scrolls teach their methods independently of the sect's voluntary gifts (author 26.10). */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class BreathingMethodGameTests {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("murim", path);
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "breathing"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
                return false;
            }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
        };
        return p;
    }

    private static void setup(ServerPlayer p, int rank, boolean trusted) {
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(rank).withPool(10));
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.of(id("six_harmonies")), CultivationState.SEEDED));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(Arts.methodArt(id("six_harmonies")), TechniqueProgress.learned(6, 12)));
        p.setData(ModAttachments.SECT, trusted
                ? SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO).with(SectStanding.APPROVAL).contribute(80)
                : SectState.NONE.joined());
    }

    private static void readScroll(GameTestHelper h, ServerPlayer p, CultivationMethod method) {
        var stack = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.METHOD_SCROLL.get());
        stack.set(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get(), method.id());
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
        var before = p.getData(ModAttachments.CULTIVATION);
        stack.getItem().use(p.level(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).equals(before), "opening scroll changed cultivation");
        h.assertTrue(MethodScrollItem.requestLearn(p, method.id()).status().equals("learned"),
                "held scroll confirmation rejected: " + method.id());
    }

    /** Exercise the real successful completion tick, not a direct reward grant. */
    private static void finishBreakthrough(ServerPlayer p) {
        p.setOnGround(true);
        p.xOld = p.getX();
        p.zOld = p.getZ();
        p.setData(ModAttachments.MEDITATION, MeditationState.started(true, CultivationState.SEEDED)
                .withBreakthrough(Realm.WARNING_TICKS
                        + Realm.breakthroughTicks(p.getData(ModAttachments.PROFILE).rank() + 1) - 1));
        MeditationService.tick(p);
    }

    @GameTest(template = "small_floor", batch = "breathing_methods", timeoutTicks = 20)
    public static void rankBreakthroughLiftsOnlySlottedBreathing(GameTestHelper h) {
        var p = player(h);
        setup(p, Realm.THIRD, false);
        var selected = Arts.methodArt(id("six_harmonies"));
        var other = Arts.methodArt(id("purple_sky_qi"));
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY)
                .with(other, TechniqueProgress.learned(3, 12)));
        finishBreakthrough(p);
        h.assertTrue(p.getData(ModAttachments.PROFILE).rank() == Realm.THIRD + 1, "rank did not advance");
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(selected).stars() == 7, "reward must be exactly one star");
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(other).stars() == 3, "unslotted art changed");
        MeditationService.tick(p);
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(selected).stars() == 7, "reward repeated after completion");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "breathing_methods", timeoutTicks = 20)
    public static void rankBreathingRewardRespectsCapAndEmptySlot(GameTestHelper h) {
        var p = player(h);
        setup(p, Realm.THIRD, false);
        var art = Arts.methodArt(id("six_harmonies"));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(art, TechniqueProgress.learned(12, 12)));
        var capped = p.getData(ModAttachments.MASTERY);
        finishBreakthrough(p);
        h.assertTrue(p.getData(ModAttachments.MASTERY).equals(capped), "capped mastery changed");
        setup(p, Realm.THIRD, false);
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.empty(), CultivationState.SEEDED));
        var empty = p.getData(ModAttachments.MASTERY);
        finishBreakthrough(p);
        h.assertTrue(p.getData(ModAttachments.PROFILE).rank() == Realm.THIRD + 1, "empty-slot breakthrough failed");
        h.assertTrue(p.getData(ModAttachments.MASTERY).equals(empty), "empty slot granted mastery");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "breathing_methods", timeoutTicks = 20)
    public static void meditationAuraSnapshotFollowsBreathingStars(GameTestHelper h) {
        var p = player(h);
        setup(p, Realm.THIRD, false);
        var art = Arts.methodArt(id("six_harmonies"));
        for (int stars = 1; stars <= 12; stars++) {
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(art, TechniqueProgress.learned(stars, 12)));
            var snapshot = MeditationService.snapshot(p,
                    io.github.verycooltimo.murim.network.SyncMeditationPayload.Event.NONE);
            h.assertTrue(snapshot.breathingStars() == stars, "snapshot has stale stars");
            h.assertTrue(Math.abs(snapshot.auraScale() - stars / 12.0F) < 0.0001F, "incorrect aura scale");
            h.assertTrue(snapshot.auraScale() <= 1.0F, "aura exceeds rank ceiling");
        }
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.empty(), CultivationState.SEEDED));
        h.assertTrue(MeditationService.breathingStars(p) == 0, "empty slot retained old stars");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "breathing_methods", timeoutTicks = 20)
    public static void heldScrollsTeachOutsidersWithoutVaultFlags(GameTestHelper h) {
        var purple = MethodLoader.get(id("purple_sky_qi"));
        var divine = MethodLoader.get(id("purple_sky_divine"));
        h.assertTrue(purple != null && divine != null, "methods not loaded");
        var p = player(h);
        setup(p, Realm.THIRD, false);
        p.setData(ModAttachments.SECT, SectState.NONE);
        h.assertTrue(!BreathingAccess.trustedFirstRank(p), "outsider qualifies for elder's gift");
        readScroll(h, p, purple);
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).method().equals(Optional.of(purple.id())), "third-rank outsider could not read held elder scroll");
        h.assertTrue(MasteryService.knows(p, Arts.methodArt(purple.id())), "elder method not learned");
        readScroll(h, p, divine);
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).method().equals(Optional.of(divine.id())), "held head scroll requires vault flag");
        h.assertTrue(MasteryService.knows(p, Arts.methodArt(divine.id())), "head method not learned");
        h.assertTrue(p.getData(ModAttachments.SECT).equals(SectState.NONE), "reading changed sect status");
        h.assertTrue(Math.abs(p.getData(ModAttachments.PROFILE).pool() - 10) < 1e-9, "switching spent qi");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "breathing_methods", timeoutTicks = 20)
    public static void allBreathingMethodsGrowWithStars(GameTestHelper h) {
        String[] names = {"street_strength", "six_harmonies", "mountain_root", "tang_poison_breath",
                "reverse_breathing", "holy_fire_breath", "purple_sky_qi", "purple_sky_divine"};
        double[] starts = {0.8D, 1.0D, 1.0D, 1.5D, 1.5D, 2.0D, 2.5D, 5.0D};
        double[] ends = {1.5D, 2.0D, 2.0D, 3.0D, 3.5D, 4.5D, 4.5D, 15.0D};
        double[][] rates = new double[names.length][Arts.MAX_STARS + 1];
        var p = player(h);
        setup(p, Realm.FIRST, true);
        var profile = p.getData(ModAttachments.PROFILE);
        p.setData(ModAttachments.PROFILE, profile.withAxes(profile.capacity(), 0.9D, profile.meridians()));
        for (int i = 0; i < names.length; i++) {
            var method = MethodLoader.get(id(names[i]));
            h.assertTrue(method != null, "method not loaded: " + names[i]);
            h.assertTrue(method.starCap() == 12, "method cap changed: " + names[i]);
            for (int stars = 0; stars <= Arts.MAX_STARS; stars++) {
                p.setData(ModAttachments.CULTIVATION,
                        new CultivationState(Optional.of(method.id()), CultivationState.SEEDED));
                p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(Arts.methodArt(method.id()),
                        TechniqueProgress.learned(stars, Arts.MAX_STARS)));
                double rate = 1200 * MeditationService.gainPerTick()
                        * p.getData(ModAttachments.PROFILE).efficiency() * MasteryService.practiceMethod(p);
                rates[i][stars] = rate;
                double expected = 20.0D * (starts[i] + (ends[i] - starts[i]) * Math.max(0, stars - 1) / 11.0D);
                h.assertTrue(Math.abs(rate - expected) < 1e-8, names[i] + " rate at " + stars + ": " + rate);
                if (stars > 1) {
                    h.assertTrue(rate > rates[i][stars - 1], names[i] + " did not grow at " + stars);
                }
            }
            h.assertTrue(Math.abs(rates[i][0] - rates[i][1]) < 1e-8, names[i] + " star zero differs");
        }
        for (int i = 0; i < names.length - 1; i++) {
            h.assertTrue(rates[7][1] > rates[i][12], "Divine star 1 must beat " + names[i] + " star 12");
        }
        h.assertTrue(rates[6][1] > rates[1][12], "Purple star 1 must beat Six star 12");
        var purple = MethodLoader.get(id("purple_sky_qi"));
        h.assertTrue(purple.capacityFactor(12) > 1.09D && purple.capacityFactor(0) < purple.capacityFactor(12), "wall bonus");
        h.succeed();
    }
}
