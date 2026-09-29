package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Компоненты данных предметов.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java
 * #createDataComponents / #registerComponentType;
 * reference/minecraft-src/net/minecraft/core/component/DataComponentType.java#Builder.
 */
public final class ModDataComponents {

    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MurimMod.MODID);

    /**
     * Какой метод записан в свитке.
     *
     * <p>Один предмет на все методы: метод — это данные, и новый метод не должен требовать
     * нового предмета в коде. Синхронизируется на клиент ради названия и подсказки.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> METHOD =
            COMPONENTS.registerComponentType("method", builder -> builder
                    .persistent(ResourceLocation.CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC));

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }

    private ModDataComponents() {
    }
}
