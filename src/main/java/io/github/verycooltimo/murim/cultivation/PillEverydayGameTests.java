package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileEvents;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.sect.MeritShop;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * GameTests of the everyday pills (24.10): the Fasting pill feeds, the Antidote clears poison and halves the next one, the
 * Blood Replenishing pill heals, the Quick Recovery pill speeds qi, the Vitality pill is a small qi pill with a little
 * regeneration, and Snow Plum is a middle pill (250 qi). The pills are eaten through the item
 * ({@link io.github.verycooltimo.murim.item.PillItem#finishUsingItem}), the same entry as in the game.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PillEverydayGameTests {

    private static final String FLOOR = "small_floor";

    private PillEverydayGameTests() {
    }

    private static ServerPlayer player(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "pill-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(),
                cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(
                net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        Vec3 at = helper.absoluteVec(new Vec3(2.5D, 3.0D, 2.5D));
        p.setPos(at.x, at.y, at.z);
        DantianProfile profile = DantianProfile.INITIAL.withTags("clear", "debug").withAxes(60.0D, 0.8D, 0.4D).withPool(100.0D);
        p.setData(ModAttachments.PROFILE, profile.withCirculating(0.0D));
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.empty(), CultivationState.SEEDED));
        p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        return p;
    }

    private static ItemStack eat(ServerPlayer p, Item item) {
        ItemStack stack = new ItemStack(item, 2);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        item.finishUsingItem(stack, p.level(), p);
        return stack;
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void everyKindHasAnItemAndTheCommonOnesAreBelowSnowPlum(GameTestHelper h) {
        for (PillKind kind : PillKind.values()) {
            h.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, kind.itemId())),
                    "no item for " + kind);
        }
        for (PillKind common : new PillKind[] {PillKind.VITALITY, PillKind.FASTING, PillKind.ANTIDOTE}) {
            h.assertTrue(common.tier() == PillTier.COMMON && common.qi() <= PillKind.SNOW_PLUM.qi() / 2,
                    common + " is not clearly below Snow Plum");
        }
        h.assertTrue(PillKind.SNOW_PLUM.tier() == PillTier.MIDDLE, "Snow Plum is not a middle pill");
        h.assertTrue(PillKind.BLOOD_REPLENISHING.tier() == PillTier.MIDDLE && PillKind.QUICK_RECOVERY.tier() == PillTier.MIDDLE,
                "the middle everyday pills");
        // the alchemist's rows are on sale now that the items exist
        for (String row : new String[] {"quick_recovery", "vitality", "fasting", "antidote", "blood_replenishing"}) {
            h.assertTrue(MeritShop.listed(row), "the merit row " + row + " is not on sale");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void theFastingPillFeedsLikeAMealAndOnlyTheHungry(GameTestHelper h) {
        ServerPlayer p = player(h);
        Item fasting = ModItems.PILL_FASTING.get();
        p.getFoodData().setFoodLevel(6);
        p.getFoodData().setSaturation(0.0F);
        ItemStack stack = eat(p, fasting);
        h.assertTrue(p.getFoodData().getFoodLevel() == 6 + PillEffects.FASTING_FOOD, "hunger: " + p.getFoodData().getFoodLevel());
        h.assertTrue(p.getFoodData().getSaturationLevel() > 5.0F, "saturation: " + p.getFoodData().getSaturationLevel());
        h.assertTrue(stack.getCount() == 1, "the pill was not taken");
        h.assertTrue(profileOf(p).pool() == 100.0D, "the fasting pill gave qi");
        // a full stomach: the pill is not wasted
        p.getFoodData().setFoodLevel(20);
        InteractionResultHolder<ItemStack> use = fasting.use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        h.assertTrue(use.getResult() == net.minecraft.world.InteractionResult.FAIL, "a full player started eating the fasting pill");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void theAntidoteClearsPoisonAndHalvesTheNextOne(GameTestHelper h) {
        ServerPlayer p = player(h);
        // the Thousand-Poison backlash and the Tang daggers both apply plain poison
        p.addEffect(new MobEffectInstance(MobEffects.POISON, 400, 1));
        h.assertTrue(p.hasEffect(MobEffects.POISON), "setup: not poisoned");
        eat(p, ModItems.PILL_ANTIDOTE.get());
        h.assertTrue(!p.hasEffect(MobEffects.POISON), "the antidote did not clear the poison");
        h.assertTrue(PillEffects.antidoteActive(p), "no resistance after the antidote");
        p.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        MobEffectInstance again = p.getEffect(MobEffects.POISON);
        h.assertTrue(again != null && again.getDuration() < 200, "the next poison was not shortened: " + again);
        // without the pill (and without the Thousand-Poison state) a poison is full length
        ServerPlayer other = player(h);
        other.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        h.assertTrue(other.getEffect(MobEffects.POISON).getDuration() == 200, "an unprotected player got a short poison");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void bloodReplenishingHealsStronglyThenCoolsDown(GameTestHelper h) {
        ServerPlayer p = player(h);
        Item blood = ModItems.PILL_BLOOD_REPLENISHING.get();
        p.setHealth(4.0F);
        eat(p, blood);
        h.assertTrue(Math.abs(p.getHealth() - (4.0F + PillEffects.BLOOD_HEAL)) < 0.01F, "health after the pill: " + p.getHealth());
        h.assertTrue(p.hasEffect(MobEffects.REGENERATION), "no regeneration");
        h.assertTrue(p.getCooldowns().isOnCooldown(blood), "no cooldown after a strong heal");
        InteractionResultHolder<ItemStack> again = blood.use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        h.assertTrue(again.getResult() == net.minecraft.world.InteractionResult.FAIL, "a second pill during the cooldown");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void quickRecoverySpeedsTheQiUpForAWhile(GameTestHelper h) {
        ServerPlayer plain = player(h);
        plain.tickCount = 2;
        ProfileEvents.passiveCirculation(plain);
        double slow = profileOf(plain).circulating();
        ServerPlayer quick = player(h);
        eat(quick, ModItems.PILL_QUICK_RECOVERY.get());
        h.assertTrue(PillEffects.qiRegenFactor(quick) == PillEffects.QUICK_FACTOR, "the buff is not on");
        quick.tickCount = 2;
        ProfileEvents.passiveCirculation(quick);
        double fast = profileOf(quick).circulating();
        h.assertTrue(slow > 0.0D && fast > slow * 2.3D && fast < slow * 2.7D, "qi per step " + slow + " -> " + fast);
        h.assertTrue(PillEffects.qiRegenFactor(plain) == 1.0D, "the buff leaked to another player");
        // the buff ends
        quick.getPersistentData().putLong("murim_quick_until", quick.level().getGameTime() - 1);
        h.assertTrue(PillEffects.qiRegenFactor(quick) == 1.0D, "the buff does not end");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void theVitalityPillIsASmallQiPillWithALittleRegeneration(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.assertTrue(PillKind.VITALITY.qi() >= 25 && PillKind.VITALITY.qi() <= 50, "a common pill carries 25-50 qi");
        h.assertTrue(PillKind.VITALITY.qi() < PillKind.SNOW_PLUM.qi() / 2, "vitality " + PillKind.VITALITY.qi());
        double pool0 = profileOf(p).pool();
        eat(p, ModItems.PILL_VITALITY.get());
        h.assertTrue(pillsOf(p).windowOpen(p.level().getGameTime()), "the vitality pill did not open the window");
        h.assertTrue(profileOf(p).pool() == pool0, "a pill paid before the window closed");
        h.assertTrue(p.hasEffect(MobEffects.REGENERATION), "no regeneration");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void snowPlumGivesNothingAtOnceAndRunsTheFlowGame(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.PROFILE, profileOf(p).withRank(Realm.SECOND));
        double pool0 = profileOf(p).pool();
        PillService.eat(p, PillKind.SNOW_PLUM);
        h.assertTrue(Math.abs(profileOf(p).pool() - pool0) < 1.0E-9D, "a middle pill paid at once");
        p.setData(ModAttachments.MEDITATION, MeditationState.started(true, CultivationState.SEEDED));
        PillService.onMeditationStart(p);
        h.assertTrue(PillService.absorbing(p), "Snow Plum did not start the flow game");
        h.succeed();
    }

    /** The sync packet carries one intake count per pill kind; it must come back whole (a cap of 8 once cut it and disconnected the client). */
    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void theSyncPacketCarriesEveryPillKind(GameTestHelper h) {
        int kinds = PillKind.values().length;
        int[] taken = new int[kinds];
        for (int i = 0; i < kinds; i++) {
            taken[i] = i + 1;
        }
        int[] tolerance = new int[kinds * 2];
        for (int i = 0; i < kinds; i++) {
            tolerance[i * 2] = i;
            tolerance[i * 2 + 1] = 5999 - i;
        }
        var sync = new io.github.verycooltimo.murim.network.PillPayloads.Sync(new int[] {0, 1}, 5, true, new int[0],
                io.github.verycooltimo.murim.network.PillPayloads.Event.NONE, false, 0.5F, false, 0.0F, false, 0.0F, taken, 3, 2, tolerance);
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        io.github.verycooltimo.murim.network.PillPayloads.Sync.STREAM_CODEC.encode(buf, sync);
        var back = io.github.verycooltimo.murim.network.PillPayloads.Sync.STREAM_CODEC.decode(buf);
        h.assertTrue(buf.readableBytes() == 0, "the packet was longer than its decoder read: " + buf.readableBytes() + " bytes extra");
        h.assertTrue(java.util.Arrays.equals(back.taken(), taken), "the intake counts changed on the wire: " + java.util.Arrays.toString(back.taken()));
        h.assertTrue(java.util.Arrays.equals(back.tolerance(), tolerance), "per-kind tolerance changed on wire");
        buf.release();
        h.assertTrue(back.streakKind() == 3 && back.streakCount() == 2 && back.quality() == 0.5F, "the tolerance changed on the wire");
        h.succeed();
    }

    private static DantianProfile profileOf(ServerPlayer p) {
        return p.getData(ModAttachments.PROFILE);
    }

    private static PillState pillsOf(ServerPlayer p) {
        return p.getData(ModAttachments.PILLS);
    }

    // API verified: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/LivingEntity.java
    // #addEffect/hasEffect, world/Container.java#countItem; the item entry is PillItem#finishUsingItem.
    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void plumBlossomCuresPillAndBreakthroughAftereffectsWithoutQi(GameTestHelper h) {
        ServerPlayer p = player(h);
        var before = p.getData(ModAttachments.PROFILE);
        for (int duration : new int[] {PillService.INJURY_TICKS, MeditationService.BACKLASH_TICKS}) {
            p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, duration, 1));
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 1));
            p.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
            p.addEffect(new MobEffectInstance(io.github.verycooltimo.murim.decor.IntWorkshop.TIPSY, 5400, 2));
            p.addEffect(new MobEffectInstance(io.github.verycooltimo.murim.decor.IntWorkshop.HANGOVER, 2400));
            p.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 2400));
            ItemStack left = eat(p, ModItems.PILL_PLUM_BLOSSOM.get());
            h.assertTrue(!p.hasEffect(io.github.verycooltimo.murim.decor.IntWorkshop.TIPSY)
                    && !p.hasEffect(io.github.verycooltimo.murim.decor.IntWorkshop.HANGOVER)
                    && !p.hasEffect(MobEffects.DIG_SLOWDOWN), "wine aftereffects remain after eating plum pill");
            h.assertTrue(left.getCount() == 1, "medicine was not consumed exactly once");
            h.assertTrue(!p.hasEffect(MobEffects.WEAKNESS) && !p.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "backlash remains");
            h.assertTrue(p.hasEffect(MobEffects.POISON), "medicine removed unrelated poison");
            h.assertTrue(p.getData(ModAttachments.PROFILE).equals(before), "medicine changed qi/profile");
            h.assertTrue(p.getData(ModAttachments.PILLS).pending().isEmpty(), "medicine opened qi window");
        }
        h.assertTrue(PillKind.PLUM_BLOSSOM.utility() && PillKind.PLUM_BLOSSOM.qi() == 0, "not a utility medicine");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void healerSellsPlumBlossomForOneMerit(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.SECT, io.github.verycooltimo.murim.sect.SectState.NONE.joined().contribute(1));
        var offer = MeritShop.offer("plum_blossom").orElseThrow();
        h.assertTrue(offer.price() == 1 && offer.seller().equals("gyeong_cho"), "wrong price/seller");
        h.assertTrue(MeritShop.buy(p, "plum_blossom") == MeritShop.Result.OK, "healer refused medicine");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == 0
                && p.getInventory().countItem(ModItems.PILL_PLUM_BLOSSOM.get()) == 1, "wrong payment or quantity");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void firstTornPillEarnsExactlyOneHealerGift(GameTestHelper h) {
        ServerPlayer p = player(h);
        var healer = new io.github.verycooltimo.murim.entity.SectDisciple(
                io.github.verycooltimo.murim.registry.ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        healer.setMember(io.github.verycooltimo.murim.sect.SectRoster.of("gyeong_cho").orElseThrow());
        h.assertTrue(!io.github.verycooltimo.murim.sect.PlumBlossomCare.meet(p, healer), "gift before injury");
        var plan = PillRules.plan(java.util.List.of(PillKind.ORIGIN_ENERGY), Realm.THIRD, 100, 1);
        PillService.tear(p, plan);
        PillService.tear(p, plan);
        h.assertTrue(p.getData(ModAttachments.SECT).has(io.github.verycooltimo.murim.sect.PlumBlossomCare.TORN),
                "survival tear did not record healer eligibility");
        // Council members refuse conversations before gift delivery. Isolate this synchronous
        // conversation from other batches' clock changes, then restore the shared world clock.
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/Level.java#getDayTime;
        // server/level/ServerLevel.java#setDayTime.
        long dayTime = h.getLevel().getDayTime();
        try {
            h.getLevel().setDayTime(0L);
            h.assertTrue(!io.github.verycooltimo.murim.sect.DialogueService.busy(p, healer), "healer is busy");
            io.github.verycooltimo.murim.sect.DialogueService.open(p, healer);
            h.assertTrue(p.getData(ModAttachments.SECT).has(io.github.verycooltimo.murim.sect.PlumBlossomCare.GIVEN),
                    "conversation did not deliver healer gift");
            h.assertTrue(p.getInventory().countItem(ModItems.PILL_PLUM_BLOSSOM.get()) == 1, "first tear gave wrong quantity");
        } finally {
            h.getLevel().setDayTime(dayTime);
        }
        h.assertTrue(healer.bubbleKey().equals(io.github.verycooltimo.murim.sect.PlumBlossomCare.LINE), "healer did not speak");
        // A reconstructed saved state still remembers delivery; tears after using the gift cannot farm it.
        var state = p.getData(ModAttachments.SECT);
        p.setData(ModAttachments.SECT, new io.github.verycooltimo.murim.sect.SectState(state.version(), state.member(),
                state.generation(), state.flags(), state.contribution(), state.spent(), state.journal()));
        PillService.tear(p, plan);
        h.assertTrue(!io.github.verycooltimo.murim.sect.PlumBlossomCare.meet(p, healer)
                && p.getInventory().countItem(ModItems.PILL_PLUM_BLOSSOM.get()) == 1, "duplicate gift after tear/reload");
        h.succeed();
    }
}
