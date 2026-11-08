package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Предметы мода. */
public final class ModItems {

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, MurimMod.MODID);

    /** Display-only advancement emblems: no creative entries, recipes or loot. */
    public static final DeferredHolder<Item, Item> ACH_ROOT = achievementIcon("root");
    public static final DeferredHolder<Item, Item> ACH_RANK_THIRD = achievementIcon("rank_third");
    public static final DeferredHolder<Item, Item> ACH_RANK_SECOND = achievementIcon("rank_second");
    public static final DeferredHolder<Item, Item> ACH_RANK_FIRST = achievementIcon("rank_first");
    public static final DeferredHolder<Item, Item> ACH_RANK_PEAK = achievementIcon("rank_peak");
    public static final DeferredHolder<Item, Item> ACH_BODY_FIRST = achievementIcon("body_first");
    public static final DeferredHolder<Item, Item> ACH_BODY_READY = achievementIcon("body_ready");
    public static final DeferredHolder<Item, Item> ACH_STARS_1 = achievementIcon("stars_1");
    public static final DeferredHolder<Item, Item> ACH_STARS_6 = achievementIcon("stars_6");
    public static final DeferredHolder<Item, Item> ACH_STARS_12 = achievementIcon("stars_12");
    public static final DeferredHolder<Item, Item> ACH_SPAR_LOST_SWEEPER = achievementIcon("spar_lost_sweeper");
    public static final DeferredHolder<Item, Item> ACH_CAMP_CLEARED = achievementIcon("camp_cleared");
    public static final DeferredHolder<Item, Item> ACH_TORN_MERIDIANS = achievementIcon("torn_meridians");

    private static DeferredHolder<Item, Item> achievementIcon(String name) {
        return ITEMS.register("ach_" + name, () -> new Item(new Item.Properties().stacksTo(1)));
    }

    /**
     * Свиток метода культивации. Один предмет на все методы: какой метод записан —
     * компонент {@link ModDataComponents#METHOD}.
     */
    /** Манускрипт техники: какая техника — компонент {@link ModDataComponents#TECHNIQUE}. */
    public static final DeferredHolder<Item, Item> TECHNIQUE_MANUAL = ITEMS.register("technique_manual",
            () -> new io.github.verycooltimo.murim.item.TechniqueManualItem(new Item.Properties().stacksTo(1)));

    /** Clone junk covers; the default component lets /give and creative copies teach the right dance. */
    public static final DeferredHolder<Item, Item> JUNK_HEAVENLY_DRAGON_MANUAL = funnyManual("heavenly_dragon_strike");
    public static final DeferredHolder<Item, Item> JUNK_THOUSAND_CRANES_MANUAL = funnyManual("thousand_cranes_dance");
    public static final DeferredHolder<Item, Item> JUNK_BEGGARS_TAP_MANUAL = funnyManual("beggars_crushing_tap");

    private static DeferredHolder<Item, Item> funnyManual(String technique) {
        return ITEMS.register("junk_" + technique + "_manual", () ->
                new io.github.verycooltimo.murim.item.TechniqueManualItem(new Item.Properties().stacksTo(1)
                        .component(ModDataComponents.TECHNIQUE.get(),
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, technique))));
    }

    /**
     * Обрывок манускрипта — добыча лагеря бандитов (docs/design/24-bandit-camp.md §3). Какая книга —
     * компонент {@link ModDataComponents#TECHNIQUE}; стопка до 16 (страницы одной книги).
     */
    public static final DeferredHolder<Item, Item> MANUAL_PAGE = ITEMS.register("manual_page",
            () -> new io.github.verycooltimo.murim.item.ManualPageItem(new Item.Properties().stacksTo(16)));

    /**
     * Серебряный лян (은자, «серебро» Срединной равнины в Return of the Mount Hua Sect): деньги
     * мира мурим. Пока только добыча — торговли в моде ещё нет (docs/design/24-bandit-camp.md §5).
     */
    public static final DeferredHolder<Item, Item> SILVER_TAEL = ITEMS.register("silver_tael",
            () -> new Item(new Item.Properties().stacksTo(64)));

    /**
     * The cook's bowl of millet with nuts (doc 39 item 6): one per meal from the serving counter, eaten anywhere. Always
     * edible: the meal counts for the day's journal, so a full stomach must not make the right click do nothing.
     */
    public static final DeferredHolder<Item, Item> MEAL_BOWL = ITEMS.register("meal_bowl",
            () -> new io.github.verycooltimo.murim.item.MealBowlItem(new Item.Properties().stacksTo(1)
                    .food(new net.minecraft.world.food.FoodProperties.Builder().nutrition(6).saturationModifier(0.5F).alwaysEdible().build())));

    // ------------------------------------------------------------------ the Mount Hua kitchen (author 18.10)
    // Plain sect food after Return of the Mount Hua Sect (SectMenu): the cook hands these at the meals instead of the generic
    // bowl. Values are small and vanilla-like; only the tea has an effect (TeaFocus). Textures: tools/art/items/make_food.py.
    // Every dish is always edible (author 19.10: «it did not work anyway»): vanilla food refuses the right click at a full hunger
    // bar, and a member who just walked in has one; the dish then still counts for the meal mark, it only feeds nothing.

    public static final DeferredHolder<Item, Item> MILLET_PORRIDGE = dish(io.github.verycooltimo.murim.sect.SectMenu.Dish.MILLET_PORRIDGE);
    public static final DeferredHolder<Item, Item> STEAMED_BUN = dish(io.github.verycooltimo.murim.sect.SectMenu.Dish.STEAMED_BUN);
    public static final DeferredHolder<Item, Item> RICE_AND_PICKLES = dish(io.github.verycooltimo.murim.sect.SectMenu.Dish.RICE_AND_PICKLES);
    public static final DeferredHolder<Item, Item> MOUNTAIN_SOUP = dish(io.github.verycooltimo.murim.sect.SectMenu.Dish.MOUNTAIN_SOUP);
    public static final DeferredHolder<Item, Item> PLUM_TEA = dish(io.github.verycooltimo.murim.sect.SectMenu.Dish.PLUM_TEA);
    public static final DeferredHolder<Item, Item> MEAT_BOWL = dish(io.github.verycooltimo.murim.sect.SectMenu.Dish.MEAT_BOWL);

    private static DeferredHolder<Item, Item> dish(io.github.verycooltimo.murim.sect.SectMenu.Dish dish) {
        return ITEMS.register(dish.id(), () -> {
            boolean tea = dish == io.github.verycooltimo.murim.sect.SectMenu.Dish.PLUM_TEA;
            net.minecraft.world.food.FoodProperties.Builder food = new net.minecraft.world.food.FoodProperties.Builder()
                    .nutrition(dish.nutrition()).saturationModifier(dish.saturation()).alwaysEdible();
            Item.Properties props = new Item.Properties().stacksTo(1).food(food.build());
            return tea ? new io.github.verycooltimo.murim.item.PlumTeaItem(props)
                    : new io.github.verycooltimo.murim.item.MealBowlItem(props, dish.drink());
        });
    }

    /** The item of a dish. */
    public static Item dishItem(io.github.verycooltimo.murim.sect.SectMenu.Dish dish) {
        return switch (dish) {
            case MILLET_PORRIDGE -> MILLET_PORRIDGE.get();
            case STEAMED_BUN -> STEAMED_BUN.get();
            case RICE_AND_PICKLES -> RICE_AND_PICKLES.get();
            case MOUNTAIN_SOUP -> MOUNTAIN_SOUP.get();
            case PLUM_TEA -> PLUM_TEA.get();
            case MEAT_BOWL -> MEAT_BOWL.get();
        };
    }

    /** The jade sect tablet (doc 39): journal with Today, Goal, Contracts, Standing and Map. One per member. */
    public static final DeferredHolder<Item, Item> JADE_SECT_TABLET = ITEMS.register("jade_sect_tablet",
            () -> new io.github.verycooltimo.murim.item.SectTabletItem(new Item.Properties().stacksTo(1)));

    /** The same bowl left to cool for a latecomer: less food, kept until the end of the next period. */
    public static final DeferredHolder<Item, Item> COLD_MEAL_BOWL = ITEMS.register("cold_meal_bowl",
            () -> new io.github.verycooltimo.murim.item.MealBowlItem(new Item.Properties().stacksTo(1)
                    .food(new net.minecraft.world.food.FoodProperties.Builder().nutrition(3).saturationModifier(0.3F).alwaysEdible().build())));

    /** Proof of a cleared bandit camp for a sect contract (tag: camp key and sect day). */
    public static final DeferredHolder<Item, Item> CONTRACT_TOKEN = ITEMS.register("contract_token",
            () -> new Item(new Item.Properties().stacksTo(16)));

    public static final DeferredHolder<Item, Item> METHOD_SCROLL = ITEMS.register("method_scroll",
            () -> new io.github.verycooltimo.murim.item.MethodScrollItem(new Item.Properties().stacksTo(1)));

    /** Яйца призыва бандитов — во вкладке «Мурим». Цвета: халат и повязка. */
    public static final DeferredHolder<Item, Item> BANDIT_SWORDSMAN_SPAWN_EGG = ITEMS.register("bandit_swordsman_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(ModEntities.BANDIT_SWORDSMAN, 0x58483A, 0x962620, new Item.Properties()));

    public static final DeferredHolder<Item, Item> SECT_DISCIPLE_SPAWN_EGG = ITEMS.register("sect_disciple_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(ModEntities.SECT_DISCIPLE, 0xE8E6DE, 0x2F4F8F, new Item.Properties()));

    public static final DeferredHolder<Item, Item> BANDIT_ARCHER_SPAWN_EGG = ITEMS.register("bandit_archer_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(ModEntities.BANDIT_ARCHER, 0x3E4A34, 0xB89A5A, new Item.Properties()));

    /**
     * Метательный кинжал клана Тан («лист ивы», гл. 194), модель автора art/items/tang_dagger. В стопке — 12:
     * Тан носят ровно двенадцать кинжалов (tang-clan.md §9.3). Рисуется BedrockItemRenderer.
     * ПКМ — бросок рукой (TangDaggerItem, автор 04.10); ЛКМ — короткий быстрый удар: урон 3, скорость 2,6
     * (железный тир, как короткий клинок). API: SwordItem#createAttributes — только атрибуты, без прочности.
     */
    public static final DeferredHolder<Item, Item> TANG_DAGGER = ITEMS.register("tang_dagger",
            () -> new io.github.verycooltimo.murim.item.TangDaggerItem(new Item.Properties().stacksTo(12)
                    .attributes(net.minecraft.world.item.SwordItem.createAttributes(net.minecraft.world.item.Tiers.IRON, 0, -1.4F))));

    /**
     * Меч Хуашань (модель автора art/items/huashan_sword, 03.10): настоящий меч уровня алмазного — урон 7,
     * скорость меча, прочность 1200, зачаровывается как меч (тег minecraft:swords → murim:swords и enchantable/*).
     */
    public static final DeferredHolder<Item, Item> HUASHAN_SWORD = ITEMS.register("huashan_sword",
            () -> new net.minecraft.world.item.SwordItem(HuashanTier.INSTANCE, new Item.Properties()
                    .attributes(net.minecraft.world.item.SwordItem.createAttributes(HuashanTier.INSTANCE, 3, -2.4F))));

    /** Hua-style swept guard sword; editable source generated by tools/art/plum_guard_sword.py. */
    public static final DeferredHolder<Item, Item> PLUM_GUARD_SWORD = ITEMS.register("plum_guard_sword",
            () -> new net.minecraft.world.item.SwordItem(HuashanTier.INSTANCE, new Item.Properties()
                    .attributes(net.minecraft.world.item.SwordItem.createAttributes(HuashanTier.INSTANCE, 3, -2.4F))));

    /** Dark sword from the Absolute Regression references; descriptive name, editable Bedrock source. */
    public static final DeferredHolder<Item, Item> BLACK_DRAGON_SWORD = ITEMS.register("black_dragon_sword",
            () -> new net.minecraft.world.item.SwordItem(HuashanTier.INSTANCE, new Item.Properties()
                    .attributes(net.minecraft.world.item.SwordItem.createAttributes(HuashanTier.INSTANCE, 3, -2.4F))));

    /**
     * Деревянный учебный меч (автор 04.10): та же модель, что у Меча Хуашань, своя текстура
     * (art/items/wooden_sword/wood.png). Слабый, как ванильный деревянный: урон 4, скорость меча, прочность 59;
     * в теге мечей — техники меча с ним работают. Наставник даёт его новому ученику при вступлении.
     */
    public static final DeferredHolder<Item, Item> WOODEN_SWORD = ITEMS.register("wooden_sword",
            () -> new net.minecraft.world.item.SwordItem(net.minecraft.world.item.Tiers.WOOD, new Item.Properties()
                    .attributes(net.minecraft.world.item.SwordItem.createAttributes(net.minecraft.world.item.Tiers.WOOD, 3, -2.4F))));

    /** Уровень Меча Хуашань: как алмаз, но прочность 1200. API: reference/minecraft-src/net/minecraft/world/item/Tier.java. */
    private enum HuashanTier implements net.minecraft.world.item.Tier {
        INSTANCE;

        @Override
        public int getUses() {
            return 1200;
        }

        @Override
        public float getSpeed() {
            return 8.0F;
        }

        @Override
        public float getAttackDamageBonus() {
            return 3.0F;
        }

        @Override
        public net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> getIncorrectBlocksForDrops() {
            return net.minecraft.tags.BlockTags.INCORRECT_FOR_DIAMOND_TOOL;
        }

        @Override
        public int getEnchantmentValue() {
            return 12;
        }

        @Override
        public net.minecraft.world.item.crafting.Ingredient getRepairIngredient() {
            return net.minecraft.world.item.crafting.Ingredient.of(net.minecraft.world.item.Items.IRON_INGOT);
        }
    }

    /** Пилюли (docs/design/19b §1). */
    public static final DeferredHolder<Item, Item> PILL_SNOW_PLUM = pill(io.github.verycooltimo.murim.cultivation.PillKind.SNOW_PLUM, 16);
    public static final DeferredHolder<Item, Item> PILL_ORIGIN_ENERGY = pill(io.github.verycooltimo.murim.cultivation.PillKind.ORIGIN_ENERGY, 4);
    public static final DeferredHolder<Item, Item> PILL_THOUSAND_POISON = pill(io.github.verycooltimo.murim.cultivation.PillKind.THOUSAND_POISON, 4);
    public static final DeferredHolder<Item, Item> PILL_BLUE = pill(io.github.verycooltimo.murim.cultivation.PillKind.BLUE, 2);
    public static final DeferredHolder<Item, Item> PILL_BLOOD = pill(io.github.verycooltimo.murim.cultivation.PillKind.BLOOD, 2);
    public static final DeferredHolder<Item, Item> BEAUTY_TEAR = pill(io.github.verycooltimo.murim.cultivation.PillKind.BEAUTY_TEAR, 1);

    /** Everyday pills (24.10): common ones stack to 16, middle ones to 8. */
    public static final DeferredHolder<Item, Item> PILL_VITALITY = pill(io.github.verycooltimo.murim.cultivation.PillKind.VITALITY, 16);
    public static final DeferredHolder<Item, Item> PILL_PLUM_BLOSSOM = pill(io.github.verycooltimo.murim.cultivation.PillKind.PLUM_BLOSSOM, 16);
    public static final DeferredHolder<Item, Item> PILL_MENDING = pill(io.github.verycooltimo.murim.cultivation.PillKind.MENDING, 16);
    public static final DeferredHolder<Item, Item> PILL_FASTING = pill(io.github.verycooltimo.murim.cultivation.PillKind.FASTING, 16);
    public static final DeferredHolder<Item, Item> PILL_ANTIDOTE = pill(io.github.verycooltimo.murim.cultivation.PillKind.ANTIDOTE, 16);
    public static final DeferredHolder<Item, Item> PILL_BLOOD_REPLENISHING = pill(io.github.verycooltimo.murim.cultivation.PillKind.BLOOD_REPLENISHING, 8);
    public static final DeferredHolder<Item, Item> PILL_QUICK_RECOVERY = pill(io.github.verycooltimo.murim.cultivation.PillKind.QUICK_RECOVERY, 8);

    // API: NeoForge 21.1 DeferredRegister#register; Minecraft Item#Item(Properties).
    public static final DeferredHolder<Item, Item> HUNDRED_GINSENG = pill(io.github.verycooltimo.murim.cultivation.PillKind.HUNDRED_GINSENG, 16);
    public static final DeferredHolder<Item, Item> PURPLE_GINSENG = pill(io.github.verycooltimo.murim.cultivation.PillKind.PURPLE_GINSENG, 16);
    public static final DeferredHolder<Item, Item> PURPLE_WOOD_HERB = ITEMS.register("purple_wood_herb", () -> new Item(new Item.Properties()));
    public static final DeferredHolder<Item, Item> ICE_CRYSTAL = ITEMS.register("ice_crystal", () -> new Item(new Item.Properties()));

    /** Thousand-year herbs (22.10): pill pipeline, cooldown. */
    public static final DeferredHolder<Item, Item> THOUSAND_GINSENG = pill(io.github.verycooltimo.murim.cultivation.PillKind.THOUSAND_GINSENG, 4);
    public static final DeferredHolder<Item, Item> THOUSAND_LINGZHI = pill(io.github.verycooltimo.murim.cultivation.PillKind.THOUSAND_LINGZHI, 4);

    public static final DeferredHolder<Item, Item> BLACK_LINGZHI = pill(io.github.verycooltimo.murim.cultivation.PillKind.BLACK_LINGZHI, 1);
    public static final DeferredHolder<Item, Item> PILL_PURPLE_HEAVEN = pill(io.github.verycooltimo.murim.cultivation.PillKind.PURPLE_HEAVEN, 4);

    public static final DeferredHolder<Item, Item> PILL_SMALL_RESTORATION = pill(io.github.verycooltimo.murim.cultivation.PillKind.SMALL_RESTORATION, 4);
    public static final DeferredHolder<Item, Item> PILL_HEAVENLY_EARTH = pill(io.github.verycooltimo.murim.cultivation.PillKind.HEAVENLY_EARTH, 4);
    public static final DeferredHolder<Item, Item> PILL_MOONLIGHT = pill(io.github.verycooltimo.murim.cultivation.PillKind.MOONLIGHT, 4);
    public static final DeferredHolder<Item, Item> PILL_ASCENDING_DRAGON = pill(io.github.verycooltimo.murim.cultivation.PillKind.ASCENDING_DRAGON, 4);
    public static final DeferredHolder<Item, Item> PILL_GREAT_RESTORATION = pill(io.github.verycooltimo.murim.cultivation.PillKind.GREAT_RESTORATION, 1);

    private static DeferredHolder<Item, Item> pill(io.github.verycooltimo.murim.cultivation.PillKind kind, int stack) {
        return ITEMS.register(kind.itemId(), () -> new io.github.verycooltimo.murim.item.PillItem(kind,
                new Item.Properties().stacksTo(stack).rarity(kind.herb() || kind.tier() == io.github.verycooltimo.murim.cultivation.PillTier.HIGH
                        ? net.minecraft.world.item.Rarity.RARE : kind.tier() == io.github.verycooltimo.murim.cultivation.PillTier.MIDDLE
                        ? net.minecraft.world.item.Rarity.UNCOMMON : net.minecraft.world.item.Rarity.COMMON)));
    }

    /** The item of a herb (cooldown helper). */
    public static Item itemOf(io.github.verycooltimo.murim.cultivation.PillKind kind) {
        return switch (kind) {
            case HUNDRED_GINSENG -> HUNDRED_GINSENG.get();
            case PURPLE_GINSENG -> PURPLE_GINSENG.get();
            case THOUSAND_GINSENG -> THOUSAND_GINSENG.get();
            case BLACK_LINGZHI -> BLACK_LINGZHI.get();
            case THOUSAND_LINGZHI -> THOUSAND_LINGZHI.get();
            default -> throw new IllegalArgumentException("Not a herb: " + kind);
        };
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
