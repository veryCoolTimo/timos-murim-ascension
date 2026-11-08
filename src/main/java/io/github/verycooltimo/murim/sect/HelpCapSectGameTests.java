package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.PageRules;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The sect's part of the ONE help pool (hua-main-1810 §7, decisions of 18.10): the merit shop sells a page for
 * {@code 8 + 2 s} merit and never more than two pages for one transition; the mentor's drill after a lost bout draws from
 * the same pool as pages and readings.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HelpCapSectGameTests {

    private static final String FLOOR = "small_floor";
    private static final double EPS = 1.0E-9D;
    private static final ResourceLocation BLOSSOMS = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_blossoms");
    private static final ResourceLocation PLUM = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_basic");
    private static final ResourceLocation ART = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum");

    private HelpCapSectGameTests() {
    }

    private static ServerPlayer disciple(GameTestHelper h, int merit, int stars) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE).contribute(merit));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(PLUM, TechniqueProgress.learned(stars, 12))
                .with(BLOSSOMS, TechniqueProgress.learned(stars, 12)));
        // The mentor teaches the art of the foundation in the loadout.
        p.setData(ModAttachments.LOADOUT, new io.github.verycooltimo.murim.mastery.Loadout(java.util.List.of(), 0,
                java.util.Optional.of(PLUM)));
        return p;
    }

    private static int pagesHeld(ServerPlayer p) {
        int n = 0;
        for (ItemStack stack : p.getInventory().items) {
            if (stack.is(ModItems.MANUAL_PAGE.get()) && BLOSSOMS.equals(stack.get(ModDataComponents.TECHNIQUE.get()))) {
                n += stack.getCount();
            }
        }
        return n;
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void aShopPageCostsEightPlusTwoPerTargetStar(GameTestHelper h) {
        MeritShop.Offer page = MeritShop.offer("page").orElseThrow();
        for (int stars : new int[] {0, 5, 11}) {
            ServerPlayer p = disciple(h, 100, stars);
            int price = 8 + 2 * (stars + 1);
            h.assertTrue(MeritShop.price(p, page) == price, "price at ★" + (stars + 1) + ": " + MeritShop.price(p, page));
            h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.OK, "the page was not sold at ★" + (stars + 1));
            h.assertTrue(p.getData(ModAttachments.SECT).merit() == 100 - price,
                    "wrong merit taken at ★" + (stars + 1) + ": left " + p.getData(ModAttachments.SECT).merit());
            h.assertTrue(pagesHeld(p) == 1, "no page in the pack");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void noMoreThanTwoPagesForOneTransition(GameTestHelper h) {
        ServerPlayer p = disciple(h, 200, 3);
        int price = PageRules.pagePrice(4);
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.OK, "first page");
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.OK, "second page");
        int merit = p.getData(ModAttachments.SECT).merit();
        h.assertTrue(merit == 200 - 2 * price, "two pages cost " + (200 - merit));
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.LIMIT, "a third page was sold for the same transition");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == merit && pagesHeld(p) == 2, "a refused sale took merit or gave a page");

        // After the transition is practised away the shop sells again, at the price of the next star.
        MasteryService.study(p, PLUM, MasteryRules.need(3, 12));
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(ART).stars() == 4, "the star was not reached");
        h.assertTrue(MeritShop.price(p, MeritShop.offer("page").orElseThrow()) == PageRules.pagePrice(5), "price did not follow the star");
        // The two pages in the pack keep their share of the NEW pool, so a third is still refused.
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.LIMIT, "pages in the pack must reserve their share");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void pagesStashedInAChestDoNotOpenTheShopAgain(GameTestHelper h) {
        ServerPlayer p = disciple(h, 200, 3);
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.OK, "first page");
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.OK, "second page");
        int merit = p.getData(ModAttachments.SECT).merit();
        // The limit is counted in the sect journal, not in the pack: with the pages put away (a chest) there is no third sale.
        p.getInventory().clearContent();
        h.assertTrue(pagesHeld(p) == 0, "the pack was not emptied");
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.LIMIT, "pages stashed away gave room for another sale");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == merit, "a refused sale took merit");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void aLessonTakesTheRoomOfAPage(GameTestHelper h) {
        ServerPlayer p = disciple(h, 100, 6);
        double cost = MasteryRules.need(6, 12);
        // The drill after a lost bout is mentor's help: 8 % of the star, from the same pool.
        SectDefeat.reward(p);
        h.assertTrue(Math.abs(p.getData(ModAttachments.MASTERY).progress(ART).help() - SectDefeat.BONUS * cost) < EPS,
                "the drill did not draw from the pool");
        // 20 % - 8 % = 12 %: one page (10 %) fits and a second does not.
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.OK, "a page must fit after one drill");
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.LIMIT, "the second page must not fit after a drill and a page");
        // More drills give nothing to the experience once the pool is full, and never more than a fifth in all.
        for (int i = 0; i < 5; i++) {
            SectDefeat.reward(p);
        }
        TechniqueProgress art = p.getData(ModAttachments.MASTERY).progress(ART);
        h.assertTrue(art.help() <= MasteryRules.HELP_CAP * cost + EPS, "the pool passed a fifth: " + art.help() / cost);
        h.assertTrue(art.progress() <= MasteryRules.HELP_CAP * cost + EPS, "help gave more than a fifth of the star: " + art.progress() / cost);
        h.succeed();
    }
}
