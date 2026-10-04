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

    /**
     * Свиток метода культивации. Один предмет на все методы: какой метод записан —
     * компонент {@link ModDataComponents#METHOD}.
     */
    /** Манускрипт техники: какая техника — компонент {@link ModDataComponents#TECHNIQUE}. */
    public static final DeferredHolder<Item, Item> TECHNIQUE_MANUAL = ITEMS.register("technique_manual",
            () -> new io.github.verycooltimo.murim.item.TechniqueManualItem(new Item.Properties().stacksTo(1)));

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
    public static final DeferredHolder<Item, Item> BEAUTY_TEAR = pill(io.github.verycooltimo.murim.cultivation.PillKind.BEAUTY_TEAR, 1);

    private static DeferredHolder<Item, Item> pill(io.github.verycooltimo.murim.cultivation.PillKind kind, int stack) {
        return ITEMS.register(kind.itemId(), () -> new io.github.verycooltimo.murim.item.PillItem(kind,
                new Item.Properties().stacksTo(stack).rarity(kind.rare()
                        ? net.minecraft.world.item.Rarity.RARE : net.minecraft.world.item.Rarity.UNCOMMON)));
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
