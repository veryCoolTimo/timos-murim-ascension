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
        event.put(ModEntities.BANDIT_SWORDSMAN.get(), BanditSwordsman.attributes().build());
        event.put(ModEntities.BANDIT_ARCHER.get(), BanditArcher.attributes().build());
        event.put(ModEntities.SECT_DISCIPLE.get(), SectDisciple.attributes().build());
    }

    /**
     * Бандиты ходят по земле и, как все монстры, появляются только в темноте — ночью и в чаще.
     * Где именно — biome_modifier {@code data/murim/neoforge/biome_modifier/bandits.json}.
     * API: reference/neoforge-src/net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent.java
     */
    @SubscribeEvent
    static void onSpawnPlacements(net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent event) {
        event.register(ModEntities.BANDIT_SWORDSMAN.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND,
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                net.minecraft.world.entity.monster.Monster::checkMonsterSpawnRules,
                net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(ModEntities.BANDIT_ARCHER.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND,
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                net.minecraft.world.entity.monster.Monster::checkMonsterSpawnRules,
                net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }

    private EntityAttributeEvents() {
    }
}
