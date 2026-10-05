package io.github.verycooltimo.murim.entity.boss;

import com.mojang.serialization.Codec;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Реестры босса M4 (docs/design/26-boss.md): сущность и флаг победы игрока. Свои
 * DeferredRegister, а не общие ModEntities/ModAttachments: эти файлы правят параллельно другие
 * ветки, а NeoForge принимает несколько регистраторов одного мода на один реестр.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/registries/DeferredRegister.java,
 * reference/neoforge-src/net/neoforged/neoforge/attachment/AttachmentType.java#copyOnDeath.
 */
@EventBusSubscriber(modid = MurimMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class BossRegistry {

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MurimMod.MODID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MurimMod.MODID);

    /**
     * Хозяин крепости Зелёного Леса. MONSTER, но естественного спавна нет: появляется только в
     * крепости ({@code world/fortress/Fortresses}). В 1,35 раза больше игрока; обновление
     * каждый тик — телеграфы читаются глазами.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<FortressMaster>> FORTRESS_MASTER =
            ENTITIES.register("fortress_master", () -> EntityType.Builder
                    .<FortressMaster>of(FortressMaster::new, MobCategory.MONSTER)
                    .sized(0.9F, 2.6F)
                    .eyeHeight(2.2F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("fortress_master"));

    /**
     * Игрок победил хозяина крепости: условие прорыва во второй ранг (Realm.Condition.DEFEAT_BOSS).
     * Сохраняется и переживает смерть; на клиент не синхронизируется — проверяет только сервер.
     */
    public static final Supplier<AttachmentType<Boolean>> BOSS_DEFEATED =
            ATTACHMENTS.register("boss_defeated", () -> AttachmentType.builder(() -> Boolean.FALSE)
                    .serialize(Codec.BOOL)
                    .copyOnDeath()
                    .build());

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        ATTACHMENTS.register(modBus);
    }

    @SubscribeEvent
    static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(FORTRESS_MASTER.get(), FortressMaster.attributes().build());
    }

    private BossRegistry() {
    }
}
