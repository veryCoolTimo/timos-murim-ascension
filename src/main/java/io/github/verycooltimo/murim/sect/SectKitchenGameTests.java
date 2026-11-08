package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.TeaFocus;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.item.MealBowlItem;
import io.github.verycooltimo.murim.item.PlumTeaItem;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Mount Hua kitchen (author 18.10): the cook serves the food of the right meal (porridge or a bun at breakfast, rice, soup,
 * buns and tea at dinner; no feast on the review day), the foods carry small vanilla-like values, the tea gives its short
 * focus, and the cook and the servants play their own poses (stir, chop, taste, ladle, tray round, wipe) at the meals.
 *
 * <p>Every test has its own batch and puts the world clock back, like the other sect tests that read the shared day time.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectKitchenGameTests {
    private static final String YARD = "sect_yard";

    private SectKitchenGameTests() { }

    /** A layout without plan zones: everything comes from the file (the same trick as the layout AI tests). */
    private record Mini(Vec3 origin, SectLayoutFile file) implements SectLayout {
        @Override
        public Vec3 at(String zone, double u, double v) {
            return null;
        }

        @Override
        public float yaw(double u, double v) {
            return SectLayout.yawOf(u, v);
        }

        @Override
        public double[] half(String zone) {
            return null;
        }

        @Override
        public double[] local(String zone, Vec3 pos) {
            return null;
        }
    }

    /** The dining hall in miniature: a counter, a row of tables beside it, a stove, tables elsewhere. */
    private static SectLayoutFile hall(GameTestHelper h) {
        List<SectLayoutFile.Spot> spots = new ArrayList<>();
        spots.add(spot(h, "spot.counter", "counter", 6, 6, List.of("servant", "steward")));
        for (int x : new int[] {4, 8, 9}) {
            spots.add(spot(h, "spot.row." + x, "table", x, 6, List.of("all")));
        }
        spots.add(spot(h, "spot.t1", "table", 5, 12, List.of("all")));
        spots.add(spot(h, "spot.t2", "table", 9, 12, List.of("all")));
        spots.add(spot(h, "spot.stove", "stove", 16, 6, List.of("servant", "cook")));
        return new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                spots, List.of(), List.of(), List.of());
    }

    private static SectLayoutFile.Spot spot(GameTestHelper h, String id, String kind, int x, int z, List<String> roles) {
        BlockPos at = h.absolutePos(new BlockPos(x, 2, z));
        return new SectLayoutFile.Spot(id, kind, "stand", at, null, at.north(), 180.0F, null, "dining_hall", null, null, roles, "ok", -1, -1);
    }

    private static SectDisciple npc(GameTestHelper h, SectLayout layout, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static ServerPlayer member(GameTestHelper h, double x, double z) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.tickCount = 2000;
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with("met_mentor"));
        return p;
    }

    /** Game time of a meal: sect day {@code day} (it turns at tick 23000 of the world day), tick of the day. */
    private static long at(long day, int tick) {
        return day * 24000L + tick;
    }

    private static List<Item> served(ServerPlayer p) {
        List<Item> out = new ArrayList<>();
        for (ItemStack s : p.getInventory().items) {
            if (SectMeals.isServed(s)) out.add(s.getItem());
        }
        return out;
    }

    // ------------------------------------------------------------------ the right food in the right meal

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_kitchen_rightfoodperperiod")
    public static void mealServesTheRightFoodPerPeriod(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        // Sect days: 2 and 4 porridge mornings, 3 and 5 bun mornings; dinners rotate by three; day 6 is the review day and eats by the normal rotation (no feast).
        record Case(long day, int tick, List<SectMenu.Dish> expect) { }
        List<Case> cases = List.of(
                new Case(2, 1300, List.of(SectMenu.Dish.MILLET_PORRIDGE)),
                new Case(3, 1300, List.of(SectMenu.Dish.STEAMED_BUN)),
                new Case(3, 9500, List.of(SectMenu.Dish.RICE_AND_PICKLES, SectMenu.Dish.MOUNTAIN_SOUP)),
                new Case(4, 9500, List.of(SectMenu.Dish.MOUNTAIN_SOUP, SectMenu.Dish.STEAMED_BUN)),
                new Case(5, 9500, List.of(SectMenu.Dish.RICE_AND_PICKLES, SectMenu.Dish.PLUM_TEA)),
                new Case(6, 9500, List.of(SectMenu.Dish.RICE_AND_PICKLES, SectMenu.Dish.MOUNTAIN_SOUP)));
        for (Case c : cases) {
            ServerPlayer p = member(h, 4, 12);
            h.getLevel().setDayTime(at(c.day(), c.tick()));
            h.assertTrue(SectMeals.mealId(h.getLevel().getDayTime()) >= 0, "day " + c.day() + " tick " + c.tick() + " is not a meal");
            h.assertTrue(SectMenu.menu(SectMeals.mealId(h.getLevel().getDayTime())).equals(c.expect()),
                    "the menu of day " + c.day() + " tick " + c.tick() + " is " + SectMenu.menu(SectMeals.mealId(h.getLevel().getDayTime())));
            h.assertTrue(SectMeals.serve(p) == SectMeals.Result.SERVED, "the cook did not serve day " + c.day() + " tick " + c.tick());
            List<Item> want = new ArrayList<>();
            for (SectMenu.Dish d : c.expect()) want.add(ModItems.dishItem(d));
            h.assertTrue(served(p).equals(want), "day " + c.day() + " tick " + c.tick() + " handed " + served(p) + " instead of " + want);
            h.assertTrue(SectMeals.serve(p) == SectMeals.Result.ALREADY && served(p).size() == want.size(), "a second portion in one meal");
        }
        // Not enough room: nothing is handed out and the claim is not spent.
        ServerPlayer full = member(h, 4, 14);
        h.getLevel().setDayTime(at(5, 9500));
        for (int i = 0; i < full.getInventory().items.size() - 1; i++) {
            full.getInventory().items.set(i, new ItemStack(net.minecraft.world.item.Items.STICK));
        }
        h.assertTrue(SectMeals.serve(full) == SectMeals.Result.FULL && served(full).isEmpty() && SectMeals.canServe(full),
                "a full inventory got half a meal or lost the claim");
        h.getLevel().setDayTime(saved);
        h.succeed();
    }

    // ------------------------------------------------------------------ the foods themselves

    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_kitchen_foodvalues")
    public static void dishesAreRegisteredWithSmallVanillaLikeValues(GameTestHelper h) {
        Set<SectMenu.Dish> seen = EnumSet.noneOf(SectMenu.Dish.class);
        for (SectMenu.Dish dish : SectMenu.Dish.values()) {
            Item item = ModItems.dishItem(dish);
            FoodProperties food = item.components().get(DataComponents.FOOD);
            h.assertTrue(food != null && food.nutrition() == dish.nutrition(), dish.id() + " has the wrong food value " + food);
            h.assertTrue(Math.abs(food.saturation() - dish.nutrition() * dish.saturation() * 2.0F) < 1.0e-4F, dish.id() + " saturation " + food.saturation());
            h.assertTrue(item instanceof MealBowlItem && item.getDefaultMaxStackSize() == 1, dish.id() + " is not a one-stack sect dish");
            h.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().equals(dish.id()), dish.id() + " registered under another id");
            // Small, vanilla-like: nothing above a cooked porkchop (8), the meat bowl is the richest, tea is a sip.
            h.assertTrue(food.nutrition() <= 8, dish.id() + " is richer than a cooked porkchop");
            h.assertTrue(food.effects().isEmpty(), dish.id() + " carries a potion effect; the only effect is the tea's focus");
            // The right click on food is refused at a full hunger bar unless it is always edible: the bowl must not depend on hunger.
            h.assertTrue(food.canAlwaysEat(), dish.id() + " cannot be eaten on a full stomach: the right click does nothing");
            // The cooled leftovers feed half as much.
            ItemStack cold = SectMeals.dishStack(dish, true, 100L);
            FoodProperties coldFood = cold.get(DataComponents.FOOD);
            h.assertTrue(coldFood.nutrition() == dish.coldNutrition() && coldFood.nutrition() <= food.nutrition() && coldFood.saturation() < food.saturation()
                    && coldFood.canAlwaysEat() == food.canAlwaysEat(), dish.id() + " cold values " + coldFood);
            ItemStack hot = SectMeals.dishStack(dish, false, 100L);
            h.assertTrue(SectMeals.expiry(cold) == 100L && SectMeals.expiry(hot) == 100L && hot.get(DataComponents.FOOD).equals(food),
                    dish.id() + " stack carries wrong tags");
            seen.add(dish);
        }
        h.assertTrue(seen.size() == 6, "not all six dishes are registered");
        for (Item old : List.of(ModItems.MEAL_BOWL.get(), ModItems.COLD_MEAL_BOWL.get())) {
            h.assertTrue(old.components().get(DataComponents.FOOD).canAlwaysEat(), "the older generic bowl cannot be eaten on a full stomach");
        }
        h.assertTrue(ModItems.dishItem(SectMenu.Dish.PLUM_TEA) instanceof PlumTeaItem, "the tea is not the tea item");
        // The meat bowl is never served (no feast, author 19.10); all menus are one or two plain things.
        for (long meal = 0; meal < 200; meal++) {
            List<SectMenu.Dish> menu = SectMenu.menu(meal);
            h.assertTrue(!menu.isEmpty() && menu.size() <= 2, "menu of meal " + meal + " is " + menu);
            h.assertTrue(!menu.contains(SectMenu.Dish.MEAT_BOWL), "meat served at meal " + meal);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ eating: anywhere, at any hunger (author 19.10)

    /**
     * The player's right click on a dish in his main hand and the end of the meal. The right click goes through the game mode, as a
     * packet from the client does ({@code Item.use}: vanilla refuses food at a full hunger bar unless it is always edible); the end
     * of the meal calls the item's own {@code finishUsingItem}, which is what the use timer calls after 32 ticks.
     */
    private static ItemStack eat(GameTestHelper h, ServerPlayer p, ItemStack dish) {
        p.setItemInHand(InteractionHand.MAIN_HAND, dish);
        p.gameMode.useItem(p, h.getLevel(), dish, InteractionHand.MAIN_HAND);
        h.assertTrue(p.isUsingItem(), "the right click on " + dish.getItem() + " did not start eating (hunger " + p.getFoodData().getFoodLevel() + ")");
        ItemStack rest = p.getUseItem().finishUsingItem(h.getLevel(), p);
        p.stopUsingItem();
        p.setItemInHand(InteractionHand.MAIN_HAND, rest);
        return rest;
    }

    /** The first dish of the player's inventory, taken out of it (the player holds it in his hand next). */
    private static ItemStack takeDish(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().items.size(); i++) {
            ItemStack s = p.getInventory().items.get(i);
            if (SectMeals.isServed(s)) {
                p.getInventory().items.set(i, ItemStack.EMPTY);
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Playtest 19.10: «I sit down, I eat, and I am told to eat with the disciples at a table», and «it did not work anyway». The
     * dish is eaten by a right click wherever the player stands (the test world has no sect layout, and nothing on the eating path
     * asks for one any more) and with a full stomach too (a bowl that vanilla refuses at 20 hunger points did nothing at all);
     * eaten in the meal it marks the meal in the journal.
     */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_kitchen_eatanywhere")
    public static void dishIsEatenByRightClickAnywhereAndAtAnyHunger(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        for (int hunger : new int[] {20, 6}) {
            ServerPlayer p = member(h, 4, 12);
            Vec3 elsewhere = p.position().add(150.0D, 0.0D, 150.0D);
            p.moveTo(elsewhere.x, elsewhere.y, elsewhere.z);
            h.getLevel().setDayTime(at(2, 1300));
            h.assertTrue(SectMeals.serve(p) == SectMeals.Result.SERVED, "setup: not served at hunger " + hunger);
            ItemStack dish = takeDish(p);
            p.getFoodData().setFoodLevel(hunger);
            ItemStack rest = eat(h, p, dish);
            h.assertTrue(rest.isEmpty(), "an empty bowl is left in the hand: " + rest);
            h.assertTrue(SectAttendance.current(p).today().did(SectAttendance.Activity.MEAL), "the dish eaten away from the tables (hunger " + hunger + ") left no MEAL mark");
            h.assertTrue(hunger == 20 ? p.getFoodData().getFoodLevel() == 20 : p.getFoodData().getFoodLevel() > hunger, "the dish fed wrongly at hunger " + hunger + ": " + p.getFoodData().getFoodLevel());
        }
        // Dinner, the other meal, every dish of it: the same.
        h.getLevel().setDayTime(at(3, 9500));
        ServerPlayer q = member(h, 4, 14);
        h.assertTrue(SectMeals.serve(q) == SectMeals.Result.SERVED, "setup: dinner not served");
        q.getFoodData().setFoodLevel(20);
        for (int n = 0; n < 2; n++) {
            ItemStack dish = takeDish(q);
            if (dish.isEmpty()) break;
            eat(h, q, dish);
        }
        h.assertTrue(served(q).isEmpty() && SectAttendance.current(q).today().did(SectAttendance.Activity.MEAL), "dinner eaten on a full stomach left a dish or no mark");
        h.getLevel().setDayTime(saved);
        h.succeed();
    }

    /** Outside a meal period nothing is marked and nothing is said; the cold portion of a latecomer counts in the period after the meal. */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_kitchen_eatmarkperiods")
    public static void theMealMarkNeedsAMealPeriodNotAPlace(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        // A bun from the creative tab at night: eaten, no mark.
        h.getLevel().setDayTime(at(2, 15000));
        ServerPlayer night = member(h, 4, 12);
        night.getFoodData().setFoodLevel(20);
        eat(h, night, new ItemStack(ModItems.STEAMED_BUN.get()));
        h.assertTrue(!SectAttendance.current(night).today().did(SectAttendance.Activity.MEAL), "a bun eaten at night marked the meal");
        // The period after breakfast: the cook leaves the cold leftovers; eaten anywhere they mark the meal.
        h.getLevel().setDayTime(at(2, 3000));
        ServerPlayer late = member(h, 4, 14);
        Vec3 elsewhere = late.position().add(150.0D, 0.0D, 150.0D);
        late.moveTo(elsewhere.x, elsewhere.y, elsewhere.z);
        h.assertTrue(SectMeals.serve(late) == SectMeals.Result.COLD, "setup: no cold portion");
        ItemStack cold = takeDish(late);
        h.assertTrue(cold.get(DataComponents.FOOD).canAlwaysEat(), "the cold portion cannot be eaten on a full stomach");
        eat(h, late, cold);
        h.assertTrue(SectAttendance.current(late).today().did(SectAttendance.Activity.MEAL), "the cold portion eaten after the meal left no MEAL mark");
        h.getLevel().setDayTime(saved);
        h.succeed();
    }

    // ------------------------------------------------------------------ the tea

    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_kitchen_teafocus")
    public static void teaGivesAShortFocusAndIsNotAMeal(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        h.getLevel().setDayTime(at(2, 1300));
        ServerPlayer p = member(h, 4, 12);
        MealBowlItem tea = (MealBowlItem) ModItems.PLUM_TEA.get();
        h.assertTrue(!TeaFocus.active(p) && TeaFocus.factor(p) == 1.0D, "focus before any tea");
        ItemStack cup = new ItemStack(tea);
        p.getFoodData().setFoodLevel(20);
        h.assertTrue(cup.get(DataComponents.FOOD).canAlwaysEat(), "a cup of tea cannot be drunk on a full stomach");
        cup.finishUsingItem(h.getLevel(), p);
        h.assertTrue(TeaFocus.active(p) && TeaFocus.factor(p) == TeaFocus.FACTOR && TeaFocus.FACTOR > 1.0D && TeaFocus.FACTOR <= 1.15D,
                "the tea gave no mild focus: " + TeaFocus.factor(p));
        long end = p.getData(ModAttachments.TEA_FOCUS_END);
        h.assertTrue(end - h.getLevel().getGameTime() == TeaFocus.TICKS, "focus length " + (end - h.getLevel().getGameTime()));
        // A cup is not a meal even in the meal period: the journal has no mark.
        h.assertTrue(!SectAttendance.current(p).today().did(SectAttendance.Activity.MEAL), "a cup of tea marked the meal");
        // Short: it is gone when the time is up; porridge gives no focus at all.
        p.setData(ModAttachments.TEA_FOCUS_END, h.getLevel().getGameTime() - 1);
        h.assertTrue(!TeaFocus.active(p), "the focus does not end");
        ServerPlayer q = member(h, 4, 14);
        new ItemStack(ModItems.MILLET_PORRIDGE.get()).finishUsingItem(h.getLevel(), q);
        h.assertTrue(!TeaFocus.active(q), "porridge gave the tea's focus");
        h.getLevel().setDayTime(saved);
        h.succeed();
    }

    // ------------------------------------------------------------------ the cook at the stove

    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_kitchen_cookposes")
    public static void cookStirsTastesAndChopsAtTheStove(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new Mini(h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D)), hall(h));
        SectDisciple cook = npc(h, layout, "cook_kim", 15, 7);
        Set<SectPose> seen = EnumSet.noneOf(SectPose.class);
        h.onEachTick(() -> seen.add(cook.pose()));
        h.succeedWhen(() -> {
            h.assertTrue(seen.contains(SectPose.COOK_STIR) && seen.contains(SectPose.COOK_TASTE) && seen.contains(SectPose.COOK_CHOP),
                    "the cook did not stir, taste and chop: " + seen + " at " + cook.position() + " doing " + cook.doingKind());
            h.assertTrue(!seen.contains(SectPose.TALK), "the cook still gestures like a talker: " + seen);
            h.getLevel().setDayTime(saved);
        });
    }

    // ------------------------------------------------------------------ the servers at a meal

    @GameTest(template = YARD, timeoutTicks = 1200, batch = "sect_kitchen_trayround")
    public static void serverCarriesATrayToATableSetsItDownAndWipes(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        h.getLevel().setDayTime(at(2, 1100));
        var layout = new Mini(h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D)), hall(h));
        SectDisciple cook = npc(h, layout, "cook_kim", 6, 7);
        SectDisciple server = npc(h, layout, "water_gu", 7, 7);
        Set<SectPose> cookSeen = EnumSet.noneOf(SectPose.class);
        Set<SectPose> serverSeen = EnumSet.noneOf(SectPose.class);
        double[] farthest = {0.0D};
        Vec3 start = server.position();
        h.onEachTick(() -> {
            cookSeen.add(cook.pose());
            serverSeen.add(server.pose());
            farthest[0] = Math.max(farthest[0], server.position().distanceTo(start));
        });
        h.succeedWhen(() -> {
            h.assertTrue(cookSeen.contains(SectPose.COOK_LADLE) && cookSeen.contains(SectPose.COOK_STIR),
                    "the cook did not ladle at the counter: " + cookSeen);
            h.assertTrue(serverSeen.contains(SectPose.TRAY) && serverSeen.contains(SectPose.TRAY_SET) && serverSeen.contains(SectPose.WIPE),
                    "the server did not carry, set down and wipe: " + serverSeen + " at " + server.position() + " doing " + server.doingKind());
            h.assertTrue(farthest[0] > 1.0D, "the server never left the counter");
            h.getLevel().setDayTime(saved);
        });
    }

    /** A member handed a bowl sees the cook ladle for him. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_kitchen_ladlefor")
    public static void cookLadlesForTheMemberHeServes(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        h.getLevel().setDayTime(at(2, 1300));
        var layout = new Mini(h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D)), null);
        SectDisciple cook = npc(h, layout, "cook_kim", 6, 12);
        cook.setNoAi(true);
        ServerPlayer p = member(h, 4, 12);
        h.assertTrue(SectMeals.serve(p) == SectMeals.Result.SERVED, "not served");
        h.assertTrue(cook.pose() == SectPose.COOK_LADLE && cook.poseHeld(), "the cook did not ladle for the member: " + cook.pose());
        h.getLevel().setDayTime(saved);
        h.succeed();
    }
}
