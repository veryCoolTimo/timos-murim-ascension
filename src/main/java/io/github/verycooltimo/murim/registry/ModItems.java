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

    public static final DeferredHolder<Item, Item> METHOD_SCROLL = ITEMS.register("method_scroll",
            () -> new io.github.verycooltimo.murim.item.MethodScrollItem(new Item.Properties().stacksTo(1)));

    /** Яйца призыва бандитов — во вкладке «Мурим». Цвета: халат и повязка. */
    public static final DeferredHolder<Item, Item> BANDIT_SWORDSMAN_SPAWN_EGG = ITEMS.register("bandit_swordsman_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(ModEntities.BANDIT_SWORDSMAN, 0x58483A, 0x962620, new Item.Properties()));

    public static final DeferredHolder<Item, Item> BANDIT_ARCHER_SPAWN_EGG = ITEMS.register("bandit_archer_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(ModEntities.BANDIT_ARCHER, 0x3E4A34, 0xB89A5A, new Item.Properties()));

    /**
     * Метательный кинжал клана Тан («лист ивы», гл. 194), модель автора art/items/tang_dagger. В стопке — 12:
     * Тан носят ровно двенадцать кинжалов (tang-clan.md §9.3). Рисуется TangDaggerItemRenderer.
     */
    public static final DeferredHolder<Item, Item> TANG_DAGGER = ITEMS.register("tang_dagger",
            () -> new Item(new Item.Properties().stacksTo(12)));

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
