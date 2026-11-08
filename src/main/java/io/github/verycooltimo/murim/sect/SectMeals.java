package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Meals for real (doc 39 item 6). In the meal periods the cook stands at the serving counter of the dining hall;
 * a right click on the cook (his dialogue) or on the counter hands the member the dishes of that meal ({@link SectMenu}: the
 * porridge or a bun at breakfast, rice and pickles, soup, buns and tea at dinner), once per meal.
 * A dish is eaten like any other food, anywhere (author 19.10: no «only at a table»; the tablet shows the dining hall as the
 * place to be, nothing forces it). Eating it feeds the player and, if it is eaten during a meal period (a hot portion in the
 * meal, a cold one in the period after it), puts the MEAL mark in the journal wherever the player stands. A player who only
 * sits with the others at the tables still gets the journal mark after {@link SectAttendance#MEAL_TICKS} (fallback), but no food.
 *
 * <p>Fixed journal key {@link #SERVED} holds the id of the last meal served, so the save does not grow.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectMeals {
    /** Journal key: id of the last meal this player took a bowl for. */
    public static final String SERVED = "meal.served";
    /** How far from the counter cell a clicked block still counts as the counter. */
    static final double COUNTER_REACH = 1.6D;

    public enum Result { SERVED, COLD, NOT_MEMBER, NOT_MEAL, ALREADY, FULL }

    private SectMeals() { }

    /** Id of the meal that is on now: two per sect day (breakfast, dinner); -1 outside the meals. */
    public static long mealId(long dayTime) {
        SectSchedule.Period period = SectSchedule.at(dayTime);
        if (!period.meal()) return -1L;
        return SectSchedule.day(dayTime) * 2L + (period == SectSchedule.Period.BREAKFAST ? 0L : 1L);
    }

    /**
     * The meal that has just ended while its leftovers still stand on the counter: the period after a meal belongs to
     * the latecomers (cold portion), until the end of that period. -1 otherwise.
     */
    public static long lateMealId(long dayTime) {
        SectSchedule.Period period = SectSchedule.at(dayTime);
        long day = SectSchedule.day(dayTime);
        return switch (period) {
            case TRAINING -> day * 2L;
            case EVENING -> day * 2L + 1L;
            default -> -1L;
        };
    }

    /** Has the player already taken a bowl for the meal that is on (or, after it, for the meal just ended)? */
    public static boolean served(ServerPlayer p) {
        long time = p.level().getDayTime();
        long id = mealId(time) >= 0 ? mealId(time) : lateMealId(time);
        return id >= 0 && SectService.state(p).entry(SERVED, -1L) == id;
    }

    /** The cook (or the counter) can hand a bowl to this player now: a hot one in the meal, a cold one right after. */
    public static boolean canServe(ServerPlayer p) {
        long time = p.level().getDayTime();
        return SectService.state(p).member() && (mealId(time) >= 0 || lateMealId(time) >= 0) && !served(p);
    }

    /** Ticks until the current period of the day ends. */
    static long untilPeriodEnd(long dayTime) {
        long tod = Math.floorMod(dayTime, 24000L);
        long best = 24000L;
        for (SectSchedule.Period period : SectSchedule.Period.values()) {
            long d = Math.floorMod(period.start() - tod, 24000L);
            if (d > 0 && d < best) best = d;
        }
        return best;
    }

    /** Game time after which this bowl is gone; 0 for a stack that carries none. */
    public static long expiry(ItemStack stack) {
        return stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getLong("expires");
    }

    /** Hands one bowl per meal; the claim is saved only after the item reached the inventory. */
    public static Result serve(ServerPlayer p) {
        if (!SectService.state(p).member()) return Result.NOT_MEMBER;
        long time = p.level().getDayTime();
        long hot = mealId(time);
        long late = lateMealId(time);
        long id = hot >= 0 ? hot : late;
        if (id < 0) {
            PlayerJournal.refuse(p, Component.translatable("murim.sect.meal.closed").withStyle(ChatFormatting.GRAY));
            return Result.NOT_MEAL;
        }
        if (served(p)) {
            PlayerJournal.refuse(p, Component.translatable("murim.sect.meal.already").withStyle(ChatFormatting.GRAY));
            return Result.ALREADY;
        }
        boolean cold = hot < 0;
        java.util.List<SectMenu.Dish> dishes = SectMenu.menu(id);
        if (freeSlots(p) < dishes.size()) {
            PlayerJournal.refuse(p, Component.translatable("murim.sect.meal.full"));
            return Result.FULL;
        }
        long expires = p.level().getGameTime() + untilPeriodEnd(time);
        for (SectMenu.Dish dish : dishes) {
            p.getInventory().add(dishStack(dish, cold, expires));
        }
        p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT, SectService.state(p).record(SERVED, id));
        p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 0.9F);
        PlayerJournal.record(p, Component.translatable(cold ? "murim.sect.meal.served_cold" : "murim.sect.meal.served", names(dishes))
                .withStyle(ChatFormatting.GRAY));
        ladle(p);
        return cold ? Result.COLD : Result.SERVED;
    }

    /** Empty slots of the main inventory: a stack of one dish never merges with another (they stack to 1). */
    static int freeSlots(ServerPlayer p) {
        int free = 0;
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty()) free++;
        }
        return free;
    }

    /** «Millet porridge, Plum tea» for the message. */
    static Component names(java.util.List<SectMenu.Dish> dishes) {
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        for (int i = 0; i < dishes.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(Component.translatable(ModItems.dishItem(dishes.get(i)).getDescriptionId()));
        }
        return out;
    }

    /**
     * The stack the cook hands out for a dish: hot, or the cooled leftovers of a latecomer (half the food, flagged, shown in the
     * tooltip). It carries the game time at which a hot portion cools; food stays in the inventory.
     */
    public static ItemStack dishStack(SectMenu.Dish dish, boolean cold, long expires) {
        ItemStack stack = new ItemStack(ModItems.dishItem(dish));
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putLong("expires", expires);
        if (cold) {
            tag.putBoolean(io.github.verycooltimo.murim.item.MealBowlItem.COLD, true);
            net.minecraft.world.food.FoodProperties.Builder food = new net.minecraft.world.food.FoodProperties.Builder()
                    .nutrition(dish.coldNutrition()).saturationModifier(dish.coldSaturation());
            if (stack.get(net.minecraft.core.component.DataComponents.FOOD).canAlwaysEat()) food.alwaysEdible();
            stack.set(net.minecraft.core.component.DataComponents.FOOD, food.build());
        }
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        return stack;
    }

    /** Is this stack food of the sect kitchen (a dish, or one of the older generic bowls)? */
    public static boolean isServed(ItemStack s) {
        return s.getItem() instanceof io.github.verycooltimo.murim.item.MealBowlItem;
    }

    /** The cook, if he is near, ladles for the member: the pose plays for the one who is served. */
    private static void ladle(ServerPlayer p) {
        for (io.github.verycooltimo.murim.entity.SectDisciple cook : p.level().getEntitiesOfClass(io.github.verycooltimo.murim.entity.SectDisciple.class,
                p.getBoundingBox().inflate(7.0D), d -> d.role() == SectRole.COOK && d.isAlive() && !d.dormant())) {
            cook.ladleFor(p);
            return;
        }
    }

    /** UX #21: uneaten hot dishes cool at the bell; carried food is never silently collected. */
    public static int collectStale(ServerPlayer p) {
        long now = p.level().getGameTime();
        int cooled = 0;
        for (var slots : java.util.List.of(p.getInventory().items, p.getInventory().offhand)) {
            for (int i = 0; i < slots.size(); i++) {
                ItemStack stack = slots.get(i);
                if (!isServed(stack) || expiry(stack) <= 0L || expiry(stack) >= now) continue;
                // Keep the original stack and its custom data; only food values and the cold flag change.
                var tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                if (!tag.getBoolean(io.github.verycooltimo.murim.item.MealBowlItem.COLD)) {
                    if (stack.is(ModItems.MEAL_BOWL.get())) {
                        stack.set(net.minecraft.core.component.DataComponents.FOOD,
                                new ItemStack(ModItems.COLD_MEAL_BOWL.get()).get(net.minecraft.core.component.DataComponents.FOOD));
                    }
                    for (SectMenu.Dish dish : SectMenu.Dish.values()) {
                        if (stack.is(ModItems.dishItem(dish))) {
                            stack.set(net.minecraft.core.component.DataComponents.FOOD,
                                    dishStack(dish, true, 0L).get(net.minecraft.core.component.DataComponents.FOOD));
                            break;
                        }
                    }
                    cooled++;
                }
                tag.putBoolean(io.github.verycooltimo.murim.item.MealBowlItem.COLD, true);
                tag.putLong("expires", 0L);
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(tag));
            }
        }
        return cooled;
    }

    @SubscribeEvent
    static void tick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % 100 == 53) collectStale(p);
    }

    /**
     * The bowl was eaten (server side): during a meal period the journal gets the MEAL mark, wherever the player stands (a cold
     * portion counts in the period after the meal). Outside a meal period (food from the creative tab, a command) nothing
     * happens: no mark, and no scolding either.
     */
    public static void eaten(ServerPlayer p) {
        if (!SectService.state(p).member()) return;
        long time = p.level().getDayTime();
        if (mealId(time) < 0 && lateMealId(time) < 0) return;
        if (SectAttendance.record(p, SectAttendance.Activity.MEAL)) {
            SectNotice.bar(p, Component.translatable("murim.sect.meal.fed").withStyle(ChatFormatting.GRAY));
        }
    }

    /** The clicked block is the serving counter of the dining hall (or its stand) in this layout file. */
    public static boolean isCounter(SectLayoutFile file, BlockPos clicked) {
        if (file == null) return false;
        Vec3 c = Vec3.atCenterOf(clicked);
        for (SectLayoutFile.Spot s : file.spots()) {
            if (!"counter".equals(s.kind()) || !"dining_hall".equals(s.building())) continue;
            if (Vec3.atCenterOf(s.pos()).distanceTo(c) <= COUNTER_REACH
                    || (s.target() != null && Vec3.atCenterOf(s.target()).distanceTo(c) <= COUNTER_REACH)) return true;
        }
        return false;
    }

    @SubscribeEvent
    static void click(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer p)) return;
        SectLayout layout = SectLife.layout(p.serverLevel());
        if (layout == null || !SectService.state(p).member() || !isCounter(layout.file(), event.getPos())) return;
        serve(p);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
