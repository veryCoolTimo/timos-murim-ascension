package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.technique.WedgeProjectile;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Типы сущностей мода.
 *
 * <p>{@code DeferredRegister.Entities} в NeoForge 21.1 отсутствует — используется общий
 * {@code DeferredRegister.create} с ключом реестра.
 */
public final class ModEntities {

    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MurimMod.MODID);

    /**
     * Летящий клин ци.
     *
     * <p>Категория {@code MISC} и запрет призыва командой: это снаряд техники, а не существо,
     * которое имеет смысл спавнить вручную.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<WedgeProjectile>> WEDGE =
            ENTITIES.register("wedge", () -> EntityType.Builder
                    .<WedgeProjectile>of(WedgeProjectile::new, MobCategory.MISC)
                    .sized(0.45F, 0.45F)
                    .clientTrackingRange(6)
                    .updateInterval(1)
                    .noSummon()
                    .build("wedge"));

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }

    private ModEntities() {
    }
}
