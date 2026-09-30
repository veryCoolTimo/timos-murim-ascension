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

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
