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

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
