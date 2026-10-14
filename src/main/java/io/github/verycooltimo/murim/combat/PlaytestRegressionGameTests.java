package io.github.verycooltimo.murim.combat;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.RankEffects;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.network.QiSwordHitPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileEvents;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Regression coverage for the author's 13 October playtest, through server entry points. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PlaytestRegressionGameTests {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    private static ServerPlayer player(GameTestHelper h, List<QiSwordHitPayload> packets) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "playtest-regression"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
                return payload instanceof QiSwordHitPayload;
            }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof QiSwordHitPayload sword) packets.add(sword);
            }
        };
        return p;
    }

    private static DantianProfile seed() {
        return DantianProfile.INITIAL.withAxes(30, 0.9D, 0.5D).withTags("pure", "murim:six_harmonies");
    }

    private static void close(GameTestHelper h, double actual, double expected, String message) {
        h.assertTrue(Math.abs(actual - expected) < 1.0E-7D, message + ": " + actual + " != " + expected);
    }

    @GameTest(template = "small_floor", batch = "playtest_regression", timeoutTicks = 20)
    public static void acceptedSwordCastRequestsVisibleBladeWithoutHit(GameTestHelper h) {
        // Includes zero stars: the canonical rank gate is independent of the execution/VFX tier.
        ResourceLocation sword = id("crescent_sweep");
        for (int stars : new int[] {0, Arts.unlockStar(sword, 1), Arts.MAX_STARS}) {
            List<QiSwordHitPayload> packets = new ArrayList<>();
            ServerPlayer p = player(h, packets);
            DantianProfile ready = seed().withRank(QiSword.MIN_RANK).withStage(QiSword.MIN_STAGE).withPool(1000);
            p.setData(ModAttachments.PROFILE, ready.withCirculating(ready.maxCirculating()));
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(sword, TechniqueProgress.learned(stars, Arts.MAX_STARS)));
            h.assertTrue(TechniqueService.tryStart(p, sword), "eligible sword cast rejected at stars=" + stars);
            h.assertTrue(packets.size() == 1 && packets.getFirst().sourceId() == p.getId(), "cast did not request visible blade");
            close(h, p.getAttributeValue(Attributes.ATTACK_DAMAGE), 6, "first sword cast lacks iron-sword damage");
            close(h, p.getAttributeValue(Attributes.ATTACK_SPEED), 1.6D, "first sword cast lacks iron-sword speed");
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "playtest_regression", timeoutTicks = 20)
    public static void rejectedSwordCastAndPalmNeverRequestBlade(GameTestHelper h) {
        for (boolean palm : new boolean[] {false, true}) {
            List<QiSwordHitPayload> packets = new ArrayList<>();
            ServerPlayer p = player(h, packets);
            ResourceLocation technique = id(palm ? "rift_palm" : "crescent_sweep");
            DantianProfile ready = seed().withRank(QiSword.MIN_RANK)
                    .withStage(palm ? QiSword.MIN_STAGE : Realm.STAGE_INITIAL).withPool(1000);
            p.setData(ModAttachments.PROFILE, ready.withCirculating(ready.maxCirculating()));
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(technique, TechniqueProgress.learned(12, 12)));
            h.assertTrue(TechniqueService.tryStart(p, technique) == palm, "rank gate or palm start changed");
            h.assertTrue(packets.isEmpty(), "rejected cast or palm requested a sword");
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "playtest_regression", timeoutTicks = 20)
    public static void physicalSwordAndNoQiDoNotRequestQiBlade(GameTestHelper h) {
        for (boolean physical : new boolean[] {false, true}) {
            List<QiSwordHitPayload> packets = new ArrayList<>();
            ServerPlayer p = player(h, packets);
            ResourceLocation sword = id("crescent_sweep");
            DantianProfile ready = seed().withRank(QiSword.MIN_RANK).withStage(QiSword.MIN_STAGE);
            p.setData(ModAttachments.PROFILE, ready.withCirculating(physical ? ready.maxCirculating() : 0));
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(sword, TechniqueProgress.learned(12, 12)));
            if (physical) p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            h.assertTrue(TechniqueService.tryStart(p, sword) == physical, "weapon cast or qi gate changed");
            h.assertTrue(packets.isEmpty(), "physical sword or failed qi payment requested qi blade");
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "playtest_regression", timeoutTicks = 20)
    public static void swordFoundationsRejectEmptyHandAndOtherItems(GameTestHelper h) {
        ServerPlayer p = player(h, new ArrayList<>());
        p.setData(ModAttachments.PROFILE, seed().withRank(Realm.PEAK).withStage(Realm.STAGE_SUMMIT));
        for (String foundation : new String[] {"six_harmonies", "seven_plum_basic"}) {
            ResourceLocation basic = id(foundation);
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(basic, TechniqueProgress.learned(12, 12)));
            p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).withFoundation(Optional.of(basic)));
            for (ItemStack hand : new ItemStack[] {ItemStack.EMPTY, new ItemStack(Items.STICK), new ItemStack(Items.IRON_SWORD)}) {
                p.setItemInHand(InteractionHand.MAIN_HAND, hand);
                h.assertTrue(FoundationService.active(p).isPresent() == QiSword.holdsSword(hand), "foundation ignored held weapon");
                if (!QiSword.holdsSword(hand)) {
                    SwordBasicAttack.input(p, 0, -1);
                    h.assertTrue(p.getData(ModAttachments.FOUNDATION_SWING)[1] < 0, "forged bare-hand packet played a form");
                    h.assertTrue(p.getData(ModAttachments.BASIC_ATTACK).basic() == null, "forged bare-hand packet queued damage");
                }
            }
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "playtest_regression", timeoutTicks = 20)
    public static void rankAndBreakthroughGrowBothQiLimitsExactlyOnce(GameTestHelper h) {
        DantianProfile before = seed().withPool(120).withCirculating(20);
        DantianProfile commanded = before.withRank(Realm.THIRD);
        DantianProfile advanced = Realm.advance(before);
        close(h, commanded.capacity(), 54, "rank command capacity");
        close(h, advanced.capacity(), commanded.capacity(), "breakthrough applied rank growth twice");
        close(h, Realm.wall(commanded), 216, "pool limit did not grow");
        close(h, commanded.maxCirculating(), before.maxCirculating() * Realm.CAPACITY_GROWTH, "combat limit did not grow");
        close(h, commanded.pool(), before.pool(), "rank granted free pool qi");
        close(h, commanded.circulating(), before.circulating(), "rank granted free combat qi");
        close(h, commanded.withRank(Realm.THIRD).capacity(), 54, "reapplying rank compounded growth");
        DantianProfile peak = before.withRank(Realm.PEAK);
        h.assertTrue(peak.capacity() > DantianProfile.MAX_CAPACITY, "old global cap still blocks rank growth");
        close(h, peak.withAxes(peak.capacity(), peak.purity(), peak.meridians()).capacity(), peak.capacity(), "axes clipped ranked capacity");
        close(h, Realm.settle(peak).capacity(), peak.withStage(1).capacity(), "stage growth applied twice");
        close(h, commanded.withRank(0).capacity(), before.capacity(), "rank round trip compounded growth");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "playtest_regression", timeoutTicks = 20)
    public static void oldRankedProfileIsRepairedAndSaveRepairIsIdempotent(GameTestHelper h) {
        ServerPlayer p = player(h, new ArrayList<>());
        var json = DantianProfile.CODEC.encodeStart(JsonOps.INSTANCE, seed().withPool(120).withCirculating(20)).getOrThrow().getAsJsonObject();
        json.addProperty("rank", Realm.FIRST); // Old /rank changed only this field.
        DantianProfile legacy = DantianProfile.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        p.setData(ModAttachments.PROFILE, legacy);
        ProfileEvents.migrateRankCapacity(p);
        RankEffects.apply(p);
        DantianProfile repaired = p.getData(ModAttachments.PROFILE);
        close(h, repaired.capacity(), 30 * Math.pow(Realm.CAPACITY_GROWTH, Realm.FIRST), "old save did not gain rank capacity");
        close(h, repaired.pool(), 120, "migration changed stored pool");
        close(h, repaired.circulating(), 20, "migration changed stored combat qi");
        close(h, p.getMaxHealth(), 20 + Realm.HEALTH_PER_RANK * Realm.FIRST, "rank health changed");
        var saved = DantianProfile.CODEC.encodeStart(JsonOps.INSTANCE, repaired).getOrThrow();
        p.setData(ModAttachments.PROFILE, DantianProfile.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
        ProfileEvents.migrateRankCapacity(p);
        h.assertTrue(p.getData(ModAttachments.PROFILE).equals(repaired), "reloading compounded migration");
        DantianProfile natural = seed().withRank(Realm.FIRST);
        h.assertTrue(Realm.migrateCapacity(natural, 30).equals(natural), "already grown profile multiplied again");
        DantianProfile boosted = natural.withAxes(natural.capacity() + 10, natural.purity(), natural.meridians());
        h.assertTrue(Realm.migrateCapacity(boosted, 30).equals(boosted), "migration removed capacity bonus");
        DantianProfile capped = new DantianProfile(240, 0.9D, 0.5D, "pure", "murim:six_harmonies", 120, 20, 1, Realm.PEAK, 1);
        close(h, Realm.migrateCapacity(capped, 30).capacity(), 30 * Realm.capacityFactor(Realm.PEAK, 1), "old cap/stage not repaired");
        h.succeed();
    }

    private PlaytestRegressionGameTests() { }
}
