package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SectTabletData;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The merit exchange (docs/adr/0012): standing counts the merit earned in all, purchases come from the balance, and the
 * personal stocks refill by the player's own play time and by nothing else.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class MeritShopSectGameTests {
    private static final String FLOOR = "small_floor";
    private static final long MIN = MeritStock.TICKS_PER_MINUTE;

    private MeritShopSectGameTests() {
    }

    /** A graduate of the White Plum with the given merit earned; the labour path to trusted is closed on purpose. */
    private static ServerPlayer member(GameTestHelper h, int earned) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO).contribute(earned));
        return p;
    }

    private static void play(ServerPlayer p, long ticks) {
        p.awardStat(Stats.PLAY_TIME, (int) ticks);
    }

    private static SectTabletData.Shop line(SectTabletData d, String id) {
        return d.shop().stream().filter(l -> l.name().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("murim.tablet.shop." + id)).findFirst().orElse(null);
    }

    private static int stock(ServerPlayer p, String id) {
        return MeritShop.stock(p, MeritShop.offer(id).orElseThrow()).count();
    }

    /** Restore the shared world counter after each scenario so GameTest order cannot spend another test's supply. */
    private static final class ElixirFixture implements AutoCloseable {
        final io.github.verycooltimo.murim.cultivation.GreatElixirStock stock;
        final java.lang.reflect.Field issued;
        final int before;
        ElixirFixture(GameTestHelper h) throws Exception {
            stock = io.github.verycooltimo.murim.cultivation.GreatElixirStock.get(h.getLevel());
            issued = stock.getClass().getDeclaredField("issued");
            issued.setAccessible(true);
            before = issued.getInt(stock);
            issued.setInt(stock, 0);
        }
        @Override public void close() throws Exception { issued.setInt(stock, before); stock.setDirty(); }
    }

    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "merit_elixir_world")
    public static void soulVitalitySupplyIsThreePerWorldAndSurvivesSaving(GameTestHelper h) throws Exception {
        try (var fixture = new ElixirFixture(h)) {
            ServerPlayer p = member(h, 300);
            ServerPlayer q = member(h, 300);
            h.assertTrue(MeritShop.buy(p, "origin_energy") == MeritShop.Result.OK, "first elixir");
            play(p, 60 * MIN);
            h.assertTrue(MeritShop.buy(p, "origin_energy") == MeritShop.Result.OK, "second elixir");
            h.assertTrue(MeritShop.buy(q, "origin_energy") == MeritShop.Result.OK, "third elixir");
            play(q, 60 * MIN);
            int balance = q.getData(ModAttachments.SECT).merit();
            h.assertTrue(MeritShop.buy(q, "origin_energy") == MeritShop.Result.STOCK, "fourth world elixir sold");
            h.assertTrue(q.getData(ModAttachments.SECT).merit() == balance, "empty world stock charged merit");
            var saved = fixture.stock.save(new net.minecraft.nbt.CompoundTag(), h.getLevel().registryAccess());
            h.assertTrue(io.github.verycooltimo.murim.cultivation.GreatElixirStock.load(saved, h.getLevel().registryAccess()).remaining() == 0,
                    "reload refilled finite stock");
            h.assertTrue(stock(p, "origin_energy") == 0 && MeritShop.minutesToNext(p, MeritShop.offer("origin_energy").orElseThrow()) == 0,
                    "shop promises a refill of exhausted world stock");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "merit_shop_a")
    public static void spendingNeverLowersStanding(GameTestHelper h) throws Exception {
        try (var fixture = new ElixirFixture(h)) {
            ServerPlayer p = SectGameTests.fakePlayer(h);
            p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO)
                    .with(SectStanding.APPROVAL).contribute(80));
            h.assertTrue(SectService.standing(p) == SectStanding.TRUSTED, "setup: not trusted: " + SectService.standing(p));
            int total = p.getData(ModAttachments.SECT).contribution();
            h.assertTrue(MeritShop.buy(p, "sword") == MeritShop.Result.OK, "sword");
            h.assertTrue(MeritShop.buy(p, "origin_energy") == MeritShop.Result.OK, "origin energy");
            h.assertTrue(MeritShop.buy(p, "snow_plum") == MeritShop.Result.OK, "snow plum");
            h.assertTrue(MeritShop.buy(p, "scriptures") == MeritShop.Result.OK, "hall pass");
            SectState s = p.getData(ModAttachments.SECT);
            h.assertTrue(s.merit() == 80 - 20 - 24 - 6 - 12, "the balance after four purchases: " + s.merit());
            h.assertTrue(s.contribution() == total, "the total earned changed: " + s.contribution());
            h.assertTrue(SectService.standing(p) == SectStanding.TRUSTED, "spending lowered the standing to " + SectService.standing(p));
            // Down to the last point of the balance: still trusted.
            p.setData(ModAttachments.SECT, s.spend(s.merit()));
            h.assertTrue(p.getData(ModAttachments.SECT).merit() == 0 && SectService.standing(p) == SectStanding.TRUSTED,
                    "an empty balance lowered the standing");
            h.succeed();
        }
    }

    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "merit_shop_b")
    public static void stockRefillsOnlyByPlayTime(GameTestHelper h) {
        ServerPlayer p = member(h, 200);
        MeritShop.Offer plum = MeritShop.offer("snow_plum").orElseThrow();
        h.assertTrue(stock(p, "snow_plum") == 2, "a fresh stock is full");
        h.assertTrue(MeritShop.buy(p, "snow_plum") == MeritShop.Result.OK, "first pill");
        h.assertTrue(MeritShop.buy(p, "snow_plum") == MeritShop.Result.OK, "second pill");
        int merit = p.getData(ModAttachments.SECT).merit();
        h.assertTrue(merit == 200 - 12 && p.getInventory().countItem(ModItems.PILL_SNOW_PLUM.get()) == 2, "two pills for 12 merit");
        h.assertTrue(MeritShop.buy(p, "snow_plum") == MeritShop.Result.STOCK, "a third pill from a stock of two");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == merit && p.getInventory().countItem(ModItems.PILL_SNOW_PLUM.get()) == 2,
                "a refused sale took merit or gave a pill");

        // Days pass and the night is slept through: the world clock moves, the stock does not.
        long clock = h.getLevel().getDayTime();
        h.getLevel().setDayTime(clock + 5 * 24000L);
        h.assertTrue(stock(p, "snow_plum") == 0, "a new day refilled the stock");
        h.assertTrue(MeritShop.buy(p, "snow_plum") == MeritShop.Result.STOCK, "the sale reopened with the day");
        h.getLevel().setDayTime(clock);

        // Play time does: 30 minutes less a tick give nothing, the last tick gives one pill.
        play(p, 30 * MIN - 1);
        h.assertTrue(stock(p, "snow_plum") == 0, "29 minutes 59 seconds of play refilled");
        play(p, 1);
        h.assertTrue(stock(p, "snow_plum") == 1, "30 minutes of play gave no pill");
        h.assertTrue(MeritShop.buy(p, "snow_plum") == MeritShop.Result.OK, "the refilled pill was not sold");
        h.assertTrue(MeritShop.buy(p, "snow_plum") == MeritShop.Result.STOCK, "only one pill came back");
        h.assertTrue(plum.refill().minutes() == 30, "catalog");
        play(p, 60 * MIN);
        h.assertTrue(stock(p, "snow_plum") == 2, "an hour of play must fill the stock of two, not more");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "merit_shop_c")
    public static void theSwordComesOncePerTwentyMinutes(GameTestHelper h) {
        ServerPlayer p = member(h, 200);
        h.assertTrue(MeritShop.buy(p, "sword") == MeritShop.Result.OK, "first sword");
        int merit = p.getData(ModAttachments.SECT).merit();
        h.assertTrue(MeritShop.buy(p, "sword") == MeritShop.Result.STOCK, "a second sword at once");
        play(p, 19 * MIN);
        h.assertTrue(MeritShop.buy(p, "sword") == MeritShop.Result.STOCK, "a second sword after 19 minutes");
        h.assertTrue(MeritShop.minutesToNext(p, MeritShop.offer("sword").orElseThrow()) == 1, "the wait is one minute");
        play(p, MIN);
        h.assertTrue(MeritShop.buy(p, "sword") == MeritShop.Result.OK, "no sword after 20 minutes");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == merit - 20 && p.getInventory().countItem(ModItems.HUASHAN_SWORD.get()) == 2,
                "two swords, forty merit");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "merit_shop_d")
    public static void aStockOfOneAndOnePerHourForOriginEnergy(GameTestHelper h) throws Exception {
        try (var fixture = new ElixirFixture(h)) {
            ServerPlayer p = member(h, 200);
            h.assertTrue(MeritShop.buy(p, "origin_energy") == MeritShop.Result.OK, "origin energy");
            h.assertTrue(MeritShop.buy(p, "origin_energy") == MeritShop.Result.STOCK, "a second one at once");
            play(p, 59 * MIN);
            h.assertTrue(MeritShop.buy(p, "origin_energy") == MeritShop.Result.STOCK, "after 59 minutes");
            play(p, MIN);
            h.assertTrue(MeritShop.buy(p, "origin_energy") == MeritShop.Result.OK, "after an hour");
            h.assertTrue(p.getData(ModAttachments.SECT).merit() == 200 - 48, "price 24 each");
            // An empty stock is told before the lack of merit.
            ServerPlayer poor = member(h, 3);
            h.assertTrue(MeritShop.buy(poor, "snow_plum") == MeritShop.Result.MERIT, "3 merit is short of a 6-merit pill");
            h.succeed();
        }
    }

    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "merit_shop_e")
    public static void theHallPassIsBoughtOnceWithoutASecondFee(GameTestHelper h) {
        ServerPlayer p = member(h, 100);
        h.assertTrue(MeritShop.buy(p, "scriptures") == MeritShop.Result.OK, "pass");
        h.assertTrue(p.getData(ModAttachments.SECT).has("pass.scriptures") && p.getData(ModAttachments.SECT).merit() == 88, "12 merit for the pass");
        h.assertTrue(MeritShop.buy(p, "scriptures") == MeritShop.Result.OWNED, "a second fee");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == 88, "the second purchase took merit");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "merit_shop_f")
    public static void unlistedRowsAreNotSold(GameTestHelper h) {
        ServerPlayer p = member(h, 500);
        // The Quick Recovery pill has no item yet: the row exists in the data and is not on sale.
        MeritShop.Offer quick = MeritShop.offer("quick_recovery").orElseThrow();
        boolean exists = net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(quick.item()));
        h.assertTrue(MeritShop.listed(quick) == exists, "listed does not follow the item");
        if (!exists) {
            h.assertTrue(MeritShop.buy(p, "quick_recovery") == MeritShop.Result.UNKNOWN, "an item that is not in the game was sold");
        }
        // The refining formulas are behind the feature: unlisted, not sold, nothing taken.
        h.assertTrue(!MeritShop.listed("formula_snow_plum") && !MeritShop.listed("formula_origin_energy"), "formulas are listed without refining");
        h.assertTrue(MeritShop.buy(p, "formula_snow_plum") == MeritShop.Result.UNKNOWN, "a formula was sold without refining");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == 500 && !p.getData(ModAttachments.SECT).has("formula.snow_plum"), "a refused formula took merit");
        // With the feature on they sell once each, at 30 and 60, and need their standing; they have no stock.
        MeritCatalog refining = MeritShop.CATALOG.with("refining", true);
        h.assertTrue(MeritShop.buy(p, "formula_snow_plum", refining) == MeritShop.Result.OK, "snow plum formula");
        h.assertTrue(MeritShop.buy(p, "formula_snow_plum", refining) == MeritShop.Result.OWNED, "snow plum formula twice");
        h.assertTrue(MeritShop.buy(p, "formula_origin_energy", refining) == MeritShop.Result.OK, "origin energy formula");
        SectState s = p.getData(ModAttachments.SECT);
        h.assertTrue(s.merit() == 500 - 30 - 60 && s.has("formula.snow_plum") && s.has("formula.origin_energy"), "formulas cost 30 and 60");
        ServerPlayer novice = SectGameTests.fakePlayer(h);
        novice.setData(ModAttachments.SECT, SectState.NONE.joined().contribute(200));
        h.assertTrue(MeritShop.buy(novice, "formula_origin_energy", refining) == MeritShop.Result.STANDING, "a novice bought a graduate's formula");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "merit_shop_g")
    public static void stockAndBalanceSurviveTheSaveAndReachTheTablet(GameTestHelper h) {
        ServerPlayer p = member(h, 90);
        MeritShop.buy(p, "snow_plum");
        MeritShop.buy(p, "sword");
        play(p, 5 * MIN);
        // The sect attachment is written and read as it is saved: stock, clocks and the balance come back the same.
        SectState saved = SectState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                SectState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, p.getData(ModAttachments.SECT)).getOrThrow()).getOrThrow();
        h.assertTrue(saved.equals(p.getData(ModAttachments.SECT)), "the sect state changed in the round trip");
        p.setData(ModAttachments.SECT, saved);
        h.assertTrue(stock(p, "snow_plum") == 1 && stock(p, "sword") == 0, "stock after reload: " + stock(p, "snow_plum") + "/" + stock(p, "sword"));

        SectTabletData d = SectTablet.collect(p);
        h.assertTrue(d.merit() == 90 - 6 - 20 && d.contribution() == 90, "balance and total on the tablet: " + d.merit() + "/" + d.contribution());
        SectTabletData.Shop plum = line(d, "snow_plum");
        SectTabletData.Shop sword = line(d, "sword");
        h.assertTrue(plum != null && plum.have() == 1 && plum.max() == 2 && plum.minutes() == 25, "the pill line: " + plum);
        h.assertTrue(sword != null && sword.have() == 0 && sword.max() == 1 && sword.minutes() == 15, "the sword line: " + sword);
        // The origin energy pill is for graduates: this one is, so it is told too; the formulas (no stock) are not.
        h.assertTrue(d.shop().stream().noneMatch(l -> l.max() == 0), "a line without a stock");
        // The wire keeps the shop lines.
        net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        SectTabletData.CODEC.encode(buf, d);
        SectTabletData back = SectTabletData.CODEC.decode(buf);
        h.assertTrue(back.shop().equals(d.shop()), "the shop lines changed on the wire");
        // A novice is not told about the pills of graduates.
        ServerPlayer novice = SectGameTests.fakePlayer(h);
        novice.setData(ModAttachments.SECT, SectState.NONE.joined().contribute(20));
        SectTabletData n = SectTablet.collect(novice);
        h.assertTrue(line(n, "origin_energy") == null && line(n, "sword") == null && line(n, "snow_plum") != null,
                "a novice is told about the pills and the sword of higher standings");
        h.succeed();
    }
}
