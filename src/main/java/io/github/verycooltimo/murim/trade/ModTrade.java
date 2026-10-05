package io.github.verycooltimo.murim.trade;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Реестры торговли (docs/design/24-bandit-camp.md §7). Свой класс и свои регистры, как у архива
 * ({@code library/ModLibrary}): общие ModEntities/ModItems правят параллельные задачи.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java,
 * reference/neoforge-src/net/neoforged/neoforge/event/entity/EntityAttributeCreationEvent.java,
 * reference/neoforge-src/net/neoforged/neoforge/common/DeferredSpawnEggItem.java.
 */
public final class ModTrade {

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    /** Бродячий торговец: CREATURE, рост человека, как ванильный странствующий торговец. */
    public static final DeferredHolder<EntityType<?>, EntityType<Peddler>> PEDDLER = ENTITIES.register("peddler",
            () -> EntityType.Builder.<Peddler>of(Peddler::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .build("peddler"));

    /** Яйцо призыва (вкладка «Мурим»): индиго халата и загар котомки. */
    public static final DeferredHolder<Item, DeferredSpawnEggItem> PEDDLER_SPAWN_EGG = ITEMS.register("peddler_spawn_egg",
            () -> new DeferredSpawnEggItem(PEDDLER, 0x2F3A5E, 0xC9A36A, new Item.Properties()));

    /**
     * Медный вэнь (文, «медяк») — мелочь мира мурим (docs/design/29-economy.md): 9 вэней = лян серебра.
     * Серебряный лян остаётся в {@code ModItems.SILVER_TAEL} (его уже знают таблицы лута).
     */
    public static final DeferredHolder<Item, Item> COPPER_COIN = ITEMS.register("copper_coin",
            () -> new Item(new Item.Properties().stacksTo(64)));

    /** Золотой лян (金子): 9 лян серебра. Редок — сокровищница крепости, её хозяин, главарь банды. */
    public static final DeferredHolder<Item, Item> GOLD_TAEL = ITEMS.register("gold_tael",
            () -> new Item(new Item.Properties().stacksTo(64).rarity(net.minecraft.world.item.Rarity.UNCOMMON)));

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(PEDDLER.get(), Peddler.attributes().build());
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) {
            event.accept(new ItemStack(PEDDLER_SPAWN_EGG.get()));
            event.accept(new ItemStack(COPPER_COIN.get()));
            event.accept(new ItemStack(GOLD_TAEL.get()));
        }
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(ModTrade::attributes);
        modBus.addListener(ModTrade::tabs);
    }

    private ModTrade() {
    }
}
