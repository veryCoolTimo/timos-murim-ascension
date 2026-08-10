package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

/**
 * Атрибуты живых сущностей мода.
 *
 * <p>Без регистрации атрибутов {@code LivingEntity} падает при спавне — здоровье и скорость
 * ему неоткуда взять. Событие модовой шины, поэтому отдельный подписчик.
 */
@EventBusSubscriber(modid = MurimMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class EntityAttributeEvents {

    @SubscribeEvent
    static void onCreateAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.DUMMY.get(), TrainingDummy.attributes().build());
    }

    private EntityAttributeEvents() {
    }
}
