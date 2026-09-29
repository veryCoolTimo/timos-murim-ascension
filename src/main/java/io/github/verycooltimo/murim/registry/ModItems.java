package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.item.ManualItem;
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
     * Мануал основ. Не стакается: это книга, а не расходник, и держать пачку одинаковых
     * мануалов в слоте бессмысленно.
     */
    public static final DeferredHolder<Item, Item> MANUAL = ITEMS.register("manual",
            () -> new ManualItem(new Item.Properties().stacksTo(1)));

    /**
     * Свиток метода культивации. Один предмет на все методы: какой метод записан —
     * компонент {@link ModDataComponents#METHOD}.
     */
    public static final DeferredHolder<Item, Item> METHOD_SCROLL = ITEMS.register("method_scroll",
            () -> new io.github.verycooltimo.murim.item.MethodScrollItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
