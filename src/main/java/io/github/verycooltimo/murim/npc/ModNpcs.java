package io.github.verycooltimo.murim.npc;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModCreativeTabs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

public final class ModNpcs {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    public static final Map<NpcProfile, DeferredHolder<EntityType<?>, EntityType<GuestFighter>>> FIGHTERS = new EnumMap<>(NpcProfile.class);
    public static final Map<NpcProfile, DeferredHolder<Item, DeferredSpawnEggItem>> EGGS = new EnumMap<>(NpcProfile.class);
    public static final DeferredHolder<EntityType<?>, EntityType<Informant>> INFORMANT = ENTITIES.register("bae_do",
            () -> EntityType.Builder.<Informant>of(Informant::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F).eyeHeight(1.62F).clientTrackingRange(10).updateInterval(1).build("bae_do"));

    static {
        for (NpcProfile p : NpcProfile.values()) {
            if (p == NpcProfile.BAE_DO) continue;
            var type = ENTITIES.register(p.id, () -> EntityType.Builder.<GuestFighter>of((t, l) -> new GuestFighter(t, l, p),
                            p == NpcProfile.HAN_DOK || p == NpcProfile.KWON_YEON ? MobCategory.MONSTER : MobCategory.CREATURE)
                    .sized(0.6F, 1.95F).eyeHeight(1.62F).clientTrackingRange(10).updateInterval(1).build(p.id));
            FIGHTERS.put(p, type);
            EGGS.put(p, ITEMS.register(p.id + "_spawn_egg", () -> new DeferredSpawnEggItem(type, 0x57301F,
                    p == NpcProfile.KWON_YEON ? 0x7A4535 : 0xA0644F, new Item.Properties())));
        }
        EGGS.put(NpcProfile.BAE_DO, ITEMS.register("bae_do_spawn_egg",
                () -> new DeferredSpawnEggItem(INFORMANT, 0x2F3A5E, 0xC9A36A, new Item.Properties())));
    }

    private static void attributes(EntityAttributeCreationEvent e) {
        FIGHTERS.forEach((p, t) -> e.put(t.get(), GuestFighter.attributes(p).build()));
        e.put(INFORMANT.get(), Informant.attributes().build());
    }

    private static void tabs(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey().equals(ModCreativeTabs.MURIM.getKey())) {
            for (NpcProfile p : NpcProfile.values()) e.accept(new net.minecraft.world.item.ItemStack(EGGS.get(p).get()));
        }
    }

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
        ITEMS.register(bus);
        bus.addListener(ModNpcs::attributes);
        bus.addListener(ModNpcs::tabs);
    }

    private ModNpcs() { }
}
