package io.github.verycooltimo.murim.trade;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.library.JunkBook;
import io.github.verycooltimo.murim.library.JunkFactory;
import io.github.verycooltimo.murim.library.JunkKind;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTest торговца (docs/design/24-bandit-camp.md §7): товар за серебро по ценам {@link PeddlerStock},
 * скупка хлама с любыми компонентами, уход из деревни по сроку.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PeddlerTests {

    private PeddlerTests() {
    }

    /** Пилюля за 7 лян (за 6 — нет), хлам и страница скупаются за серебро, страница на продажу — с книгой. */
    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void peddlerTradesForSilver(GameTestHelper helper) {
        Peddler p = PeddlerSpawns.arrive(helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 2)), Peddler.VILLAGE_STAY);
        helper.assertTrue(p != null, "торговец не появился");
        MerchantOffers offers = p.getOffers();
        helper.assertTrue(offers.size() >= 9, "предложений " + offers.size());
        MerchantOffer pill = find(offers, ModItems.PILL_SNOW_PLUM.get().getDefaultInstance());
        helper.assertTrue(pill != null, "нет пилюли в продаже");
        int price = PeddlerStock.sellPrice("murim:pill_snow_plum");
        helper.assertTrue(pill.satisfiedBy(new ItemStack(ModItems.SILVER_TAEL.get(), price), ItemStack.EMPTY), "пилюля не продаётся за " + price);
        helper.assertTrue(!pill.satisfiedBy(new ItemStack(ModItems.SILVER_TAEL.get(), price - 1), ItemStack.EMPTY), "пилюля дешевле цены");
        ItemStack junk = JunkFactory.stack(new JunkBook(JunkKind.FAKE_GRAND, 7L, -1, false));
        boolean junkBought = offers.stream().anyMatch(o -> o.getResult().is(ModItems.SILVER_TAEL.get()) && o.satisfiedBy(junk, ItemStack.EMPTY));
        helper.assertTrue(junkBought, "хлам " + junk.getItem() + " не скупается");
        ItemStack page = new ItemStack(ModItems.MANUAL_PAGE.get());
        page.set(ModDataComponents.TECHNIQUE.get(), net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "wind_god_steps"));
        helper.assertTrue(offers.stream().anyMatch(o -> o.getResult().is(ModItems.SILVER_TAEL.get()) && o.satisfiedBy(page, ItemStack.EMPTY)),
                "страница не скупается");
        offers.stream().filter(o -> o.getResult().is(ModItems.MANUAL_PAGE.get())).forEach(o ->
                helper.assertTrue(o.getResult().get(ModDataComponents.TECHNIQUE.get()) != null, "страница на продажу без книги"));
        p.discard();
        helper.succeed();
    }

    /** Деревенский торговец уходит по сроку; лавочник (срок 0) — нет. */
    @GameTest(template = "small_floor", timeoutTicks = 60)
    public static void peddlerLeavesVillage(GameTestHelper helper) {
        Peddler visitor = PeddlerSpawns.arrive(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)), 10);
        Peddler stall = PeddlerSpawns.arrive(helper.getLevel(), helper.absolutePos(new BlockPos(3, 2, 3)), 0);
        helper.assertTrue(visitor != null && stall != null && stall.isStall() && !visitor.isStall(), "не появились");
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(visitor.isRemoved(), "деревенский торговец не ушёл");
            helper.assertTrue(stall.isAlive(), "лавочник ушёл");
            stall.discard();
            helper.succeed();
        });
    }

    private static MerchantOffer find(MerchantOffers offers, ItemStack result) {
        for (MerchantOffer o : offers) {
            if (ItemStack.isSameItem(o.getResult(), result)) {
                return o;
            }
        }
        return null;
    }
}
